package com.zeny.desktop.core.session

import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.sdk.client.IamApi
import com.zeny.desktop.sdk.model.ChangePasswordRequest
import com.zeny.desktop.sdk.model.DeviceRequest
import com.zeny.desktop.sdk.model.LoginRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val DEVICE_NAME_MAX_LENGTH = 100
private const val SESSION_ENDED_MESSAGE = "Sua sessão foi encerrada. Entre novamente."

/** Nome do dispositivo no login, no limite de tamanho do contrato. */
fun defaultDeviceName(): String = "Zeny Desktop (${System.getProperty("os.name")})".take(DEVICE_NAME_MAX_LENGTH)

/**
 * Login, troca de senha e logout, e o estado da sessão que a interface observa. Os tokens ficam só no
 * `TokenStore`; em disco vai só o último login. O `sessionEnded` é o `onSessionEnded` do `Authenticator`.
 */
class SessionManager(
    private val iam: IamApi,
    private val executor: ApiExecutor,
    private val store: TokenStore,
    private val authenticator: Authenticator,
    private val settings: LocalSettings,
    private val deviceName: String = defaultDeviceName(),
) {
    /** Torna atômica cada transição que mexe no store e no estado juntos (chamadas vêm de threads diferentes). */
    private val lock = Any()
    private val _state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Abre a sessão; um `ApiError` (por exemplo `401 INVALID_CREDENTIALS`) sai sem mudar o estado. */
    suspend fun login(
        login: String,
        password: String,
    ): SessionState.Active {
        val request = LoginRequest(DeviceRequest(id = settings.deviceId, name = deviceName), login, password)
        val tokens = executor.execute { iam.login(request, it.headers) }.value.toTokens()
        settings.lastLogin = login
        return synchronized(lock) {
            store.set(tokens)
            activate(tokens)
        }
    }

    /**
     * Troca a senha e renova o par pelo voo único, para o estado refletir o `passwordChangeRequired` do servidor.
     * `401 INVALID_CREDENTIALS`/`ACCOUNT_LOCKED` conferem a senha atual: lança `ApiError.Problem` e mantém a sessão.
     * Sessão encerrada ou trocada no meio (o refresh devolve `null` ou o par de outra sessão): o estado não muda.
     */
    suspend fun changePassword(
        current: String,
        new: String,
    ) {
        val sessionId = store.current()?.sessionId
        executor.execute(credentialCheck = true) { iam.changePassword(ChangePasswordRequest(current, new), it.headers) }
        val tokens = authenticator.forceRefresh() ?: return
        synchronized(lock) {
            if (tokens.sessionId == sessionId && store.current()?.sessionId == sessionId) activate(tokens)
        }
    }

    /** Avisa o servidor em melhor esforço (sem rede ou recusado, segue igual) e limpa a sessão local. */
    suspend fun logout() {
        try {
            executor.execute { iam.logout(it.headers) }
        } catch (ignored: ApiError) {
            // Melhor esforço: a sessão local é descartada de qualquer jeito.
        }
        synchronized(lock) {
            store.clear()
            _state.value = SessionState.LoggedOut
        }
    }

    /**
     * A sessão acabou do lado do servidor (`code` do `401`); a interface pede novo login. Chamado de qualquer
     * thread (o escopo do refresh ou quem fez a chamada).
     */
    fun sessionEnded(
        @Suppress("UNUSED_PARAMETER") code: String?,
    ) {
        synchronized(lock) {
            store.clear()
            _state.value = SessionState.Ended(SESSION_ENDED_MESSAGE)
        }
    }

    private fun activate(tokens: Tokens): SessionState.Active =
        SessionState.Active(tokens.userId, tokens.sessionId, tokens.passwordChangeRequired).also { _state.value = it }
}
