package com.zeny.desktop.app.reservations

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

private const val NOTICE_TEXT =
    "Não foi possível ler as suas permissões. As ações desta área ficam indisponíveis até a leitura dar certo."

/** Uma leitura falsa: cada chamada espera a próxima resposta, que o teste completa. */
private class FakeRead {
    var calls = 0
    private val answers = ArrayDeque<CompletableDeferred<Set<String>>>()

    suspend fun read(): Set<String> {
        calls++
        val answer = CompletableDeferred<Set<String>>()
        answers.addLast(answer)
        return answer.await()
    }

    fun answer(permissions: Set<String>) = answers.removeFirst().complete(permissions)

    fun fail(error: Throwable) = answers.removeFirst().completeExceptionally(error)
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class AreaPermissionsStateTest {
    private fun TestScope.state(read: FakeRead) = AreaPermissionsState(read::read, backgroundScope)

    @Test
    fun startsLoadingAndGrantsWhatTheReadReturns() =
        runTest {
            val read = FakeRead()
            val state = state(read)
            assertEquals(AreaPermissions.Loading, state.value)
            state.start()
            runCurrent()
            assertEquals(AreaPermissions.Loading, state.value)
            read.answer(setOf("reservation.read"))
            runCurrent()
            assertEquals(AreaPermissions.Granted(setOf("reservation.read")), state.value)
            assertEquals(1, read.calls)
        }

    @Test
    fun failedReadIsNotAnEmptyGrant() =
        runTest {
            listOf(
                ApiError.Problem(500, "X", null, emptyList()),
                IllegalStateException("falha"),
            ).forEach { error ->
                val read = FakeRead()
                val state = state(read)
                state.start()
                runCurrent()
                read.fail(error)
                runCurrent()
                assertEquals(AreaPermissions.Failed, state.value)
                assertNotEquals(AreaPermissions.Granted(emptySet()), state.value)
            }
        }

    @Test
    fun retryAfterFailureReadsAgainAndGrants() =
        runTest {
            val read = FakeRead()
            val state = state(read)
            state.start()
            runCurrent()
            read.fail(IllegalStateException("falha"))
            runCurrent()
            assertEquals(AreaPermissions.Failed, state.value)
            state.retry()
            assertEquals(AreaPermissions.Loading, state.value)
            runCurrent()
            read.answer(setOf("a"))
            runCurrent()
            assertEquals(AreaPermissions.Granted(setOf("a")), state.value)
            assertEquals(2, read.calls)
        }

    @Test
    fun retryIsIgnoredWhileLoadingOrGranted() =
        runTest {
            val read = FakeRead()
            val state = state(read)
            state.start()
            runCurrent()
            state.retry()
            runCurrent()
            assertEquals(1, read.calls)
            assertEquals(AreaPermissions.Loading, state.value)
            read.answer(setOf("a"))
            runCurrent()
            state.retry()
            runCurrent()
            assertEquals(1, read.calls)
            assertIs<AreaPermissions.Granted>(state.value)
        }

    @Test
    fun startTwiceReadsOnce() =
        runTest {
            val read = FakeRead()
            val state = state(read)
            state.start()
            state.start()
            runCurrent()
            read.answer(setOf("a"))
            runCurrent()
            state.start()
            runCurrent()
            assertEquals(1, read.calls)
        }

    @Test
    fun lateAnswerAfterCloseIsDropped() =
        runTest {
            val read = FakeRead()
            val state = state(read)
            state.start()
            runCurrent()
            state.close()
            read.answer(setOf("a"))
            runCurrent()
            assertEquals(AreaPermissions.Loading, state.value)
        }

    @Test
    fun cancellationIsNotSwallowed() =
        runTest {
            val read = FakeRead()
            val parent = Job()
            val state = AreaPermissionsState(read::read, CoroutineScope(coroutineContext + parent))
            state.start()
            runCurrent()
            parent.cancel()
            runCurrent()
            assertEquals(AreaPermissions.Loading, state.value)
        }

    @Test
    fun noticeShowsTheLiteralTextAndRetries() =
        runComposeUiTest {
            var retries = 0
            setContent { ZenyTheme { PermissionsNotice(onRetry = { retries++ }) } }
            onNodeWithTag("permissions-notice").assertTextEquals(NOTICE_TEXT)
            onNodeWithTag("permissions-retry").assertTextEquals("Tentar de novo")
            onNodeWithTag("permissions-retry").performClick()
            assertEquals(1, retries)
        }
}
