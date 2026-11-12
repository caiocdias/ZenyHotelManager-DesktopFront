package com.zeny.desktop.core.session

import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.sdk.client.IamApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

/**
 * Ataque à correção do logout em voo: o fim do logout de uma sessão só limpa o que é dela. Cada teste termina com
 * o estado e o `TokenStore` coerentes (a sessão nova ativa, com o par dela intacto, ou nenhuma sessão), e nenhum
 * token da sessão antiga sobrevive. Servidor IAM falso por `MockEngine`; threads reais, como no escopo do core.
 */
@Suppress("InjectDispatcher", "LargeClass")
class SessionManagerLogoutRaceAtaqueTest {
    private val userId = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"
    private val sessionA = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0a"
    private val sessionB = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0b"
    private val sessionC = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0c"
    private val sessions = listOf(sessionA, sessionB, sessionC)
    private val logins = AtomicInteger()
    private val logoutPosts = AtomicInteger()
    private val refreshPosts = AtomicInteger()
    private val logoutEntered = Channel<Unit>(Channel.UNLIMITED)
    private val refreshEntered = Channel<Unit>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var logoutGate = CompletableDeferred<Unit>()

    private val refreshGate = CompletableDeferred<Unit>()

    @Volatile private var logoutOutcome: suspend MockRequestHandleScope.() -> HttpResponseData = {
        respond("", HttpStatusCode.NoContent)
    }

    @Volatile private var refreshOutcome: suspend MockRequestHandleScope.(String) -> HttpResponseData = { session ->
        tokens(session, "2")
    }

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0f"
    }

    private fun MockRequestHandleScope.tokens(
        session: String,
        generation: String = "",
    ) = respond(
        """
        {"accessToken":"A$generation-$session","accessTokenExpiresAt":"2026-11-11T13:15:00Z",
         "passwordChangeRequired":false,"refreshToken":"R$generation-$session",
         "refreshTokenExpiresAt":"2026-12-11T13:00:00Z","sessionId":"$session",
         "tokenType":"Bearer","userId":"$userId"}
        """.trimIndent(),
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.problem(
        code: String,
        status: HttpStatusCode = HttpStatusCode.Unauthorized,
    ) = respond(
        """{"code":"$code","detail":"detalhe"}""",
        status,
        headersOf(HttpHeaders.ContentType, "application/problem+json"),
    )

    private val engine =
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> {
                    tokens(sessions[logins.getAndIncrement()])
                }

                "/api/v1/auth/logout" -> {
                    logoutPosts.incrementAndGet()
                    logoutEntered.send(Unit)
                    logoutGate.await()
                    logoutOutcome()
                }

                "/api/v1/auth/refresh" -> {
                    refreshPosts.incrementAndGet()
                    val body = String(request.body.toByteArray())
                    val session = sessions.first { body.contains("-$it") }
                    refreshEntered.send(Unit)
                    refreshGate.await()
                    refreshOutcome(session)
                }

                else -> {
                    respond("", HttpStatusCode.NotFound)
                }
            }
        }
    private val store = TokenStore()
    private val iam: IamApi =
        IamApi(
            ogenBase(
                createCoreHttpClient(engine) { install(AuthPlugin) { authenticator = { auth } } },
                "http://servidor.teste",
            ),
        )
    private val executor = ApiExecutor()
    private val auth: Authenticator =
        Authenticator(store, IamTokenRefresher(iam, executor), scope) { code, session ->
            manager.sessionEnded(code, session)
        }
    private val manager: SessionManager = SessionManager(iam, executor, store, auth, MemorySettings())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun loginNext() = manager.login("maria.silva", "senha-longa-123")

    private fun startLogout(): Job = scope.launch { manager.logout() }

    private fun active(session: String) = SessionState.Active(userId, session, passwordChangeRequired = false)

    private fun assertNoSession() {
        assertNull(store.current(), "sobrou token no store")
        assertEquals(SessionState.LoggedOut, manager.state.value)
    }

    private fun assertOnly(
        session: String,
        pair: Tokens,
    ) {
        assertSame(pair, store.current(), "o par da sessão $session não é mais o do store")
        assertEquals(active(session), manager.state.value)
    }

    /** A abre; o logout de A fica preso no POST; B entra no mesmo endereço. Devolve o logout em voo e o par de B. */
    private suspend fun logoutInFlightThenLoginB(): Pair<Job, Tokens> {
        loginNext()
        val logout = startLogout()
        logoutEntered.receive()
        loginNext()
        return logout to assertNotNull(store.current())
    }

    @Test
    fun loginDuringLogoutThatFailsWithServerErrorKeepsTheNewSession() =
        runTest(timeout = 10.seconds) {
            logoutOutcome = { problem("INTERNAL", HttpStatusCode.InternalServerError) }
            val (logout, b) = logoutInFlightThenLoginB()
            logoutGate.complete(Unit)
            logout.join()
            assertOnly(sessionB, b)
        }

    @Test
    fun loginDuringLogoutThatFailsByNetworkKeepsTheNewSession() =
        runTest(timeout = 10.seconds) {
            logoutOutcome = { throw IOException("rede caiu") }
            val (logout, b) = logoutInFlightThenLoginB()
            logoutGate.complete(Unit)
            logout.join()
            assertOnly(sessionB, b)
        }

    @Test
    fun loginDuringLogoutRejectedAsUnauthenticatedKeepsTheNewSessionWithoutRefresh() =
        runTest(timeout = 10.seconds) {
            logoutOutcome = { problem("UNAUTHENTICATED") }
            val (logout, b) = logoutInFlightThenLoginB()
            logoutGate.complete(Unit)
            logout.join()
            assertOnly(sessionB, b)
            assertEquals(0, refreshPosts.get(), "o 401 do logout de A girou um refresh")
        }

    @Test
    fun loginDuringLogoutThatIsCancelledKeepsTheNewSession() =
        runTest(timeout = 10.seconds) {
            val (logout, b) = logoutInFlightThenLoginB()
            logout.cancel()
            logout.join()
            assertOnly(sessionB, b)
        }

    @Test
    fun loginDuringLogoutThatTimesOutKeepsTheNewSession() =
        runTest(timeout = 10.seconds) {
            loginNext()
            val timedOut = CompletableDeferred<Unit>()
            val logout =
                scope.launch {
                    assertNull(withTimeoutOrNull(300) { manager.logout() })
                    timedOut.complete(Unit)
                }
            logoutEntered.receive()
            loginNext()
            val b = assertNotNull(store.current())
            timedOut.await()
            logout.join()
            assertOnly(sessionB, b)
        }

    @Test
    fun cancelledLogoutWithoutAnotherLoginStillClearsTheSession() =
        runTest(timeout = 10.seconds) {
            loginNext()
            val logout = startLogout()
            logoutEntered.receive()
            logout.cancel()
            logout.join()
            assertNoSession()
        }

    @Test
    fun logoutThatFailsByNetworkWithoutAnotherLoginStillClearsTheSession() =
        runTest(timeout = 10.seconds) {
            logoutOutcome = { throw IOException("rede caiu") }
            logoutGate.complete(Unit)
            loginNext()
            manager.logout()
            assertNoSession()
        }

    @Test
    fun logoutAnsweredUnauthenticatedWhoseRefreshIsRefusedEndsInLoggedOut() =
        runTest(timeout = 10.seconds) {
            logoutOutcome = { problem("UNAUTHENTICATED") }
            refreshOutcome = { problem("REFRESH_TOKEN_REUSED") }
            logoutGate.complete(Unit)
            refreshGate.complete(Unit)
            loginNext()
            manager.logout()
            assertNoSession()
        }

    @Test
    fun logoutAnsweredUnauthenticatedWhoseRefreshWorksRetriesAndClearsEverything() =
        runTest(timeout = 10.seconds) {
            var first = true
            logoutOutcome = {
                if (first) {
                    first = false
                    problem("UNAUTHENTICATED")
                } else {
                    respond("", HttpStatusCode.NoContent)
                }
            }
            logoutGate.complete(Unit)
            refreshGate.complete(Unit)
            loginNext()
            manager.logout()
            assertNoSession()
            assertEquals(2, logoutPosts.get())
        }

    @Test
    fun refreshOfAInFlightWhenLogoutEndsDoesNotRevive() =
        runTest(timeout = 10.seconds) {
            loginNext()
            val logout = startLogout()
            logoutEntered.receive()
            val refresh = scope.launch { auth.forceRefresh() }
            refreshEntered.receive()
            logoutGate.complete(Unit)
            logout.join()
            refreshGate.complete(Unit)
            refresh.join()
            assertNoSession()
        }

    @Test
    fun refreshOfAFinishingAfterBLoggedInDoesNotTouchB() =
        runTest(timeout = 10.seconds) {
            loginNext()
            val logout = startLogout()
            logoutEntered.receive()
            val refresh = scope.launch { auth.forceRefresh() }
            refreshEntered.receive()
            loginNext()
            val b = assertNotNull(store.current())
            logoutGate.complete(Unit)
            logout.join()
            refreshGate.complete(Unit)
            refresh.join()
            assertOnly(sessionB, b)
        }

    @Test
    fun refreshOfARefusedAfterBLoggedInDoesNotEndB() =
        runTest(timeout = 10.seconds) {
            refreshOutcome = { problem("REFRESH_TOKEN_REUSED") }
            loginNext()
            val logout = startLogout()
            logoutEntered.receive()
            val refresh = scope.launch { auth.forceRefresh() }
            refreshEntered.receive()
            loginNext()
            val b = assertNotNull(store.current())
            refreshGate.complete(Unit)
            refresh.join()
            logoutGate.complete(Unit)
            logout.join()
            assertOnly(sessionB, b)
        }

    @Test
    fun authenticatorEndingAMidLogoutLeavesLoggedOutAtTheEnd() =
        runTest(timeout = 10.seconds) {
            refreshOutcome = { problem("REFRESH_TOKEN_REUSED") }
            refreshGate.complete(Unit)
            loginNext()
            val logout = startLogout()
            logoutEntered.receive()
            auth.forceRefresh()
            assertNull(store.current(), "o Authenticator devia ter limpado o store")
            assertEquals(SessionState.Ended("Sua sessão foi encerrada. Entre novamente."), manager.state.value)
            logoutGate.complete(Unit)
            logout.join()
            assertNoSession()
        }

    @Test
    fun authenticatorEndingAThenLoginOfBMidLogoutKeepsB() =
        runTest(timeout = 10.seconds) {
            refreshOutcome = { problem("REFRESH_TOKEN_REUSED") }
            refreshGate.complete(Unit)
            loginNext()
            val logout = startLogout()
            logoutEntered.receive()
            auth.forceRefresh()
            loginNext()
            val b = assertNotNull(store.current())
            logoutGate.complete(Unit)
            logout.join()
            assertOnly(sessionB, b)
        }

    @Test
    fun logoutWithoutSessionDoesNotWipeALoginThatHappenedDuringIt() =
        runTest(timeout = 10.seconds) {
            val logout = startLogout()
            logoutEntered.receive()
            loginNext()
            val a = assertNotNull(store.current())
            logoutGate.complete(Unit)
            logout.join()
            assertOnly(sessionA, a)
        }

    @Test
    fun twoConcurrentLogoutsOfTheSameSessionEndLoggedOut() =
        runTest(timeout = 10.seconds) {
            loginNext()
            val first = startLogout()
            val second = startLogout()
            logoutEntered.receive()
            logoutEntered.receive()
            logoutGate.complete(Unit)
            first.join()
            second.join()
            assertNoSession()
            assertEquals(2, logoutPosts.get())
        }

    @Test
    fun twoConcurrentLogoutsWithALoginBetweenKeepTheNewSession() =
        runTest(timeout = 10.seconds) {
            loginNext()
            val first = startLogout()
            val second = startLogout()
            logoutEntered.receive()
            logoutEntered.receive()
            loginNext()
            val b = assertNotNull(store.current())
            logoutGate.complete(Unit)
            first.join()
            second.join()
            assertOnly(sessionB, b)
        }

    @Test
    fun logoutOfAFinishingAfterBLoggedOutAndCLoggedInKeepsC() =
        runTest(timeout = 10.seconds) {
            loginNext()
            val logoutA = startLogout()
            logoutEntered.receive()
            loginNext()
            val gateA = logoutGate
            val gateB = CompletableDeferred<Unit>()
            logoutGate = gateB
            val logoutB = startLogout()
            logoutEntered.receive()
            gateB.complete(Unit)
            logoutB.join()
            assertNoSession()
            loginNext()
            val c = assertNotNull(store.current())
            gateA.complete(Unit)
            logoutA.join()
            assertOnly(sessionC, c)
        }

    @Test
    fun logoutOfAFinishingAfterBLoggedOutLeavesLoggedOut() =
        runTest(timeout = 10.seconds) {
            loginNext()
            val logoutA = startLogout()
            logoutEntered.receive()
            loginNext()
            val logoutB = startLogout()
            logoutEntered.receive()
            logoutGate.complete(Unit)
            logoutA.join()
            logoutB.join()
            assertNoSession()
        }
}
