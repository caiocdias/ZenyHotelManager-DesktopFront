package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import kotlinx.coroutines.CompletableDeferred

/** Uma execução de `reactivateReservation` registrada pelo falso: a `Intent.key` em uso e o token (se houve). */
data class ReactivateRun(
    val key: String,
    val token: String?,
)

/** Uma chamada de `reactivateReservation` (a criação da ação) registrada pelo falso; a `Intent` é a da tela. */
data class ReactivateCall(
    val id: String,
    val intent: Intent,
)

/**
 * Servidor de mentira da reativação. Grava cada chamada, a chave da `Intent` e o token de cada execução, e devolve a
 * resposta ou a falha programada. Com [hold] ligado, a execução só responde quando o teste solta o portão.
 */
class FakeReservationReactivateSource : ReservationReactivateSource {
    var response: ReservationDetailResponse? = null

    /** A primeira execução sem token falha com `ApprovalRequired`; a seguinte, com token, passa. */
    var requiresApproval: ApiError.ApprovalRequired? = null

    /** O resultado de cada execução, na ordem (`null` = sucesso); acabando a fila, sucesso. */
    val outcomes = ArrayDeque<Throwable?>()
    var hold = false
    val held = mutableListOf<CompletableDeferred<Unit>>()
    val calls = mutableListOf<ReactivateCall>()
    val runs = mutableListOf<ReactivateRun>()
    val actions = mutableListOf<ApprovableAction<ReservationDetailResponse>>()

    fun release() {
        held.forEach { it.complete(Unit) }
        held.clear()
        hold = false
    }

    override fun reactivateReservation(
        id: String,
        intent: Intent,
    ): ApprovableAction<ReservationDetailResponse> {
        calls += ReactivateCall(id, intent)
        val action =
            ApprovableAction<ReservationDetailResponse> { token ->
                runs += ReactivateRun(intent.key, token)
                if (hold) CompletableDeferred<Unit>().also { held += it }.await()
                val approval = requiresApproval
                if (token == null && approval != null) throw approval
                outcomes.removeFirstOrNull()?.let { throw it }
                ApiResponse(checkNotNull(response) { "Resposta da reativação fora do servidor de mentira" }, null, 200)
            }
        actions += action
        return action
    }
}
