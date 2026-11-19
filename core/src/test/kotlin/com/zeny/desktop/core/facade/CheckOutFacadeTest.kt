package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ErrorCatalog
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val FOLIO_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val FOLIO_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f02"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"

/** A `CheckOutFacade` (`ZenyCore.checkOut`), contra o `MockEngine`. */
class CheckOutFacadeTest {
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

    private fun quote(late: String) =
        """{"arrival":"2026-11-17","checkInTime":"14:00:00","checkOutTime":"12:00:00","departure":"2026-11-19",
        "earlyCheckIn":{"offered":false},"lateCheckOut":$late,"reservationId":"$RES","timezone":"America/Sao_Paulo"}"""

    private val checkedOut =
        """{"reservation":{"adults":2,"arrival":"2026-11-17","bookingId":"$RES","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-19","folioId":"$FOLIO_A","guests":[],"id":"$RES","roomId":"$ROOM",
        "roomTypeId":"$ROOM","status":"CHECKED_OUT","version":4},
        "room":{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"101","occupancy":"VACANT",
        "roomId":"$ROOM","version":5},"closedFolios":[],"releasedPreAuthorizations":[],"stayCharges":[]}"""

    private fun balanceProblem(extensions: String) =
        """{"code":"FOLIO_BALANCE_NOT_ZERO","detail":"saldo","status":409$extensions}"""

    private suspend fun bodyText(request: HttpRequestData): String = request.body.toByteArray().decodeToString()

    @Test
    fun quoteOfferedGivesLimitTimeAndFee() =
        runTest {
            core { json(quote("""{"offered":true,"fee":"80.50","limitTime":"10:00:00"}""")) }.use { core ->
                val quote = core.checkOut.lateCheckOutQuote(RES)
                assertEquals(LateCheckOutQuote("10:00", Money.parse("80.50")), quote)
                val request = requests.single()
                assertEquals(HttpMethod.Get, request.method)
                assertTrue(request.url.encodedPath.endsWith("/$RES/early-late-quote"), request.url.encodedPath)
            }
        }

    @Test
    fun quoteOfferedWithoutFeeOrLimitIsNull() =
        runTest {
            core { json(quote("""{"offered":true,"limitTime":"10:00:00"}""")) }.use { core ->
                assertNull(core.checkOut.lateCheckOutQuote(RES))
            }
            core { json(quote("""{"offered":true,"fee":"80.50","limitTime":null}""")) }.use { core ->
                assertNull(core.checkOut.lateCheckOutQuote(RES))
            }
        }

    @Test
    fun quoteNotOfferedGivesNull() =
        runTest {
            core { json(quote("""{"offered":false,"fee":"80.50","limitTime":"10:00:00"}""")) }.use { core ->
                assertNull(core.checkOut.lateCheckOutQuote(RES))
            }
        }

    @Test
    fun malformedQuoteIsIncompatible() =
        runTest {
            core { json(quote("""{"offered":true,"fee":"80,50","limitTime":"10:00:00"}""")) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.checkOut.lateCheckOutQuote(RES) }
            }
            core { json(quote("""{"offered":true,"fee":"80.50","limitTime":"dez horas"}""")) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.checkOut.lateCheckOutQuote(RES) }
            }
        }

    @Test
    fun checkOutSendsKeyAndNoBodyWithoutLate() =
        runTest {
            core { json(checkedOut) }.use { core ->
                assertSame(CheckOutResult.Done, core.checkOut.checkOut(RES, null, Intent()))
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/$RES/check-out"), request.url.encodedPath)
                assertEquals(1, request.headers.getAll("Idempotency-Key")?.size)
                assertTrue(request.headers["Idempotency-Key"]!!.isNotBlank())
                assertEquals("", bodyText(request))
                assertNull(request.headers[HttpHeaders.ContentType])
                assertNull(request.body.contentType)
            }
        }

    @Test
    fun checkOutSendsTheLateFeeAsExactDecimal() =
        runTest {
            core { json(checkedOut) }.use { core ->
                core.checkOut.checkOut(RES, Money.parse("80.50"), Intent())
                val body = Json.parseToJsonElement(bodyText(requests.single())).jsonObject
                val fee = body["lateCheckOutFee"]!!.jsonPrimitive
                assertTrue(fee.isString)
                assertEquals("80.50", fee.content)
            }
        }

    @Test
    fun sameIntentKeepsTheKey() =
        runTest {
            var calls = 0
            core {
                if (++calls == 1) throw IOException("sem rede")
                json(checkedOut)
            }.use { core ->
                val intent = Intent()
                assertFailsWith<ApiError.Network> { core.checkOut.checkOut(RES, null, intent) }
                core.checkOut.checkOut(RES, null, intent)
                assertEquals(2, requests.size)
                assertEquals(intent.key, requests[0].headers["Idempotency-Key"])
                assertEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun balanceNotZeroBecomesBalanceDue() =
        runTest {
            val extensions =
                ""","folios":[{"folioId":"$FOLIO_A","number":"F-1","status":"OPEN","balance":"150.00"},
                {"folioId":"$FOLIO_B","number":"F-2","status":"OPEN","balance":"-20.00"}],"total":"130.00""""
            core { json(balanceProblem(extensions), HttpStatusCode.Conflict) }.use { core ->
                val result = core.checkOut.checkOut(RES, Money.parse("80.50"), Intent())
                assertEquals(
                    CheckOutResult.BalanceDue(
                        listOf(
                            FolioDue(FOLIO_A, "F-1", Money.parse("150.00")),
                            FolioDue(FOLIO_B, "F-2", Money.parse("-20.00")),
                        ),
                        Money.parse("130.00"),
                    ),
                    result,
                )
            }
        }

    @Test
    fun malformedBalanceDueFallsBackToTheCatalogText() =
        runTest {
            val folio = """{"folioId":"$FOLIO_A","number":"F-1","status":"OPEN","balance":"150.00"}"""
            val malformed =
                listOf(
                    ""","total":"150.00"""",
                    ""","folios":[{"folioId":"$FOLIO_A","number":"F-1","status":"OPEN","balance":"150,00"}],
                    "total":"150.00"""",
                    ""","folios":[$folio]""",
                    ""","folios":[],"total":"0.00"""",
                    ""","folios":{"a":1},"total":"150.00"""",
                    ""","folios":[{"folioId":"não-é-uuid","number":"F-1","balance":"150.00"}],"total":"150.00"""",
                    ""","folios":[{"folioId":"$FOLIO_A","balance":"150.00"}],"total":"150.00"""",
                    ""","folios":[$folio],"total":150.00""",
                )
            val expected = ErrorCatalog.userMessage(409, "FOLIO_BALANCE_NOT_ZERO", "saldo")
            for (extensions in malformed) {
                core { json(balanceProblem(extensions), HttpStatusCode.Conflict) }.use { core ->
                    val error =
                        assertFailsWith<ApiError.Problem>(extensions) {
                            core.checkOut.checkOut(RES, null, Intent())
                        }
                    assertEquals("FOLIO_BALANCE_NOT_ZERO", error.code)
                    assertEquals(409, error.status)
                    assertEquals(expected, error.userMessage)
                }
            }
        }

    @Test
    fun otherConflictsStayErrors() =
        runTest {
            val codes =
                listOf(
                    HttpStatusCode.Conflict to "RESERVATION_CHANGED",
                    HttpStatusCode.Conflict to "RESERVATION_TRANSITION_NOT_ALLOWED",
                    HttpStatusCode.Conflict to "EARLY_LATE_FEE_CHANGED",
                    HttpStatusCode.UnprocessableEntity to "EARLY_LATE_NOT_OFFERED",
                    HttpStatusCode.NotFound to "RESERVATION_NOT_FOUND",
                )
            for ((status, code) in codes) {
                core { problem(status, code) }.use { core ->
                    val error = assertFailsWith<ApiError.Problem>(code) { core.checkOut.checkOut(RES, null, Intent()) }
                    assertEquals(code, error.code)
                    assertEquals(status.value, error.status)
                }
            }
        }

    @Test
    fun nonUuidIdFailsBeforeTheNetwork() =
        runTest {
            core { json(checkedOut) }.use { core ->
                val quote = assertFailsWith<IllegalArgumentException> { core.checkOut.lateCheckOutQuote("R-segredo") }
                val checkOut =
                    assertFailsWith<IllegalArgumentException> {
                        core.checkOut.checkOut("R-segredo", null, Intent())
                    }
                assertFalse(quote.message.orEmpty().contains("R-segredo"))
                assertFalse(checkOut.message.orEmpty().contains("R-segredo"))
                assertTrue(requests.isEmpty())
            }
        }
}
