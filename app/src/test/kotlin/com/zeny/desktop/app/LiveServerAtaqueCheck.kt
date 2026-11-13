package com.zeny.desktop.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
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
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val ATTACK_STEP_TIMEOUT = 30.seconds
private val ATTACK_PROBE_LIMIT = 11.seconds
private const val ATTACK_LOAD_TIMEOUT_MILLIS = 30_000L
private const val ATTACK_CLOSED_PORT = "http://127.0.0.1:1"
private val RAW_FRAGMENTS = listOf("{", "}", "\"", "INVALID", "_", "code", "HTTP", "401", "403", "Exception", "ogen")

/**
 * Ataques ao vivo à casca (Task 9): o que a verificação do desenvolvedor não comparava. Pulada sem `ZENY_LIVE_URL`.
 * Segredos só do ambiente; nenhuma asserção aqui imprime uma senha (comparações de senha são booleanas).
 */
@OptIn(ExperimentalTestApi::class)
class LiveServerAtaqueCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    private class Harness(
        val url: ServerUrl,
    ) {
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

        fun close() {
            controller.close()
            scope.cancel()
        }
    }

    private val liveUrl: ServerUrl by lazy {
        ServerUrl.parse(System.getenv("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida")
    }

    @BeforeTest
    fun requireLiveServer() {
        assumeTrue(!System.getenv("ZENY_LIVE_URL").isNullOrBlank(), "sem ZENY_LIVE_URL: ataques reais pulados")
    }

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fail("defina $name para a verificação real")

    private fun assertUserText(message: String) {
        assertTrue(message.isNotBlank())
        RAW_FRAGMENTS.forEach { assertFalse(message.contains(it), "mensagem com resto técnico '$it': $message") }
    }

    private fun harness(
        url: ServerUrl = liveUrl,
        block: Harness.() -> Unit,
    ) {
        val h = Harness(url)
        try {
            h.block()
        } finally {
            h.close()
        }
    }

    /** Entra com a senha antiga ou, recusada, com a nova; troca a senha se pendente (mesma regra do check). */
    private fun Harness.enter(role: String) =
        runBlocking {
            val login = env("ZENY_LIVE_${role}_LOGIN")
            val old = env("ZENY_LIVE_${role}_PASSWORD")
            val new = env("ZENY_LIVE_${role}_NEW_PASSWORD")
            var usedOld = true
            var outcome = controller.login(url, login, old)
            if (outcome is LoginOutcome.Failed) {
                usedOld = false
                outcome = controller.login(url, login, new)
            }
            when (outcome) {
                is LoginOutcome.Failed -> {
                    fail("$role não entrou: ${outcome.message}")
                }

                LoginOutcome.PasswordChangeRequired -> {
                    assertTrue(usedOld, "$role pede troca já com a senha nova")
                    val connection = checkNotNull(controller.connection)
                    val job = SupervisorJob()
                    val state =
                        PasswordChangeState(
                            change = connection::changePassword,
                            retry = connection::retryPasswordChangeRefresh,
                            awaitingRefresh = {
                                val s = connection.state.value
                                s is SessionState.Active && s.passwordChangeAwaitingRefresh
                            },
                            scope = CoroutineScope(job),
                        )
                    state.current = old
                    state.new = new
                    state.confirm = new
                    state.submit {}
                    withTimeout(ATTACK_STEP_TIMEOUT) { job.children.toList().joinAll() }
                    job.cancel()
                    assertNull(state.message, "troca de senha de $role recusada")
                }

                LoginOutcome.Success -> {
                    Unit
                }
            }
            withTimeout(ATTACK_STEP_TIMEOUT) { controller.screen.first { it == Screen.Shell } }
        }

    /** Desenha a casca real, espera a carga e entrega o estado ao bloco. */
    private fun loadedShell(
        connection: ServerConnection,
        block: ComposeUiTest.(ShellState) -> Unit,
    ) {
        runDesktopComposeUiTest(1280, 800) {
            lateinit var state: ShellState
            setContent {
                val scope = rememberCoroutineScope()
                state = remember { ShellState(connection, devMode = true, scope) }
                ZenyTheme { ShellScreen(state, onLogout = {}) }
            }
            waitUntil(timeoutMillis = ATTACK_LOAD_TIMEOUT_MILLIS) { !state.loading }
            block(state)
            state.close()
        }
    }

    // ---------------------------------------------------------------- login recusado

    @Test
    fun wrongPasswordShowsPortugueseMessageWithoutJsonAndKeepsLoginScreen() =
        harness {
            val wrong = "errada-" + UUID.randomUUID()
            val outcome = runBlocking { controller.login(url, env("ZENY_LIVE_ADMIN_LOGIN"), wrong) }
            val failed = assertIs<LoginOutcome.Failed>(outcome)
            assertEquals("Login ou senha incorretos.", failed.message)
            assertUserText(failed.message)
            assertEquals(Screen.Login, controller.screen.value)
            assertNull(settings.serverUrl, "endereço só é salvo depois de um login aceito")
            assertNull(settings.lastLogin, "último login só é salvo depois de um login aceito")
            assertEquals(SessionState.LoggedOut, connections.single().state.value)
        }

    @Test
    fun unknownLoginGivesTheSameMessageAsWrongPassword() =
        harness {
            val unknown = "nao.existe." + UUID.randomUUID()
            val outcome = runBlocking { controller.login(url, unknown, "x-" + UUID.randomUUID()) }
            val failed = assertIs<LoginOutcome.Failed>(outcome)
            assertEquals("Login ou senha incorretos.", failed.message)
            assertEquals(Screen.Login, controller.screen.value)
        }

    @Test
    fun loginAgainstClosedPortFailsInPortugueseAndSavesNothing() =
        harness(checkNotNull(ServerUrl.parse(ATTACK_CLOSED_PORT))) {
            val started = TimeSource.Monotonic.markNow()
            val failed = assertIs<LoginOutcome.Failed>(runBlocking { controller.login(url, "qualquer", "qualquer") })
            assertTrue(started.elapsedNow() < ATTACK_PROBE_LIMIT, "login sem servidor levou ${started.elapsedNow()}")
            assertEquals(ApiError.Network(RuntimeException()).userMessage, failed.message)
            assertEquals(Screen.Login, controller.screen.value)
            assertNull(settings.serverUrl)
        }

    // ---------------------------------------------------------------- Testar contra servidores que não são o Zeny

    private fun fakeServer(handler: (Socket) -> Unit): Pair<ServerSocket, ServerUrl> {
        val socket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { handler(client) }
            }
        }
        return socket to checkNotNull(ServerUrl.parse("http://127.0.0.1:${socket.localPort}"))
    }

    private fun reply(
        client: Socket,
        body: String,
    ) {
        val bytes = body.toByteArray()
        val head =
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
        client.close()
    }

    @Test
    fun probeRejectsAnotherApplicationWithTheNotZenyMessage() {
        val (socket, url) = fakeServer { reply(it, """{"application":"outro","apiVersion":"v1","version":"1"}""") }
        try {
            val failed = assertIs<ProbeResult.Failed>(runBlocking { ServerProbe.check(url) })
            assertEquals("Esse endereço não é um servidor do Zeny Hotel Manager.", failed.message)
        } finally {
            socket.close()
        }
    }

    @Test
    fun probeRejectsAnHtmlPageWithoutLeakingItsBody() {
        val (socket, url) = fakeServer { reply(it, "<html><body>corpo-secreto</body></html>") }
        try {
            val failed = assertIs<ProbeResult.Failed>(runBlocking { ServerProbe.check(url) })
            assertFalse(failed.message.contains("corpo-secreto"))
            assertFalse(failed.message.contains("<"))
        } finally {
            socket.close()
        }
    }

    @Test
    fun probeOfAServerThatNeverAnswersFailsInPortugueseWithinTheLimit() {
        val held = mutableListOf<Socket>()
        val (socket, url) = fakeServer { synchronized(held) { held += it } }
        try {
            val started = TimeSource.Monotonic.markNow()
            val failed = assertIs<ProbeResult.Failed>(runBlocking { ServerProbe.check(url) })
            assertTrue(started.elapsedNow() < ATTACK_PROBE_LIMIT, "o Testar levou ${started.elapsedNow()}")
            assertUserText(failed.message)
        } finally {
            synchronized(held) { held.forEach { runCatching { it.close() } } }
            socket.close()
        }
    }

    // ---------------------------------------------------------------- casca contra dados do servidor

    @Test
    fun receptionistHasExactlyItsAreasAndNoManagement() =
        harness {
            enter("RECEPTIONIST")
            val connection = checkNotNull(controller.connection)
            val me = runBlocking { connection.me() }
            assertEquals(env("ZENY_LIVE_RECEPTIONIST_LOGIN"), me.login)
            val granted = me.permissions.any { it in Area.MANAGEMENT.permissions }
            assertFalse(granted, "o servidor deu gestão ao Recepcionista")

            loadedShell(connection) { state ->
                assertNull(state.error)
                assertEquals(
                    listOf(Area.RECEPTION, Area.RESERVATIONS, Area.ACCOUNTS, Area.GUESTS, Area.HOUSEKEEPING),
                    state.areas.map { it.first },
                )
                onNodeWithText("Gestão (em breve)").assertDoesNotExist()
                onNodeWithText("Gestão").assertDoesNotExist()
                assertEquals(me.name, state.userName)
            }
        }

    @Test
    fun adminShellShowsServerHotelNameBusinessDateAndUserExactly() =
        harness {
            enter("ADMIN")
            val connection = checkNotNull(controller.connection)
            val me = runBlocking { connection.me() }
            val hotel = runBlocking { connection.hotel() }
            val date = runBlocking { connection.businessDate() }
            assertEquals(env("ZENY_LIVE_ADMIN_LOGIN"), me.login)
            val expectedHotel = hotel.tradeName ?: if (hotel.configured) "Hotel" else "Hotel ainda não configurado"

            loadedShell(connection) { state ->
                assertNull(state.error)
                assertEquals(me.name, state.userName)
                assertEquals(expectedHotel, state.hotelName)
                assertEquals(DateInput.format(date), state.businessDate)
                onNodeWithText(expectedHotel).assertExists()
                onNodeWithText("Data operacional: ${DateInput.format(date)}").assertExists()
                // Expectativa independente do IMPLEMENTED_AREAS de produção: só a Governança está pronta.
                assertEquals(Area.entries.map { it to (it == Area.HOUSEKEEPING) }, state.areas)
                (Area.entries - Area.HOUSEKEEPING).forEach { onNodeWithText("${it.label} (em breve)").assertExists() }
                onNodeWithText("Governança").assertExists()
                onNodeWithText("Governança (em breve)").assertDoesNotExist()
            }
        }

    @Test
    fun shellOnAnEndedSessionShowsAPortugueseErrorNotJson() =
        harness {
            enter("RECEPTIONIST")
            val connection = checkNotNull(controller.connection)
            runBlocking { controller.logout() }
            assertEquals(Screen.Login, controller.screen.value)
            loadedShell(connection) { state ->
                val error = assertNotNull(state.error, "sem sessão a casca não pode parecer carregada")
                assertUserText(error.userMessage)
                assertTrue(state.areas.isEmpty(), "sem sessão, nenhuma área")
            }
        }

    @Test
    fun logoutThenLoginAsAnotherUserDoesNotKeepThePreviousAreas() =
        harness {
            enter("ADMIN")
            runBlocking { controller.logout() }
            enter("RECEPTIONIST")
            val connection = checkNotNull(controller.connection)
            loadedShell(connection) { state ->
                assertNull(state.error)
                assertFalse(Area.MANAGEMENT in state.areas.map { it.first }, "áreas do administrador vazaram")
                assertEquals(env("ZENY_LIVE_RECEPTIONIST_LOGIN"), runBlocking { connection.me() }.login)
            }
        }
}
