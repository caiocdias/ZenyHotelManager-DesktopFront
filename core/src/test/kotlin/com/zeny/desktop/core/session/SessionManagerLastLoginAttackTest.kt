package com.zeny.desktop.core.session

import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.sdk.client.IamApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.util.prefs.BackingStoreException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Ataque à gravação do último login em melhor esforço: qualquer falha das preferências, sessão segue `Active`. */
class SessionManagerLastLoginAttackTest {
    private class ThrowingSettings(
        private val failure: () -> Throwable,
    ) : LocalSettings {
        var attempts = 0
        override var serverUrl: String? = null
        override var lastLogin: String?
            get() = null
            set(
                @Suppress("UNUSED_PARAMETER") value,
            ) {
                attempts++
                throw failure()
            }
        override val deviceId: String = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0f"
    }

    private val loginBody =
        """
        {"accessToken":"A1","accessTokenExpiresAt":"2026-11-11T13:15:00Z",
         "passwordChangeRequired":false,"refreshToken":"R1",
         "refreshTokenExpiresAt":"2026-12-11T13:00:00Z",
         "sessionId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d","tokenType":"Bearer",
         "userId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0e"}
        """.trimIndent()

    private suspend fun loginWith(failure: () -> Throwable) {
        val engine =
            MockEngine { respond(loginBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val iam = IamApi(ogenBase(createCoreHttpClient(engine), "http://servidor.teste"))
        val executor = ApiExecutor()
        val store = TokenStore()
        val scope = CoroutineScope(Job())
        val auth = Authenticator(store, IamTokenRefresher(iam, executor), scope) { _, _ -> }
        val settings = ThrowingSettings(failure)
        val manager = SessionManager(iam, executor, store, auth, settings)

        val state = manager.login("maria.silva", "senha-longa-123")

        assertIs<SessionState.Active>(state)
        assertEquals("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d", state.sessionId)
        assertEquals(state, manager.state.value)
        assertEquals(1, settings.attempts, "a gravação foi tentada uma vez")
        assertEquals("A1", assertNotNull(store.current()).accessToken, "os tokens ficam no store")
        scope.cancel()
    }

    @Test
    fun backingStoreExceptionKeepsSessionActive() = runTest { loginWith { BackingStoreException("disco cheio") } }

    @Test
    fun illegalArgumentExceptionKeepsSessionActive() =
        runTest { loginWith { IllegalArgumentException("valor grande demais") } }

    @Test
    fun securityExceptionKeepsSessionActive() = runTest { loginWith { SecurityException("sem permissão") } }

    @Test
    fun illegalStateExceptionFromClosedNodeKeepsSessionActive() =
        runTest { loginWith { IllegalStateException("Node has been removed.") } }
}
