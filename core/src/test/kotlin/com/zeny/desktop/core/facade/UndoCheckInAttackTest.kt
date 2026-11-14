package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

/** Ataque ao desfazer check-in: a chave de idempotência nunca sai, o token sai uma vez e só na execução aprovada. */
class UndoCheckInAttackTest {
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

    private fun undone() =
        """{"room":{"housekeepingStatus":"CLEAN","inspectionPending":false,"number":"101","occupancy":"VACANT",
        "roomId":"$ROOM","version":6},
        "reservation":{"adults":2,"arrival":"2026-11-13","bookingId":"$ANY_ID","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$ANY_ID","guests":[],"id":"$RES","roomId":"$ROOM",
        "roomTypeId":"$ANY_ID","status":"CONFIRMED","version":4}}"""

    @Test
    fun noExecutionEverSendsAnIdempotencyKeyEvenAfterNetworkFailure() =
        runTest {
            var calls = 0
            core { if (++calls == 1) throw IOException("sem rede") else json(undone()) }.use { core ->
                val action = core.reservations.undoCheckIn(RES)
                assertFailsWith<ApiError.Network> { action.run(null) }
                action.run(null)
                action.run("tok-1")
                action.run("tok-1")
                action.run(null)
                assertEquals(5, requests.size)
                requests.forEach { assertTrue(it.headers.getAll("Idempotency-Key").isNullOrEmpty(), "${it.headers}") }
            }
        }

    @Test
    fun tokenGoesOnlyInTheApprovedRunAndExactlyOnce() =
        runTest {
            core { json(undone()) }.use { core ->
                val action = core.reservations.undoCheckIn(RES)
                action.run("tok-exato")
                action.run(null)
                assertEquals(listOf("tok-exato"), requests[0].headers.getAll("X-Approval-Token"))
                assertNull(requests[1].headers.getAll("X-Approval-Token"), "a execução sem token não herda o anterior")
            }
        }

    @Test
    fun twoActionsDoNotShareTheToken() =
        runTest {
            core { json(undone()) }.use { core ->
                core.reservations.undoCheckIn(RES).run("tok-A")
                core.reservations.undoCheckIn(RES).run(null)
                assertNull(requests[1].headers.getAll("X-Approval-Token"))
            }
        }

    @Test
    fun actionIsLazy() =
        runTest {
            core { json(undone()) }.use { core ->
                core.reservations.undoCheckIn(RES)
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun approvalRequiredWithoutLimitHasNullLimit() =
        runTest {
            val body =
                """{"code":"APPROVAL_REQUIRED","detail":"d","action":"frontdesk.undocheckin","permission":"p",
                "resourceRef":"r"}"""
            core { json(body, HttpStatusCode.Forbidden) }.use { core ->
                val error = assertFailsWith<ApiError.ApprovalRequired> { core.reservations.undoCheckIn(RES).run(null) }
                assertNull(error.limit)
            }
        }

    @Test
    fun accessDeniedIsProblemNotApprovalRequired() =
        runTest {
            core { problem(HttpStatusCode.Forbidden, "ACCESS_DENIED") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.reservations.undoCheckIn(RES).run(null) }
                assertEquals("ACCESS_DENIED", error.code)
                assertEquals(403, error.status)
            }
        }

    @Test
    fun transitionNotAllowedAndNotFoundAreProblems() =
        runTest {
            listOf(
                Triple(HttpStatusCode.Conflict, "RESERVATION_TRANSITION_NOT_ALLOWED", 409),
                Triple(HttpStatusCode.NotFound, "RESERVATION_NOT_FOUND", 404),
            ).forEach { (status, code, number) ->
                core { problem(status, code) }.use { core ->
                    val error = assertFailsWith<ApiError.Problem> { core.reservations.undoCheckIn(RES).run(null) }
                    assertEquals(code, error.code)
                    assertEquals(number, error.status)
                }
            }
        }

    @Test
    fun rejectedApprovalTokenIsProblemWithoutEchoingTheToken() =
        runTest {
            val body = """{"code":"APPROVAL_TOKEN_USED","detail":"token tok-secreto ja usado"}"""
            core { json(body, HttpStatusCode.Forbidden) }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.reservations.undoCheckIn(RES).run("tok-secreto") }
                assertEquals("APPROVAL_TOKEN_USED", error.code)
                assertEquals("A aprovação já foi usada. Peça uma nova aprovação.", error.userMessage)
                assertTrue("tok-secreto" !in error.message.orEmpty())
                assertTrue("tok-secreto" !in error.userMessage)
            }
        }

    @Test
    fun incompatibleSuccessBodyIsIncompatibleResponseWithoutEcho() =
        runTest {
            core { json("""{"segredo-no-corpo":"xyz"}""") }.use { core ->
                val action = core.reservations.undoCheckIn(RES)
                val error = assertFailsWith<ApiError.IncompatibleResponse> { action.run(null) }
                assertTrue("segredo-no-corpo" !in error.message.orEmpty())
                assertTrue("segredo-no-corpo" !in error.cause?.message.orEmpty())
            }
        }

    @Test
    fun malformedIdsFailBeforeNetworkWithoutEcho() =
        runTest {
            core { json(undone()) }.use { core ->
                listOf("", "   ", "$RES-extra", " $RES", "../$RES", "segredo-xyz").forEach { bad ->
                    val error =
                        assertFailsWith<IllegalArgumentException>("'$bad'") { core.reservations.undoCheckIn(bad) }
                    if (bad.isNotBlank()) assertTrue(bad !in error.message.orEmpty())
                }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun moveRoomRefusesWhitespaceEtagsBeforeNetwork() =
        runTest {
            core { json(undone()) }.use { core ->
                listOf("\t", "\n", " \t\n ").forEach { blank ->
                    val error =
                        assertFailsWith<IllegalArgumentException>("'$blank'") {
                            val input = RoomMoveInput(ROOM, "GUEST_REQUEST", "KEEP")
                            core.reservations.moveRoom(RES, input, blank, Intent())
                        }
                    assertEquals("etag inválido", error.message)
                }
                assertTrue(requests.isEmpty())
            }
        }
}
