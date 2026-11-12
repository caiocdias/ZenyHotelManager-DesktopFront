package com.zeny.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.login.LoginBackend
import com.zeny.desktop.app.login.LoginOutcome
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val SESSION_ENDED_NOTICE = "Sua sessão foi encerrada. Entre novamente."
private val LOGOUT_TIMEOUT = 3.seconds

sealed interface Screen {
    data object Login : Screen

    data object PasswordChange : Screen

    data object Shell : Screen
}

/**
 * A tela do app segue o estado da sessão da conexão atual: `Active` com troca de senha pendente vai a
 * `PasswordChange`, `Active` a `Shell`, `Ended` volta a `Login` com aviso e o logout volta a `Login` sem aviso.
 * A conexão nasce no login, para o endereço digitado; trocar de endereço encerra e fecha a anterior.
 * Em disco só vão o endereço e o último login, depois de um login aceito e em melhor esforço.
 * O `scope` observa a sessão até o `close()`.
 */
class AppController(
    private val settings: LocalSettings,
    private val connect: (ServerUrl) -> ServerConnection,
    probe: suspend (ServerUrl) -> ProbeResult,
    private val scope: CoroutineScope,
    private val logoutTimeout: Duration = LOGOUT_TIMEOUT,
) : LoginBackend,
    AutoCloseable {
    private val prober = probe
    private val currentScreen = MutableStateFlow<Screen>(Screen.Login)
    val screen: StateFlow<Screen> = currentScreen.asStateFlow()

    var notice by mutableStateOf<String?>(null)
        private set

    @Volatile
    var connection: ServerConnection? = null
        private set
    private var connectionUrl: ServerUrl? = null
    private var watcher: Job? = null

    override suspend fun probe(url: ServerUrl): ProbeResult = prober(url)

    override suspend fun login(
        url: ServerUrl,
        login: String,
        password: String,
    ): LoginOutcome {
        val conn = connectionFor(url)
        return try {
            conn.login(login, password)
            accepted(conn, url, login)
        } catch (
            @Suppress("SwallowedException") e: ApiError.PasswordChangeRequired,
        ) {
            LoginOutcome.PasswordChangeRequired
        } catch (e: ApiError) {
            LoginOutcome.Failed(e.userMessage)
        }
    }

    private fun accepted(
        conn: ServerConnection,
        url: ServerUrl,
        login: String,
    ): LoginOutcome {
        bestEffort { settings.serverUrl = url.value }
        bestEffort { settings.lastLogin = login }
        val state = conn.state.value
        show(conn, state)
        val changeRequired = state is SessionState.Active && state.passwordChangeRequired
        return if (changeRequired) LoginOutcome.PasswordChangeRequired else LoginOutcome.Success
    }

    /** Volta ao login já, sem esperar o servidor; a conexão fica para o próximo login no mesmo endereço. */
    suspend fun logout() {
        notice = null
        currentScreen.value = Screen.Login
        val conn = connection ?: return
        // Sem observar no meio do logout: um `Active` ainda não lido não pode devolver a casca.
        watcher?.cancel()
        try {
            conn.logout()
        } finally {
            if (conn === connection) watch(conn)
        }
    }

    /** Encerra a sessão (no máximo `logoutTimeout`, para servidor fora do ar não prender a saída) e fecha a conexão. */
    override fun close() {
        watcher?.cancel()
        watcher = null
        val conn = connection ?: return
        connection = null
        connectionUrl = null
        runBlocking { shutdown(conn) }
    }

    private suspend fun connectionFor(url: ServerUrl): ServerConnection {
        val current = connection
        if (current != null && connectionUrl == url) return current
        if (current != null) {
            watcher?.cancel()
            connection = null
            connectionUrl = null
            shutdown(current)
        }
        return connect(url).also {
            connection = it
            connectionUrl = url
            watch(it)
        }
    }

    private suspend fun shutdown(conn: ServerConnection) {
        try {
            if (conn.state.value is SessionState.Active) withTimeoutOrNull(logoutTimeout) { conn.logout() }
        } finally {
            conn.close()
        }
    }

    private fun watch(conn: ServerConnection) {
        watcher?.cancel()
        watcher = scope.launch { conn.state.collect { show(conn, it) } }
    }

    private fun show(
        conn: ServerConnection,
        state: SessionState,
    ) {
        if (conn !== connection) return
        when (state) {
            is SessionState.Active -> {
                notice = null
                currentScreen.value = if (state.passwordChangeRequired) Screen.PasswordChange else Screen.Shell
            }

            is SessionState.Ended -> {
                notice = SESSION_ENDED_NOTICE
                currentScreen.value = Screen.Login
            }

            SessionState.LoggedOut -> {
                currentScreen.value = Screen.Login
            }
        }
    }

    /** Preferências que falham (disco cheio, sem permissão) não derrubam um login já aceito. */
    private inline fun bestEffort(write: () -> Unit) {
        try {
            write()
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // Só conveniência de preenchimento no próximo início.
        }
    }
}
