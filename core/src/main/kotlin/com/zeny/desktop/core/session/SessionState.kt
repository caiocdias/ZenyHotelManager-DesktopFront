package com.zeny.desktop.core.session

/** Estado da sessão que a interface observa; nenhum campo guarda token. */
sealed interface SessionState {
    data object LoggedOut : SessionState

    /**
     * `passwordChangeAwaitingRefresh`: o servidor aceitou a troca de senha, mas o refresh seguinte caiu por rede;
     * a interface oferece só "tentar de novo" (`SessionManager.retryPasswordChangeRefresh`).
     */
    data class Active(
        val userId: String,
        val sessionId: String,
        val passwordChangeRequired: Boolean,
        val passwordChangeAwaitingRefresh: Boolean = false,
    ) : SessionState

    data class Ended(
        val message: String,
    ) : SessionState
}
