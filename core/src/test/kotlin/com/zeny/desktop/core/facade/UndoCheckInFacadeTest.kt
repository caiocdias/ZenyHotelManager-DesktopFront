package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

/** Desfazer check-in da `ReservationsFacade`, contra o `MockEngine`: ação sensível, sem `Idempotency-Key`. */
class UndoCheckInFacadeTest {
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

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"frontdesk.undocheckin","permission":"p",
        "resourceRef":"r"}"""

    @Test
    fun undoNeverSendsIdempotencyKey() =
        runTest {
            core { json(undone()) }.use { core ->
                val action = core.reservations.undoCheckIn(RES)
                val first = action.run(null)
                action.run("tok-1")
                assertEquals("CONFIRMED", first.value.reservation.status)
                assertEquals(2, requests.size)
                requests.forEach {
                    assertEquals(HttpMethod.Post, it.method)
                    assertTrue(it.url.encodedPath.endsWith("/$RES/undo-check-in"), it.url.encodedPath)
                    assertNull(it.headers["Idempotency-Key"])
                }
            }
        }

    @Test
    fun approvedUndoCarriesToken() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(approvalProblem, HttpStatusCode.Forbidden)
                } else {
                    json(undone())
                }
            }.use { core ->
                val action = core.reservations.undoCheckIn(RES)
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                action.run("tok-1")
                assertNull(requests[0].headers["X-Approval-Token"])
                assertEquals(listOf("tok-1"), requests[1].headers.getAll("X-Approval-Token"))
            }
        }

    @Test
    fun approvalRequiredSurfaces() =
        runTest {
            core { json(approvalProblem, HttpStatusCode.Forbidden) }.use { core ->
                val error = assertFailsWith<ApiError.ApprovalRequired> { core.reservations.undoCheckIn(RES).run(null) }
                assertEquals("frontdesk.undocheckin", error.action)
                assertEquals("p", error.permission)
                assertEquals("r", error.resourceRef)
            }
        }

    @Test
    fun expiredIsProblem() =
        runTest {
            core { problem(HttpStatusCode.UnprocessableEntity, "UNDO_CHECK_IN_EXPIRED") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.reservations.undoCheckIn(RES).run(null) }
                assertEquals("UNDO_CHECK_IN_EXPIRED", error.code)
                assertEquals("O check-in só pode ser desfeito no mesmo dia em que foi feito.", error.userMessage)
            }
        }

    @Test
    fun notInHouseIsProblem() =
        runTest {
            core { problem(HttpStatusCode.Conflict, "RESERVATION_NOT_IN_HOUSE") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.reservations.undoCheckIn(RES).run(null) }
                assertEquals("RESERVATION_NOT_IN_HOUSE", error.code)
            }
        }

    @Test
    fun invalidIdFailsBeforeNetwork() =
        runTest {
            core { json(undone()) }.use { core ->
                val bad = "segredo-nao-uuid"
                val error = assertFailsWith<IllegalArgumentException> { core.reservations.undoCheckIn(bad) }
                assertTrue(bad !in error.message.orEmpty())
                assertTrue(requests.isEmpty())
            }
        }
}
