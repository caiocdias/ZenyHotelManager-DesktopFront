package com.zeny.desktop.app

import com.zeny.desktop.app.login.LoginOutcome
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.PreferencesSettings
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
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private const val ENDED_NOTICE = "Sua sessão foi encerrada. Entre novamente."

/** Ataques ao ciclo de vida da sessão no controlador do app (Task 5). Só com conexão falsa; nada de Ktor. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerAtaqueTest {
    private class Events : MutableList<String> by mutableListOf()

    private class Fake(
        val url: ServerUrl,
        val events: Events,
    ) : ServerConnection {
        val tag = if (url.value.contains("localhost")) "A" else "B"
        override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
        var passwordChangeRequired = false
        var loginError: Exception? = null
        var logoutError: Exception? = null
        var logoutHangs = false
        var loginGate: CompletableDeferred<Unit>? = null
        var logoutCalls = 0
        var closeCalls = 0

        override suspend fun login(
            login: String,
            password: String,
        ) {
            events += "$tag:login-start"
            loginGate?.await()
            loginError?.let { throw it }
            state.value = SessionState.Active("user-1", "session-1", passwordChangeRequired)
            events += "$tag:login-done"
        }

        override suspend fun changePassword(
            current: String,
            new: String,
        ) {
            state.value = SessionState.Active("user-1", "session-1", passwordChangeRequired = false)
        }

        override suspend fun retryPasswordChangeRefresh() = Unit

        override suspend fun logout() {
            logoutCalls++
            events += "$tag:logout"
            if (logoutHangs) awaitCancellation()
            logoutError?.let { throw it }
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
            closeCalls++
            events += "$tag:close"
        }
    }

    private class Settings(
        private val events: Events,
        private val failServerUrl: Boolean = false,
        private val failLastLogin: Boolean = false,
    ) : LocalSettings {
        val writes = mutableListOf<Pair<String, String?>>()
        var deviceIdReads = 0

        override var serverUrl: String? = null
            set(value) {
                events += "write:serverUrl"
                check(!failServerUrl) { "disco cheio" }
                writes += "serverUrl" to value
                field = value
            }

        override var lastLogin: String? = null
            set(value) {
                events += "write:lastLogin"
                check(!failLastLogin) { "disco cheio" }
                writes += "lastLogin" to value
                field = value
            }

        override val deviceId: String
            get() {
                deviceIdReads++
                return "device-1"
            }
    }

    private val serverA = ServerUrl.parse("http://localhost:8088")!!
    private val serverB = ServerUrl.parse("http://outro:9090")!!

    private class Rig(
        scope: CoroutineScope,
        val events: Events = Events(),
        val settings: Settings = Settings(events),
        timeout: kotlin.time.Duration = 50.milliseconds,
        private val connectError: (ServerUrl) -> Exception? = { null },
        private val configure: (Fake) -> Unit = {},
    ) {
        val created = mutableListOf<Fake>()
        val controller =
            AppController(
                settings = settings,
                connect = { url ->
                    connectError(url)?.let { throw it }
                    Fake(url, events).also(configure).also { created += it }
                },
                probe = { ProbeResult.Ok("1.0") },
                scope = scope,
                logoutTimeout = timeout,
            )

        fun fake(tag: String) = created.last { it.tag == tag }
    }

    // --- sequências de estado -------------------------------------------------------------------------------

    @Test
    fun activeWithPasswordChangeThenActiveThenEndedThenNewLoginWalksTheScreens() =
        runTest {
            val r = Rig(backgroundScope) { it.passwordChangeRequired = true }
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            assertEquals(Screen.PasswordChange, r.controller.screen.value)

            val a = r.fake("A")
            a.state.value = SessionState.Active("user-1", "session-1", passwordChangeRequired = false)
            runCurrent()
            assertEquals(Screen.Shell, r.controller.screen.value)
            assertNull(r.controller.notice)

            a.state.value = SessionState.Ended("qualquer coisa do servidor")
            runCurrent()
            assertEquals(Screen.Login, r.controller.screen.value)
            assertEquals(ENDED_NOTICE, r.controller.notice)

            a.passwordChangeRequired = false
            val outcome = r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            assertEquals(LoginOutcome.Success, outcome)
            assertEquals(Screen.Shell, r.controller.screen.value)
            assertNull(r.controller.notice, "o aviso deve sumir no próximo login")
            assertEquals(1, r.created.size)
        }

    @Test
    fun endedWhileOnPasswordChangeReturnsToLoginWithNoticeAndNextLoginAsksChangeAgain() =
        runTest {
            val r = Rig(backgroundScope) { it.passwordChangeRequired = true }
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            assertEquals(Screen.PasswordChange, r.controller.screen.value)

            r.fake("A").state.value = SessionState.Ended("x")
            runCurrent()
            assertEquals(Screen.Login, r.controller.screen.value)
            assertEquals(ENDED_NOTICE, r.controller.notice)

            val outcome = r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            assertEquals(LoginOutcome.PasswordChangeRequired, outcome)
            assertEquals(Screen.PasswordChange, r.controller.screen.value)
            assertNull(r.controller.notice)
        }

    @Test
    fun loggedOutComingFromTheServerSideGoesToLoginWithoutNotice() =
        runTest {
            val r = Rig(backgroundScope)
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            assertEquals(Screen.Shell, r.controller.screen.value)

            r.fake("A").state.value = SessionState.LoggedOut
            runCurrent()

            assertEquals(Screen.Login, r.controller.screen.value)
            assertNull(r.controller.notice)
        }

    @Test
    fun noticeNeverCarriesTheMessageOfTheEndedState() =
        runTest {
            val r = Rig(backgroundScope)
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()

            r.fake("A").state.value = SessionState.Ended("token=abc123 code=SECRET_CODE senha=segredo")
            runCurrent()

            val notice = assertNotNull(r.controller.notice)
            assertEquals(ENDED_NOTICE, notice)
            assertFalse("abc123" in notice || "SECRET_CODE" in notice || "segredo" in notice)
        }

    @Test
    fun logoutAfterEndedClearsTheNotice() =
        runTest {
            val r = Rig(backgroundScope)
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            r.fake("A").state.value = SessionState.Ended("x")
            runCurrent()
            assertNotNull(r.controller.notice)

            r.controller.logout()
            runCurrent()

            assertNull(r.controller.notice)
            assertEquals(Screen.Login, r.controller.screen.value)
        }

    @Test
    fun watcherIsReattachedAfterLogoutSoALaterEndedStillShowsTheNotice() =
        runTest {
            val r = Rig(backgroundScope)
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            r.controller.logout()
            runCurrent()
            assertEquals(Screen.Login, r.controller.screen.value)

            r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            assertEquals(Screen.Shell, r.controller.screen.value)
            r.fake("A").state.value = SessionState.Ended("x")
            runCurrent()

            assertEquals(Screen.Login, r.controller.screen.value)
            assertEquals(ENDED_NOTICE, r.controller.notice)
            assertEquals(1, r.created.size)
        }

    @Test
    fun logoutWithoutConnectionStaysOnLoginWithoutFailing() =
        runTest {
            val r = Rig(backgroundScope)

            r.controller.logout()

            assertEquals(Screen.Login, r.controller.screen.value)
            assertNull(r.controller.notice)
            assertTrue(r.created.isEmpty())
        }

    @Test
    fun trailingSlashAddressReusesTheSameConnection() =
        runTest {
            val r = Rig(backgroundScope)
            r.controller.login(ServerUrl.parse("http://localhost:8088")!!, "ana", "segredo")
            runCurrent()
            r.controller.logout()
            runCurrent()

            r.controller.login(ServerUrl.parse("http://localhost:8088/")!!, "ana", "segredo")
            runCurrent()

            assertEquals(1, r.created.size)
            assertEquals(0, r.created[0].closeCalls)
        }

    // --- troca de servidor ----------------------------------------------------------------------------------

    @Test
    fun switchingServerWithActiveSessionLogsOutPreviousBeforeClosingItAndBeforeConnectingTheNext() =
        runTest {
            val r = Rig(backgroundScope)
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            r.events.clear()

            r.controller.login(serverB, "bia", "segredo")
            runCurrent()

            val relevant = r.events.filter { it.endsWith(":logout") || it.endsWith(":close") || it == "B:login-start" }
            assertEquals(listOf("A:logout", "A:close", "B:login-start"), relevant)
            assertEquals(1, r.created[0].logoutCalls)
            assertEquals(1, r.created[0].closeCalls)
            assertEquals(Screen.Shell, r.controller.screen.value)
        }

    @Test
    fun switchingServerWhenPreviousLogoutNeverReturnsStillClosesItAndLogsIntoTheNext() =
        runTest {
            val r = Rig(backgroundScope) { if (it.tag == "A") it.logoutHangs = true }
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()

            val outcome = r.controller.login(serverB, "bia", "segredo")
            runCurrent()

            assertEquals(LoginOutcome.Success, outcome)
            assertEquals(1, r.created[0].closeCalls)
            assertSame(r.created[1], r.controller.connection)
            assertEquals(Screen.Shell, r.controller.screen.value)
        }

    @Test
    fun failedLoginOnNewServerAfterAnActiveOneLeavesTheLoginScreenAndKeepsSavedServer() =
        runTest {
            val r = Rig(backgroundScope) { if (it.tag == "B") it.loginError = ApiError.Network(RuntimeException("x")) }
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()

            val outcome = r.controller.login(serverB, "bia", "errada")
            runCurrent()

            assertTrue(outcome is LoginOutcome.Failed)
            assertEquals(Screen.Login, r.controller.screen.value)
            assertEquals("http://localhost:8088", r.settings.serverUrl, "o servidor salvo não muda sem login aceito")
            assertEquals("ana", r.settings.lastLogin)
        }

    @Test
    fun connectThrowingAfterAnActiveSessionLeavesNoStaleShellAndNoConnection() =
        runTest {
            val r =
                Rig(backgroundScope, connectError = { if (it == serverB) IllegalStateException("sem porta") else null })
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            assertEquals(Screen.Shell, r.controller.screen.value)

            assertFailsWith<IllegalStateException> { r.controller.login(serverB, "bia", "segredo") }
            runCurrent()

            assertEquals(1, r.created[0].closeCalls)
            assertNull(r.controller.connection)
            assertEquals(Screen.Login, r.controller.screen.value, "sem conexão não pode sobrar a casca")
        }

    // --- falhas de login e de conexão -----------------------------------------------------------------------

    @Test
    fun connectThrowingPropagatesLeavesNothingAndALaterLoginWorks() =
        runTest {
            var broken = true
            val r = Rig(backgroundScope, connectError = { if (broken) IllegalStateException("sem rede") else null })

            assertFailsWith<IllegalStateException> { r.controller.login(serverA, "ana", "segredo") }
            runCurrent()
            assertNull(r.controller.connection)
            assertEquals(Screen.Login, r.controller.screen.value)
            assertTrue(r.settings.writes.isEmpty())

            broken = false
            val outcome = r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            assertEquals(LoginOutcome.Success, outcome)
            assertEquals(Screen.Shell, r.controller.screen.value)
        }

    @Test
    fun nonApiErrorFromLoginPropagatesAndSavesNothing() =
        runTest {
            val r = Rig(backgroundScope) { it.loginError = IllegalStateException("falha com senha=segredo") }

            assertFailsWith<IllegalStateException> { r.controller.login(serverA, "ana", "segredo") }
            runCurrent()

            assertEquals(Screen.Login, r.controller.screen.value)
            assertTrue(r.settings.writes.isEmpty())
            assertNull(r.controller.notice)
        }

    @Test
    fun failedMessageNeverCarriesTheServerCode() =
        runTest {
            val error = ApiError.Problem(500, "SECRET_CODE_XYZ", null, emptyList())
            val r = Rig(backgroundScope) { it.loginError = error }

            val outcome = r.controller.login(serverA, "ana", "segredo")

            val message = (outcome as LoginOutcome.Failed).message
            assertFalse("SECRET_CODE_XYZ" in message, message)
            assertFalse("segredo" in message, message)
        }

    @Test
    fun failedLoginAfterAFailedOneStillSavesNothingAndASuccessfulOneSavesExactlyTwoKeys() =
        runTest {
            val invalid = ApiError.Problem(401, "INVALID_CREDENTIALS", null, emptyList())
            val r = Rig(backgroundScope) { it.loginError = invalid }
            r.controller.login(serverA, "ana", "errada")
            r.controller.login(serverA, "ana", "errada")
            assertTrue(r.settings.writes.isEmpty())

            r.fake("A").loginError = null
            r.controller.login(serverA, "ana", "certa")
            runCurrent()

            val expected = listOf<Pair<String, String?>>("serverUrl" to "http://localhost:8088", "lastLogin" to "ana")
            assertEquals(expected, r.settings.writes)
            assertEquals(0, r.settings.deviceIdReads, "o controlador não mexe no id do dispositivo")
        }

    @Test
    fun preferencesAreWrittenOnlyAfterTheConnectionAcceptedTheLogin() =
        runTest {
            val r = Rig(backgroundScope)

            r.controller.login(serverA, "ana", "segredo")
            runCurrent()

            val firstWrite = r.events.indexOfFirst { it.startsWith("write:") }
            assertTrue(r.events.indexOf("A:login-done") in 0 until firstWrite, "${r.events}")
        }

    @Test
    fun serverUrlWriteFailureDoesNotPreventSavingLastLoginNorBreakLogin() =
        runTest {
            val events = Events()
            val r = Rig(backgroundScope, events, Settings(events, failServerUrl = true))

            val outcome = r.controller.login(serverA, "ana", "segredo")
            runCurrent()

            assertEquals(LoginOutcome.Success, outcome)
            assertEquals(Screen.Shell, r.controller.screen.value)
            assertEquals(listOf<Pair<String, String?>>("lastLogin" to "ana"), r.settings.writes)
        }

    @Test
    fun lastLoginWriteFailureDoesNotPreventSavedServerNorBreakLogin() =
        runTest {
            val events = Events()
            val r = Rig(backgroundScope, events, Settings(events, failLastLogin = true))

            val outcome = r.controller.login(serverA, "ana", "segredo")
            runCurrent()

            assertEquals(LoginOutcome.Success, outcome)
            assertEquals(Screen.Shell, r.controller.screen.value)
            assertEquals(listOf<Pair<String, String?>>("serverUrl" to "http://localhost:8088"), r.settings.writes)
        }

    // --- logins rápidos -------------------------------------------------------------------------------------

    @Test
    fun twoQuickLoginsOnTheSameServerShareOneConnectionAndBothSucceed() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val r = Rig(backgroundScope) { it.loginGate = gate }
            val first = async { r.controller.login(serverA, "ana", "segredo") }
            val second = async { r.controller.login(serverA, "ana", "segredo") }
            runCurrent()

            gate.complete(Unit)
            runCurrent()

            assertEquals(LoginOutcome.Success, first.await())
            assertEquals(LoginOutcome.Success, second.await())
            assertEquals(1, r.created.size)
            assertEquals(0, r.created[0].closeCalls)
            assertEquals(Screen.Shell, r.controller.screen.value)
            assertTrue(r.settings.writes.all { it.first in setOf("serverUrl", "lastLogin") })
        }

    @Test
    fun loginOfAReplacedConnectionDoesNotReportSuccessNorOverwriteTheSavedServer() =
        runTest {
            val gateA = CompletableDeferred<Unit>()
            val r = Rig(backgroundScope) { if (it.tag == "A") it.loginGate = gateA }
            val slowA = async { r.controller.login(serverA, "ana", "segredo") }
            runCurrent()
            val fastB = async { r.controller.login(serverB, "bia", "segredo") }
            runCurrent()
            assertEquals(LoginOutcome.Success, fastB.await())
            assertSame(r.created[1], r.controller.connection)

            gateA.complete(Unit)
            runCurrent()
            val outcomeA = slowA.await()

            assertEquals("http://outro:9090", r.settings.serverUrl, "o login velho de A sobrescreveu o servidor salvo")
            assertEquals("bia", r.settings.lastLogin)
            assertTrue(outcomeA !is LoginOutcome.Success, "o login de A não vale: a conexão já foi substituída")
            assertEquals(Screen.Shell, r.controller.screen.value)
        }

    // --- close ----------------------------------------------------------------------------------------------

    @Test
    fun closeTwiceLogsOutAndClosesOnlyOnce() =
        runTest {
            val r = Rig(backgroundScope)
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()

            r.controller.close()
            r.controller.close()

            assertEquals(1, r.created[0].logoutCalls)
            assertEquals(1, r.created[0].closeCalls)
            assertNull(r.controller.connection)
        }

    @Test
    fun closeWithoutAnyConnectionDoesNothing() =
        runTest {
            val r = Rig(backgroundScope)

            r.controller.close()
            r.controller.close()

            assertTrue(r.created.isEmpty())
            assertTrue(r.settings.writes.isEmpty())
        }

    @Test
    fun closeAfterLogoutDoesNotLogOutAgainButClosesTheConnection() =
        runTest {
            val r = Rig(backgroundScope)
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            r.controller.logout()
            runCurrent()

            r.controller.close()

            assertEquals(1, r.created[0].logoutCalls)
            assertEquals(1, r.created[0].closeCalls)
        }

    @Test
    fun closeWithEndedSessionDoesNotLogOutButClosesTheConnection() =
        runTest {
            val r = Rig(backgroundScope)
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            r.fake("A").state.value = SessionState.Ended("x")
            runCurrent()

            r.controller.close()

            assertEquals(0, r.created[0].logoutCalls)
            assertEquals(1, r.created[0].closeCalls)
        }

    @Test
    fun closeWithServerDownRespectsTheLogoutLimitInRealTime() =
        runTest {
            val r = Rig(backgroundScope, timeout = 200.milliseconds) { it.logoutHangs = true }
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()

            val start = System.nanoTime()
            r.controller.close()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertTrue(r.created[0].closeCalls == 1)
            assertTrue(elapsedMs in 150..3000, "close levou ${elapsedMs}ms; esperado perto do limite de 200ms")
        }

    @Test
    fun closeStillClosesTheConnectionAndDoesNotThrowWhenLogoutFails() =
        runTest {
            val r = Rig(backgroundScope) { it.logoutError = ApiError.Network(RuntimeException("caiu")) }
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()

            r.controller.close()

            assertEquals(1, r.created[0].closeCalls)
            assertNull(r.controller.connection)
        }

    @Test
    fun serverSwitchStillWorksWhenThePreviousLogoutFails() =
        runTest {
            val down = ApiError.Network(RuntimeException("caiu"))
            val r = Rig(backgroundScope) { if (it.tag == "A") it.logoutError = down }
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()

            val outcome = r.controller.login(serverB, "bia", "segredo")
            runCurrent()

            assertEquals(LoginOutcome.Success, outcome)
            assertEquals(1, r.created[0].closeCalls)
            assertSame(r.created[1], r.controller.connection)
        }

    @Test
    fun stateChangesAfterCloseDoNotMoveTheScreenOrTheNotice() =
        runTest {
            val r = Rig(backgroundScope)
            r.controller.login(serverA, "ana", "segredo")
            runCurrent()
            r.controller.close()
            runCurrent()
            val screenAfterClose = r.controller.screen.value

            r.created[0].state.value = SessionState.Ended("x")
            runCurrent()

            assertEquals(screenAfterClose, r.controller.screen.value)
            assertNull(r.controller.notice)
        }

    // --- preferências reais ---------------------------------------------------------------------------------

    @Test
    fun reopeningAsksLoginAgainWithRealIsolatedPreferencesAndOnlyAllowedKeysOnDisk() =
        runTest {
            val node = Preferences.userRoot().node("zeny-task5-${UUID.randomUUID()}")
            try {
                val events = Events()
                val firstSettings = PreferencesSettings(node)
                val created = mutableListOf<Fake>()
                val controller =
                    AppController(
                        settings = firstSettings,
                        connect = { url -> Fake(url, events).also { created += it } },
                        probe = { ProbeResult.Ok("1.0") },
                        scope = backgroundScope,
                        logoutTimeout = 50.milliseconds,
                    )
                controller.login(serverB, "ana", "segredo-123")
                runCurrent()
                assertEquals(Screen.Shell, controller.screen.value)
                controller.close()
                assertEquals(1, created.size)

                val reopenedSettings = PreferencesSettings(node)
                val reopened =
                    AppController(
                        settings = reopenedSettings,
                        connect = { url -> Fake(url, events).also { created += it } },
                        probe = { ProbeResult.Ok("1.0") },
                        scope = backgroundScope,
                    )

                assertEquals(Screen.Login, reopened.screen.value)
                assertNull(reopened.connection)
                assertEquals(1, created.size, "reabrir não cria conexão")
                assertEquals("http://outro:9090", reopenedSettings.serverUrl)
                assertEquals("ana", reopenedSettings.lastLogin)
                val keys = node.keys().toSet()
                assertTrue(setOf("serverUrl", "lastLogin", "deviceId").containsAll(keys), "$keys")
                assertTrue(node.keys().none { node.get(it, "").contains("segredo") })
            } finally {
                node.removeNode()
                Preferences.userRoot().flush()
            }
        }
}
