package com.zeny.desktop.app.login

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ProbeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginStateTest {
    private class FakeBackend : LoginBackend {
        var probes = 0
        var logins = 0
        var probeGate: CompletableDeferred<ProbeResult>? = null
        var loginGate: CompletableDeferred<LoginOutcome>? = null
        var loginResult: () -> LoginOutcome = { LoginOutcome.Success }
        val passwordsSeen = mutableListOf<String>()

        override suspend fun probe(url: ServerUrl): ProbeResult {
            probes++
            return probeGate?.await() ?: ProbeResult.Ok("1.0")
        }

        override suspend fun login(
            url: ServerUrl,
            login: String,
            password: String,
        ): LoginOutcome {
            logins++
            passwordsSeen += password
            return loginGate?.await() ?: loginResult()
        }
    }

    private fun TestScope.state(
        backend: FakeBackend,
        server: String = "http://localhost:8088",
        login: String? = "ana",
    ) = LoginState(backend, server, login, this).also { it.password = "segredo" }

    @Test
    fun invalidServerDoesNotCallBackend() =
        runTest {
            val backend = FakeBackend()
            val s = state(backend, server = "nao e url")
            s.testConnection()
            assertEquals("Endereço inválido. Use http://endereço:porta.", s.probeMessage)
            s.submit {}
            advanceUntilIdle()
            assertEquals("Endereço inválido. Use http://endereço:porta.", s.message)
            assertEquals(0, backend.probes + backend.logins)
        }

    @Test
    fun serverWithPathRejected() =
        runTest {
            val backend = FakeBackend()
            val s = state(backend, server = "http://localhost:8088/api")
            s.testConnection()
            assertEquals("Informe só o endereço e a porta, sem caminho.", s.probeMessage)
            s.submit {}
            advanceUntilIdle()
            assertEquals("Informe só o endereço e a porta, sem caminho.", s.message)
            assertEquals(0, backend.probes + backend.logins)
        }

    @Test
    fun testConnectionShowsMessageWithoutBlocking() =
        runTest {
            val backend = FakeBackend().apply { probeGate = CompletableDeferred() }
            val s = state(backend)
            s.testConnection()
            runCurrent()
            assertFalse(s.busy)
            assertNull(s.probeMessage)
            backend.probeGate!!.complete(ProbeResult.Failed("Sem resposta."))
            advanceUntilIdle()
            assertEquals("Sem resposta.", s.probeMessage)
            assertFalse(s.busy)
        }

    @Test
    fun doubleSubmitLogsInOnce() =
        runTest {
            val backend = FakeBackend().apply { loginGate = CompletableDeferred() }
            val s = state(backend)
            var done = 0
            s.submit { done++ }
            runCurrent()
            assertTrue(s.busy)
            s.submit { done++ }
            runCurrent()
            backend.loginGate!!.complete(LoginOutcome.Success)
            advanceUntilIdle()
            assertEquals(1, backend.logins)
            assertEquals(1, done)
            assertFalse(s.busy)
        }

    @Test
    fun passwordIsClearedAfterAttempt() =
        runTest {
            val backend = FakeBackend()
            val s = state(backend)
            s.submit {}
            advanceUntilIdle()
            assertEquals(listOf("segredo"), backend.passwordsSeen)
            assertEquals("", s.password)
            backend.loginResult = { LoginOutcome.Failed("Credenciais inválidas.") }
            s.password = "outra"
            s.submit {}
            advanceUntilIdle()
            assertEquals("", s.password)
            assertFalse(s.toString().contains("outra"))
        }

    @Test
    fun blankLoginOrPasswordShowLocalMessages() =
        runTest {
            val backend = FakeBackend()
            val s = state(backend, login = " ")
            s.submit {}
            advanceUntilIdle()
            assertEquals("Informe o login.", s.message)
            s.login = "ana"
            s.password = ""
            s.submit {}
            advanceUntilIdle()
            assertEquals("Informe a senha.", s.message)
            assertEquals(0, backend.logins)
        }

    @Test
    fun failedLoginShowsMessage() =
        runTest {
            val backend = FakeBackend().apply { loginResult = { LoginOutcome.Failed("Login ou senha incorretos.") } }
            val s = state(backend)
            var outcome: LoginOutcome? = null
            s.submit { outcome = it }
            advanceUntilIdle()
            assertEquals("Login ou senha incorretos.", s.message)
            assertEquals(LoginOutcome.Failed("Login ou senha incorretos."), outcome)
            assertFalse(s.busy)
        }

    @Test
    fun unexpectedExceptionShowsGenericMessage() =
        runTest {
            val backend = FakeBackend().apply { loginResult = { error("boom com segredo") } }
            val s = state(backend)
            s.submit {}
            advanceUntilIdle()
            assertEquals("Não foi possível concluir o login.", s.message)
            assertFalse(s.busy)
            assertEquals("", s.password)
        }

    @Test
    fun apiErrorShowsItsUserMessage() =
        runTest {
            val backend = FakeBackend().apply { loginResult = { throw ApiError.Network(RuntimeException("x")) } }
            val s = state(backend)
            s.submit {}
            advanceUntilIdle()
            assertEquals(ApiError.Network(RuntimeException()).userMessage, s.message)
        }

    @Test
    fun passwordChangeRequiredOutcome() =
        runTest {
            val backend = FakeBackend().apply { loginResult = { LoginOutcome.PasswordChangeRequired } }
            val s = state(backend)
            var outcome: LoginOutcome? = null
            s.submit { outcome = it }
            advanceUntilIdle()
            assertEquals(LoginOutcome.PasswordChangeRequired, outcome)
            assertNull(s.message)
            assertEquals("", s.password)
        }

    @Test
    fun toStringNeverContainsPassword() =
        runTest {
            val s = state(FakeBackend())
            assertFalse(s.toString().contains("segredo"))
        }
}
