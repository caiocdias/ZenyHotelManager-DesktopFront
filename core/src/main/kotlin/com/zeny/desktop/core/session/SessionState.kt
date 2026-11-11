package com.zeny.desktop.core.session

/** Estado da sessão que a interface observa; nenhum campo guarda token. */
sealed interface SessionState {
    data object LoggedOut : SessionState

    data class Active(
        val userId: String,
        val sessionId: String,
        val passwordChangeRequired: Boolean,
    ) : SessionState

    data class Ended(
        val message: String,
    ) : SessionState
}
