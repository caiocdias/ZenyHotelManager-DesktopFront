package com.zeny.desktop.app.login

import com.zeny.desktop.core.error.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordChangeStateAtaqueTest {
    private class Calls {
        val changes = mutableListOf<Pair<String, String>>()
        var retries = 0
        var awaiting = false
        var changeFailure: Throwable? = null
        var retryFailure: Throwable? = null
        var changeGate: CompletableDeferred<Unit>? = null
        var retryGate: CompletableDeferred<Unit>? = null
    }

    private fun TestScope.state(
        calls: Calls,
        scope: CoroutineScope = this,
    ) = PasswordChangeState(
        change = { c, n ->
            calls.changes += c to n
            calls.changeGate?.await()
            calls.changeFailure?.let { throw it }
        },
        retry = {
            calls.retries++
            calls.retryGate?.await()
            calls.retryFailure?.let { throw it }
        },
        awaitingRefresh = { calls.awaiting },
        scope = scope,
    )

    private fun PasswordChangeState.fill(
        current: String = "senha-atual-1",
        new: String = "uma-senha-bem-longa",
        confirm: String = new,
    ) {
        this.current = current
        this.new = new
        this.confirm = confirm
    }

    private val badLength = "A nova senha deve ter de 12 a 128 caracteres."

    @Test
    fun lengthBoundariesRejectBelow12AndAbove128() =
        runTest {
            val expected =
                mapOf(
                    0 to false,
                    1 to false,
                    11 to false,
                    12 to true,
                    13 to true,
                    127 to true,
                    128 to true,
                    129 to false,
                    1000 to false,
                )
            for ((len, accepted) in expected) {
                val calls = Calls()
                val s = state(calls)
                s.fill(new = "x".repeat(len))
                s.submit {}
                advanceUntilIdle()
                assertEquals(accepted, calls.changes.size == 1, "tamanho $len")
                if (!accepted) assertEquals(badLength, s.message, "tamanho $len")
                if (accepted) assertNull(s.message, "tamanho $len")
            }
        }

    @Test
    fun rejectedLengthKeepsTheFieldsSoTheUserCanFixThem() =
        runTest {
            val s = state(Calls())
            s.fill(new = "curta")
            s.submit {}
            advanceUntilIdle()
            assertEquals("senha-atual-1", s.current)
            assertEquals("curta", s.new)
            assertEquals("curta", s.confirm)
            assertFalse(s.busy)
        }

    @Test
    fun validationOrderIsCurrentThenLengthThenConfirmationThenSameAsCurrent() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            s.fill(current = "", new = "curta", confirm = "outra")
            s.submit {}
            assertEquals("Informe a senha atual.", s.message)
            s.fill(current = "atual", new = "curta", confirm = "outra")
            s.submit {}
            assertEquals(badLength, s.message)
            s.fill(current = "uma-senha-bem-longa", new = "uma-senha-bem-longa", confirm = "diferente-bem-longa")
            s.submit {}
            assertEquals("A confirmação não confere com a nova senha.", s.message)
            s.fill(current = "uma-senha-bem-longa", new = "uma-senha-bem-longa")
            s.submit {}
            assertEquals("A nova senha deve ser diferente da atual.", s.message)
            advanceUntilIdle()
            assertTrue(calls.changes.isEmpty())
        }

    @Test
    fun confirmationIsCaseAndWhitespaceSensitive() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            s.fill(new = "Uma-Senha-Longa-1", confirm = "uma-senha-longa-1")
            s.submit {}
            assertEquals("A confirmação não confere com a nova senha.", s.message)
            s.fill(new = "Uma-Senha-Longa-1", confirm = "Uma-Senha-Longa-1 ")
            s.submit {}
            assertEquals("A confirmação não confere com a nova senha.", s.message)
            assertTrue(calls.changes.isEmpty())
        }

    @Test
    fun newPasswordDifferingFromCurrentOnlyByCaseOrSpaceIsAccepted() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            s.fill(current = "uma-senha-bem-longa", new = "Uma-senha-bem-longa")
            s.submit {}
            advanceUntilIdle()
            s.fill(current = "uma-senha-bem-longa", new = "uma-senha-bem-longa ")
            s.submit {}
            advanceUntilIdle()
            assertEquals(2, calls.changes.size)
        }

    @Test
    fun passwordsAreSentVerbatimWithoutTrimming() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            s.fill(current = " atual com espaco ", new = "  nova senha longa  ")
            s.submit {}
            advanceUntilIdle()
            assertEquals(listOf(" atual com espaco " to "  nova senha longa  "), calls.changes)
        }

    @Test
    fun validAttemptClearsThePreviousValidationMessage() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            s.fill(new = "curta")
            s.submit {}
            assertEquals(badLength, s.message)
            s.fill()
            s.submit {}
            assertNull(s.message)
            advanceUntilIdle()
            assertNull(s.message)
        }

    @Test
    fun doubleSubmitInFlightChangesOnce() =
        runTest {
            val calls = Calls().apply { changeGate = CompletableDeferred() }
            val s = state(calls)
            s.fill()
            var done = 0
            s.submit { done++ }
            runCurrent()
            assertTrue(s.busy)
            s.submit { done++ }
            s.submit { done++ }
            runCurrent()
            calls.changeGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, calls.changes.size)
            assertEquals(1, done)
            assertFalse(s.busy)
        }

    @Test
    fun doubleSubmitBeforeTheCoroutineStartsStillChangesOnce() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            s.fill()
            var done = 0
            s.submit { done++ }
            s.submit { done++ }
            advanceUntilIdle()
            assertEquals(1, calls.changes.size)
            assertEquals(1, done)
        }

    @Test
    fun doubleSubmitInRetryModeRetriesOnce() =
        runTest {
            val calls = Calls().apply { awaiting = true }
            calls.retryGate = CompletableDeferred()
            val s = state(calls)
            s.submit {}
            runCurrent()
            s.submit {}
            runCurrent()
            calls.retryGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, calls.retries)
            assertTrue(calls.changes.isEmpty())
        }

    @Test
    fun fieldsAreClearedOnlyAfterTheCallEnds() =
        runTest {
            val calls = Calls().apply { changeGate = CompletableDeferred() }
            val s = state(calls)
            s.fill()
            s.submit {}
            runCurrent()
            assertEquals(listOf("senha-atual-1" to "uma-senha-bem-longa"), calls.changes)
            calls.changeGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals("", s.current + s.new + s.confirm)
        }

    @Test
    fun awaitingRefreshIgnoresInvalidFieldsAndNeverValidates() =
        runTest {
            val calls = Calls().apply { awaiting = true }
            val s = state(calls)
            s.fill(current = "", new = "x", confirm = "y")
            var done = 0
            s.submit { done++ }
            advanceUntilIdle()
            assertEquals(1, calls.retries)
            assertTrue(calls.changes.isEmpty())
            assertNull(s.message)
            assertEquals(1, done)
            assertEquals("", s.current + s.new + s.confirm)
        }

    @Test
    fun awaitingRefreshFlippingBetweenSubmitsSwitchesBetweenChangeAndRetry() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            s.fill()
            s.submit {}
            advanceUntilIdle()
            assertEquals(1, calls.changes.size)
            assertEquals(0, calls.retries)
            calls.awaiting = true
            s.submit {}
            advanceUntilIdle()
            assertEquals(1, calls.changes.size)
            assertEquals(1, calls.retries)
            calls.awaiting = false
            s.fill()
            s.submit {}
            advanceUntilIdle()
            assertEquals(2, calls.changes.size)
            assertEquals(1, calls.retries)
        }

    @Test
    fun awaitingRefreshFlippingMidFlightDoesNotAddACall() =
        runTest {
            val calls = Calls().apply { changeGate = CompletableDeferred() }
            val s = state(calls)
            s.fill()
            s.submit {}
            runCurrent()
            calls.awaiting = true
            s.submit {}
            runCurrent()
            calls.changeGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, calls.changes.size)
            assertEquals(0, calls.retries)
        }

    @Test
    fun retryFailureShowsMessageDoesNotFinishAndCanBeRepeated() =
        runTest {
            val calls = Calls().apply { awaiting = true }
            calls.retryFailure = ApiError.Network(RuntimeException("x"))
            val s = state(calls)
            var done = 0
            s.submit { done++ }
            advanceUntilIdle()
            assertEquals(ApiError.Network(RuntimeException()).userMessage, s.message)
            assertEquals(0, done)
            assertFalse(s.busy)
            calls.retryFailure = null
            s.submit { done++ }
            advanceUntilIdle()
            assertNull(s.message)
            assertEquals(1, done)
            assertEquals(2, calls.retries)
        }

    @Test
    fun changeFailingWithEachApiErrorShowsItsUserMessageAndClearsAllFields() =
        runTest {
            val errors =
                listOf(
                    ApiError.Problem(400, "password.weak", "detalhe do servidor", emptyList()),
                    ApiError.Problem(401, null, null, emptyList()),
                    ApiError.ApprovalRequired("a", "p", "r", null, null),
                    ApiError.PreconditionFailed(null),
                    ApiError.PasswordChangeRequired,
                    ApiError.SessionEnded("expired"),
                    ApiError.Network(RuntimeException("segredo-xyz")),
                    ApiError.IncompatibleResponse(RuntimeException("x")),
                    ApiError.Unexpected(500),
                )
            for (e in errors) {
                val calls = Calls().apply { changeFailure = e }
                val s = state(calls)
                s.fill(current = "atual-segredo-xyz", new = "nova-segredo-xyz-1")
                var done = 0
                s.submit { done++ }
                advanceUntilIdle()
                assertEquals(e.userMessage, s.message, e::class.simpleName)
                assertEquals(0, done, e::class.simpleName)
                assertEquals("", s.current + s.new + s.confirm, e::class.simpleName)
                assertFalse(s.busy, e::class.simpleName)
                assertFalse(s.toString().contains("segredo-xyz"))
            }
        }

    @Test
    fun anyOtherExceptionShowsOnlyTheGenericMessage() =
        runTest {
            val throwables =
                listOf<Throwable>(
                    IllegalStateException("nova-segredo"),
                    java.io.IOException("atual-segredo"),
                    NullPointerException(),
                )
            for (t in throwables) {
                val calls = Calls().apply { changeFailure = t }
                val s = state(calls)
                s.fill(current = "atual-segredo", new = "nova-segredo-1234")
                s.submit {}
                advanceUntilIdle()
                assertEquals("Não foi possível trocar a senha.", s.message, t::class.simpleName)
                assertFalse(s.message!!.contains("segredo"))
                assertFalse(s.busy)
            }
        }

    @Test
    fun anyExceptionFromRetryShowsTheGenericMessage() =
        runTest {
            val calls = Calls().apply { awaiting = true }
            calls.retryFailure = IllegalStateException("x")
            val s = state(calls)
            s.submit {}
            advanceUntilIdle()
            assertEquals("Não foi possível trocar a senha.", s.message)
            assertFalse(s.busy)
        }

    @Test
    fun cancellationFromChangeDoesNotFinishNorShowAMessageButResetsState() =
        runTest {
            val calls = Calls().apply { changeFailure = CancellationException("cancelado") }
            val s = state(calls)
            s.fill()
            var done = 0
            s.submit { done++ }
            advanceUntilIdle()
            assertEquals(0, done)
            assertNull(s.message)
            assertFalse(s.busy)
            assertEquals("", s.current + s.new + s.confirm)
        }

    @Test
    fun cancellationFromRetryDoesNotFinishNorShowAMessageButResetsState() =
        runTest {
            val calls = Calls().apply { awaiting = true }
            calls.retryFailure = CancellationException("cancelado")
            val s = state(calls)
            var done = 0
            s.submit { done++ }
            advanceUntilIdle()
            assertEquals(0, done)
            assertNull(s.message)
            assertFalse(s.busy)
        }

    @Test
    fun scopeCancelledInTheMiddleOfChangeResetsBusyAndClearsEveryField() =
        runTest {
            val calls = Calls().apply { changeGate = CompletableDeferred() }
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val s = state(calls, scope)
            s.fill()
            var done = 0
            s.submit { done++ }
            runCurrent()
            assertTrue(s.busy)
            scope.cancel()
            runCurrent()
            assertFalse(s.busy)
            assertEquals("", s.current + s.new + s.confirm)
            assertEquals(0, done)
        }

    @Test
    fun scopeCancelledBeforeTheChangeStartsStillClearsFieldsAndBusy() =
        runTest {
            val calls = Calls()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val s = state(calls, scope)
            s.fill()
            s.submit {}
            scope.cancel()
            runCurrent()
            assertTrue(calls.changes.isEmpty())
            assertEquals("", s.current + s.new + s.confirm, "as senhas ficaram no estado depois do cancelamento")
            assertFalse(s.busy, "busy ficou preso em verdadeiro")
        }

    @Test
    fun noPasswordInToStringOrMessageAfterAnyOutcome() =
        runTest {
            val secretA = "Atual-Secreta-123"
            val secretB = "Nova-Secreta-456789"
            val failures: List<Throwable?> =
                listOf(
                    null,
                    ApiError.Network(RuntimeException(secretA)),
                    IllegalStateException(secretB),
                    CancellationException(secretA),
                )
            for (f in failures) {
                val calls = Calls().apply { changeFailure = f }
                val s = state(calls)
                s.fill(current = secretA, new = secretB)
                assertFalse(s.toString().contains(secretA) || s.toString().contains(secretB), "antes")
                s.submit {}
                advanceUntilIdle()
                val text = s.toString() + s.message.orEmpty()
                assertFalse(text.contains(secretA) || text.contains(secretB), "depois de $f")
            }
            val s = state(Calls())
            s.fill(current = secretA, new = secretA, confirm = secretA)
            s.submit {}
            assertFalse(s.message.orEmpty().contains(secretA))
        }
}
