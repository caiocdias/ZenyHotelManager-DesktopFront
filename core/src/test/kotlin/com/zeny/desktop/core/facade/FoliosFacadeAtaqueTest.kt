package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.types.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f11"
private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f13"
private const val POSTING = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f14"
private const val POSTING_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f15"
private const val CODE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f16"
private const val SECRET_ID = "conta-secreta-que-nao-e-uuid"
private const val TOKEN = "X-Approval-Token"
private const val KEY = "Idempotency-Key"
private const val PDF_CAP = 20 * 1024 * 1024
private val MAGIC = "%PDF-".encodeToByteArray()

/** Ataque às bordas da `FoliosFacade` e do extrato em PDF: dinheiro, quantidade, chaves e páginas. */
class FoliosFacadeAtaqueTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-ataque"
    }

    private fun core(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ZenyCore =
        ZenyCore(
            checkNotNull(ServerUrl.parse("http://servidor.teste")),
            MemorySettings(),
            MockEngine { request ->
                requests += request
                handler(request)
            },
        )

    private fun posting(id: String = POSTING) =
        """{"amount":"25.00","businessDate":"2026-11-18","description":"Frigobar","folioId":"$FOLIO","id":"$id",
        "postedAt":"2026-11-18T10:00:00Z","quantity":1.0,"source":"MANUAL","transactionCodeId":"$CODE",
        "type":"REGULAR","unitPrice":"25.00"}"""

    private val emptyPage = """{"items":[]}"""

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"billing.postings.reverse","permission":"p",
        "resourceRef":"r"}"""

    private fun statement() =
        """{"audience":"GUEST","balance":"10.00","chargesTotal":"10.00","days":[],
        "folio":{"id":"$FOLIO","number":"C-1","openedOn":"2026-11-18","owner":{"id":"$RES","type":"RESERVATION"},
        "status":"OPEN"},"generatedAt":"2026-11-18T10:00:00Z","paymentsTotal":"0.00","preAuthorizations":[]}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    @Test
    fun quantityEdgesOneAnd9999GoAsWholeNumbers() =
        runTest {
            core { json(posting(), HttpStatusCode.Created) }.use { core ->
                core.folios.post(FOLIO, PostingInput(CODE, 1, Money.parse("1.00"), null), Intent())
                core.folios.post(FOLIO, PostingInput(CODE, 9999, Money.parse("1.00"), null), Intent())
                val sent =
                    requests.map {
                        bodyOf(it)
                            .getValue("quantity")
                            .jsonPrimitive.content
                            .toDouble()
                    }
                assertEquals(listOf(1.0, 9999.0), sent)
            }
        }

    @Test
    fun unitPriceEdgesGoAsExactDecimalStrings() =
        runTest {
            core { json(posting(), HttpStatusCode.Created) }.use { core ->
                listOf("0.01", "1000000.00", "1234.56").forEach {
                    core.folios.post(FOLIO, PostingInput(CODE, 1, Money.parse(it), null), Intent())
                }
                val sent = requests.map { bodyOf(it).getValue("unitPrice").jsonPrimitive }
                assertTrue(sent.all { it.isString }, "o valor unitário foi como número")
                assertEquals(listOf("0.01", "1000000.00", "1234.56"), sent.map { it.content })
            }
        }

    @Test
    fun anotherIntentGetsAnotherKey() =
        runTest {
            core { json(posting(), HttpStatusCode.Created) }.use { core ->
                val input = PostingInput(CODE, 1, Money.parse("1.00"), null)
                core.folios.post(FOLIO, input, Intent())
                core.folios.post(FOLIO, input, Intent())
                assertNotEquals(requests[0].headers[KEY], requests[1].headers[KEY])
            }
        }

    @Test
    fun approvedReversalLostOnTheNetworkRetriesWithTheApprovedKeyAndToken() =
        runTest {
            var lost = true
            core { request ->
                when {
                    request.headers[TOKEN] == null -> {
                        json(approvalProblem, HttpStatusCode.Forbidden)
                    }

                    lost -> {
                        lost = false
                        throw IOException("conexão caiu depois da aprovação")
                    }

                    else -> {
                        json(posting(POSTING_2), HttpStatusCode.Created)
                    }
                }
            }.use { core ->
                val intent = Intent()
                val first = core.folios.reverse(FOLIO, POSTING, "lançado errado", intent)
                assertFailsWith<ApiError.ApprovalRequired> { first.run(null) }
                assertFailsWith<ApiError> { first.run("tok-gerente") }

                // A tela repete a mesma intenção sem token: o núcleo reenvia a chave e o token da aprovação.
                val again = core.folios.reverse(FOLIO, POSTING, "lançado errado", intent)
                assertEquals(
                    POSTING_2,
                    again
                        .run(null)
                        .value.id
                        .toString(),
                )

                val unapproved = requests.first()
                val approved = requests.drop(1)
                assertNull(unapproved.headers[TOKEN])
                assertTrue(approved.size >= 2, "a repetição não chegou ao servidor")
                assertTrue(approved.all { it.headers[TOKEN] == "tok-gerente" }, "a repetição perdeu o token")
                assertEquals(1, approved.map { it.headers[KEY] }.distinct().size, "a repetição trocou a chave")
                assertNotEquals(unapproved.headers[KEY], approved.last().headers[KEY])
            }
        }

    @Test
    fun allPagesOfPostingsAndCodesAsk200() =
        runTest {
            core { json(emptyPage) }.use { core ->
                core.folios.postings(FOLIO)
                core.folios.transactionCodes()
                assertEquals(listOf("200", "200"), requests.map { it.url.parameters["limit"] })
            }
        }

    @Test
    fun openFolioPageDefaultsTo50() =
        runTest {
            core { json(emptyPage) }.use { core ->
                core.folios.openFolioPage(null)
                assertEquals("50", requests.single().url.parameters["limit"])
                assertNull(requests.single().url.parameters["cursor"])
            }
        }

    @Test
    fun statementGoesToTheFolioStatementPath() =
        runTest {
            core { json(statement()) }.use { core ->
                core.folios.statement(FOLIO, StatementAudience.GUEST)
                assertTrue(
                    requests
                        .single()
                        .url.encodedPath
                        .endsWith("/folios/$FOLIO/statement"),
                )
            }
        }

    @Test
    fun statementPdfWithNonUuidFailsBeforeTheNetworkWithoutTheValue() =
        runTest {
            core { json(statement()) }.use { core ->
                StatementAudience.entries.forEach { audience ->
                    val failure =
                        assertFailsWith<IllegalArgumentException> { core.documents.folioStatement(SECRET_ID, audience) }
                    assertTrue(SECRET_ID !in failure.message.orEmpty(), "a mensagem mostra o id recebido")
                }
                assertTrue(requests.isEmpty())
            }
        }

    private fun MockRequestHandleScope.pdf(
        body: ByteArray,
        contentType: String = "application/pdf",
    ): HttpResponseData = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, contentType))

    @Test
    fun statementPdfWithoutThePdfSignatureIsIncompatible() =
        runTest {
            core { pdf("<html>extrato</html>".encodeToByteArray()) }.use { core ->
                StatementAudience.entries.forEach { audience ->
                    assertFailsWith<ApiError.IncompatibleResponse> { core.documents.folioStatement(FOLIO, audience) }
                }
            }
        }

    @Test
    fun statementPdfWithAnotherContentTypeIsIncompatible() =
        runTest {
            core { pdf(MAGIC + "1.7\n%%EOF".encodeToByteArray(), "application/octet-stream") }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> {
                    core.documents.folioStatement(FOLIO, StatementAudience.INTERNAL)
                }
            }
        }

    @Test
    fun statementPdfAboveTheCeilingIsIncompatibleAndAtTheCeilingOpens() =
        runTest {
            core { pdf(MAGIC + ByteArray(PDF_CAP + 1 - MAGIC.size)) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> {
                    core.documents.folioStatement(FOLIO, StatementAudience.GUEST)
                }
            }
            core { pdf(MAGIC + ByteArray(PDF_CAP - MAGIC.size)) }.use { core ->
                assertEquals(
                    PDF_CAP,
                    core.documents
                        .folioStatement(FOLIO, StatementAudience.GUEST)
                        .bytes.size,
                )
            }
        }
}
