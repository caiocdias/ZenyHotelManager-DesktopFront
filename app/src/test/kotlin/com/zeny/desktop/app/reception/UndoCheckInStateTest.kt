package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.UndoCheckInResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UNDO_RES = "20000000-0000-0000-0000-0000000000b1"
private const val UNDO_ANY = "30000000-0000-0000-0000-0000000000b1"
private const val UNDO_ROOM = "50000000-0000-0000-0000-0000000000b1"

private fun undone(): UndoCheckInResponse =
    Json { ignoreUnknownKeys = true }.decodeFromString(
        UndoCheckInResponse.serializer(),
        """{"reservation":{"adults":2,"arrival":"2026-11-13","bookingId":"$UNDO_ANY","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$UNDO_ANY","guests":[],"id":"$UNDO_RES",
        "roomId":"$UNDO_ROOM","roomTypeId":"$UNDO_ANY","status":"CONFIRMED","version":3},
        "room":{"housekeepingStatus":"CLEAN","inspectionPending":false,"number":"101","occupancy":"VACANT",
        "roomId":"$UNDO_ROOM","version":5}}""",
    )

private fun undoProblem(
    status: Int,
    code: String?,
) = ApiError.Problem(status, code, "detalhe do servidor", emptyList())

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private fun undoApproval() = ApiError.ApprovalRequired("UNDO", "p", "r", null, null)

private class UndoApprover {
    var cancel = false
    var calls = 0

    suspend fun approve(action: ApprovableAction<UndoCheckInResponse>): ApiResponse<UndoCheckInResponse>? {
        calls++
        return try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
    }
}

class UndoCheckInStateTest {
    private var changes = 0
    private val approver = UndoApprover()

    private fun source() = FakeReceptionSource().apply { undoResponse = undone() }

    private fun TestScope.state(source: FakeReceptionSource) =
        UndoCheckInState(source, backgroundScope, UNDO_RES, approver::approve) { changes++ }

    private fun TestScope.confirmNow(state: UndoCheckInState) {
        state.confirm()
        runCurrent()
    }

    @Test
    fun confirmUndoesAndNotifies() =
        runTest {
            val source = source()
            val state = state(source)
            confirmNow(state)

            assertTrue(state.done)
            assertNull(state.error)
            assertFalse(state.busy)
            assertEquals(1, changes)
            assertEquals(listOf(UNDO_RES), source.undoCalls)
            assertEquals(listOf(UndoRun(null)), source.undoRuns)
        }

    @Test
    fun approvedRetryCarriesOnlyTheToken() =
        runTest {
            val source = source().apply { undoRequiresApproval = undoApproval() }
            val state = state(source)
            confirmNow(state)

            assertEquals(listOf(UndoRun(null), UndoRun("tok")), source.undoRuns)
            assertTrue(state.done)
        }

    @Test
    fun cancelledApprovalLeavesReservation() =
        runTest {
            val source = source().apply { undoOutcomes += undoProblem(409, "UNDO_CHECK_IN_EXPIRED") }
            val state = state(source)
            confirmNow(state)
            val before = state.error
            source.undoRequiresApproval = undoApproval()
            approver.cancel = true
            confirmNow(state)

            assertEquals(before, state.error)
            assertFalse(state.done)
            assertFalse(state.busy)
            assertEquals(0, changes)
            assertEquals(2, approver.calls)
        }

    @Test
    fun expiredShowsMessage() =
        runTest {
            val source = source().apply { undoOutcomes += undoProblem(409, "UNDO_CHECK_IN_EXPIRED") }
            val state = state(source)
            confirmNow(state)

            assertEquals(ActionText.undoError(undoProblem(409, "UNDO_CHECK_IN_EXPIRED")), state.error)
            assertFalse(state.done)
            assertEquals(0, changes)
        }

    @Test
    fun transitionNotAllowedRefreshes() =
        runTest {
            val source = source().apply { undoOutcomes += undoProblem(409, "RESERVATION_TRANSITION_NOT_ALLOWED") }
            val state = state(source)
            confirmNow(state)

            assertFalse(state.done)
            assertEquals(1, changes)
            assertTrue(state.error != null)
        }

    @Test
    fun unknownResultRefreshes() =
        runTest {
            val source = source().apply { undoOutcomes += ApiError.Network(IOException("x")) }
            val state = state(source)
            confirmNow(state)

            assertFalse(state.done)
            assertEquals(1, changes)
            assertEquals(ActionText.undoError(ApiError.Network(IOException("x"))), state.error)
        }

    @Test
    fun sessionEndedDoesNotRefresh() =
        runTest {
            val source = source().apply { undoOutcomes += ApiError.SessionEnded(null) }
            val state = state(source)
            confirmNow(state)

            assertFalse(state.done)
            assertEquals(0, changes)
            assertTrue(state.error != null)
        }

    @Test
    fun doubleConfirmSendsOnce() =
        runTest {
            val source = source().apply { holdUndo = true }
            val state = state(source)
            state.confirm()
            runCurrent()
            state.confirm()
            runCurrent()
            assertTrue(state.busy)
            source.heldUndos.forEach { it.complete(Unit) }
            runCurrent()
            state.confirm()
            runCurrent()

            assertEquals(1, source.undoRuns.size)
            assertEquals(1, approver.calls)
            assertTrue(state.done)
        }

    @Test
    fun cancellationNeverAnError() =
        runTest {
            val source = source().apply { holdUndo = true }
            val state = state(source)
            state.confirm()
            runCurrent()
            state.close()
            runCurrent()

            assertNull(state.error)
            assertFalse(state.done)
            assertEquals(0, changes)
        }

    @Test
    fun foreignCancellationExceptionBecomesError() =
        runTest {
            val source = source().apply { undoOutcomes += CancellationException("alheia") }
            val state = state(source)
            confirmNow(state)

            assertTrue(state.error != null)
            assertFalse(state.busy)
            assertFalse(state.done)
        }

    @Test
    fun closedStateIgnoresConfirmAndLateResults() =
        runTest {
            val source = source().apply { holdUndo = true }
            val state = state(source)
            state.confirm()
            runCurrent()
            state.close()
            source.heldUndos.forEach { it.complete(Unit) }
            runCurrent()
            state.confirm()
            runCurrent()

            assertEquals(1, source.undoRuns.size)
            assertFalse(state.done)
            assertNull(state.error)
            assertEquals(0, changes)
        }
}
