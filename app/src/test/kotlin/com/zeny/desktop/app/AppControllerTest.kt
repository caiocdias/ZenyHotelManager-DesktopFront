package com.zeny.desktop.app

import com.zeny.desktop.app.login.LoginOutcome
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {
    private class FakeConnection(
        val url: ServerUrl,
    ) : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
        var passwordChangeRequired = false
        var loginError: Exception? = null
        var activeBeforeLoginError = false
        var logoutHangs = false
        var logoutGate: CompletableDeferred<Unit>? = null
        var closed = false
        val calls = mutableListOf<String>()

        override suspend fun login(
            login: String,
            password: String,
        ) {
            calls += "login:$login"
            loginError?.let {
                if (activeBeforeLoginError) {
                    state.value = SessionState.Active("user-1", "session-1", passwordChangeRequired)
                }
                throw it
            }
            state.value = SessionState.Active("user-1", "session-1", passwordChangeRequired)
        }

        override suspend fun changePassword(
            current: String,
            new: String,
        ) {
            calls += "changePassword"
            state.value = SessionState.Active("user-1", "session-1", passwordChangeRequired = false)
        }

        override suspend fun retryPasswordChangeRefresh() {
            calls += "retry"
        }

        override suspend fun logout() {
            calls += "logout"
            if (logoutHangs) awaitCancellation()
            logoutGate?.await()
            state.value = SessionState.LoggedOut
        }

        override suspend fun me(): Me = error("não usado")

        override suspend fun hotel(): HotelInfo = error("não usado")

        override suspend fun businessDate(): BusinessDate = error("não usado")

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun close() {
            closed = true
        }
    }

    /** Guarda o que foi escrito; com `failWrites`, toda escrita lança como um disco sem permissão. */
    private class RecordingSettings(
        var failWrites: Boolean = false,
    ) : LocalSettings {
        val writes = mutableListOf<Pair<String, String?>>()

        override var serverUrl: String? = null
            set(value) {
                record("serverUrl", value)
                field = value
            }

        override var lastLogin: String? = null
            set(value) {
                record("lastLogin", value)
                field = value
            }

        override val deviceId: String = "device-1"

        private fun record(
            key: String,
            value: String?,
        ) {
            check(!failWrites) { "sem permissão de escrita" }
            writes += key to value
        }
    }

    private val serverA = ServerUrl.parse("http://localhost:8088")!!
    private val serverB = ServerUrl.parse("http://outro:9090")!!
    private val serverC = ServerUrl.parse("http://terceiro:7070")!!

    private class Fixture(
        val settings: RecordingSettings,
        scope: CoroutineScope,
        configure: (FakeConnection) -> Unit = {},
    ) {
        val created = mutableListOf<FakeConnection>()
        val controller =
            AppController(
                settings = settings,
                connect = { url -> FakeConnection(url).also(configure).also { created += it } },
                probe = { ProbeResult.Ok("1.0") },
                scope = scope,
                logoutTimeout = 50.milliseconds,
            )
    }

    @Test
    fun successfulLoginGoesToShellAndSavesServer() =
        runTest {
            val settings = RecordingSettings()
            val f = Fixture(settings, backgroundScope)
            assertEquals(Screen.Login, f.controller.screen.value)

            val outcome = f.controller.login(serverA, "ana", "segredo")
            runCurrent()

            assertEquals(LoginOutcome.Success, outcome)
            assertEquals(Screen.Shell, f.controller.screen.value)
            assertEquals("http://localhost:8088", settings.serverUrl)
            assertEquals("ana", settings.lastLogin)
            assertSame(f.created.single(), f.controller.connection)
            assertNull(f.controller.notice)
        }

    @Test
    fun failedLoginMapsApiErrorAndSavesNothing() =
        runTest {
            val settings = RecordingSettings()
            val error = ApiError.Problem(401, "INVALID_CREDENTIALS", null, emptyList())
            val f = Fixture(settings, backgroundScope) { it.loginError = error }

            val outcome = f.controller.login(serverA, "ana", "errada")
            runCurrent()

            assertEquals(LoginOutcome.Failed(error.userMessage), outcome)
            assertEquals(Screen.Login, f.controller.screen.value)
            assertTrue(settings.writes.isEmpty())
        }

    @Test
    fun passwordChangeRequiredErrorWithoutSessionFails() =
        runTest {
            val settings = RecordingSettings()
            val f = Fixture(settings, backgroundScope) { it.loginError = ApiError.PasswordChangeRequired }

            val outcome = f.controller.login(serverA, "ana", "segredo")
            runCurrent()

            assertEquals(LoginOutcome.Failed(ApiError.PasswordChangeRequired.userMessage), outcome)
            assertEquals(Screen.Login, f.controller.screen.value)
            assertTrue(settings.writes.isEmpty())
        }

    @Test
    fun passwordChangeRequiredErrorWithSessionAskingChangeGoesToPasswordChange() =
        runTest {
            val settings = RecordingSettings()
            val f =
                Fixture(settings, backgroundScope) {
                    it.passwordChangeRequired = true
                    it.activeBeforeLoginError = true
                    it.loginError = ApiError.PasswordChangeRequired
                }

            val outcome = f.controller.login(serverA, "ana", "segredo")
            runCurrent()

            assertEquals(LoginOutcome.PasswordChangeRequired, outcome)
            assertEquals(Screen.PasswordChange, f.controller.screen.value)
            assertEquals("http://localhost:8088", settings.serverUrl)
            assertEquals("ana", settings.lastLogin)
        }

    @Test
    fun concurrentServerSwitchesLeaveOnlyOneOpenConnection() =
        runTest {
            val gateA = CompletableDeferred<Unit>()
            val f = Fixture(RecordingSettings(), backgroundScope) { if (it.url == serverA) it.logoutGate = gateA }
            f.controller.login(serverA, "ana", "segredo")
            runCurrent()

            // B fica preso no logout de A; C chega no meio.
            val toB = async { f.controller.login(serverB, "ana", "segredo") }
            runCurrent()
            val toC = async { f.controller.login(serverC, "ana", "segredo") }
            runCurrent()
            gateA.complete(Unit)
            runCurrent()
            toB.await()
            toC.await()
            runCurrent()

            val open = f.created.filterNot { it.closed }
            assertEquals(1, open.size, "conexões abertas: ${open.map { it.url }}")
            assertSame(open.single(), f.controller.connection)
            assertEquals(serverC, open.single().url)
        }

    @Test
    fun passwordChangeRequiredGoesToPasswordChange() =
        runTest {
            val settings = RecordingSettings()
            val f = Fixture(settings, backgroundScope) { it.passwordChangeRequired = true }

            val outcome = f.controller.login(serverA, "ana", "segredo")
            runCurrent()

            assertEquals(LoginOutcome.PasswordChangeRequired, outcome)
            assertEquals(Screen.PasswordChange, f.controller.screen.value)
            assertEquals("http://localhost:8088", settings.serverUrl)
        }

    @Test
    fun completedPasswordChangeGoesToShell() =
        runTest {
            val f = Fixture(RecordingSettings(), backgroundScope) { it.passwordChangeRequired = true }
            f.controller.login(serverA, "ana", "segredo")
            runCurrent()
            assertEquals(Screen.PasswordChange, f.controller.screen.value)

            f.controller.connection!!.changePassword("segredo", "uma-senha-bem-longa")
            runCurrent()

            assertEquals(Screen.Shell, f.controller.screen.value)
        }

    @Test
    fun endedSessionReturnsToLoginWithNotice() =
        runTest {
            val f = Fixture(RecordingSettings(), backgroundScope)
            f.controller.login(serverA, "ana", "segredo")
            runCurrent()

            val connection = f.created.single()
            connection.state.value = SessionState.Ended("Sua sessão foi encerrada. Entre novamente.")
            runCurrent()

            assertEquals(Screen.Login, f.controller.screen.value)
            assertEquals("Sua sessão foi encerrada. Entre novamente.", f.controller.notice)

            f.controller.login(serverA, "ana", "segredo")
            runCurrent()
            assertEquals(Screen.Shell, f.controller.screen.value)
            assertNull(f.controller.notice)
        }

    @Test
    fun logoutReturnsToLoginWithoutNotice() =
        runTest {
            val f = Fixture(RecordingSettings(), backgroundScope)
            f.controller.login(serverA, "ana", "segredo")
            runCurrent()

            f.controller.logout()
            runCurrent()

            assertEquals(Screen.Login, f.controller.screen.value)
            assertNull(f.controller.notice)
            assertTrue("logout" in f.created.single().calls)
        }

    @Test
    fun changingServerClosesPreviousConnection() =
        runTest {
            val f = Fixture(RecordingSettings(), backgroundScope)
            f.controller.login(serverA, "ana", "segredo")
            runCurrent()
            f.controller.logout()
            runCurrent()

            f.controller.login(serverA, "ana", "segredo")
            assertEquals(1, f.created.size, "mesmo endereço reaproveita a conexão")

            f.controller.logout()
            f.controller.login(serverB, "ana", "segredo")
            runCurrent()

            assertEquals(listOf(serverA, serverB), f.created.map { it.url })
            assertTrue(f.created[0].closed)
            assertFalse(f.created[1].closed)
            assertSame(f.created[1], f.controller.connection)
            assertEquals(Screen.Shell, f.controller.screen.value)

            // A conexão antiga, já fechada, não mexe mais na tela.
            f.created[0].state.value = SessionState.Ended("Sua sessão foi encerrada. Entre novamente.")
            runCurrent()
            assertEquals(Screen.Shell, f.controller.screen.value)
            assertNull(f.controller.notice)
        }

    @Test
    fun reopeningAsksLoginAgain() =
        runTest {
            val settings = RecordingSettings()
            val first = Fixture(settings, backgroundScope)
            first.controller.login(serverB, "ana", "segredo")
            runCurrent()
            first.controller.close()

            val reopened = Fixture(settings, backgroundScope)

            assertEquals(Screen.Login, reopened.controller.screen.value)
            assertNull(reopened.controller.connection)
            assertTrue(reopened.created.isEmpty())
            assertEquals("http://outro:9090", settings.serverUrl)
            assertEquals("ana", settings.lastLogin)
        }

    @Test
    fun loginDefaultsComeFromResolverAndLastLogin() =
        runTest {
            val settings = RecordingSettings().apply { lastLogin = "ana" }
            val controller =
                AppController(
                    settings = settings,
                    connect = { error("sem conexão") },
                    probe = { ProbeResult.Ok("1.0") },
                    scope = backgroundScope,
                    resolveServer = { serverB },
                )

            assertEquals("http://outro:9090", controller.initialServer)
            assertEquals("ana", controller.initialLogin)
        }

    @Test
    fun defaultResolverPrefersTheSavedServer() =
        runTest {
            val settings = RecordingSettings().apply { serverUrl = "http://salvo:9000" }
            val controller =
                AppController(settings, { error("sem conexão") }, { ProbeResult.Ok("1.0") }, backgroundScope)

            assertEquals("http://salvo:9000", controller.initialServer)
            assertEquals(null, controller.initialLogin)
        }

    @Test
    fun nothingButAllowedKeysPersisted() =
        runTest {
            val settings = RecordingSettings()
            val f = Fixture(settings, backgroundScope) { it.passwordChangeRequired = true }
            f.controller.login(serverA, "ana", "segredo-123")
            runCurrent()
            f.controller.connection!!.changePassword("segredo-123", "uma-senha-bem-longa")
            runCurrent()
            f.controller.logout()
            f.controller.close()

            assertTrue(settings.writes.all { it.first in setOf("serverUrl", "lastLogin") }, "${settings.writes}")
            val values = settings.writes.map { it.second }
            assertFalse(values.any { it != null && ("segredo" in it || "senha" in it) }, "$values")
        }

    @Test
    fun preferencesWriteFailureDoesNotBreakLogin() =
        runTest {
            val settings = RecordingSettings(failWrites = true)
            val f = Fixture(settings, backgroundScope)

            val outcome = f.controller.login(serverA, "ana", "segredo")
            runCurrent()

            assertEquals(LoginOutcome.Success, outcome)
            assertEquals(Screen.Shell, f.controller.screen.value)
        }

    @Test
    fun probeIsForwarded() =
        runTest {
            val f = Fixture(RecordingSettings(), backgroundScope)

            assertEquals(ProbeResult.Ok("1.0"), f.controller.probe(serverA))
        }

    @Test
    fun closeLogsOutAndClosesTheConnection() =
        runTest {
            val f = Fixture(RecordingSettings(), backgroundScope)
            f.controller.login(serverA, "ana", "segredo")
            runCurrent()

            f.controller.close()

            val connection = f.created.single()
            assertTrue("logout" in connection.calls)
            assertTrue(connection.closed)
            assertNull(f.controller.connection)
        }

    @Test
    fun closeDoesNotHangWhenTheServerIsDown() =
        runTest {
            val f = Fixture(RecordingSettings(), backgroundScope) { it.logoutHangs = true }
            f.controller.login(serverA, "ana", "segredo")
            runCurrent()

            f.controller.close()

            assertTrue(f.created.single().closed)
        }
}
