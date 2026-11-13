package com.zeny.desktop.core.session

import com.zeny.desktop.core.FakeZenyServer
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.sdk.client.IamApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** O aviso síncrono de saída de sessão do `SessionManager`, dado dentro da própria transição. */
@Suppress("InjectDispatcher")
class SessionExitTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private val server = FakeZenyServer()
    private val store = TokenStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth: Authenticator
    private val http = createCoreHttpClient(server.engine) { install(AuthPlugin) { authenticator = { auth } } }
    private val iam = IamApi(ogenBase(http, "http://servidor.teste"))
    private val executor = ApiExecutor()
    private val session: SessionManager

    /** Cada sessão que saiu, com o estado visto no momento do aviso. */
    private val exits = CopyOnWriteArrayList<Pair<String, SessionState>>()

    init {
        auth =
            Authenticator(store, IamTokenRefresher(iam, executor), scope) { code, id -> session.sessionEnded(code, id) }
        session = SessionManager(iam, executor, store, auth, MemorySettings())
        session.onSessionExit = { id -> exits += id to session.state.value }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        http.close()
    }

    @Test
    fun loginFromLoggedOutReportsNothing() =
        runTest {
            session.login("maria", "segredo")
            assertEquals(emptyList(), exits)
        }

    @Test
    fun loginOverAnotherSessionReportsTheExitAfterTheSwitch() =
        runTest {
            val maria = session.login("maria", "segredo")
            val joao = session.login("joao", "segredo")
            assertEquals(listOf<Pair<String, SessionState>>(maria.sessionId to joao), exits)
        }

    @Test
    fun logoutReportsTheExitAfterTheSwitch() =
        runTest {
            val maria = session.login("maria", "segredo")
            session.logout()
            assertEquals(listOf<Pair<String, SessionState>>(maria.sessionId to SessionState.LoggedOut), exits)
            session.logout()
            assertEquals(1, exits.size)
        }

    @Test
    fun endedReportsTheExitAfterTheSwitch() =
        runTest {
            val maria = session.login("maria", "segredo")
            server.rejectAllWith = "SESSION_REVOKED"
            assertFailsWith<ApiError.SessionEnded> { executor.execute { iam.getMe(it.headers) } }
            val (id, state) = exits.single()
            assertEquals(maria.sessionId, id)
            assertIs<SessionState.Ended>(state)
        }
}
