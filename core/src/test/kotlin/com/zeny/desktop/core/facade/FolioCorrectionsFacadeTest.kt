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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val FOLIO_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f02"
private const val FOLIO_3 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f03"
private const val POSTING = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f04"
private const val POSTING_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f05"
private const val CODE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f06"
private const val SECRET_ID = "id-secreto-que-nao-e-uuid"
private const val MAX_REASON_PLUS_ONE = 501
private const val ONE_MORE_THAN_THE_MOST_IDS = 101

/** As quatro correções da conta da `FoliosFacade`: ajustar, remover a taxa, transferir e dividir. */
class FolioCorrectionsFacadeTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
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

    private fun posting(
        id: String = POSTING,
        folioId: String = FOLIO,
    ) = """{"amount":"25.00","businessDate":"2026-11-18","description":"Frigobar","folioId":"$folioId","id":"$id",
        "postedAt":"2026-11-18T10:00:00Z","quantity":1.0,"source":"MANUAL","transactionCodeId":"$CODE",
        "type":"ADJUSTMENT","unitPrice":"25.00"}"""

    private fun transferred() =
        """{"transfers":[{"incoming":${posting(POSTING_2, FOLIO_2)},"originalId":"$POSTING",
        "outgoing":${posting(POSTING_2, FOLIO)}}]}"""

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"billing.postings.adjust","permission":"p",
        "resourceRef":"r"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private fun percent(
        folioId: String,
        value: String,
    ) = SplitPart.Percent(folioId, BigDecimal(value))

    private fun amount(
        folioId: String,
        value: String,
    ) = SplitPart.Amount(folioId, Money.parse(value))

    private fun uuidOf(index: Int) = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b${index.toString().padStart(4, '0')}"

    @Test
    fun adjustSendsAmountReasonAndKey() =
        runTest {
            core { json(posting(), HttpStatusCode.Created) }.use { core ->
                val done = core.folios.adjust(FOLIO, POSTING, Money.parse("12.50"), "valor errado", Intent()).run(null)
                assertEquals(POSTING, done.value.id.toString())
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/folios/$FOLIO/postings/$POSTING/adjust"))
                assertTrue(!request.headers["Idempotency-Key"].isNullOrBlank())
                val body = bodyOf(request)
                assertEquals("12.50", body.getValue("amount").jsonPrimitive.content)
                assertTrue(body.getValue("amount").jsonPrimitive.isString)
                assertEquals("valor errado", body.getValue("reason").jsonPrimitive.content)
            }
        }

    @Test
    fun adjustApprovedRetriesWithTokenAndNewKey() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(approvalProblem, HttpStatusCode.Forbidden)
                } else {
                    json(posting(POSTING_2), HttpStatusCode.Created)
                }
            }.use { core ->
                val action = core.folios.adjust(FOLIO, POSTING, Money.parse("1.00"), "motivo", Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                val done = action.run("tok-gerente")
                assertEquals(POSTING_2, done.value.id.toString())
                assertEquals(2, requests.size)
                assertNull(requests[0].headers["X-Approval-Token"])
                assertEquals("tok-gerente", requests[1].headers["X-Approval-Token"])
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun adjustOutOfShapeFailsBeforeTheNetwork() =
        runTest {
            core { json(posting(), HttpStatusCode.Created) }.use { core ->
                listOf("0.00", "0", "-1.00").forEach {
                    assertFailsWith<IllegalArgumentException> {
                        core.folios.adjust(FOLIO, POSTING, Money.parse(it), "motivo", Intent())
                    }
                }
                listOf("", "   ", "x".repeat(MAX_REASON_PLUS_ONE)).forEach {
                    assertFailsWith<IllegalArgumentException> {
                        core.folios.adjust(FOLIO, POSTING, Money.parse("1.00"), it, Intent())
                    }
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun removeServiceChargeSendsNoBody() =
        runTest {
            core { json(posting(), HttpStatusCode.Created) }.use { core ->
                val done = core.folios.removeServiceCharge(FOLIO, POSTING, Intent())
                assertEquals(POSTING, done.id.toString())
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/folios/$FOLIO/postings/$POSTING/remove-service-charge"))
                assertTrue(!request.headers["Idempotency-Key"].isNullOrBlank())
                assertEquals(0, request.body.toByteArray().size)
            }
        }

    @Test
    fun transferSendsIdsTargetAndReason() =
        runTest {
            core { json(transferred(), HttpStatusCode.OK) }.use { core ->
                val done = core.folios.transfer(FOLIO, listOf(POSTING, POSTING_2), FOLIO_2, "conta errada", Intent())
                assertEquals(1, done.transfers.size)
                val request = requests.single()
                assertTrue(request.url.encodedPath.endsWith("/folios/$FOLIO/postings/transfer"))
                assertTrue(!request.headers["Idempotency-Key"].isNullOrBlank())
                val body = bodyOf(request)
                assertEquals(
                    listOf(POSTING, POSTING_2),
                    body.getValue("postingIds").jsonArray.map { it.jsonPrimitive.content },
                )
                assertEquals(FOLIO_2, body.getValue("targetFolioId").jsonPrimitive.content)
                assertEquals("conta errada", body.getValue("reason").jsonPrimitive.content)

                core.folios.transfer(FOLIO, listOf(POSTING), FOLIO_2, "   ", Intent())
                core.folios.transfer(FOLIO, listOf(POSTING), FOLIO_2, null, Intent())
                assertFalse("reason" in bodyOf(requests[1]))
                assertFalse("reason" in bodyOf(requests[2]))
            }
        }

    @Test
    fun splitByPercentSendsPercentStrings() =
        runTest {
            core { json(transferred(), HttpStatusCode.OK) }.use { core ->
                val parts = listOf(percent(FOLIO, "33.3333"), percent(FOLIO_2, "66.6666"), percent(FOLIO_3, "0.0001"))
                core.folios.split(FOLIO, POSTING, parts, "dividir", Intent())
                val request = requests.single()
                assertTrue(request.url.encodedPath.endsWith("/folios/$FOLIO/postings/$POSTING/split"))
                assertTrue(!request.headers["Idempotency-Key"].isNullOrBlank())
                val body = bodyOf(request)
                assertEquals("PERCENT", body.getValue("by").jsonPrimitive.content)
                assertEquals("dividir", body.getValue("reason").jsonPrimitive.content)
                val sent = body.getValue("parts").jsonArray.map { it.jsonObject }
                assertEquals(listOf(FOLIO, FOLIO_2, FOLIO_3), sent.map { it.getValue("folioId").jsonPrimitive.content })
                assertEquals(
                    listOf("33.3333", "66.6666", "0.0001"),
                    sent.map { it.getValue("percent").jsonPrimitive.content },
                )
                assertTrue(sent.all { it.getValue("percent").jsonPrimitive.isString })
                assertTrue(sent.none { "amount" in it })
            }
        }

    @Test
    fun splitByPercentNeverSendsAnExponent() =
        runTest {
            core { json(transferred(), HttpStatusCode.OK) }.use { core ->
                val parts = listOf(percent(FOLIO, "5E+1"), percent(FOLIO_2, "50.00000"))
                core.folios.split(FOLIO, POSTING, parts, null, Intent())
                val sent = bodyOf(requests.single()).getValue("parts").jsonArray.map { it.jsonObject }
                assertEquals(listOf("50", "50"), sent.map { it.getValue("percent").jsonPrimitive.content })
            }
        }

    @Test
    fun splitByAmountSendsAmountStrings() =
        runTest {
            core { json(transferred(), HttpStatusCode.OK) }.use { core ->
                val parts = listOf(amount(FOLIO, "10.10"), amount(FOLIO_2, "14.90"))
                core.folios.split(FOLIO, POSTING, parts, "  ", Intent())
                val body = bodyOf(requests.single())
                assertEquals("AMOUNT", body.getValue("by").jsonPrimitive.content)
                assertFalse("reason" in body)
                val sent = body.getValue("parts").jsonArray.map { it.jsonObject }
                assertEquals(listOf("10.10", "14.90"), sent.map { it.getValue("amount").jsonPrimitive.content })
                assertTrue(sent.none { "percent" in it })
            }
        }

    @Test
    fun splitPartsOutOfShapeFailBeforeTheNetwork() =
        runTest {
            core { json(transferred(), HttpStatusCode.OK) }.use { core ->
                val eleven = listOf(FOLIO) + (1..10).map { uuidOf(it) }
                val outOfShape: List<List<SplitPart>> =
                    listOf(
                        listOf(percent(FOLIO, "50.12345"), percent(FOLIO_2, "49.87655")),
                        listOf(percent(FOLIO, "0"), percent(FOLIO_2, "100")),
                        listOf(percent(FOLIO, "100.0001"), percent(FOLIO_2, "1")),
                        listOf(percent(FOLIO, "-10"), percent(FOLIO_2, "110")),
                        listOf(percent(FOLIO, "50"), amount(FOLIO_2, "10.00")),
                        listOf(amount(FOLIO, "0.00"), amount(FOLIO_2, "10.00")),
                        listOf(percent(FOLIO, "50"), percent(FOLIO, "50")),
                        listOf(percent(FOLIO, "100")),
                        eleven.map { percent(it, "9") },
                        listOf(percent(FOLIO_2, "50"), percent(FOLIO_3, "50")),
                        emptyList(),
                    )
                outOfShape.forEach {
                    val failure =
                        assertFailsWith<IllegalArgumentException> {
                            core.folios.split(FOLIO, POSTING, it, null, Intent())
                        }
                    assertTrue("50.12345" !in failure.message.orEmpty())
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun transferOutOfShapeFailsBeforeTheNetwork() =
        runTest {
            core { json(transferred(), HttpStatusCode.OK) }.use { core ->
                val many = (1..ONE_MORE_THAN_THE_MOST_IDS).map { uuidOf(it) }
                listOf(emptyList(), many, listOf(POSTING, POSTING)).forEach {
                    assertFailsWith<IllegalArgumentException> {
                        core.folios.transfer(FOLIO, it, FOLIO_2, null, Intent())
                    }
                }
                assertFailsWith<IllegalArgumentException> {
                    core.folios.transfer(FOLIO, listOf(POSTING), FOLIO, null, Intent())
                }
                assertFailsWith<IllegalArgumentException> {
                    core.folios.transfer(FOLIO, listOf(POSTING), FOLIO_2, "x".repeat(MAX_REASON_PLUS_ONE), Intent())
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun sameIntentKeepsTheKey() =
        runTest {
            core { request ->
                if (request.url.encodedPath.endsWith("/transfer") || request.url.encodedPath.endsWith("/split")) {
                    json(transferred(), HttpStatusCode.OK)
                } else {
                    json(posting(), HttpStatusCode.Created)
                }
            }.use { core ->
                val removal = Intent()
                core.folios.removeServiceCharge(FOLIO, POSTING, removal)
                core.folios.removeServiceCharge(FOLIO, POSTING, removal)
                val transfer = Intent()
                core.folios.transfer(FOLIO, listOf(POSTING), FOLIO_2, null, transfer)
                core.folios.transfer(FOLIO, listOf(POSTING), FOLIO_2, null, transfer)
                val split = Intent()
                val parts = listOf(percent(FOLIO, "50"), percent(FOLIO_2, "50"))
                core.folios.split(FOLIO, POSTING, parts, null, split)
                core.folios.split(FOLIO, POSTING, parts, null, split)
                val adjust = Intent()
                core.folios.adjust(FOLIO, POSTING, Money.parse("1.00"), "m", adjust).run(null)
                core.folios.adjust(FOLIO, POSTING, Money.parse("1.00"), "m", adjust).run(null)
                assertEquals(8, requests.size)
                requests.chunked(2).forEach {
                    assertEquals(it[0].headers["Idempotency-Key"], it[1].headers["Idempotency-Key"])
                }
            }
        }

    @Test
    fun nonUuidIdFailsBeforeTheNetwork() =
        runTest {
            core { json(transferred(), HttpStatusCode.OK) }.use { core ->
                val one = Money.parse("1.00")
                val two = listOf(percent(FOLIO, "50"), percent(FOLIO_2, "50"))
                val badPart = listOf(percent(FOLIO, "50"), percent(SECRET_ID, "50"))
                val calls: List<() -> Unit> =
                    listOf(
                        { core.folios.adjust(SECRET_ID, POSTING, one, "m", Intent()) },
                        { core.folios.adjust(FOLIO, SECRET_ID, one, "m", Intent()) },
                    )
                val suspended: List<suspend () -> Unit> =
                    listOf(
                        { core.folios.removeServiceCharge(SECRET_ID, POSTING, Intent()) },
                        { core.folios.removeServiceCharge(FOLIO, SECRET_ID, Intent()) },
                        { core.folios.transfer(SECRET_ID, listOf(POSTING), FOLIO_2, null, Intent()) },
                        { core.folios.transfer(FOLIO, listOf(SECRET_ID), FOLIO_2, null, Intent()) },
                        { core.folios.transfer(FOLIO, listOf(POSTING), SECRET_ID, null, Intent()) },
                        { core.folios.split(SECRET_ID, POSTING, two, null, Intent()) },
                        { core.folios.split(FOLIO, SECRET_ID, two, null, Intent()) },
                        { core.folios.split(FOLIO, POSTING, badPart, null, Intent()) },
                    )
                val failures =
                    calls.map { assertFailsWith<IllegalArgumentException> { it() } } +
                        suspended.map { assertFailsWith<IllegalArgumentException> { it() } }
                failures.forEach { assertTrue(SECRET_ID !in it.message.orEmpty()) }
                assertTrue(requests.isEmpty())
            }
        }
}
