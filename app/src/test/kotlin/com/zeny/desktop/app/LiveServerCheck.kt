package com.zeny.desktop.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.login.LoginOutcome
import com.zeny.desktop.app.login.PasswordChangeState
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.app.shell.ShellScreen
import com.zeny.desktop.app.shell.ShellState
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.facade.ServerProbe
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val WIDTH = 1280
private const val HEIGHT = 800
private const val SHELL_LOAD_TIMEOUT_MILLIS = 30_000L
private val STEP_TIMEOUT = 30.seconds
private val PROBE_LIMIT = 11.seconds
private const val CLOSED_PORT = "http://127.0.0.1:1"
private val DATE_PATTERN = Regex("""\d{2}/\d{2}/\d{4}""")

/**
 * Verificação de ponta a ponta contra um servidor real (o ambiente `zeny-front`), pelo `CoreConnection` real e com
 * preferências em memória. Pulada sem `ZENY_LIVE_URL`; as credenciais vêm só do ambiente:
 * `ZENY_LIVE_ADMIN_LOGIN`, `ZENY_LIVE_ADMIN_PASSWORD`, `ZENY_LIVE_ADMIN_NEW_PASSWORD` e os mesmos com
 * `RECEPTIONIST`. Com troca de senha pendente, troca para a nova pela `PasswordChangeState`; num ambiente já usado,
 * a senha antiga é recusada e vale a nova. Grava `build/live/shell-admin.png` e `shell-receptionist.png`.
 */
@OptIn(ExperimentalTestApi::class)
class LiveServerCheck {
    private class LiveUser(
        val login: String,
        val password: String,
        val newPassword: String,
    ) {
        override fun toString() = "LiveUser($login)"
    }

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    private val url: ServerUrl by lazy {
        ServerUrl.parse(System.getenv("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida")
    }

    @BeforeTest
    fun requireLiveServer() {
        assumeTrue(!System.getenv("ZENY_LIVE_URL").isNullOrBlank(), "sem ZENY_LIVE_URL: verificação real pulada")
    }

    @Test
    fun probeAcceptsTheServerAndRejectsAClosedPortQuickly() =
        runBlocking {
            assertIs<ProbeResult.Ok>(ServerProbe.check(url))

            val started = TimeSource.Monotonic.markNow()
            val failed = assertIs<ProbeResult.Failed>(ServerProbe.check(checkNotNull(ServerUrl.parse(CLOSED_PORT))))
            val elapsed = started.elapsedNow()

            assertTrue(elapsed < PROBE_LIMIT, "o Testar levou $elapsed")
            assertEquals(ApiError.Network(RuntimeException()).userMessage, failed.message)
        }

    @Test
    fun adminAndReceptionistSeeTheirOwnShell() {
        val admin = user("ADMIN")
        val receptionist = user("RECEPTIONIST")
        val settings = MemorySettings()
        val connections = mutableListOf<ServerConnection>()
        val scope = CoroutineScope(SupervisorJob())
        val controller =
            AppController(
                settings = settings,
                connect = { CoreConnection(ZenyCore(it, settings)).also(connections::add) },
                probe = ServerProbe::check,
                scope = scope,
                resolveServer = { url },
            )
        try {
            enter(controller, admin)
            assertEquals(url.value, settings.serverUrl)
            val adminAreas = shell(controller, "shell-admin.png")
            assertEquals(Area.entries.toList(), adminAreas, "o administrador vê todas as áreas")

            runBlocking { controller.logout() }
            assertEquals(Screen.Login, controller.screen.value)
            assertEquals(SessionState.LoggedOut, connections.single().state.value)

            // O mesmo endereço reaproveita a conexão: o segundo login passa pelo mesmo adaptador.
            enter(controller, receptionist)
            val receptionistAreas = shell(controller, "shell-receptionist.png")
            assertTrue(receptionistAreas.isNotEmpty(), "o Recepcionista vê alguma área")
            assertNotEquals(adminAreas, receptionistAreas)
        } finally {
            controller.close()
            scope.cancel()
        }

        // O `close` do controlador encerrou a sessão do Recepcionista e fechou o adaptador: com o `core` fechado,
        // a chamada termina em cancelamento, não em `ApiError` (que viria de um `core` aberto e sem sessão).
        val connection = connections.single()
        assertEquals(SessionState.LoggedOut, connection.state.value)
        assertNull(controller.connection)
        assertFailsWith<CancellationException> { runBlocking { connection.me() } }
    }

    private fun user(role: String) =
        LiveUser(
            login = env("ZENY_LIVE_${role}_LOGIN"),
            password = env("ZENY_LIVE_${role}_PASSWORD"),
            newPassword = env("ZENY_LIVE_${role}_NEW_PASSWORD"),
        )

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fail("defina $name para a verificação real")

    /** Entra com a senha antiga ou, se recusada (ambiente já usado), com a nova; troca a senha se pendente. */
    private fun enter(
        controller: AppController,
        user: LiveUser,
    ) = runBlocking {
        var current = user.password
        var outcome = controller.login(url, user.login, current)
        if (outcome is LoginOutcome.Failed) {
            current = user.newPassword
            outcome = controller.login(url, user.login, current)
        }
        when (outcome) {
            is LoginOutcome.Failed -> fail("$user não entrou nem com a senha nova: ${outcome.message}")
            LoginOutcome.PasswordChangeRequired -> changePassword(controller, current, user)
            LoginOutcome.Success -> Unit
        }
        withTimeout(STEP_TIMEOUT) { controller.screen.first { it == Screen.Shell } }
    }

    /** Troca a senha como a tela faz: pela `PasswordChangeState`, sobre a conexão do controlador. */
    private suspend fun changePassword(
        controller: AppController,
        current: String,
        user: LiveUser,
    ) {
        assertEquals(user.password, current, "$user pede troca de senha já com a senha nova")
        val changeJob = SupervisorJob()
        val connection = checkNotNull(controller.connection)
        val state =
            PasswordChangeState(
                change = connection::changePassword,
                retry = connection::retryPasswordChangeRefresh,
                awaitingRefresh = {
                    val session = connection.state.value
                    session is SessionState.Active && session.passwordChangeAwaitingRefresh
                },
                scope = CoroutineScope(changeJob),
            )
        state.current = current
        state.new = user.newPassword
        state.confirm = user.newPassword
        var done = false
        state.submit { done = true }
        withTimeout(STEP_TIMEOUT) { changeJob.children.toList().joinAll() }
        changeJob.cancel()
        assertNull(state.message, "troca de senha de $user recusada")
        assertTrue(done)
    }

    /** Desenha o `ShellScreen` real em modo de desenvolvimento, confere o topo e grava a captura. */
    private fun shell(
        controller: AppController,
        fileName: String,
    ): List<Area> {
        val connection = checkNotNull(controller.connection)
        var areas = emptyList<Area>()
        runDesktopComposeUiTest(WIDTH, HEIGHT) {
            lateinit var state: ShellState
            setContent {
                val scope = rememberCoroutineScope()
                state = remember { ShellState(connection, devMode = true, scope) }
                ZenyTheme { ShellScreen(state, onLogout = {}) }
            }
            waitUntil(timeoutMillis = SHELL_LOAD_TIMEOUT_MILLIS) { !state.loading && state.hotelName.isNotEmpty() }

            assertNull(state.error, "a casca falhou: ${state.error?.userMessage}")
            assertTrue(state.userName.isNotBlank())
            assertTrue(DATE_PATTERN.matches(state.businessDate), "data operacional: ${state.businessDate}")
            assertTrue(state.areas.none { (_, implemented) -> implemented }, "nenhuma área tem tela ainda")
            onNodeWithText(state.hotelName).assertExists()
            onNodeWithText("Data operacional: ${state.businessDate}").assertExists()

            val file = File("build/live/$fileName")
            file.parentFile.mkdirs()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            areas = state.areas.map { it.first }
            state.close()
        }
        return areas
    }
}
