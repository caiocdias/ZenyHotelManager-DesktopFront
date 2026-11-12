package com.zeny.desktop.app.login

import com.zeny.desktop.core.error.ApiError
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordChangeStateTest {
    private val longPassword = "uma-senha-bem-longa"

    private class Calls {
        val changes = mutableListOf<Pair<String, String>>()
        var retries = 0
        var awaiting = false
        var changeFailure: Throwable? = null
    }

    private fun TestScope.state(calls: Calls) =
        PasswordChangeState(
            change = { c, n ->
                calls.changes += c to n
                calls.changeFailure?.let { throw it }
            },
            retry = { calls.retries++ },
            awaitingRefresh = { calls.awaiting },
            scope = this,
        )

    private fun PasswordChangeState.fill(
        current: String = "senha-atual-1",
        new: String = longPassword,
        confirm: String = new,
    ) {
        this.current = current
        this.new = new
        this.confirm = confirm
    }

    @Test
    fun validChangeCallsBackendAndFinishes() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            s.fill()
            var done = 0
            s.submit { done++ }
            advanceUntilIdle()
            assertEquals(listOf("senha-atual-1" to longPassword), calls.changes)
            assertEquals(1, done)
            assertEquals("", s.current + s.new + s.confirm)
            assertFalse(s.busy)
        }

    @Test
    fun newPasswordLengthRules() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            val msg = "A nova senha deve ter de 12 a 128 caracteres."
            s.fill(new = "a".repeat(11))
            s.submit {}
            advanceUntilIdle()
            assertEquals(msg, s.message)
            s.fill(new = "a".repeat(129))
            s.submit {}
            advanceUntilIdle()
            assertEquals(msg, s.message)
            assertTrue(calls.changes.isEmpty())
            s.fill(new = "a".repeat(12))
            s.submit {}
            advanceUntilIdle()
            s.fill(new = "b".repeat(128))
            s.submit {}
            advanceUntilIdle()
            assertEquals(2, calls.changes.size)
        }

    @Test
    fun confirmationMustMatch() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            s.fill(confirm = "diferente-da-nova")
            s.submit {}
            advanceUntilIdle()
            assertEquals("A confirmação não confere com a nova senha.", s.message)
            assertTrue(calls.changes.isEmpty())
        }

    @Test
    fun newMustDifferFromCurrent() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            s.fill(current = longPassword)
            s.submit {}
            advanceUntilIdle()
            assertEquals("A nova senha deve ser diferente da atual.", s.message)
            assertTrue(calls.changes.isEmpty())
        }

    @Test
    fun blankCurrentIsRejected() =
        runTest {
            val calls = Calls()
            val s = state(calls)
            s.fill(current = "")
            s.submit {}
            advanceUntilIdle()
            assertEquals("Informe a senha atual.", s.message)
            assertTrue(calls.changes.isEmpty())
        }

    @Test
    fun awaitingRefreshOnlyRetries() =
        runTest {
            val calls = Calls().apply { awaiting = true }
            val s = state(calls)
            var done = 0
            s.submit { done++ }
            advanceUntilIdle()
            assertTrue(calls.changes.isEmpty())
            assertEquals(1, calls.retries)
            assertEquals(1, done)
        }

    @Test
    fun failureShowsMessageAndClearsFields() =
        runTest {
            val calls = Calls().apply { changeFailure = ApiError.Network(RuntimeException()) }
            val s = state(calls)
            s.fill()
            var done = 0
            s.submit { done++ }
            advanceUntilIdle()
            assertEquals(ApiError.Network(RuntimeException()).userMessage, s.message)
            assertEquals(0, done)
            assertEquals("", s.current + s.new + s.confirm)
            calls.changeFailure = IllegalStateException("x")
            s.fill()
            s.submit {}
            advanceUntilIdle()
            assertEquals("Não foi possível trocar a senha.", s.message)
            assertFalse(s.busy)
        }

    @Test
    fun noPasswordInToString() =
        runTest {
            val s = state(Calls())
            s.fill()
            assertFalse(s.toString().contains(longPassword))
            assertFalse(s.toString().contains("senha-atual-1"))
        }
}
