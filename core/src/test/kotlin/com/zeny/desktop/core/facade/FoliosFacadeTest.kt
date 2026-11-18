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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val FOLIO_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f02"
private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f03"
private const val POSTING = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f04"
private const val POSTING_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f05"
private const val CODE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f06"
private const val CODE_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f07"
private const val SECRET_ID = "id-secreto-que-nao-e-uuid"

/** As contas da hospedagem da `FoliosFacade`, contra o `MockEngine`. */
class FoliosFacadeTest {
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

    private fun folio(id: String = FOLIO) =
        """{"balance":"10.00","creditLimitExceeded":false,"id":"$id","number":"C-1","openedAt":"2026-11-18T10:00:00Z",
        "openedOn":"2026-11-18","owner":{"id":"$RES","type":"RESERVATION"},"status":"OPEN",
        "updatedAt":"2026-11-18T10:00:00Z"}"""

    private fun posting(id: String = POSTING) =
        """{"amount":"25.00","businessDate":"2026-11-18","description":"Frigobar","folioId":"$FOLIO","id":"$id",
        "postedAt":"2026-11-18T10:00:00Z","quantity":2.0,"source":"MANUAL","transactionCodeId":"$CODE","type":"CHARGE",
        "unitPrice":"12.50"}"""

    private fun code(id: String) =
        """{"active":true,"code":"FRG","createdAt":"2026-11-01T10:00:00Z","id":"$id","kind":"CHARGE","name":"Frigobar",
        "passThrough":false,"revenueGroup":"FOOD_BEVERAGE","serviceChargeApplies":true,
        "updatedAt":"2026-11-01T10:00:00Z"}"""

    private fun page(
        items: String,
        next: String? = null,
    ): String {
        val cursor = if (next == null) "" else ""","nextCursor":"$next""""
        return """{"items":[$items]$cursor}"""
    }

    private fun statement(audience: String) =
        """{"audience":"$audience","balance":"10.00","chargesTotal":"10.00","days":[],
        "folio":{"id":"$FOLIO","number":"C-1","openedOn":"2026-11-18","owner":{"id":"$RES","type":"RESERVATION"},
        "status":"OPEN"},"generatedAt":"2026-11-18T10:00:00Z","paymentsTotal":"0.00","preAuthorizations":[]}"""

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"billing.postings.reverse","permission":"p",
        "resourceRef":"r"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private fun input(description: String? = "Frigobar") = PostingInput(CODE, 2, Money.parse("12.50"), description)

    @Test
    fun openFolioPageAsksOpenStatus() =
        runTest {
            core { json(page(folio(), "next-2")) }.use { core ->
                val result = core.folios.openFolioPage("cur-1", 25)
                assertEquals(listOf(FOLIO), result.items.map { it.id.toString() })
                assertEquals("next-2", result.nextCursor)
                assertEquals("OPEN", requests.single().url.parameters["status"])
                assertEquals("cur-1", requests.single().url.parameters["cursor"])
                assertEquals("25", requests.single().url.parameters["limit"])
            }
        }

    @Test
    fun reservationFoliosReadsAllPages() =
        runTest {
            core { request ->
                if (request.url.parameters["cursor"] == null) {
                    json(page(folio(FOLIO), "next-2"))
                } else {
                    json(page(folio(FOLIO_2)))
                }
            }.use { core ->
                val all = core.folios.reservationFolios(RES)
                assertEquals(listOf(FOLIO, FOLIO_2), all.map { it.id.toString() })
                requests.forEach {
                    assertEquals("RESERVATION", it.url.parameters["ownerType"])
                    assertEquals(RES, it.url.parameters["ownerId"])
                    assertEquals("200", it.url.parameters["limit"])
                }
                assertEquals("next-2", requests[1].url.parameters["cursor"])
            }
        }

    @Test
    fun postingsReadAllPages() =
        runTest {
            core { request ->
                if (request.url.parameters["cursor"] == null) {
                    json(page(posting(POSTING), "next-2"))
                } else {
                    json(page(posting(POSTING_2)))
                }
            }.use { core ->
                val all = core.folios.postings(FOLIO)
                assertEquals(listOf(POSTING, POSTING_2), all.map { it.id.toString() })
                assertEquals(2, requests.size)
                assertTrue(requests.all { it.url.encodedPath.endsWith("/folios/$FOLIO/postings") })
            }
        }

    @Test
    fun transactionCodesOnlyActive() =
        runTest {
            core { request ->
                if (request.url.parameters["cursor"] == null) {
                    json(page(code(CODE), "next-2"))
                } else {
                    json(page(code(CODE_2)))
                }
            }.use { core ->
                assertEquals(listOf(CODE, CODE_2), core.folios.transactionCodes().map { it.id.toString() })
                requests.forEach { assertEquals("true", it.url.parameters["active"]) }
            }
        }

    @Test
    fun folioReadsOne() =
        runTest {
            core { json(folio()) }.use { core ->
                assertEquals(
                    FOLIO,
                    core.folios
                        .folio(FOLIO)
                        .id
                        .toString(),
                )
            }
        }

    @Test
    fun postSendsExactDecimalAndIdempotencyKey() =
        runTest {
            core { json(posting(), HttpStatusCode.Created) }.use { core ->
                core.folios.post(FOLIO, input(), Intent())
                val body = bodyOf(requests.single())
                assertEquals("12.50", body.getValue("unitPrice").jsonPrimitive.content)
                assertTrue(body.getValue("unitPrice").jsonPrimitive.isString)
                assertEquals(
                    2.0,
                    body
                        .getValue("quantity")
                        .jsonPrimitive.content
                        .toDouble(),
                )
                assertEquals(CODE, body.getValue("transactionCodeId").jsonPrimitive.content)
                assertEquals("Frigobar", body.getValue("description").jsonPrimitive.content)
                assertTrue(requests.single().headers["Idempotency-Key"]?.isNotBlank() == true)
            }
        }

    @Test
    fun blankDescriptionIsNotSent() =
        runTest {
            core { json(posting(), HttpStatusCode.Created) }.use { core ->
                core.folios.post(FOLIO, input("   "), Intent())
                assertNull(bodyOf(requests.single())["description"])
            }
        }

    @Test
    fun sameIntentKeepsTheKey() =
        runTest {
            core { json(posting(), HttpStatusCode.Created) }.use { core ->
                val intent = Intent()
                core.folios.post(FOLIO, input(), intent)
                core.folios.post(FOLIO, input(), intent)
                assertEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun reverseAsksApprovalThenRetriesWithTokenAndNewKey() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(approvalProblem, HttpStatusCode.Forbidden)
                } else {
                    json(posting(POSTING_2), HttpStatusCode.Created)
                }
            }.use { core ->
                val action = core.folios.reverse(FOLIO, POSTING, "lançado errado", Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                val done = action.run("tok-gerente")
                assertEquals(POSTING_2, done.value.id.toString())
                assertEquals(2, requests.size)
                assertNull(requests[0].headers["X-Approval-Token"])
                assertEquals("tok-gerente", requests[1].headers["X-Approval-Token"])
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
                assertEquals("lançado errado", bodyOf(requests[1]).getValue("reason").jsonPrimitive.content)
                assertTrue(requests[1].url.encodedPath.endsWith("/folios/$FOLIO/postings/$POSTING/reverse"))
            }
        }

    @Test
    fun statementSendsAudience() =
        runTest {
            core { request -> json(statement(request.url.parameters["audience"].orEmpty())) }.use { core ->
                core.folios.statement(FOLIO, StatementAudience.GUEST)
                core.folios.statement(FOLIO, StatementAudience.INTERNAL)
                assertEquals(listOf("GUEST", "INTERNAL"), requests.map { it.url.parameters["audience"] })
            }
        }

    @Test
    fun quantityOutOfRangeFailsBeforeTheNetwork() =
        runTest {
            core { json(posting(), HttpStatusCode.Created) }.use { core ->
                listOf(0, 10_000).forEach {
                    assertFailsWith<IllegalArgumentException> {
                        core.folios.post(FOLIO, PostingInput(CODE, it, Money.parse("1.00"), null), Intent())
                    }
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun nonUuidIdFailsBeforeTheNetwork() =
        runTest {
            core { json(folio()) }.use { core ->
                val one = Money.parse("1.00")
                val failures =
                    listOf(
                        assertFailsWith<IllegalArgumentException> { core.folios.folio(SECRET_ID) },
                        assertFailsWith<IllegalArgumentException> { core.folios.reservationFolios(SECRET_ID) },
                        assertFailsWith<IllegalArgumentException> { core.folios.postings(SECRET_ID) },
                        assertFailsWith<IllegalArgumentException> {
                            core.folios.statement(SECRET_ID, StatementAudience.GUEST)
                        },
                        assertFailsWith<IllegalArgumentException> { core.folios.post(SECRET_ID, input(), Intent()) },
                        assertFailsWith<IllegalArgumentException> {
                            core.folios.post(FOLIO, PostingInput(SECRET_ID, 1, one, null), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.folios.reverse(SECRET_ID, POSTING, "m", Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.folios.reverse(FOLIO, SECRET_ID, "m", Intent())
                        },
                    )
                failures.forEach { assertTrue(SECRET_ID !in it.message.orEmpty()) }
                assertTrue(requests.isEmpty())
            }
        }
}
