package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.UndoCheckInResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ATK_RES = "20000000-0000-0000-0000-0000000000c1"
private const val ATK_ANY = "30000000-0000-0000-0000-0000000000c1"
private const val ATK_ROOM = "50000000-0000-0000-0000-0000000000c1"

private const val ATK_EXPIRED = "O check-in só pode ser desfeito na mesma data operacional em que foi feito."
private const val ATK_NOT_ALLOWED =
    "A reserva não está mais hospedada; o check-in pode já ter sido desfeito. Feche e confira o detalhe."
private const val ATK_UNKNOWN =
    "Não foi possível confirmar o desfazer. Feche e confira o detalhe antes de tentar de novo."

private fun atkUndone(): UndoCheckInResponse =
    Json { ignoreUnknownKeys = true }.decodeFromString(
        UndoCheckInResponse.serializer(),
        """{"reservation":{"adults":2,"arrival":"2026-11-13","bookingId":"$ATK_ANY","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$ATK_ANY","guests":[],"id":"$ATK_RES",
        "roomId":"$ATK_ROOM","roomTypeId":"$ATK_ANY","status":"CONFIRMED","version":3},
        "room":{"housekeepingStatus":"CLEAN","inspectionPending":false,"number":"101","occupancy":"VACANT",
        "roomId":"$ATK_ROOM","version":5}}""",
    )

private fun atkProblem(
    status: Int,
    code: String?,
) = ApiError.Problem(status, code, "detalhe do servidor", emptyList())

private fun atkApproval() = ApiError.ApprovalRequired("UNDO", "p", "r", null, null)

private class AtkApprover {
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

class UndoCheckInStateAtaqueTest {
    private var changes = 0
    private val approver = AtkApprover()

    private fun source() = FakeReceptionSource().apply { undoResponse = atkUndone() }

    private fun TestScope.state(
        source: FakeReceptionSource,
        approve: suspend (ApprovableAction<UndoCheckInResponse>) -> ApiResponse<UndoCheckInResponse>? =
            approver::approve,
    ) = UndoCheckInState(source, backgroundScope, ATK_RES, approve) { changes++ }

    private fun TestScope.confirmNow(state: UndoCheckInState) {
        state.confirm()
        runCurrent()
    }

    private fun TestScope.outcome(failure: Throwable): Pair<UndoCheckInState, FakeReceptionSource> {
        val source = source().apply { undoOutcomes += failure }
        val state = state(source)
        confirmNow(state)
        return state to source
    }

    @Test
    fun expiredHasExactText() =
        runTest {
            val (state, _) = outcome(atkProblem(409, "UNDO_CHECK_IN_EXPIRED"))
            assertEquals(ATK_EXPIRED, state.error)
            assertEquals(0, changes)
        }

    @Test
    fun transitionNotAllowedHasExactTextAndRefreshesOnce() =
        runTest {
            val (state, _) = outcome(atkProblem(409, "RESERVATION_TRANSITION_NOT_ALLOWED"))
            assertEquals(ATK_NOT_ALLOWED, state.error)
            assertEquals(1, changes)
            assertFalse(state.done)
        }

    @Test
    fun networkHasUnknownOutcomeTextAndRefreshesOnce() =
        runTest {
            val (state, _) = outcome(ApiError.Network(IOException("x")))
            assertEquals(ATK_UNKNOWN, state.error)
            assertEquals(1, changes)
            assertFalse(state.done)
        }

    @Test
    fun incompatibleResponseHasUnknownOutcomeTextAndRefreshesOnce() =
        runTest {
            val (state, _) = outcome(ApiError.IncompatibleResponse(IllegalStateException("x")))
            assertEquals(ATK_UNKNOWN, state.error)
            assertEquals(1, changes)
        }

    @Test
    fun sessionEndedUsesSessionTextAndNeverRefreshes() =
        runTest {
            val (state, _) = outcome(ApiError.SessionEnded("SESSION_EXPIRED"))
            assertEquals("Sua sessão foi encerrada. Entre novamente.", state.error)
            assertEquals(0, changes)
        }

    @Test
    fun unknownCodeShowsNoRawCodeNorDetailNorJson() =
        runTest {
            val failure = atkProblem(409, "SOME_FUTURE_CODE")
            val (state, _) = outcome(failure)
            val text = assertNotNull(state.error)
            assertEquals(ActionText.undoError(failure), text)
            assertFalse(text.contains("SOME_FUTURE_CODE"))
            assertFalse(text.contains("{"))
            assertEquals(0, changes)
        }

    @Test
    fun nullCodeProblemDoesNotRefreshNorCrash() =
        runTest {
            val (state, _) = outcome(atkProblem(500, null))
            assertNotNull(state.error)
            assertEquals(0, changes)
        }

    @Test
    fun approvalTokenUsedShowsTextWithoutRawCodeAndNeverRefreshes() =
        runTest {
            val (state, _) = outcome(atkProblem(409, "APPROVAL_TOKEN_USED"))
            val text = assertNotNull(state.error)
            assertFalse(text.contains("APPROVAL_TOKEN_USED"))
            assertEquals(0, changes)
            assertFalse(state.done)
        }

    @Test
    fun forbiddenAndUnexpectedNeverRefresh() =
        runTest {
            val (state, _) = outcome(atkProblem(403, "ACCESS_DENIED"))
            assertNotNull(state.error)
            val (other, _) = outcome(ApiError.Unexpected(500))
            assertNotNull(other.error)
            assertEquals(0, changes)
        }

    @Test
    fun approvalRequiredEscapingTheApproverIsAnErrorWithoutRefresh() =
        runTest {
            val source = source().apply { undoRequiresApproval = atkApproval() }
            val state = state(source) { it.run(null) }
            confirmNow(state)

            assertEquals(ApiError.ApprovalRequired("a", "b", "c", null, null).userMessage, state.error)
            assertEquals(0, changes)
            assertFalse(state.busy)
        }

    @Test
    fun nonApiExceptionFromApproverBecomesUnknownOutcome() =
        runTest {
            val state = state(source()) { throw IOException("rede") }
            confirmNow(state)

            assertEquals(ATK_UNKNOWN, state.error)
            assertFalse(state.busy)
            assertFalse(state.done)
        }

    @Test
    fun successNotifiesExactlyOnceEvenWithApprovalRetry() =
        runTest {
            val source = source().apply { undoRequiresApproval = atkApproval() }
            val state = state(source)
            confirmNow(state)
            advanceUntilIdle()

            assertEquals(1, changes)
            assertEquals(listOf(UndoRun(null), UndoRun("tok")), source.undoRuns)
            assertEquals(1, approver.calls)
        }

    @Test
    fun networkFailureIsNeverRetriedAutomatically() =
        runTest {
            val (state, source) = outcome(ApiError.Network(IOException("x")))
            advanceUntilIdle()

            assertEquals(1, source.undoRuns.size)
            assertEquals(1, approver.calls)
            assertEquals(1, changes)
            assertFalse(state.busy)
        }

    @Test
    fun tokenIsOnlyOnTheApprovedExecutionAndNextConfirmStartsWithoutIt() =
        runTest {
            val source =
                source().apply {
                    undoRequiresApproval = atkApproval()
                    undoOutcomes += atkProblem(409, "UNDO_CHECK_IN_EXPIRED")
                }
            val state = state(source)
            confirmNow(state)
            assertEquals(listOf(UndoRun(null), UndoRun("tok")), source.undoRuns)
            assertEquals(ATK_EXPIRED, state.error)

            confirmNow(state)
            assertEquals(listOf(UndoRun(null), UndoRun("tok"), UndoRun(null), UndoRun("tok")), source.undoRuns)
            assertTrue(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun retryAfterFailureClearsErrorWhileBusy() =
        runTest {
            val source = source().apply { undoOutcomes += atkProblem(409, "UNDO_CHECK_IN_EXPIRED") }
            val state = state(source)
            confirmNow(state)
            assertNotNull(state.error)

            source.holdUndo = true
            state.confirm()
            runCurrent()
            assertTrue(state.busy)
            assertNull(state.error)
            source.heldUndos.forEach { it.complete(Unit) }
            runCurrent()
            assertTrue(state.done)
            assertNull(state.error)
        }

    @Test
    fun confirmWhileBusyAfterApprovalRequiredStillSendsOnce() =
        runTest {
            val source =
                source().apply {
                    holdUndo = true
                    undoRequiresApproval = atkApproval()
                }
            val state = state(source)
            state.confirm()
            runCurrent()
            repeat(5) { state.confirm() }
            runCurrent()
            while (source.heldUndos.any { !it.isCompleted }) {
                source.heldUndos.forEach { it.complete(Unit) }
                runCurrent()
            }

            assertEquals(1, approver.calls)
            assertEquals(listOf(UndoRun(null), UndoRun("tok")), source.undoRuns)
            assertEquals(1, changes)
        }

    @Test
    fun confirmAfterDoneIsNoOp() =
        runTest {
            val source = source()
            val state = state(source)
            confirmNow(state)
            confirmNow(state)
            confirmNow(state)

            assertEquals(1, source.undoRuns.size)
            assertEquals(1, changes)
            assertTrue(state.done)
        }

    @Test
    fun cancelledApprovalWithoutPriorErrorChangesNothingAndAllowsNewConfirm() =
        runTest {
            val source = source().apply { undoRequiresApproval = atkApproval() }
            approver.cancel = true
            val state = state(source)
            confirmNow(state)

            assertNull(state.error)
            assertFalse(state.done)
            assertFalse(state.busy)
            assertEquals(0, changes)

            approver.cancel = false
            confirmNow(state)
            assertTrue(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun approverReturningNullDirectlyDoesNotRunNorNotify() =
        runTest {
            val source = source()
            val state = state(source) { null }
            confirmNow(state)

            assertEquals(0, changes)
            assertNull(state.error)
            assertFalse(state.done)
            assertFalse(state.busy)
        }

    @Test
    fun closeBeforeConfirmMakesConfirmNoOp() =
        runTest {
            val source = source()
            val state = state(source)
            state.close()
            confirmNow(state)

            assertEquals(0, approver.calls)
            assertTrue(source.undoRuns.isEmpty())
            assertFalse(state.busy)
        }

    @Test
    fun closeDuringHeldCallReleasesBusyWithoutErrorOrNotification() =
        runTest {
            val source = source().apply { holdUndo = true }
            val state = state(source)
            state.confirm()
            runCurrent()
            assertTrue(state.busy)
            state.close()
            runCurrent()

            assertFalse(state.busy)
            assertNull(state.error)
            assertFalse(state.done)
            assertEquals(0, changes)
        }

    @Test
    fun lateSuccessAfterCloseDoesNotChangeStateNorNotify() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = source()
            val state =
                state(source) { action ->
                    withContext(NonCancellable) {
                        gate.await()
                        action.run(null)
                    }
                }
            state.confirm()
            runCurrent()
            state.close()
            gate.complete(Unit)
            runCurrent()

            assertFalse(state.done)
            assertNull(state.error)
            assertEquals(0, changes)
        }

    @Test
    fun lateNetworkFailureAfterCloseDoesNotChangeStateNorNotify() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val state =
                state(source()) {
                    withContext(NonCancellable) {
                        gate.await()
                        throw ApiError.Network(IOException("tarde"))
                    }
                }
            state.confirm()
            runCurrent()
            state.close()
            gate.complete(Unit)
            runCurrent()

            assertNull(state.error)
            assertFalse(state.done)
            assertEquals(0, changes)
        }

    @Test
    fun lateCancelledApprovalAfterCloseKeepsPreviousError() =
        runTest {
            val source = source().apply { undoOutcomes += atkProblem(409, "UNDO_CHECK_IN_EXPIRED") }
            val state = state(source)
            confirmNow(state)
            val gate = CompletableDeferred<Unit>()
            val late =
                state(source) {
                    withContext(NonCancellable) {
                        gate.await()
                        null
                    }
                }
            late.confirm()
            runCurrent()
            late.close()
            gate.complete(Unit)
            runCurrent()

            assertNull(late.error)
            assertEquals(ATK_EXPIRED, state.error)
            assertEquals(0, changes)
        }

    @Test
    fun confirmUsesTheReservationIdGiven() =
        runTest {
            val source = source()
            confirmNow(state(source))
            assertEquals(listOf(ATK_RES), source.undoCalls)
        }
}
