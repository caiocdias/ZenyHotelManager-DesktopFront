package com.zeny.desktop.core.session

import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.sdk.client.IamApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** O logout lento de uma sessão não derruba a sessão que outro login abriu no mesmo endereço enquanto ele voava. */
@Suppress("InjectDispatcher")
class SessionManagerLogoutRaceTest {
    private val userId = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"
    private val sessionA = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0a"
    private val sessionB = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0b"
    private val sessions = listOf(sessionA, sessionB)
    private val logins = AtomicInteger()
    private val logoutEntered = CompletableDeferred<Unit>()
    private val logoutGate = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0f"
    }

    private fun MockRequestHandleScope.tokens(session: String) =
        respond(
            """
            {"accessToken":"A-$session","accessTokenExpiresAt":"2026-11-11T13:15:00Z",
             "passwordChangeRequired":false,"refreshToken":"R-$session",
             "refreshTokenExpiresAt":"2026-12-11T13:00:00Z","sessionId":"$session",
             "tokenType":"Bearer","userId":"$userId"}
            """.trimIndent(),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )

    private val engine =
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> {
                    tokens(sessions[logins.getAndIncrement()])
                }

                "/api/v1/auth/logout" -> {
                    logoutEntered.complete(Unit)
                    logoutGate.await()
                    respond("", HttpStatusCode.NoContent)
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

    @Test
    fun loginDuringInFlightLogoutKeepsTheNewSession() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            val logout = launch(Dispatchers.Default) { manager.logout() }
            logoutEntered.await()

            manager.login("joao.souza", "outra-senha-123")
            logoutGate.complete(Unit)
            logout.join()

            assertEquals(sessionB, store.current()?.sessionId, "o fim do logout de A apagou os tokens de B")
            assertEquals(SessionState.Active(userId, sessionB, passwordChangeRequired = false), manager.state.value)
        }

    @Test
    fun logoutWithoutAnotherLoginStillClears() =
        runTest(timeout = 5.seconds) {
            manager.login("maria.silva", "senha-longa-123")
            logoutGate.complete(Unit)

            manager.logout()

            assertNull(store.current())
            assertEquals(SessionState.LoggedOut, manager.state.value)
        }
}
