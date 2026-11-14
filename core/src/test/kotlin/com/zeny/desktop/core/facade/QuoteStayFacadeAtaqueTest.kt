package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.BusinessDate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val Q_ANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val Q_ROOM_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val Q_ROOM_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val Q_SECRET = "SEGREDO-DO-HOSPEDE"

/**
 * Ataque a cotação da estadia e as UHs alocadas: query exata, um filho, erros do contrato, corpo incompatível,
 * cursor em branco, ciclo e página vazia com cursor, ids sem repetição e sem eco em mensagens.
 */
class QuoteStayFacadeAtaqueTest {
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

    private fun listed(roomId: String?): String {
        val assignment =
            if (roomId == null) {
                ""
            } else {
                ""","roomAssignment":{"doNotMove":false,"id":"$Q_ANY","reallocationRequired":false,"roomId":"$roomId"}"""
            }
        return """{"adults":2,"arrival":"2026-11-13","arrivalOverdue":false,"bookingCode":"B-1","bookingId":"$Q_ANY",
        "channel":"DIRECT","children":0,"code":"R-1","dayUse":false,"departure":"2026-11-15","departureOverdue":false,
        "folioId":"$Q_ANY","holderGuestId":"$Q_ANY","id":"$Q_ANY","origin":"FRONT_DESK",
        "ratePlanCategory":"NORMAL","ratePlanId":"$Q_ANY","roomTypeId":"$Q_ANY","segment":"LEISURE",
        "status":"CONFIRMED","total":"300.00","version":1$assignment}"""
    }

    private fun page(
        items: List<String>,
        next: String?,
    ) = """{"items":[${items.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

    private fun date(text: String) = BusinessDate.parse(text)

    private val emptyQuote =
        """{"adults":1,"arrival":"2026-11-13","channel":"DIRECT","childAges":[],"departure":"2026-11-14",
        "nights":1,"offers":[]}"""

    @Test
    fun singleChildSendsOneAgeWithoutComma() =
        runTest(timeout = 10.seconds) {
            core { json(emptyQuote) }.use { core ->
                core.reservations.quoteStay(date("2026-11-13"), date("2026-11-14"), 1, listOf(7))
                assertEquals("7", requests.single().url.parameters["childAges"])
            }
        }

    @Test
    fun childAgesKeepTheCallerOrderAndRepeats() =
        runTest(timeout = 10.seconds) {
            core { json(emptyQuote) }.use { core ->
                core.reservations.quoteStay(date("2026-11-13"), date("2026-11-14"), 2, listOf(8, 0, 8))
                assertEquals("8,0,8", requests.single().url.parameters["childAges"])
            }
        }

    @Test
    fun quoteQueryHasOnlyTheFiveExpectedParameters() =
        runTest(timeout = 10.seconds) {
            core { json(emptyQuote) }.use { core ->
                core.reservations.quoteStay(date("2026-11-13"), date("2026-11-14"), 1, emptyList())
                val parameters = requests.single().url.parameters
                val names = parameters.names()
                assertEquals(setOf("arrival", "departure", "adults", "channel"), names)
            }
        }

    @Test
    fun availabilityQueryInvalidIsProblemWithItsFieldErrors() =
        runTest(timeout = 10.seconds) {
            val body =
                """{"code":"AVAILABILITY_QUERY_INVALID","detail":"d","errors":[{"field":"adults",
                "code":"AVAILABILITY_QUERY_INVALID","message":"Adultos inválidos."}]}"""
            core { json(body, HttpStatusCode.BadRequest) }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.quoteStay(date("2026-11-13"), date("2026-11-14"), 0, emptyList())
                    }
                assertEquals("AVAILABILITY_QUERY_INVALID", error.code)
                assertEquals(listOf("adults"), error.fieldErrors.map { it.field })
            }
        }

    @Test
    fun stayInvalidKeepsFieldErrors() =
        runTest(timeout = 10.seconds) {
            val body =
                """{"code":"STAY_INVALID","detail":"d","errors":[{"field":"departure","code":"STAY_INVALID",
                "message":"Saída inválida."}]}"""
            core { json(body, HttpStatusCode.BadRequest) }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.quoteStay(date("2026-11-13"), date("2026-11-13"), 2, emptyList())
                    }
                assertEquals(listOf("departure"), error.fieldErrors.map { it.field })
            }
        }

    @Test
    fun forbiddenQuoteIsProblem403() =
        runTest(timeout = 10.seconds) {
            core { problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED") }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.quoteStay(date("2026-11-13"), date("2026-11-14"), 1, emptyList())
                    }
                assertEquals(403, error.status)
            }
        }

    @Test
    fun incompatibleQuoteBodyIsIncompatibleResponseWithoutEcho() =
        runTest(timeout = 10.seconds) {
            val bodies =
                listOf(
                    "<html>$Q_SECRET</html>",
                    """{"offers":"$Q_SECRET"}""",
                    """{"adults":"$Q_SECRET","offers":[]}""",
                )
            for (body in bodies) {
                core { json(body) }.use { core ->
                    val error =
                        assertFailsWith<ApiError.IncompatibleResponse> {
                            core.reservations.quoteStay(date("2026-11-13"), date("2026-11-14"), 1, emptyList())
                        }
                    val echoes = generateSequence(error as Throwable) { it.cause }.any { Q_SECRET in "$it" }
                    assertFalse(echoes, "a causa ecoa o corpo")
                    assertFalse(Q_SECRET in error.userMessage)
                }
            }
        }

    @Test
    fun assignedRoomIdsStopsOnBlankNextCursor() =
        runTest(timeout = 10.seconds) {
            core { json(page(listOf(listed(Q_ROOM_A)), "   ")) }.use { core ->
                val ids = core.reservations.assignedRoomIds(date("2026-11-13"), date("2026-11-14"))
                assertEquals(setOf(Q_ROOM_A), ids)
            }
            assertEquals(1, requests.size)
        }

    @Test
    fun assignedRoomIdsStopsWhenTheServerRepeatsTheCursor() =
        runTest(timeout = 10.seconds) {
            core { json(page(listOf(listed(Q_ROOM_A)), "p2")) }.use { core ->
                val ids = core.reservations.assignedRoomIds(date("2026-11-13"), date("2026-11-14"))
                assertEquals(setOf(Q_ROOM_A), ids)
            }
            assertEquals(listOf(null, "p2"), requests.map { it.url.parameters["cursor"] })
        }

    @Test
    fun assignedRoomIdsKeepsReadingPastAnEmptyPageWithCursor() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(listed(Q_ROOM_A)), "p2"))
                    "p2" -> json(page(emptyList(), "p3"))
                    "p3" -> json(page(listOf(listed(Q_ROOM_B)), null))
                    else -> problem(HttpStatusCode.BadRequest, "INVALID_CURSOR")
                }
            }.use { core ->
                val ids = core.reservations.assignedRoomIds(date("2026-11-13"), date("2026-11-14"))
                assertEquals(setOf(Q_ROOM_A, Q_ROOM_B), ids)
            }
            assertEquals(listOf(null, "p2", "p3"), requests.map { it.url.parameters["cursor"] })
        }

    @Test
    fun assignedRoomIdsEmptyOnlyPageIsEmptySetAfterOneRequest() =
        runTest(timeout = 10.seconds) {
            core { json(page(emptyList(), null)) }.use { core ->
                assertTrue(core.reservations.assignedRoomIds(date("2026-11-13"), date("2026-11-13")).isEmpty())
            }
            assertEquals(1, requests.size)
        }

    @Test
    fun assignedRoomIdsAllowsFromEqualToAndSendsBoth() =
        runTest(timeout = 10.seconds) {
            core { json(page(emptyList(), null)) }.use { core ->
                core.reservations.assignedRoomIds(date("2026-11-13"), date("2026-11-13"))
                val query = requests.single().url.parameters
                assertEquals("2026-11-13", query["from"])
                assertEquals("2026-11-13", query["to"])
                assertEquals("PROVISIONAL,CONFIRMED", query["status"])
            }
        }

    @Test
    fun assignedRoomIdsFailureOnALaterPageFailsWholeCall() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(listed(Q_ROOM_A)), "p2"))
                    else -> problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
                }
            }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.assignedRoomIds(date("2026-11-13"), date("2026-11-14"))
                    }
                assertEquals(403, error.status)
            }
        }

    @Test
    fun assignedRoomIdsMalformedBodyIsIncompatibleWithoutEcho() =
        runTest(timeout = 10.seconds) {
            core { json("""{"items":[{"id":"$Q_SECRET","adults":"$Q_SECRET"}]}""") }.use { core ->
                val error =
                    assertFailsWith<ApiError.IncompatibleResponse> {
                        core.reservations.assignedRoomIds(date("2026-11-13"), date("2026-11-14"))
                    }
                val echoes = generateSequence(error as Throwable) { it.cause }.any { Q_SECRET in "$it" }
                assertFalse(echoes, "a causa ecoa o corpo")
                assertFalse(Q_SECRET in error.userMessage)
            }
        }

    @Test
    fun reversedRangeMessageCarriesNoDateOrId() =
        runTest(timeout = 10.seconds) {
            core { json(page(emptyList(), null)) }.use { core ->
                val error =
                    assertFailsWith<IllegalArgumentException> {
                        core.reservations.assignedRoomIds(date("2026-11-15"), date("2026-11-13"))
                    }
                assertFalse("2026" in error.message.orEmpty())
                assertTrue(requests.isEmpty())
            }
        }
}
