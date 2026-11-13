package com.zeny.desktop.core.facade

import com.zeny.desktop.core.FakeZenyServer
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

private const val A_RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val A_ANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val A_P1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e11"
private const val A_P2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e12"
private const val A_P3 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e13"
private const val SECRET = "SEGREDO-DO-HOSPEDE"

/**
 * Ataque à fachada das reservas do dia: cursor que não avança ou vem em branco, página vazia no meio do histórico,
 * `id` fora do formato, corpo malformado sem eco, `401` e `404`, de ponta a ponta com sessão aberta.
 */
class ReservationsFacadeAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private val server = FakeZenyServer()

    private suspend fun core(routes: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ZenyCore {
        server.routes = routes
        val core = ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemorySettings(), server.engine)
        core.session.login("maria", "segredo")
        return core
    }

    private fun item(id: String) =
        """{"adults":2,"arrival":"2026-11-13","arrivalOverdue":false,"bookingCode":"B-1","bookingId":"$A_ANY",
        "channel":"DIRECT","children":0,"code":"R-1","dayUse":false,"departure":"2026-11-15","departureOverdue":false,
        "folioId":"$A_ANY","holderGuestId":"$A_ANY","id":"$id","origin":"FRONT_DESK","ratePlanCategory":"NORMAL",
        "ratePlanId":"$A_ANY","roomTypeId":"$A_ANY","segment":"LEISURE","status":"CONFIRMED","total":"300.00",
        "version":1}"""

    private fun state() =
        """{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-13","cancellationPolicyId":"$A_ANY",
        "cancellationPolicyVersion":1,"childAges":[],"departure":"2026-11-15","guaranteePolicyId":"$A_ANY",
        "guaranteePolicyVersion":1,"hasSpecialRequests":false,"nights":[],"ratePlanId":"$A_ANY","roomTotal":"300.00",
        "roomTypeId":"$A_ANY","segment":"LEISURE","status":"CONFIRMED","stayGuests":[],"total":"300.00"}"""

    private fun entry(
        id: String,
        kind: String,
    ) = """{"after":${state()},"before":${state()},"businessDate":"2026-11-13","changedFields":["status"],"id":"$id",
        "kind":"$kind","occurredAt":"2026-11-13T12:00:00Z","version":2}"""

    private fun page(
        items: List<String>,
        next: String?,
    ) = """{"items":[${items.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

    private fun historyRequests() =
        server.requests.filter { it.url.encodedPath == "/api/v1/reservations/$A_RES/history" }

    private val day = BusinessDate.parse("2026-11-13")

    @Test
    fun historyStopsWhenServerRepeatsTheCursorItWasSent() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(entry(A_P1, "CONFIRM")), "p2"))
                    else -> json(page(listOf(entry(A_P2, "CHECK_IN")), "p2"))
                }
            }.use { core ->
                assertEquals(listOf("CONFIRM", "CHECK_IN"), core.reservations.history(A_RES).map { it.kind })
            }
            assertEquals(listOf(null, "p2"), historyRequests().map { it.url.parameters["cursor"] })
        }

    @Test
    fun historyStopsOnBlankNextCursor() =
        runTest(timeout = 10.seconds) {
            core { json(page(listOf(entry(A_P1, "CONFIRM")), "   ")) }.use { core ->
                assertEquals(listOf("CONFIRM"), core.reservations.history(A_RES).map { it.kind })
            }
            assertEquals(1, historyRequests().size)
        }

    @Test
    fun historyKeepsReadingPastAnEmptyPageInTheMiddle() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(entry(A_P1, "CONFIRM")), "p2"))
                    "p2" -> json(page(emptyList(), "p3"))
                    "p3" -> json(page(listOf(entry(A_P3, "CHECK_OUT")), null))
                    else -> problem(HttpStatusCode.BadRequest, "INVALID_CURSOR")
                }
            }.use { core ->
                assertEquals(listOf("CONFIRM", "CHECK_OUT"), core.reservations.history(A_RES).map { it.kind })
            }
            assertEquals(listOf(null, "p2", "p3"), historyRequests().map { it.url.parameters["cursor"] })
        }

    @Test
    fun historyEmptyOnlyPageIsEmptyListAfterOneRequest() =
        runTest(timeout = 10.seconds) {
            core { json(page(emptyList(), null)) }.use { core ->
                assertEquals(emptyList(), core.reservations.history(A_RES))
            }
            assertEquals(1, historyRequests().size)
        }

    @Test
    fun historyFailureOnALaterPageFailsTheWholeCallWithoutPartialList() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(entry(A_P1, "CONFIRM")), "p2"))
                    else -> problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
                }
            }.use { core ->
                assertEquals(403, assertFailsWith<ApiError.Problem> { core.reservations.history(A_RES) }.status)
                assertIs<SessionState.Active>(core.session.state.value)
            }
        }

    @Test
    fun dayPageExplicitNullNextCursorIsNull() =
        runTest(timeout = 10.seconds) {
            core { json("""{"items":[${item(A_P1)}],"nextCursor":null}""") }.use { core ->
                assertNull(core.reservations.dayPage(DayList.IN_HOUSE, day, null, 50).nextCursor)
            }
        }

    @Test
    fun dayPageBlankNextCursorIsTheLastPage() =
        runTest(timeout = 10.seconds) {
            // `Page` documenta "nextCursor nulo ou em branco é a última"; quem testa só `!= null` não pode girar.
            core { json(page(listOf(item(A_P1)), "")) }.use { core ->
                assertNull(core.reservations.dayPage(DayList.ARRIVALS, day, null, 50).nextCursor)
            }
        }

    @Test
    fun dayPageEmptyListIsEmptyPage() =
        runTest(timeout = 10.seconds) {
            core { json(page(emptyList(), null)) }.use { core ->
                val result = core.reservations.dayPage(DayList.DEPARTURES, day, null, 50)
                assertEquals(emptyList(), result.items)
                assertNull(result.nextCursor)
            }
        }

    @Test
    fun malformedIdsFailBeforeNetworkEvenWhenTheyLookLikeUuids() =
        runTest(timeout = 10.seconds) {
            core { problem(HttpStatusCode.NotFound, "RESERVATION_NOT_FOUND") }.use { core ->
                val before = server.requests.size
                val bad =
                    listOf(" $A_RES", "$A_RES ", "$A_RES\n", A_RES.dropLast(1), "${A_RES}0", "{$A_RES}", "%20$A_RES")
                for (id in bad) {
                    assertFailsWith<IllegalArgumentException>("id '$id'") { core.reservations.reservation(id) }
                    assertFailsWith<IllegalArgumentException>("id '$id'") { core.reservations.history(id) }
                    assertFailsWith<IllegalArgumentException>("id '$id'") { core.reservations.roomAssignments(id) }
                }
                assertEquals(before, server.requests.size, "nenhuma requisição para id inválido")
            }
        }

    @Test
    fun uppercaseIdIsSentCanonicalLowercaseInThePath() =
        runTest(timeout = 10.seconds) {
            core { json(page(emptyList(), null)) }.use { core ->
                core.reservations.history(A_RES.uppercase())
            }
            assertEquals("/api/v1/reservations/$A_RES/history", historyRequests().single().url.encodedPath)
        }

    @Test
    fun invalidIdMessageDoesNotEchoTheId() =
        runTest(timeout = 10.seconds) {
            core { problem(HttpStatusCode.NotFound, "RESERVATION_NOT_FOUND") }.use { core ->
                val error = assertFailsWith<IllegalArgumentException> { core.reservations.reservation("id-$SECRET") }
                assertFalse(SECRET in error.message.orEmpty())
            }
        }

    @Test
    fun malformedBodiesAreIncompatibleResponseWithoutEchoingTheBody() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when {
                    request.url.encodedPath.endsWith("/history") -> {
                        json("""{"items":[{"id":"$SECRET","adults":"$SECRET"}]}""")
                    }

                    request.url.encodedPath.endsWith("/room-assignments") -> {
                        json("""{"items":"$SECRET"}""")
                    }

                    request.url.encodedPath.contains("/arrivals") -> {
                        json("<html>$SECRET</html>")
                    }

                    else -> {
                        json("""{"id":"$SECRET"}""")
                    }
                }
            }.use { core ->
                val errors =
                    listOf(
                        assertFailsWith<ApiError.IncompatibleResponse> { core.reservations.history(A_RES) },
                        assertFailsWith<ApiError.IncompatibleResponse> { core.reservations.roomAssignments(A_RES) },
                        assertFailsWith<ApiError.IncompatibleResponse> {
                            core.reservations.dayPage(DayList.ARRIVALS, day, null, 50)
                        },
                        assertFailsWith<ApiError.IncompatibleResponse> { core.reservations.reservation(A_RES) },
                    )
                for (error in errors) {
                    val echoes = generateSequence(error as Throwable) { it.cause }.any { SECRET in "$it" }
                    assertFalse(echoes, "a causa ecoa o corpo")
                    assertFalse(SECRET in error.userMessage)
                }
                assertIs<SessionState.Active>(core.session.state.value, "resposta incompatível não encerra a sessão")
            }
        }

    @Test
    fun wrongTypedFieldInALaterHistoryPageIsIncompatibleNotPartial() =
        runTest(timeout = 10.seconds) {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> {
                        json(page(listOf(entry(A_P1, "CONFIRM")), "p2"))
                    }

                    else -> {
                        val broken = entry(A_P2, "CHECK_IN").replace("\"adults\":2", "\"adults\":\"dois\"")
                        json(page(listOf(broken), null))
                    }
                }
            }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.reservations.history(A_RES) }
            }
        }

    @Test
    fun revokedSessionIsSessionEndedOnEveryOperation() =
        runTest(timeout = 10.seconds) {
            core { json(page(emptyList(), null)) }.use { core ->
                server.rejectAllWith = "SESSION_REVOKED"
                val ended =
                    assertFailsWith<ApiError.SessionEnded> {
                        core.reservations.dayPage(DayList.ARRIVALS, day, null, 50)
                    }
                assertEquals("SESSION_REVOKED", ended.code)
                assertIs<SessionState.Ended>(core.session.state.value)
                assertFailsWith<ApiError.SessionEnded> { core.reservations.dayPage(DayList.DEPARTURES, day, null, 50) }
                assertFailsWith<ApiError.SessionEnded> { core.reservations.dayPage(DayList.IN_HOUSE, day, null, 50) }
                assertFailsWith<ApiError.SessionEnded> { core.reservations.reservation(A_RES) }
                assertFailsWith<ApiError.SessionEnded> { core.reservations.history(A_RES) }
                assertFailsWith<ApiError.SessionEnded> { core.reservations.roomAssignments(A_RES) }
            }
            assertEquals(0, server.count("/api/v1/auth/refresh"))
        }

    @Test
    fun forbiddenAndNotFoundAreProblemsAndKeepTheSession() =
        runTest(timeout = 10.seconds) {
            core { request ->
                if (request.url.encodedPath.endsWith("/room-assignments")) {
                    problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
                } else {
                    problem(HttpStatusCode.NotFound, "RESERVATION_NOT_FOUND")
                }
            }.use { core ->
                assertEquals(404, assertFailsWith<ApiError.Problem> { core.reservations.history(A_RES) }.status)
                assertEquals(403, assertFailsWith<ApiError.Problem> { core.reservations.roomAssignments(A_RES) }.status)
                val list =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.dayPage(DayList.ARRIVALS, day, null, 50)
                    }
                assertEquals(404, list.status)
                assertIs<SessionState.Active>(core.session.state.value)
            }
        }

    @Test
    fun dayPageSendsTheBusinessDateUnconvertedAndTheSessionBearer() =
        runTest(timeout = 10.seconds) {
            core { json(page(emptyList(), null)) }.use { core ->
                core.reservations.dayPage(DayList.ARRIVALS, BusinessDate.parse("2026-01-01"), null, 1)
            }
            val sent = server.requests.single { it.url.encodedPath == "/api/v1/reservations/arrivals" }
            assertEquals("2026-01-01", sent.url.parameters["date"])
            assertEquals(listOf("Bearer A1"), server.authorizationOf(sent))
        }
}
