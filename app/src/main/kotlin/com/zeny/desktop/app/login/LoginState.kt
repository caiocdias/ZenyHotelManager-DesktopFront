package com.zeny.desktop.app.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URI

private const val INVALID_SERVER = "Endereço inválido. Use http://endereço:porta."
private const val SERVER_WITH_PATH = "Informe só o endereço e a porta, sem caminho."
private const val BLANK_LOGIN = "Informe o login."
private const val BLANK_PASSWORD = "Informe a senha."
private const val GENERIC_FAILURE = "Não foi possível concluir o login."

/** O que a tela de login precisa do restante do app (a implementação real fica na fiação). */
interface LoginBackend {
    suspend fun probe(url: ServerUrl): ProbeResult

    suspend fun login(
        url: ServerUrl,
        login: String,
        password: String,
    ): LoginOutcome
}

sealed interface LoginOutcome {
    data object Success : LoginOutcome

    data object PasswordChangeRequired : LoginOutcome

    data class Failed(
        val message: String,
    ) : LoginOutcome
}

/** Estado da tela de login. A senha nunca aparece em `toString`, mensagem ou disco. */
class LoginState(
    private val backend: LoginBackend,
    initialServer: String,
    initialLogin: String?,
    private val scope: CoroutineScope,
) {
    var server by mutableStateOf(initialServer)
    var login by mutableStateOf(initialLogin.orEmpty())
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var probeMessage by mutableStateOf<String?>(null)
        private set

    private var probing = false

    fun testConnection() {
        probeMessage = null
        val url = ServerUrl.parse(server)
        val problem = serverProblem(url)
        if (problem != null || url == null) {
            probeMessage = problem
            return
        }
        if (probing) return
        probing = true
        scope.launch {
            try {
                probeMessage =
                    when (val result = backend.probe(url)) {
                        is ProbeResult.Ok -> "Servidor encontrado (versão ${result.version})."
                        is ProbeResult.Failed -> result.message
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                probeMessage = (e as? ApiError)?.userMessage ?: ApiError.Network(e).userMessage
            } finally {
                probing = false
            }
        }
    }

    fun submit(onDone: (LoginOutcome) -> Unit) {
        if (busy) return
        val url = ServerUrl.parse(server)
        val typedLogin = login.trim()
        val typedPassword = password
        val problem = serverProblem(url) ?: credentialsProblem(typedLogin, typedPassword)
        message = problem
        if (problem != null || url == null) return
        busy = true
        scope.launch {
            val outcome =
                try {
                    backend.login(url, typedLogin, typedPassword)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    LoginOutcome.Failed((e as? ApiError)?.userMessage ?: GENERIC_FAILURE)
                } finally {
                    password = ""
                    busy = false
                }
            if (outcome is LoginOutcome.Failed) message = outcome.message
            onDone(outcome)
        }
    }

    private fun serverProblem(url: ServerUrl?): String? =
        when {
            url == null -> INVALID_SERVER
            hasPath(url) -> SERVER_WITH_PATH
            else -> null
        }

    private fun credentialsProblem(
        typedLogin: String,
        typedPassword: String,
    ): String? =
        when {
            typedLogin.isEmpty() -> BLANK_LOGIN
            typedPassword.isEmpty() -> BLANK_PASSWORD
            else -> null
        }

    private fun hasPath(url: ServerUrl) = URI(url.value).rawPath.orEmpty().isNotEmpty()

    override fun toString() = "LoginState(server=$server, login=$login, busy=$busy)"
}
