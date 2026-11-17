package com.zeny.desktop.app.reservations

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val LITERAL =
    "Não foi possível ler as suas permissões. " +
        "As ações desta área ficam indisponíveis até a leitura dar certo."
private const val SECRET = "segredo-do-servidor-123"

/** Leitura falsa que conta chamadas, registra o cancelamento e espera a resposta do teste. */
private class AttackRead {
    var calls = 0
    var cancelled = 0
    private val answers = ArrayDeque<CompletableDeferred<Set<String>>>()

    suspend fun read(): Set<String> {
        calls++
        val answer = CompletableDeferred<Set<String>>()
        answers.addLast(answer)
        try {
            return answer.await()
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
    }

    fun answer(permissions: Set<String>) = answers.removeFirst().complete(permissions)

    fun fail(error: Throwable) = answers.removeFirst().completeExceptionally(error)
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class AreaPermissionsStateAtaqueTest {
    private fun TestScope.state(read: AttackRead) = AreaPermissionsState(read::read, backgroundScope)

    private fun TestScope.started(read: AttackRead): AreaPermissionsState {
        val state = state(read)
        state.start()
        runCurrent()
        return state
    }

    @Test
    fun everyApiErrorSubtypeIsFailedNeverAGrant() =
        runTest {
            val errors =
                listOf(
                    ApiError.Problem(403, "FORBIDDEN", "x", emptyList()),
                    ApiError.ApprovalRequired("a", "p", "r", null, null),
                    ApiError.PreconditionFailed(null),
                    ApiError.PasswordChangeRequired,
                    ApiError.SessionEnded("c"),
                    ApiError.Network(RuntimeException(SECRET)),
                    ApiError.IncompatibleResponse(RuntimeException(SECRET)),
                    ApiError.Unexpected(502),
                )
            errors.forEach { error ->
                val read = AttackRead()
                val state = started(read)
                read.fail(error)
                runCurrent()
                assertEquals(AreaPermissions.Failed, state.value, "subtipo ${error::class.simpleName}")
            }
        }

    @Test
    fun nonApiExceptionsAreFailed() =
        runTest {
            val errors = listOf(RuntimeException("x"), NullPointerException(), java.io.IOException("io"))
            errors
                .forEach { error ->
                    val read = AttackRead()
                    val state = started(read)
                    read.fail(error)
                    runCurrent()
                    assertEquals(AreaPermissions.Failed, state.value, "tipo ${error::class.simpleName}")
                }
        }

    @Test
    fun throwableThatIsNotAnExceptionIsAlsoFailedNotStuckLoading() =
        runTest {
            // Falha de qualquer tipo: um Error não pode deixar a área em Loading para sempre, sem aviso nem retry.
            val handler = CoroutineExceptionHandler { _, _ -> }
            val read = AttackRead()
            val state = AreaPermissionsState(read::read, CoroutineScope(backgroundScope.coroutineContext + handler))
            state.start()
            runCurrent()
            read.fail(NotImplementedError("sem implementação"))
            runCurrent()
            assertNotEquals(AreaPermissions.Loading, state.value)
            assertEquals(AreaPermissions.Failed, state.value)
        }

    @Test
    fun emptySetReturnedWithSuccessIsALegitimateEmptyGrant() =
        runTest {
            val read = AttackRead()
            val state = started(read)
            read.answer(emptySet())
            runCurrent()
            assertEquals(AreaPermissions.Granted(emptySet()), state.value)
            assertNotEquals(AreaPermissions.Failed, state.value)
        }

    @Test
    fun severalRetriesInARowFromFailedReadOnlyOnce() =
        runTest {
            val read = AttackRead()
            val state = started(read)
            read.fail(IllegalStateException("falha"))
            runCurrent()
            state.retry()
            state.retry()
            state.retry()
            runCurrent()
            assertEquals(2, read.calls)
            assertEquals(AreaPermissions.Loading, state.value)
            read.answer(setOf("a"))
            runCurrent()
            assertEquals(AreaPermissions.Granted(setOf("a")), state.value)
            assertEquals(2, read.calls)
        }

    @Test
    fun readerSeesLoadingBetweenRetryAndAnswerNeverFailedOrOldGrant() =
        runTest {
            val read = AttackRead()
            val state = started(read)
            read.fail(IllegalStateException("falha"))
            runCurrent()
            state.retry()
            assertEquals(AreaPermissions.Loading, state.value)
            runCurrent()
            assertEquals(AreaPermissions.Loading, state.value)
            read.answer(setOf("nova"))
            runCurrent()
            assertEquals(AreaPermissions.Granted(setOf("nova")), state.value)
        }

    @Test
    fun secondFailureAfterRetryIsFailedAgainAndRetryableAgain() =
        runTest {
            val read = AttackRead()
            val state = started(read)
            read.fail(IllegalStateException("1"))
            runCurrent()
            state.retry()
            runCurrent()
            read.fail(IllegalStateException("2"))
            runCurrent()
            assertEquals(AreaPermissions.Failed, state.value)
            state.retry()
            runCurrent()
            read.answer(setOf("ok"))
            runCurrent()
            assertEquals(AreaPermissions.Granted(setOf("ok")), state.value)
            assertEquals(3, read.calls)
        }

    @Test
    fun startAfterCloseNeverReads() =
        runTest {
            val read = AttackRead()
            val state = state(read)
            state.close()
            state.start()
            runCurrent()
            assertEquals(0, read.calls)
            assertEquals(AreaPermissions.Loading, state.value)
        }

    @Test
    fun retryAfterCloseFromFailedNeverReadsAndStaysFailed() =
        runTest {
            val read = AttackRead()
            val state = started(read)
            read.fail(IllegalStateException("falha"))
            runCurrent()
            state.close()
            state.retry()
            runCurrent()
            assertEquals(1, read.calls)
            assertEquals(AreaPermissions.Failed, state.value)
        }

    @Test
    fun closeInTheMiddleOfRetryCancelsTheReadAndDropsTheAnswer() =
        runTest {
            val read = AttackRead()
            val state = started(read)
            read.fail(IllegalStateException("falha"))
            runCurrent()
            state.retry()
            runCurrent()
            state.close()
            runCurrent()
            assertEquals(1, read.cancelled)
            assertEquals(AreaPermissions.Loading, state.value)
            state.retry()
            runCurrent()
            assertEquals(2, read.calls)
            assertEquals(AreaPermissions.Loading, state.value)
        }

    @Test
    fun closeCancelsPendingReadAndLateFailureDoesNotShowFailed() =
        runTest {
            val read = AttackRead()
            val state = started(read)
            state.close()
            runCurrent()
            assertEquals(1, read.cancelled)
            assertEquals(AreaPermissions.Loading, state.value)
        }

    @Test
    fun closeAfterGrantKeepsTheGrantAndDoesNotRead() =
        runTest {
            val read = AttackRead()
            val state = started(read)
            read.answer(setOf("a"))
            runCurrent()
            state.close()
            state.start()
            state.retry()
            runCurrent()
            assertEquals(AreaPermissions.Granted(setOf("a")), state.value)
            assertEquals(1, read.calls)
        }

    @Test
    fun closeIsIdempotentAndDoesNotCancelTheParentScope() =
        runTest {
            val read = AttackRead()
            val parent = Job()
            val state = AreaPermissionsState(read::read, CoroutineScope(coroutineContext + parent))
            state.start()
            runCurrent()
            state.close()
            state.close()
            runCurrent()
            assertTrue(parent.isActive, "o close de uma área não pode cancelar o escopo da UI")
            parent.cancel()
        }

    @Test
    fun scopeCancellationReachesTheReadAndNeverBecomesFailed() =
        runTest {
            val read = AttackRead()
            val parent = Job()
            val state = AreaPermissionsState(read::read, CoroutineScope(coroutineContext + parent))
            state.start()
            runCurrent()
            parent.cancel()
            runCurrent()
            assertEquals(1, read.cancelled, "a leitura precisa ver o cancelamento")
            assertEquals(AreaPermissions.Loading, state.value)
        }

    @Test
    fun cancellationExceptionThrownByTheReadIsNotFailedAndNotGranted() =
        runTest {
            val read = AttackRead()
            val state = started(read)
            read.fail(CancellationException("cancelada"))
            runCurrent()
            assertNotEquals(AreaPermissions.Failed, state.value)
            assertFalse(state.value is AreaPermissions.Granted)
        }

    @Test
    fun retryAfterScopeCancellationNeverGrants() =
        runTest {
            val read = AttackRead()
            val parent = Job()
            val state = AreaPermissionsState(read::read, CoroutineScope(coroutineContext + parent))
            state.start()
            runCurrent()
            parent.cancel()
            runCurrent()
            state.retry()
            runCurrent()
            assertFalse(state.value is AreaPermissions.Granted)
        }

    @Test
    fun exceptionMessageIsNeverKeptInTheStateOrItsValues() =
        runTest {
            val read = AttackRead()
            val state = started(read)
            read.fail(IllegalStateException(SECRET))
            runCurrent()
            assertFalse(state.value.toString().contains(SECRET))
            assertFalse(state.toString().contains(SECRET))
            assertEquals("Failed", state.value.toString())
            assertEquals("Loading", AreaPermissions.Loading.toString())
        }

    @Test
    fun grantedToStringListsOnlyThePermissions() =
        runTest {
            val read = AttackRead()
            val state = started(read)
            read.answer(setOf("reservation.read"))
            runCurrent()
            assertEquals("Granted(permissions=[reservation.read])", state.value.toString())
        }

    @Test
    fun grantedDoesNotChangeIfTheReadMutatesItsOwnSetAfterwards() =
        runTest {
            // Endurecimento: o valor concedido não muda depois, mesmo que a leitura devolva um conjunto mutável.
            val mutable = mutableSetOf("a")
            val state = AreaPermissionsState({ mutable }, backgroundScope)
            state.start()
            runCurrent()
            val granted = assertIs<AreaPermissions.Granted>(state.value)
            mutable.add("admin.everything")
            assertEquals(setOf("a"), granted.permissions)
        }

    @Test
    fun noticeShowsNothingBesidesTheTwoLiteralTexts() =
        runComposeUiTest {
            setContent { ZenyTheme { PermissionsNotice(onRetry = {}) } }
            assertEquals(1, onAllNodesWithTag("permissions-notice").fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithTag("permissions-retry").fetchSemanticsNodes().size)
            onNodeWithTag("permissions-notice").assertTextEquals(LITERAL)
            onNodeWithTag("permissions-retry").assertTextEquals("Tentar de novo")
        }

    @Test
    fun noticeCallsOnRetryOncePerClick() =
        runComposeUiTest {
            var retries = 0
            setContent { ZenyTheme { PermissionsNotice(onRetry = { retries++ }) } }
            onNodeWithTag("permissions-retry").performClick()
            assertEquals(1, retries)
            onNodeWithTag("permissions-retry").performClick()
            assertEquals(2, retries)
        }

    @Test
    fun noticeDoesNotCallOnRetryWithoutAClick() =
        runComposeUiTest {
            var retries = 0
            setContent { ZenyTheme { PermissionsNotice(onRetry = { retries++ }) } }
            onNodeWithTag("permissions-notice").assertTextEquals(LITERAL)
            assertEquals(0, retries)
        }
}
