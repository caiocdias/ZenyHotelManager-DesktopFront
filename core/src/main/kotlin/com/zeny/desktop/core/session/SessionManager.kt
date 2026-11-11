package com.zeny.desktop.core.session

import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.sdk.client.IamApi
import com.zeny.desktop.sdk.model.ChangePasswordRequest
import com.zeny.desktop.sdk.model.DeviceRequest
import com.zeny.desktop.sdk.model.LoginRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val DEVICE_NAME_MAX_LENGTH = 100
private const val SESSION_ENDED_MESSAGE = "Sua sessão foi encerrada. Entre novamente."

/** Nome do dispositivo no login, no limite de tamanho do contrato. */
fun defaultDeviceName(): String = "Zeny Desktop (${System.getProperty("os.name")})".take(DEVICE_NAME_MAX_LENGTH)

/**
 * Login, troca de senha e logout, e o estado da sessão que a interface observa. Os tokens ficam só no
 * `TokenStore`; em disco vai só o último login. O `sessionEnded(code, sessionId)` é o `onSessionEnded` do
 * `Authenticator`.
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
     * Se o servidor aceitou a troca e o refresh seguinte caiu por rede, lança o `ApiError.Network` e o estado passa a
     * `Active(passwordChangeAwaitingRefresh = true)`: a senha nova já vale, e a continuação é
     * `retryPasswordChangeRefresh`. Chamar `changePassword` com essa pendência é erro de programação
     * (`IllegalStateException`), porque um novo POST com outras senhas não refletiria o que o servidor gravou.
     * Sessão encerrada ou trocada no meio (o refresh devolve `null` ou o par de outra sessão): o estado não muda.
     */
    suspend fun changePassword(
        current: String,
        new: String,
    ) {
        check(pendingPasswordChange() == null) {
            "A troca de senha já foi aceita e espera o refresh: chame retryPasswordChangeRefresh, não changePassword."
        }
        val sessionId = store.current()?.sessionId
        executor.execute(credentialCheck = true) { iam.changePassword(ChangePasswordRequest(current, new), it.headers) }
        refreshAfterPasswordChange(sessionId)
    }

    /**
     * Continua a troca de senha que o servidor aceitou e cujo refresh caiu por rede (`Active` com
     * `passwordChangeAwaitingRefresh`): só refaz o refresh pelo voo único, sem novo POST, com a mesma regra de
     * sessão de `changePassword`. No sucesso, o estado passa a `Active` com o `passwordChangeRequired` do par novo
     * e sem a pendência; rede caída de novo lança `ApiError.Network` e mantém a pendência. Sem pendência (por
     * exemplo, a sessão acabou no meio), não faz nada.
     */
    suspend fun retryPasswordChangeRefresh() {
        val pending = pendingPasswordChange() ?: return
        refreshAfterPasswordChange(pending.sessionId)
    }

    private fun pendingPasswordChange(): SessionState.Active? =
        (_state.value as? SessionState.Active)?.takeIf { it.passwordChangeAwaitingRefresh }

    private suspend fun refreshAfterPasswordChange(sessionId: String?) {
        val tokens =
            try {
                authenticator.forceRefresh()
            } catch (e: ApiError.Network) {
                synchronized(lock) {
                    val active = _state.value as? SessionState.Active
                    if (active != null && active.sessionId == sessionId && store.current()?.sessionId == sessionId) {
                        _state.value = active.copy(passwordChangeAwaitingRefresh = true)
                    }
                }
                throw e
            }
        synchronized(lock) {
            val sameSession = tokens?.sessionId == sessionId && store.current()?.sessionId == sessionId
            if (tokens != null && sameSession) activate(tokens)
        }
    }

    /**
     * Avisa o servidor em melhor esforço (sem rede ou recusado, segue igual) e limpa a sessão local, mesmo se a
     * corrotina for cancelada no meio do aviso.
     */
    suspend fun logout() {
        try {
            executor.execute { iam.logout(it.headers) }
        } catch (ignored: ApiError) {
            // Melhor esforço: a sessão local é descartada de qualquer jeito.
        } finally {
            withContext(NonCancellable) {
                synchronized(lock) {
                    store.clear()
                    _state.value = SessionState.LoggedOut
                }
            }
        }
    }

    /** Só para testes: a forma do brief, que limpa o store sem olhar de que sessão é o aviso. */
    internal fun sessionEnded(
        @Suppress("UNUSED_PARAMETER") code: String?,
    ) {
        synchronized(lock) {
            store.clear()
            _state.value = SessionState.Ended(SESSION_ENDED_MESSAGE)
        }
    }

    /**
     * O `onSessionEnded` do `Authenticator`, que já limpou o store: só passa a `Ended` se o estado ativo é da
     * sessão `sessionId` (o aviso de uma sessão antiga não derruba a atual). Chamado de qualquer thread.
     */
    fun sessionEnded(
        @Suppress("UNUSED_PARAMETER") code: String?,
        sessionId: String,
    ) {
        synchronized(lock) {
            if ((_state.value as? SessionState.Active)?.sessionId == sessionId) {
                _state.value = SessionState.Ended(SESSION_ENDED_MESSAGE)
            }
        }
    }

    private fun activate(tokens: Tokens): SessionState.Active =
        SessionState.Active(tokens.userId, tokens.sessionId, tokens.passwordChangeRequired).also { _state.value = it }
}
