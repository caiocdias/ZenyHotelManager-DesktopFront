package com.zeny.desktop.core.session

import com.zeny.desktop.core.config.PreferencesSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.sdk.client.IamApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Servidor falso do IAM: o refresh token vale uma vez e gira a cada `/auth/refresh` (repetido, responde
 * `REFRESH_TOKEN_REUSED`); `getMe` recusa o primeiro access token com `UNAUTHENTICATED`. O refresh roda em
 * threads reais (`Dispatchers.Default`), como no escopo do core.
 */
@Suppress("InjectDispatcher")
class SessionManagerTest {
    private val sessionId = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
    private val userId = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"
    private val node: Preferences = Preferences.userRoot().node("zeny-test-${UUID.randomUUID()}")
    private val settings = PreferencesSettings(node)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = TokenStore()
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val refreshTokensReceived = CopyOnWriteArrayList<String>()

    @Volatile private var validRefreshToken = "R1"

    @Volatile private var generation = 1

    @Volatile private var passwordChangeRequired = false

    /** Quantos `/auth/refresh` seguintes caem por rede antes de girar o token. */
    @Volatile private var refreshNetworkFailures = 0

    /** Sessão que o servidor falso põe nos pares que devolve. */
    @Volatile private var serverSessionId = sessionId

    /** Respostas por caminho que o teste troca; o padrão é o servidor feliz. */
    @Volatile private var onLogin: suspend MockRequestHandleScope.() -> HttpResponseData = { tokens("A1", "R1") }

    @Volatile private var onPassword: suspend MockRequestHandleScope.() -> HttpResponseData = {
        passwordChangeRequired = false
        respond("", HttpStatusCode.NoContent)
    }

    @Volatile private var onLogout: suspend MockRequestHandleScope.() -> HttpResponseData = {
        respond("", HttpStatusCode.NoContent)
    }

    /** Portões que seguram a resposta do `/auth/refresh` seguinte; sinal quando um refresh chega. */
    private val refreshGates = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
    private val refreshEntered = Channel<Unit>(Channel.UNLIMITED)
    private val passwordEntered = CompletableDeferred<Unit>()

    private val http: HttpClient =
        createCoreHttpClient(
            MockEngine { request ->
                requests += request
                when (request.url.encodedPath) {
                    "/api/v1/auth/login" -> {
                        onLogin()
                    }

                    "/api/v1/auth/refresh" -> {
                        refresh(request)
                    }

                    "/api/v1/auth/password" -> {
                        passwordEntered.complete(Unit)
                        onPassword()
                    }

                    "/api/v1/auth/logout" -> {
                        onLogout()
                    }

                    "/api/v1/auth/me" -> {
                        if (request.authorization == listOf("Bearer A1")) problem("UNAUTHENTICATED") else json("{}")
                    }

                    else -> {
                        respond("", HttpStatusCode.NotFound)
                    }
                }
            },
        ) { install(AuthPlugin) { authenticator = { auth } } }
    private val iam: IamApi = IamApi(ogenBase(http, "http://servidor.teste"))
    private val executor = ApiExecutor()
    private val auth: Authenticator =
        Authenticator(store, IamTokenRefresher(iam, executor), scope) { code, session ->
            manager.sessionEnded(code, session)
        }
    private val manager: SessionManager = SessionManager(iam, executor, store, auth, settings)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        node.removeNode()
        Preferences.userRoot().flush()
    }

    private val HttpRequestData.authorization: List<String>? get() = headers.getAll(HttpHeaders.Authorization)

    private val paths: List<String> get() = requests.map { it.url.encodedPath }

    private suspend fun HttpRequestData.jsonBody(): JsonObject =
        Json.parseToJsonElement(body.toByteArray().decodeToString()).jsonObject

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.problem(code: String) =
        json("""{"code":"$code","detail":"detalhe"}""", HttpStatusCode.Unauthorized)

    private fun MockRequestHandleScope.tokens(
        access: String,
        refresh: String,
        changeRequired: Boolean = passwordChangeRequired,
    ) = json(
        """
        {"accessToken":"$access","accessTokenExpiresAt":"2026-11-11T13:15:00Z",
         "passwordChangeRequired":$changeRequired,"refreshToken":"$refresh",
         "refreshTokenExpiresAt":"2026-12-11T13:00:00Z","sessionId":"$serverSessionId",
         "tokenType":"Bearer","userId":"$userId"}
        """.trimIndent(),
    )

    private suspend fun MockRequestHandleScope.refresh(request: HttpRequestData): HttpResponseData {
        val token =
            request
                .jsonBody()
                .getValue("refreshToken")
                .jsonPrimitive.content
        refreshTokensReceived += token
        refreshEntered.trySend(Unit)
        if (refreshNetworkFailures > 0) {
            refreshNetworkFailures--
            throw IOException("sem rede")
        }
        val (next, changeRequired) =
            synchronized(this@SessionManagerTest) {
                if (token != validRefreshToken) return problem("REFRESH_TOKEN_REUSED")
                generation++
                validRefreshToken = "R$generation"
                generation to passwordChangeRequired
            }
        refreshGates.poll()?.await()
        return tokens("A$next", "R$next", changeRequired)
    }

    private fun active(changeRequired: Boolean) = SessionState.Active(userId, sessionId, changeRequired)

    /** A troca de senha da sessão foi aceita, e o refresh seguinte ainda não chegou. */
    private fun awaitingRefresh() =
        SessionState.Active(userId, sessionId, passwordChangeRequired = true, passwordChangeAwaitingRefresh = true)

    @Test
    fun loginSendsPersistedDeviceAndKeepsTokensInMemory() =
        runTest(timeout = 5.seconds) {
            val state = manager.login("maria.silva", "senha-longa-123")

            val login = requests.single()
            assertEquals("/api/v1/auth/login", login.url.encodedPath)
            val device = login.jsonBody().getValue("device").jsonObject
            assertEquals(settings.deviceId, device.getValue("id").jsonPrimitive.content)
            assertTrue(
                device
                    .getValue("name")
                    .jsonPrimitive.content
                    .isNotBlank(),
            )
            assertEquals("A1", store.current()?.accessToken)
            assertEquals("maria.silva", settings.lastLogin)
            assertEquals(active(false), state)
            assertEquals(active(false), manager.state.value)
        }

    @Test
    fun invalidCredentialsKeepsLoggedOut() =
        runTest(timeout = 5.seconds) {
            onLogin = { problem("INVALID_CREDENTIALS") }

            val error = assertFailsWith<ApiError.Problem> { manager.login("maria.silva", "errada") }

            assertEquals("Login ou senha incorretos.", error.userMessage)
            assertEquals(SessionState.LoggedOut, manager.state.value)
            assertNull(store.current())
            assertNull(settings.lastLogin)
        }

    @Test
    fun passwordChangeRequiredFlow() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true

            assertEquals(active(true), manager.login("maria.silva", "provisoria-123"))
            assertEquals(active(true), manager.state.value)

            manager.changePassword("velha", "nova-senha-123")

            assertEquals(listOf("/api/v1/auth/login", "/api/v1/auth/password", "/api/v1/auth/refresh"), paths)
            assertEquals(listOf("R1"), refreshTokensReceived.toList())
            assertEquals(active(false), manager.state.value)
            assertEquals("A2", store.current()?.accessToken)
        }

    @Test
    fun changePasswordWrongCurrentKeepsSession() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            onPassword = { problem("INVALID_CREDENTIALS") }

            val error = assertFailsWith<ApiError.Problem> { manager.changePassword("errada", "nova-senha-123") }

            assertEquals("Login ou senha incorretos.", error.userMessage)
            assertEquals(active(true), manager.state.value)
            assertEquals("A1", store.current()?.accessToken)
            assertEquals(listOf("/api/v1/auth/login", "/api/v1/auth/password"), paths)
        }

    @Test
    fun changePasswordRefreshAfterConcurrentRefresh() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            val firstRefresh = CompletableDeferred<Unit>().also { refreshGates += it }

            // getMe com A1 recebe UNAUTHENTICATED e abre um refresh, preso no portão.
            val me = async(Dispatchers.Default) { iam.getMe() }
            refreshEntered.receive()
            val change = async(Dispatchers.Default) { manager.changePassword("velha", "nova-senha-123") }
            // A troca de senha chega ao servidor com o primeiro refresh ainda em voo.
            passwordEntered.await()
            firstRefresh.complete(Unit)
            change.await()

            assertEquals(
                200,
                me
                    .await()
                    .raw.status.value,
            )
            assertEquals(listOf("R1", "R2"), refreshTokensReceived.toList())
            assertEquals(2, paths.count { it == "/api/v1/auth/refresh" })
            assertEquals(active(false), manager.state.value)
            assertEquals("A3", store.current()?.accessToken)
        }

    @Test
    fun changePasswordDoesNotReviveEndedSession() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            // A sessão é encerrada enquanto a troca de senha está no servidor (pelo Authenticator, como em produção:
            // limpa o store da sessão e avisa o manager com o sessionId).
            onPassword = {
                auth.endSession(store.current()!!, "SESSION_REVOKED")
                respond("", HttpStatusCode.NoContent)
            }

            manager.changePassword("velha", "nova-senha-123")

            assertEquals(SessionState.Ended("Sua sessão foi encerrada. Entre novamente."), manager.state.value)
            assertNull(store.current())
            assertTrue("/api/v1/auth/refresh" !in paths)
        }

    @Test
    fun changePasswordDoesNotPromoteOtherSession() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            val otherSession = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0f"
            // Outra sessão toma o store enquanto a troca de senha está no servidor.
            onPassword = {
                passwordChangeRequired = false
                serverSessionId = otherSession
                store.set(Tokens("B1", validRefreshToken, otherSession, userId, true))
                respond("", HttpStatusCode.NoContent)
            }

            manager.changePassword("velha", "nova-senha-123")

            assertEquals(active(true), manager.state.value)
        }

    @Test
    fun logoutClearsEvenOffline() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            onLogout = { throw IOException("sem rede") }

            manager.logout()

            assertEquals("/api/v1/auth/logout", paths.last())
            assertNull(store.current())
            assertEquals(SessionState.LoggedOut, manager.state.value)
        }

    @Test
    fun sessionEndedSetsEndedState() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")

            // O fim da sessão vem do Authenticator, que limpa o store e chama sessionEnded(code, sessionId).
            auth.endSession(store.current()!!, "REFRESH_TOKEN_REUSED")

            assertEquals(SessionState.Ended("Sua sessão foi encerrada. Entre novamente."), manager.state.value)
            assertNull(store.current())
        }

    @Test
    fun cancelledLogoutStillClearsTokens() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            val logoutEntered = CompletableDeferred<Unit>()
            onLogout = {
                logoutEntered.complete(Unit)
                awaitCancellation()
            }

            val logout = launch(Dispatchers.Default) { manager.logout() }
            logoutEntered.await()
            logout.cancelAndJoin()

            assertNull(store.current())
            assertEquals(SessionState.LoggedOut, manager.state.value)
        }

    @Test
    fun sessionEndedOfTheCurrentSessionEndsIt() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")

            manager.sessionEnded("REFRESH_TOKEN_REUSED", sessionId)

            assertEquals(SessionState.Ended("Sua sessão foi encerrada. Entre novamente."), manager.state.value)
        }

    @Test
    fun sessionEndedOfAnotherSessionKeepsTheState() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")

            manager.sessionEnded("REFRESH_TOKEN_REUSED", "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0f")

            assertEquals(active(false), manager.state.value)
            assertEquals("A1", store.current()?.accessToken)
        }

    @Test
    fun sessionEndedWithSessionIdLeavesTheStoreToTheAuthenticator() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            val tokens = store.current()

            manager.sessionEnded("REFRESH_TOKEN_REUSED", sessionId)

            assertSame(tokens, store.current())
        }

    @Test
    fun changePasswordWithPendingRefreshFailsClearly() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            refreshNetworkFailures = 1
            assertFailsWith<ApiError.Network> { manager.changePassword("velha", "nova-senha-123") }
            assertEquals(awaitingRefresh(), manager.state.value)

            val error = assertFailsWith<IllegalStateException> { manager.changePassword("velha", "outra-senha-456") }

            assertTrue("retryPasswordChangeRefresh" in error.message.orEmpty())
            assertEquals(listOf("/api/v1/auth/login", "/api/v1/auth/password", "/api/v1/auth/refresh"), paths)
        }

    @Test
    fun retryPasswordChangeRefreshCompletesTheChange() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            refreshNetworkFailures = 1
            assertFailsWith<ApiError.Network> { manager.changePassword("velha", "nova-senha-123") }

            manager.retryPasswordChangeRefresh()

            assertEquals(
                listOf("/api/v1/auth/login", "/api/v1/auth/password", "/api/v1/auth/refresh", "/api/v1/auth/refresh"),
                paths,
            )
            assertEquals(listOf("R1", "R1"), refreshTokensReceived.toList())
            assertEquals(active(false), manager.state.value)
            assertEquals("A2", store.current()?.accessToken)
        }

    @Test
    fun retryPasswordChangeRefreshFailingAgainKeepsThePendingChange() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            refreshNetworkFailures = 2
            assertFailsWith<ApiError.Network> { manager.changePassword("velha", "nova-senha-123") }

            assertFailsWith<ApiError.Network> { manager.retryPasswordChangeRefresh() }

            assertEquals(awaitingRefresh(), manager.state.value)
        }

    @Test
    fun retryPasswordChangeRefreshWithoutPendingChangeDoesNothing() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")

            manager.retryPasswordChangeRefresh()

            assertEquals(listOf("/api/v1/auth/login"), paths)
            assertEquals(active(false), manager.state.value)
        }

    @Test
    fun reloginForgetsAPasswordChangeAwaitingItsRefresh() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            refreshNetworkFailures = 1
            assertFailsWith<ApiError.Network> { manager.changePassword("velha", "nova-senha-123") }
            manager.logout()
            manager.login("maria.silva", "nova-senha-123")
            requests.clear()

            manager.changePassword("nova-senha-123", "outra-senha-456")

            assertEquals(listOf("/api/v1/auth/password", "/api/v1/auth/refresh"), paths)
        }
}
