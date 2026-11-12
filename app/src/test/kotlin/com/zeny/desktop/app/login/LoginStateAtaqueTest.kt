package com.zeny.desktop.app.login

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ProbeResult
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

class LoginStateAtaqueTest {
    private class Backend : LoginBackend {
        val probedUrls = mutableListOf<String>()
        val loggedUrls = mutableListOf<String>()
        val logins = mutableListOf<String>()
        val passwords = mutableListOf<String>()
        var probeGate: CompletableDeferred<ProbeResult>? = null
        var loginGate: CompletableDeferred<LoginOutcome>? = null
        var probeResult: () -> ProbeResult = { ProbeResult.Ok("1.0") }
        var loginResult: () -> LoginOutcome = { LoginOutcome.Success }

        override suspend fun probe(url: ServerUrl): ProbeResult {
            probedUrls += url.value
            return probeGate?.await() ?: probeResult()
        }

        override suspend fun login(
            url: ServerUrl,
            login: String,
            password: String,
        ): LoginOutcome {
            loggedUrls += url.value
            logins += login
            passwords += password
            return loginGate?.await() ?: loginResult()
        }
    }

    private fun TestScope.state(
        backend: Backend,
        server: String = "http://localhost:8088",
        login: String? = "ana",
        scope: CoroutineScope = this,
        password: String = "segredo-1",
    ) = LoginState(backend, server, login, scope).also { it.password = password }

    private val invalid = "Endereço inválido. Use http://endereço:porta."
    private val withPath = "Informe só o endereço e a porta, sem caminho."

    // ---------- endereços ----------

    @Test
    fun validAddressesReachTheBackendWithoutTrailingSlash() =
        runTest {
            val cases =
                mapOf(
                    "http://h:8088/" to "http://h:8088",
                    "http://h:8088//" to "http://h:8088",
                    "  http://h:8088  " to "http://h:8088",
                    "https://h" to "https://h",
                    "HTTP://H:8088" to "HTTP://H:8088",
                    "http://[::1]:8088" to "http://[::1]:8088",
                    "http://[::1]:8088/" to "http://[::1]:8088",
                )
            for ((typed, expected) in cases) {
                val backend = Backend()
                val s = state(backend, server = typed)
                s.testConnection()
                s.submit {}
                advanceUntilIdle()
                assertEquals("Servidor encontrado (versão 1.0).", s.probeMessage, "probe de $typed")
                assertNull(s.message, "login de $typed")
                assertEquals(listOf(expected), backend.probedUrls, "probe de $typed")
                assertEquals(listOf(expected), backend.loggedUrls, "login de $typed")
            }
        }

    @Test
    fun invalidAddressesAreRejectedLocallyOnBothActions() =
        runTest {
            val bad =
                listOf(
                    "",
                    "   ",
                    "ftp://h:8088",
                    "ftp://",
                    "http://",
                    "h:8088",
                    "localhost",
                    "http://h:8088?x=1",
                    "http://h:8088/?x=1",
                    "http://h:8088#frag",
                    "http://user:pw@h:8088",
                    "http://h:0",
                    "http://h:65536",
                    "http://h :8088",
                    "javascript:alert(1)",
                    "file:///etc/passwd",
                )
            for (typed in bad) {
                val backend = Backend()
                val s = state(backend, server = typed)
                s.testConnection()
                s.submit {}
                advanceUntilIdle()
                assertEquals(invalid, s.probeMessage, "probe de '$typed'")
                assertEquals(invalid, s.message, "login de '$typed'")
                assertTrue(
                    backend.probedUrls.isEmpty() && backend.loggedUrls.isEmpty(),
                    "backend chamado para '$typed'",
                )
                assertFalse(s.busy, "busy para '$typed'")
            }
        }

    @Test
    fun addressesWithPathAreRejectedLocallyOnBothActions() =
        runTest {
            val bad =
                listOf(
                    "http://h:8088/api",
                    "http://h:8088/api/",
                    "http://h/a/b",
                    "https://[::1]:8088/x",
                    "HTTP://h:8088/api",
                )
            for (typed in bad) {
                val backend = Backend()
                val s = state(backend, server = typed)
                s.testConnection()
                s.submit {}
                advanceUntilIdle()
                assertEquals(withPath, s.probeMessage, "probe de '$typed'")
                assertEquals(withPath, s.message, "login de '$typed'")
                assertTrue(
                    backend.probedUrls.isEmpty() && backend.loggedUrls.isEmpty(),
                    "backend chamado para '$typed'",
                )
            }
        }

    @Test
    fun invalidServerKeepsThePasswordAndDoesNotBecomeBusy() =
        runTest {
            val s = state(Backend(), server = "ftp://h")
            s.submit {}
            advanceUntilIdle()
            assertFalse(s.busy)
            assertEquals("segredo-1", s.password)
        }

    @Test
    fun invalidServerDoesNotCallOnDone() =
        runTest {
            var done = 0
            state(Backend(), server = "nao").submit { done++ }
            advanceUntilIdle()
            assertEquals(0, done)
        }

    @Test
    fun correctedAddressClearsThePreviousMessages() =
        runTest {
            val backend = Backend()
            val s = state(backend, server = "ftp://h")
            s.testConnection()
            s.submit {}
            s.server = "http://h:8088"
            s.testConnection()
            s.submit {}
            advanceUntilIdle()
            assertNull(s.message)
            assertEquals("Servidor encontrado (versão 1.0).", s.probeMessage)
        }

    // ---------- testar e entrar ----------

    @Test
    fun testConnectionOkShowsTheVersion() =
        runTest {
            val backend = Backend().apply { probeResult = { ProbeResult.Ok("2.7.1") } }
            val s = state(backend)
            s.testConnection()
            advanceUntilIdle()
            assertEquals("Servidor encontrado (versão 2.7.1).", s.probeMessage)
        }

    @Test
    fun doubleTestConnectionProbesOnce() =
        runTest {
            val backend = Backend().apply { probeGate = CompletableDeferred() }
            val s = state(backend)
            s.testConnection()
            runCurrent()
            s.testConnection()
            runCurrent()
            backend.probeGate!!.complete(ProbeResult.Ok("1"))
            advanceUntilIdle()
            assertEquals(1, backend.probedUrls.size)
        }

    @Test
    fun testConnectionCanBeRepeatedAfterItFinishes() =
        runTest {
            val backend = Backend()
            val s = state(backend)
            s.testConnection()
            advanceUntilIdle()
            s.testConnection()
            advanceUntilIdle()
            assertEquals(2, backend.probedUrls.size)
        }

    @Test
    fun probeThrowingApiErrorShowsItsUserMessage() =
        runTest {
            val errors =
                listOf(
                    ApiError.Network(RuntimeException("x")),
                    ApiError.IncompatibleResponse(RuntimeException("x")),
                    ApiError.Unexpected(502),
                    ApiError.SessionEnded("x"),
                )
            for (e in errors) {
                val backend = Backend().apply { probeResult = { throw e } }
                val s = state(backend)
                s.testConnection()
                advanceUntilIdle()
                assertEquals(e.userMessage, s.probeMessage)
                s.testConnection()
                advanceUntilIdle()
                assertEquals(2, backend.probedUrls.size, "probing deve voltar a falso depois de ${e::class.simpleName}")
            }
        }

    @Test
    fun probeThrowingAnythingElseShowsTheNetworkMessageAndLeaksNothing() =
        runTest {
            val backend = Backend().apply { probeResult = { error("detalhe interno segredo-1") } }
            val s = state(backend)
            s.testConnection()
            advanceUntilIdle()
            assertEquals(ApiError.Network(RuntimeException()).userMessage, s.probeMessage)
            assertFalse(s.probeMessage!!.contains("segredo-1"))
            assertFalse(s.busy)
        }

    @Test
    fun probeCancellationDoesNotShowAMessageAndAllowsAnotherProbe() =
        runTest {
            val backend = Backend().apply { probeResult = { throw CancellationException("cancelado") } }
            val s = state(backend)
            s.testConnection()
            advanceUntilIdle()
            assertNull(s.probeMessage)
            backend.probeResult = { ProbeResult.Ok("1") }
            s.testConnection()
            advanceUntilIdle()
            assertEquals("Servidor encontrado (versão 1).", s.probeMessage)
        }

    @Test
    fun testConnectionWhileLoginIsInFlightDoesNotTouchBusyNorThePassword() =
        runTest {
            val backend = Backend().apply { loginGate = CompletableDeferred() }
            val s = state(backend)
            s.submit {}
            runCurrent()
            assertTrue(s.busy)
            s.testConnection()
            advanceUntilIdle()
            assertEquals("Servidor encontrado (versão 1.0).", s.probeMessage)
            assertTrue(s.busy, "o login continua em voo")
            backend.loginGate!!.complete(LoginOutcome.Success)
            advanceUntilIdle()
            assertFalse(s.busy)
            assertEquals(1, backend.logins.size)
        }

    @Test
    fun loginWhileProbeIsInFlightIsNotBlockedAndProbeStillFinishes() =
        runTest {
            val backend = Backend().apply { probeGate = CompletableDeferred() }
            val s = state(backend)
            s.testConnection()
            runCurrent()
            var done: LoginOutcome? = null
            s.submit { done = it }
            advanceUntilIdle()
            assertEquals(LoginOutcome.Success, done)
            assertNull(s.probeMessage)
            backend.probeGate!!.complete(ProbeResult.Failed("Sem resposta."))
            advanceUntilIdle()
            assertEquals("Sem resposta.", s.probeMessage)
            assertEquals("", s.password)
        }

    @Test
    fun staleProbeResultDoesNotOverwriteALaterInvalidAddressMessage() =
        runTest {
            val backend = Backend().apply { probeGate = CompletableDeferred() }
            val s = state(backend)
            s.testConnection()
            runCurrent()
            s.server = "ftp://outro"
            s.testConnection()
            assertEquals(invalid, s.probeMessage)
            backend.probeGate!!.complete(ProbeResult.Ok("1.0"))
            advanceUntilIdle()
            assertEquals(invalid, s.probeMessage, "o resultado do endereço antigo apagou o aviso do endereço atual")
        }

    // ---------- login ----------

    @Test
    fun loginIsTrimmedButPasswordIsSentVerbatim() =
        runTest {
            val backend = Backend()
            val s = state(backend, login = "  ana  ", password = "  com espacos  ")
            s.submit {}
            advanceUntilIdle()
            assertEquals(listOf("ana"), backend.logins)
            assertEquals(listOf("  com espacos  "), backend.passwords)
            assertEquals("  ana  ", s.login, "o campo digitado não é reescrito")
        }

    @Test
    fun whitespaceOnlyLoginIsBlankAndNothingIsSent() =
        runTest {
            val backend = Backend()
            val s = state(backend, login = " \t ")
            s.submit {}
            advanceUntilIdle()
            assertEquals("Informe o login.", s.message)
            assertTrue(backend.logins.isEmpty())
        }

    @Test
    fun nullInitialLoginStartsEmpty() =
        runTest {
            val backend = Backend()
            val s = state(backend, login = null)
            assertEquals("", s.login)
            s.submit {}
            advanceUntilIdle()
            assertEquals("Informe o login.", s.message)
        }

    @Test
    fun loginMessageHasPriorityOverPasswordMessage() =
        runTest {
            val s = state(Backend(), login = "", password = "")
            s.submit {}
            assertEquals("Informe o login.", s.message)
        }

    @Test
    fun blankPasswordIsNotSentAndStaysNotBusy() =
        runTest {
            val backend = Backend()
            val s = state(backend, password = "")
            s.submit {}
            advanceUntilIdle()
            assertEquals("Informe a senha.", s.message)
            assertFalse(s.busy)
            assertTrue(backend.passwords.isEmpty())
        }

    @Test
    fun successfulSubmitClearsThePreviousMessage() =
        runTest {
            val backend = Backend().apply { loginResult = { LoginOutcome.Failed("Login ou senha incorretos.") } }
            val s = state(backend)
            s.submit {}
            advanceUntilIdle()
            assertEquals("Login ou senha incorretos.", s.message)
            backend.loginResult = { LoginOutcome.Success }
            s.password = "outra"
            s.submit {}
            advanceUntilIdle()
            assertNull(s.message)
        }

    @Test
    fun submitCanBeRepeatedAfterAFailure() =
        runTest {
            val backend = Backend().apply { loginResult = { LoginOutcome.Failed("x") } }
            val s = state(backend)
            repeat(3) {
                s.password = "tentativa-$it"
                s.submit {}
                advanceUntilIdle()
                assertFalse(s.busy)
            }
            assertEquals(listOf("tentativa-0", "tentativa-1", "tentativa-2"), backend.passwords)
        }

    @Test
    fun busyDuringTheCallAndPasswordIsSnapshotted() =
        runTest {
            val backend = Backend().apply { loginGate = CompletableDeferred() }
            val s = state(backend)
            s.submit {}
            runCurrent()
            assertTrue(s.busy)
            s.password = "digitada-durante-o-voo"
            backend.loginGate!!.complete(LoginOutcome.Success)
            advanceUntilIdle()
            assertEquals(listOf("segredo-1"), backend.passwords)
            assertEquals("", s.password)
        }

    @Test
    fun backendThrowingEachApiErrorShowsItsUserMessageAndClearsThePassword() =
        runTest {
            val errors =
                listOf(
                    ApiError.Problem(401, "auth.invalid", "detalhe do servidor", emptyList()),
                    ApiError.Problem(500, null, null, emptyList()),
                    ApiError.ApprovalRequired("a", "p", "r", null, null),
                    ApiError.PreconditionFailed(null),
                    ApiError.PasswordChangeRequired,
                    ApiError.SessionEnded("expired"),
                    ApiError.Network(RuntimeException("segredo-1")),
                    ApiError.IncompatibleResponse(RuntimeException("x")),
                    ApiError.Unexpected(503),
                )
            for (e in errors) {
                val backend = Backend().apply { loginResult = { throw e } }
                val s = state(backend)
                var outcome: LoginOutcome? = null
                s.submit { outcome = it }
                advanceUntilIdle()
                assertEquals(e.userMessage, s.message, e::class.simpleName)
                assertEquals(LoginOutcome.Failed(e.userMessage), outcome, e::class.simpleName)
                assertEquals("", s.password, e::class.simpleName)
                assertFalse(s.busy, e::class.simpleName)
                assertFalse(s.toString().contains("segredo-1"))
                assertFalse(s.message!!.contains("segredo-1"), "mensagem de ${e::class.simpleName}")
            }
        }

    @Test
    fun anyOtherExceptionShowsOnlyTheGenericMessage() =
        runTest {
            val throwables =
                listOf<Throwable>(
                    IllegalStateException("segredo-1"),
                    RuntimeException("segredo-1"),
                    java.io.IOException("segredo-1"),
                    NullPointerException(),
                    ArithmeticException("segredo-1"),
                )
            for (t in throwables) {
                val backend = Backend().apply { loginResult = { throw t } }
                val s = state(backend)
                var outcome: LoginOutcome? = null
                s.submit { outcome = it }
                advanceUntilIdle()
                assertEquals("Não foi possível concluir o login.", s.message, t::class.simpleName)
                assertEquals(LoginOutcome.Failed("Não foi possível concluir o login."), outcome)
                assertEquals("", s.password)
                assertFalse(s.busy)
            }
        }

    @Test
    fun backendCancellationExceptionDoesNotCallOnDoneButResetsBusyAndPassword() =
        runTest {
            val backend = Backend().apply { loginResult = { throw CancellationException("cancelado") } }
            val s = state(backend)
            var done = 0
            s.submit { done++ }
            advanceUntilIdle()
            assertEquals(0, done)
            assertFalse(s.busy)
            assertEquals("", s.password)
            assertNull(s.message, "cancelamento não é falha de login")
        }

    @Test
    fun scopeCancelledInTheMiddleOfLoginResetsBusyAndClearsThePassword() =
        runTest {
            val backend = Backend().apply { loginGate = CompletableDeferred() }
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val s = state(backend, scope = scope)
            var done = 0
            s.submit { done++ }
            runCurrent()
            assertTrue(s.busy)
            scope.cancel()
            runCurrent()
            assertFalse(s.busy)
            assertEquals("", s.password)
            assertEquals(0, done)
            assertNull(s.message)
        }

    @Test
    fun scopeCancelledInTheMiddleOfProbeLeavesNoMessage() =
        runTest {
            val backend = Backend().apply { probeGate = CompletableDeferred() }
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val s = state(backend, scope = scope)
            s.testConnection()
            runCurrent()
            scope.cancel()
            runCurrent()
            assertNull(s.probeMessage)
            assertFalse(s.busy)
        }

    @Test
    fun scopeCancelledBeforeTheLoginStartsStillClearsThePasswordAndBusy() =
        runTest {
            val backend = Backend()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val s = state(backend, scope = scope)
            s.submit {}
            scope.cancel()
            runCurrent()
            assertTrue(backend.logins.isEmpty(), "o login nem chegou a rodar")
            assertEquals("", s.password, "a senha ficou no estado depois do cancelamento")
            assertFalse(s.busy, "busy ficou preso em verdadeiro")
        }

    @Test
    fun outcomesAreDeliveredExactlyOnceToOnDone() =
        runTest {
            val outcomes =
                listOf(
                    LoginOutcome.Success,
                    LoginOutcome.PasswordChangeRequired,
                    LoginOutcome.Failed("falhou"),
                )
            for (expected in outcomes) {
                val backend = Backend().apply { loginResult = { expected } }
                val s = state(backend)
                val received = mutableListOf<LoginOutcome>()
                s.submit { received += it }
                advanceUntilIdle()
                assertEquals(listOf(expected), received)
                assertEquals("", s.password)
            }
        }

    @Test
    fun passwordNeverAppearsInToStringOrMessagesAfterAnyAttempt() =
        runTest {
            val secret = "S3gredo-Unico-Xyz"
            val results: List<() -> LoginOutcome> =
                listOf(
                    { LoginOutcome.Success },
                    { LoginOutcome.Failed("Login ou senha incorretos.") },
                    { throw IllegalStateException("falha com $secret") },
                    { throw ApiError.Network(RuntimeException(secret)) },
                    { throw CancellationException(secret) },
                )
            for (r in results) {
                val backend = Backend().apply { loginResult = r }
                val s = state(backend, password = secret)
                assertFalse(s.toString().contains(secret), "antes")
                s.submit {}
                advanceUntilIdle()
                assertFalse(s.toString().contains(secret), "depois")
                assertFalse(s.message.orEmpty().contains(secret))
                assertFalse(s.probeMessage.orEmpty().contains(secret))
                assertEquals("", s.password)
            }
        }
}
