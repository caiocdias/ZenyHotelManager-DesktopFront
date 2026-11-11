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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Ataque ao `SessionManager` sobre o cliente real (`createCoreHttpClient` + `AuthPlugin` + `Authenticator`) e um
 * servidor IAM falso: o refresh token vale uma vez e gira a cada `/auth/refresh` (repetido, `REFRESH_TOKEN_REUSED`);
 * `getMe` recusa `A1` com `UNAUTHENTICATED`. O `Authenticator` avisa o fim da sessão na forma com `sessionId`,
 * como na ligação de produção. O refresh roda em threads reais (`Dispatchers.Default`), como no escopo do core.
 */
@Suppress("InjectDispatcher", "LargeClass")
class SessionManagerAttackTest {
    private val s1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
    private val s2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
    private val userId = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"
    private val endedMessage = "Sua sessão foi encerrada. Entre novamente."
    private val node: Preferences = Preferences.userRoot().node("zeny-test-${UUID.randomUUID()}")
    private val settings = PreferencesSettings(node)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = TokenStore()
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val refreshTokensReceived = CopyOnWriteArrayList<String>()
    private val ended = CopyOnWriteArrayList<Pair<String?, String>>()

    @Volatile private var validRefreshToken = "R1"

    @Volatile private var generation = 1

    @Volatile private var passwordChangeRequired = false

    /** Sessão que o servidor falso põe nos pares que devolve. */
    @Volatile private var serverSessionId = s1

    /** Quantos `/auth/refresh` seguintes caem por rede antes de o servidor girar o token (ele segue valendo). */
    @Volatile private var refreshNetworkFailures = 0

    /** `code` com que o servidor recusa o próximo `/auth/refresh`, ou `null`. */
    @Volatile private var refreshRefusal: String? = null

    @Volatile private var onLogin: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        tokens("A1", "R1")
    }

    /** Padrão: exige o Bearer e aceita a troca uma vez; depois dela a senha "velha" não confere mais. */
    @Volatile private var onPassword: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        when {
            it.authorization == null -> {
                problem("UNAUTHENTICATED")
            }

            passwordChanged -> {
                problem("INVALID_CREDENTIALS")
            }

            else -> {
                passwordChanged = true
                passwordChangeRequired = false
                respond("", HttpStatusCode.NoContent)
            }
        }
    }

    @Volatile private var passwordChanged = false

    @Volatile private var onLogout: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        if (it.authorization == null) problem("UNAUTHENTICATED") else respond("", HttpStatusCode.NoContent)
    }

    private val refreshGates = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
    private val refreshEntered = Channel<Unit>(Channel.UNLIMITED)
    private val passwordEntered = CompletableDeferred<Unit>()

    @Volatile private var passwordGate: CompletableDeferred<Unit>? = null

    private val http: HttpClient =
        createCoreHttpClient(
            MockEngine { request ->
                requests += request
                when (request.url.encodedPath) {
                    "/api/v1/auth/login" -> {
                        onLogin(request)
                    }

                    "/api/v1/auth/refresh" -> {
                        refresh(request)
                    }

                    "/api/v1/auth/password" -> {
                        passwordEntered.complete(Unit)
                        passwordGate?.await()
                        onPassword(request)
                    }

                    "/api/v1/auth/logout" -> {
                        onLogout(request)
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
        Authenticator(store, IamTokenRefresher(iam, executor), scope) { code, sessionId ->
            ended += code to sessionId
            manager.sessionEnded(code, sessionId)
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

    private fun requestsTo(path: String) = requests.filter { it.url.encodedPath == "/api/v1/auth/$path" }

    private suspend fun HttpRequestData.jsonBody(): JsonObject =
        Json.parseToJsonElement(body.toByteArray().decodeToString()).jsonObject

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.problem(
        code: String,
        status: HttpStatusCode = HttpStatusCode.Unauthorized,
    ) = respond(
        """{"code":"$code","detail":"detalhe"}""",
        status,
        headersOf(HttpHeaders.ContentType, "application/problem+json"),
    )

    private fun MockRequestHandleScope.html(status: HttpStatusCode) =
        respond("<html><body>Bad Gateway</body></html>", status, headersOf(HttpHeaders.ContentType, "text/html"))

    private fun MockRequestHandleScope.tokens(
        access: String,
        refresh: String,
        changeRequired: Boolean = passwordChangeRequired,
        session: String = serverSessionId,
    ) = json(
        """
        {"accessToken":"$access","accessTokenExpiresAt":"2026-11-11T13:15:00Z",
         "passwordChangeRequired":$changeRequired,"refreshToken":"$refresh",
         "refreshTokenExpiresAt":"2026-12-11T13:00:00Z","sessionId":"$session",
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
        // A sessão é a do momento em que o refresh chega; a queda, a recusa e o giro, os de quando o portão abre.
        val session = serverSessionId
        refreshGates.poll()?.await()
        synchronized(this@SessionManagerAttackTest) {
            if (refreshNetworkFailures > 0) {
                refreshNetworkFailures--
                throw IOException("sem rede")
            }
        }
        val refusal = refreshRefusal
        val issued =
            synchronized(this@SessionManagerAttackTest) {
                if (refusal != null || token != validRefreshToken) {
                    null
                } else {
                    generation++
                    validRefreshToken = "R$generation"
                    generation to passwordChangeRequired
                }
            }
        return if (issued == null) {
            problem(refusal ?: "REFRESH_TOKEN_REUSED")
        } else {
            tokens("A${issued.first}", "R${issued.first}", issued.second, session)
        }
    }

    private fun active(
        changeRequired: Boolean,
        session: String = s1,
    ) = SessionState.Active(userId, session, changeRequired)

    /** A troca foi aceita e o refresh seguinte caiu por rede (ruling do ledger). */
    private fun awaitingRefresh(changeRequired: Boolean = true) =
        SessionState.Active(userId, s1, changeRequired, passwordChangeAwaitingRefresh = true)

    /** Login com troca obrigatória e uma troca aceita cujo refresh caiu por rede. */
    private suspend fun loginWithPendingPasswordChange() {
        passwordChangeRequired = true
        manager.login("maria.silva", "provisoria-123")
        refreshNetworkFailures = 1
        assertFailsWith<ApiError.Network> { manager.changePassword("velha", "nova-senha-123") }
        assertEquals(awaitingRefresh(), manager.state.value)
    }

    // ---------------------------------------------------------------- login

    @Test
    fun loginBodyHasExactlyLoginPasswordAndDeviceAndNeverABearer() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            // Segundo login com uma sessão ativa: o token dela não vai junto.
            onLogin = {
                serverSessionId = s2
                tokens("B1", "Q1")
            }
            manager.login("Joao.Souza", "outra-senha-456")

            val logins = requestsTo("login")
            assertEquals(2, logins.size)
            for (request in logins) {
                assertNull(request.authorization, "login levou Authorization")
                val body = request.jsonBody()
                assertEquals(setOf("login", "password", "device"), body.keys)
                assertEquals(setOf("id", "name"), body.getValue("device").jsonObject.keys)
            }
            val second = logins[1].jsonBody()
            assertEquals("Joao.Souza", second.getValue("login").jsonPrimitive.content)
            assertEquals("outra-senha-456", second.getValue("password").jsonPrimitive.content)
            assertEquals(active(false, s2), manager.state.value)
            assertEquals("B1", store.current()?.accessToken)
            assertEquals("Joao.Souza", settings.lastLogin)
        }

    @Test
    fun deviceIdIsTheSameUuidAcrossLoginsAndRestarts() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            manager.logout()
            manager.login("maria.silva", "senha-longa-123")

            val ids =
                requestsTo("login").map {
                    it
                        .jsonBody()
                        .getValue("device")
                        .jsonObject
                        .getValue("id")
                }
            val id = ids.first().jsonPrimitive.content
            assertEquals(listOf(id, id), ids.map { it.jsonPrimitive.content })
            assertEquals(id, UUID.fromString(id).toString())
            assertEquals(id, PreferencesSettings(node).deviceId, "o id não sobrevive a um reinício")
        }

    @Test
    fun defaultDeviceNameIsCutAt100CharsAndIsWhatLoginSends() =
        runTest(timeout = 5.seconds) {
            val original = System.getProperty("os.name")
            val name =
                try {
                    System.setProperty("os.name", "Sistema-" + "x".repeat(300))
                    defaultDeviceName().also {
                        SessionManager(iam, executor, store, auth, settings).login("maria.silva", "senha-longa-123")
                    }
                } finally {
                    System.setProperty("os.name", original)
                }

            assertEquals(100, name.length)
            assertTrue(name.startsWith("Zeny Desktop (Sistema-xxx"), name)
            val sent =
                requestsTo("login")
                    .single()
                    .jsonBody()
                    .getValue("device")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content
            assertEquals(name, sent)
            assertEquals("Zeny Desktop (${System.getProperty("os.name")})", defaultDeviceName())
        }

    @Test
    fun successfulLoginPersistsOnlyDeviceIdAndLastLogin() =
        runTest(timeout = 5.seconds) {
            onLogin = { tokens("acc-SECRETO-1", "ref-SECRETO-1") }

            manager.login("maria.silva", "senha-SECRETA-1")

            assertEquals(setOf("deviceId", "lastLogin"), node.keys().toSet())
            for (key in node.keys()) assertTrue("SECRET" !in node.get(key, ""), "segredo em disco: $key")
        }

    private suspend fun assertLoginFailsAndLeavesNothing(expected: (ApiError) -> Unit) {
        val error = assertFailsWith<ApiError> { manager.login("maria.silva", "senha-longa-123") }
        expected(error)
        assertEquals(SessionState.LoggedOut, manager.state.value)
        assertNull(store.current())
        assertNull(settings.lastLogin)
        assertTrue(node.keys().toSet() - "deviceId" == emptySet<String>(), "gravou em disco: ${node.keys().toList()}")
    }

    @Test
    fun lockedAccountLoginKeepsLoggedOut() =
        runTest(timeout = 5.seconds) {
            onLogin = { problem("ACCOUNT_LOCKED") }

            assertLoginFailsAndLeavesNothing { error ->
                assertIs<ApiError.Problem>(error)
                assertEquals("Conta bloqueada por tentativas seguidas. Aguarde e tente de novo.", error.userMessage)
            }
            assertTrue(ended.isEmpty())
        }

    @Test
    fun disabledAccountLoginKeepsLoggedOut() =
        runTest(timeout = 5.seconds) {
            onLogin = { problem("ACCOUNT_DISABLED") }

            assertLoginFailsAndLeavesNothing { error ->
                assertIs<ApiError.Problem>(error)
                assertEquals("Esta conta está desativada. Fale com um administrador.", error.userMessage)
            }
        }

    @Test
    fun offlineLoginKeepsLoggedOut() =
        runTest(timeout = 5.seconds) {
            onLogin = { throw IOException("sem rede") }

            assertLoginFailsAndLeavesNothing { assertIs<ApiError.Network>(it) }
        }

    @Test
    fun htmlServerErrorOnLoginKeepsLoggedOut() =
        runTest(timeout = 5.seconds) {
            onLogin = { html(HttpStatusCode.BadGateway) }

            assertLoginFailsAndLeavesNothing { assertIs<ApiError.Unexpected>(it) }
        }

    @Test
    fun malformedSuccessfulLoginKeepsLoggedOut() =
        runTest(timeout = 5.seconds) {
            onLogin = { json("""{"accessToken":"A1","sessionId":"nao-e-uuid"}""") }

            assertLoginFailsAndLeavesNothing { assertIs<ApiError.IncompatibleResponse>(it) }
        }

    @Test
    fun failedReloginKeepsTheCurrentSessionUntouched() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            val before = store.current()
            onLogin = { problem("INVALID_CREDENTIALS") }

            assertFailsWith<ApiError.Problem> { manager.login("joao.souza", "errada") }

            assertEquals(active(false), manager.state.value)
            assertSame(before, store.current())
            assertEquals("maria.silva", settings.lastLogin)
            assertTrue(ended.isEmpty(), "um login recusado encerrou a sessão atual: $ended")
        }

    @Test
    fun reloginDuringOldSessionRefreshKeepsTheNewSession() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            val gate = CompletableDeferred<Unit>().also { refreshGates += it }
            // getMe com A1 abre um refresh da sessão s1, preso no portão.
            val me = async(Dispatchers.Default) { iam.getMe() }
            refreshEntered.receive()
            onLogin = {
                serverSessionId = s2
                tokens("B1", "Q1")
            }

            manager.login("maria.silva", "senha-longa-123")
            gate.complete(Unit)
            me.await()

            assertEquals(active(false, s2), manager.state.value)
            assertEquals("B1", store.current()?.accessToken)
            assertTrue(requestsTo("me").none { it.authorization == listOf("Bearer A2") }, "repetiu com o par antigo")
            assertEquals(2, generation, "o refresh antigo não chegou a girar o par")
        }

    @Test
    fun oldSessionRefusedRefreshAfterReloginDoesNotEndTheNewSession() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            val gate = CompletableDeferred<Unit>().also { refreshGates += it }
            val me = async(Dispatchers.Default) { iam.getMe() }
            refreshEntered.receive()
            onLogin = {
                serverSessionId = s2
                tokens("B1", "Q1")
            }
            manager.login("maria.silva", "senha-longa-123")
            // O servidor revogou s1 no novo login: o refresh antigo volta recusado.
            refreshRefusal = "REFRESH_TOKEN_INVALID"
            gate.complete(Unit)
            me.await()

            assertEquals(active(false, s2), manager.state.value)
            assertEquals("B1", store.current()?.accessToken)
            assertEquals(1, generation, "o refresh antigo não foi recusado")
            assertTrue(ended.isEmpty(), "o refresh recusado de s1 avisou fim de sessão: $ended")
        }

    // ---------------------------------------------------------------- sessionEnded(code, sessionId)

    @Test
    fun sessionEndedOfTheActiveSessionEndsIt() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            store.clear() // o Authenticator já limpou antes de avisar

            manager.sessionEnded("REFRESH_TOKEN_REUSED", s1)

            assertEquals(SessionState.Ended(endedMessage), manager.state.value)
        }

    @Test
    fun staleSessionEndedDoesNotTouchTheNewSession() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            onLogin = {
                serverSessionId = s2
                tokens("B1", "Q1")
            }
            manager.login("maria.silva", "senha-longa-123")
            val current = store.current()

            manager.sessionEnded("REFRESH_TOKEN_REUSED", s1)

            assertEquals(active(false, s2), manager.state.value)
            assertSame(current, store.current(), "o aviso de s1 mexeu no store da sessão s2")
        }

    @Test
    fun sessionEndedAfterLogoutStaysLoggedOut() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            manager.logout()

            manager.sessionEnded("SESSION_REVOKED", s1)

            assertEquals(SessionState.LoggedOut, manager.state.value)
        }

    @Test
    fun reusedRefreshTokenEndsTheSessionThroughTheAuthenticator() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            refreshRefusal = "REFRESH_TOKEN_REUSED"

            val me = iam.getMe()

            assertEquals(401, me.raw.status.value)
            assertEquals(listOf<Pair<String?, String>>("REFRESH_TOKEN_REUSED" to s1), ended.toList())
            assertEquals(SessionState.Ended(endedMessage), manager.state.value)
            assertNull(store.current())
        }

    // ---------------------------------------------------------------- changePassword

    @Test
    fun changePasswordSendsCurrentAndNewInTheirFieldsWithTheSessionBearer() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")

            manager.changePassword("velha", "nova-senha-123")

            val post = requestsTo("password").single()
            assertEquals(listOf("Bearer A1"), post.authorization)
            val body = post.jsonBody()
            assertEquals(setOf("currentPassword", "newPassword"), body.keys)
            assertEquals("velha", body.getValue("currentPassword").jsonPrimitive.content)
            assertEquals("nova-senha-123", body.getValue("newPassword").jsonPrimitive.content)
            val refresh = requestsTo("refresh").single()
            assertNull(refresh.authorization, "o refresh levou Authorization")
            assertEquals(listOf("R1"), refreshTokensReceived.toList())
        }

    @Test
    fun changePasswordKeepsTheFlagWhenTheServerStillRequiresIt() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            // O servidor aceita a troca mas o par novo ainda vem com a pendência.
            onPassword = { respond("", HttpStatusCode.NoContent) }

            manager.changePassword("velha", "nova-senha-123")

            assertEquals(active(true), manager.state.value)
            assertEquals("A2", store.current()?.accessToken)
        }

    @Test
    fun lockedAccountOnChangePasswordKeepsSessionWithoutRefresh() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            val before = store.current()
            onPassword = { problem("ACCOUNT_LOCKED") }

            val error = assertFailsWith<ApiError.Problem> { manager.changePassword("errada", "nova-senha-123") }

            assertEquals("ACCOUNT_LOCKED", error.code)
            assertEquals("Conta bloqueada por tentativas seguidas. Aguarde e tente de novo.", error.userMessage)
            assertEquals(active(true), manager.state.value)
            assertSame(before, store.current())
            assertTrue(requestsTo("refresh").isEmpty())
            assertTrue(ended.isEmpty())
        }

    @Test
    fun unchangedPasswordKeepsSessionWithoutRefresh() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            val before = store.current()
            onPassword = { problem("PASSWORD_UNCHANGED", HttpStatusCode.UnprocessableEntity) }

            val error = assertFailsWith<ApiError.Problem> { manager.changePassword("mesma-senha-12", "mesma-senha-12") }

            assertEquals(422, error.status)
            assertEquals("A nova senha deve ser diferente da atual.", error.userMessage)
            assertEquals(active(true), manager.state.value)
            assertSame(before, store.current())
            assertTrue(requestsTo("refresh").isEmpty())
        }

    @Test
    fun offlineChangePasswordKeepsSessionWithoutRefresh() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            val before = store.current()
            onPassword = { throw IOException("sem rede") }

            assertFailsWith<ApiError.Network> { manager.changePassword("velha", "nova-senha-123") }

            assertEquals(active(true), manager.state.value)
            assertSame(before, store.current())
            assertTrue(requestsTo("refresh").isEmpty())
        }

    @Test
    fun networkFailureOnTheRefreshAfterChangeSurfacesAndKeepsTheSession() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            val before = store.current()
            refreshNetworkFailures = 1

            assertFailsWith<ApiError.Network> { manager.changePassword("velha", "nova-senha-123") }

            // Ruling do ledger: a sessão continua, agora marcada como troca aceita à espera do refresh.
            assertEquals(awaitingRefresh(), manager.state.value)
            assertSame(before, store.current(), "o par não refeito foi perdido")
            assertTrue(ended.isEmpty())
        }

    /**
     * Ruling do ledger: com o POST já aceito e o refresh caído por rede, `changePassword` recusa (sem POST) e a
     * retomada é `retryPasswordChangeRefresh`, que só refaz o refresh.
     */
    @Test
    fun changePasswordRetryAfterNetworkFailureOnlyRefreshes() =
        runTest(timeout = 5.seconds) {
            loginWithPendingPasswordChange()

            assertFailsWith<IllegalStateException> { manager.changePassword("outra", "outra-senha-456") }
            manager.retryPasswordChangeRefresh()

            assertEquals(
                listOf("/api/v1/auth/login", "/api/v1/auth/password", "/api/v1/auth/refresh", "/api/v1/auth/refresh"),
                paths,
                "a retomada repetiu o POST da troca já aceita",
            )
            assertEquals(listOf("R1", "R1"), refreshTokensReceived.toList())
            assertEquals(active(false), manager.state.value)
            assertEquals("A2", store.current()?.accessToken)
        }

    @Test
    fun retryWithoutAPendingChangeDoesNothing() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")

            manager.retryPasswordChangeRefresh()

            assertEquals(listOf("/api/v1/auth/login"), paths)
            assertEquals(active(true), manager.state.value)
        }

    @Test
    fun retryFailingAgainByNetworkKeepsThePendingAndTheSession() =
        runTest(timeout = 5.seconds) {
            loginWithPendingPasswordChange()
            val before = store.current()
            refreshNetworkFailures = 1

            assertFailsWith<ApiError.Network> { manager.retryPasswordChangeRefresh() }

            assertEquals(awaitingRefresh(), manager.state.value)
            assertSame(before, store.current())
            manager.retryPasswordChangeRefresh()
            assertEquals(active(false), manager.state.value)
            assertEquals(1, requestsTo("password").size, "uma retomada repetiu o POST")
            assertEquals(listOf("R1", "R1", "R1"), refreshTokensReceived.toList())
        }

    @Test
    fun retryKeepsTheFlagWhenTheServerStillRequiresIt() =
        runTest(timeout = 5.seconds) {
            loginWithPendingPasswordChange()
            passwordChangeRequired = true

            manager.retryPasswordChangeRefresh()

            assertEquals(active(true), manager.state.value, "a pendência ficou ou o flag virou false fixo")
        }

    @Test
    fun logoutDuringTheRetryRefreshDiscardsThePairAndThePending() =
        runTest(timeout = 5.seconds) {
            loginWithPendingPasswordChange()
            val gate = CompletableDeferred<Unit>().also { refreshGates += it }

            val retry = async(Dispatchers.Default) { runCatching { manager.retryPasswordChangeRefresh() } }
            refreshEntered.receive() // o refresh que caiu
            refreshEntered.receive() // o da retomada, preso no portão
            manager.logout()
            gate.complete(Unit)
            retry.await()

            assertEquals(SessionState.LoggedOut, manager.state.value)
            assertNull(store.current(), "o par da retomada voltou ao store depois do logout")
            val before = requests.size
            manager.retryPasswordChangeRefresh()
            assertEquals(before, requests.size, "retomada depois do logout foi ao servidor")
        }

    @Test
    fun reloginDropsThePendingOfTheOldSession() =
        runTest(timeout = 5.seconds) {
            loginWithPendingPasswordChange()
            onLogin = {
                serverSessionId = s2
                validRefreshToken = "Q1"
                tokens("B1", "Q1", changeRequired = false)
            }
            manager.login("maria.silva", "nova-senha-123")
            val before = requests.size

            manager.retryPasswordChangeRefresh()

            assertEquals(active(false, s2), manager.state.value)
            assertEquals(before, requests.size, "a pendência da s1 refez refresh na s2")
            onPassword = { respond("", HttpStatusCode.NoContent) }
            manager.changePassword("nova-senha-123", "mais-nova-789") // sem IllegalStateException
            assertEquals(2, requestsTo("password").size)
            assertEquals(active(false, s2), manager.state.value)
        }

    @Test
    fun networkFailureAfterReloginDoesNotMarkTheNewSession() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            val gate = CompletableDeferred<Unit>().also { refreshGates += it }
            refreshNetworkFailures = 1

            val change =
                async(Dispatchers.Default) { runCatching { manager.changePassword("velha", "nova-senha-123") } }
            refreshEntered.receive()
            onLogin = {
                serverSessionId = s2
                tokens("B1", "Q1", changeRequired = false)
            }
            manager.login("maria.silva", "nova-senha-123")
            gate.complete(Unit)
            val failure = change.await().exceptionOrNull()

            assertIs<ApiError.Network>(failure)
            assertEquals(active(false, s2), manager.state.value, "a queda do refresh da s1 marcou a s2")
            assertEquals("B1", store.current()?.accessToken)
        }

    @Test
    fun sessionEndedWhileAwaitingRefreshEndsItAndRetryDoesNothing() =
        runTest(timeout = 5.seconds) {
            loginWithPendingPasswordChange()
            store.clear() // o Authenticator limpa antes de avisar
            manager.sessionEnded("SESSION_REVOKED", s1)
            val before = requests.size

            manager.retryPasswordChangeRefresh()

            assertEquals(SessionState.Ended(endedMessage), manager.state.value)
            assertEquals(before, requests.size)
        }

    @Test
    fun refusedRetryRefreshEndsTheSession() =
        runTest(timeout = 5.seconds) {
            loginWithPendingPasswordChange()
            refreshRefusal = "REFRESH_TOKEN_REUSED"

            runCatching { manager.retryPasswordChangeRefresh() }

            assertEquals(SessionState.Ended(endedMessage), manager.state.value)
            assertNull(store.current())
            assertEquals(listOf<Pair<String?, String>>("REFRESH_TOKEN_REUSED" to s1), ended.toList())
        }

    @Test
    fun retryAfterAReactiveRefreshUsesTheRotatedToken() =
        runTest(timeout = 5.seconds) {
            loginWithPendingPasswordChange()
            // getMe com A1 leva a um refresh reativo (R1 -> R2) enquanto a pendência está no estado.
            assertEquals(
                200,
                iam
                    .getMe()
                    .raw.status.value,
            )

            manager.retryPasswordChangeRefresh()

            assertEquals(listOf("R1", "R1", "R2"), refreshTokensReceived.toList())
            assertTrue(ended.isEmpty(), "token reusado: $ended")
            assertEquals(active(false), manager.state.value)
            assertEquals("A3", store.current()?.accessToken)
        }

    @Test
    fun concurrentRetriesNeverReuseARefreshToken() =
        runTest(timeout = 5.seconds) {
            loginWithPendingPasswordChange()

            List(2) { async(Dispatchers.Default) { manager.retryPasswordChangeRefresh() } }.awaitAll()

            val retries = refreshTokensReceived.drop(1) // o primeiro é o refresh que caiu
            assertEquals(retries.distinct(), retries, "refresh token repetido entre as retomadas")
            assertTrue(ended.isEmpty(), "sessão encerrada: $ended")
            assertEquals(active(false), manager.state.value)
            assertEquals(1, requestsTo("password").size)
        }

    @Test
    fun logoutWhileThePasswordPostIsInFlightDoesNotReviveTheSession() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            val gate = CompletableDeferred<Unit>().also { passwordGate = it }

            val change = async(Dispatchers.Default) { runCatching { manager.changePassword("velha", "nova") } }
            passwordEntered.await()
            manager.logout()
            gate.complete(Unit)
            change.await()

            assertEquals(SessionState.LoggedOut, manager.state.value)
            assertNull(store.current())
            assertTrue(requestsTo("refresh").isEmpty(), "refresh depois do logout")
        }

    @Test
    fun logoutWhileTheRefreshAfterChangeIsInFlightDiscardsTheNewPair() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            val gate = CompletableDeferred<Unit>().also { refreshGates += it }

            val change = async(Dispatchers.Default) { runCatching { manager.changePassword("velha", "nova") } }
            refreshEntered.receive()
            manager.logout()
            gate.complete(Unit)
            change.await()

            assertEquals(SessionState.LoggedOut, manager.state.value)
            assertNull(store.current(), "o par do refresh voltou ao store depois do logout")
        }

    @Test
    fun changePasswordWhileLoggedOutFailsWithoutTokenOrRefresh() =
        runTest(timeout = 5.seconds) {
            assertFailsWith<ApiError> { manager.changePassword("velha", "nova-senha-123") }

            assertEquals(SessionState.LoggedOut, manager.state.value)
            assertNull(store.current())
            assertNull(requestsTo("password").single().authorization)
            assertTrue(requestsTo("refresh").isEmpty())
        }

    @Test
    fun concurrentPasswordChangesNeverReuseARefreshToken() =
        runTest(timeout = 5.seconds) {
            passwordChangeRequired = true
            manager.login("maria.silva", "provisoria-123")
            onPassword = {
                passwordChangeRequired = false
                respond("", HttpStatusCode.NoContent)
            }

            List(2) { async(Dispatchers.Default) { manager.changePassword("velha", "nova-senha-123") } }.awaitAll()

            assertEquals(refreshTokensReceived.distinct(), refreshTokensReceived.toList())
            assertTrue(ended.isEmpty(), "sessão encerrada: $ended")
            val current = store.current()
            assertEquals(active(false), manager.state.value)
            assertEquals("A$generation", current?.accessToken)
        }

    // ---------------------------------------------------------------- logout

    @Test
    fun logoutSendsTheBearerOfTheCurrentSession() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")

            manager.logout()

            assertEquals(listOf("Bearer A1"), requestsTo("logout").single().authorization)
            assertNull(store.current())
            assertEquals(SessionState.LoggedOut, manager.state.value)
        }

    @Test
    fun logoutTwiceIsHarmless() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")

            manager.logout()
            manager.logout()

            assertNull(store.current())
            assertEquals(SessionState.LoggedOut, manager.state.value)
            assertEquals("maria.silva", settings.lastLogin, "o logout apagou o último login")
        }

    @Test
    fun logoutIgnoresAnHtmlServerErrorAndClearsLocally() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            onLogout = { html(HttpStatusCode.BadGateway) }

            manager.logout()

            assertNull(store.current())
            assertEquals(SessionState.LoggedOut, manager.state.value)
        }

    @Test
    fun logoutFromAnEndedSessionGoesLoggedOut() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            refreshRefusal = "REFRESH_TOKEN_REUSED"
            iam.getMe()
            assertEquals(SessionState.Ended(endedMessage), manager.state.value)

            manager.logout()

            assertEquals(SessionState.LoggedOut, manager.state.value)
            assertNull(store.current())
        }

    @Test
    fun logoutDuringAReactiveRefreshDiscardsTheNewPair() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            val gate = CompletableDeferred<Unit>().also { refreshGates += it }
            val me = async(Dispatchers.Default) { iam.getMe() }
            refreshEntered.receive()

            manager.logout()
            gate.complete(Unit)
            me.await()

            assertNull(store.current(), "o par do refresh voltou ao store depois do logout")
            assertEquals(SessionState.LoggedOut, manager.state.value)
            assertEquals(listOf(listOf("Bearer A1")), requestsTo("me").map { it.authorization })
        }

    @Test
    fun logoutWithAnExpiredAccessTokenStillEndsWithAnEmptyStore() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            onLogout = {
                if (it.authorization ==
                    listOf("Bearer A1")
                ) {
                    problem("UNAUTHENTICATED")
                } else {
                    respond("", HttpStatusCode.NoContent)
                }
            }

            manager.logout()

            assertEquals(
                listOf(listOf("Bearer A1"), listOf("Bearer A2")),
                requestsTo("logout").map { it.authorization },
            )
            assertNull(store.current())
            assertEquals(SessionState.LoggedOut, manager.state.value)
        }

    // ---------------------------------------------------------------- segredos

    @Test
    fun noPasswordOrTokenInStateErrorsOrStandardOutput() =
        runTest(timeout = 5.seconds) {
            onLogin = { tokens("acc-SECRETO-1", "ref-SECRETO-1") }
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val originalOut = System.out
            val originalErr = System.err
            val seen = mutableListOf<Throwable>()
            val states = mutableListOf<SessionState>()
            System.setOut(PrintStream(out, true))
            System.setErr(PrintStream(err, true))
            try {
                manager.login("maria.silva", "senha-SECRETA-0")
                states += manager.state.value
                onPassword = { problem("INVALID_CREDENTIALS") }
                runCatching {
                    manager.changePassword(
                        "senha-SECRETA-1",
                        "senha-SECRETA-2",
                    )
                }.exceptionOrNull()?.let(seen::add)
                onPassword = { throw IOException("sem rede") }
                runCatching {
                    manager.changePassword(
                        "senha-SECRETA-1",
                        "senha-SECRETA-2",
                    )
                }.exceptionOrNull()?.let(seen::add)
                manager.logout()
                states += manager.state.value
                onLogin = { problem("INVALID_CREDENTIALS") }
                runCatching { manager.login("maria.silva", "senha-SECRETA-3") }.exceptionOrNull()?.let(seen::add)
                onLogin = { throw IOException("sem rede") }
                runCatching { manager.login("maria.silva", "senha-SECRETA-4") }.exceptionOrNull()?.let(seen::add)
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }

            assertEquals(4, seen.size, "esperava quatro falhas: $seen")
            val texts =
                seen.map { it.stackTraceToString() } + states.map { it.toString() } + out.toString() + err.toString()
            for (text in texts) assertTrue("SECRET" !in text, "segredo vazou em: $text")
        }

    @Test
    fun incompatibleLoginResponseDoesNotCarryTheTokensInTheErrorChain() =
        runTest(timeout = 5.seconds) {
            // Corpo 200 cortado no meio do refresh token (na ordem de campos do servidor real): o JSON não fecha.
            onLogin = {
                json(
                    """{"tokenType":"Bearer","accessToken":"acc-SECRETO-1",""" +
                        """"accessTokenExpiresAt":"2026-11-11T13:15:00Z","refreshToken":"ref-SECRETO-1""",
                )
            }

            val error =
                assertFailsWith<ApiError.IncompatibleResponse> { manager.login("maria.silva", "senha-longa-123") }

            val chain = generateSequence<Throwable>(error) { it.cause }.toList()
            for (cause in chain) {
                assertTrue(
                    "SECRETO" !in cause.message.orEmpty(),
                    "token em ${cause::class.qualifiedName}: ${cause.message}",
                )
            }
            assertTrue("SECRETO" !in error.stackTraceToString(), "token no stack trace do erro do login")
            assertNull(store.current())
        }
}
