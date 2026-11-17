package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.CancelInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import kotlinx.coroutines.CompletableDeferred

/** Uma chamada de `cancelReservation` (a criação da ação) registrada pelo falso; a `Intent` é a da tela. */
data class CancelCall(
    val id: String,
    val input: CancelInput,
    val intent: Intent,
)

/** Uma execução (`run`) de uma ação de `cancelReservation`: a `Intent.key` em uso e o token, se houve. */
data class CancelRun(
    val key: String,
    val token: String?,
)

/**
 * Servidor de mentira para o cancelamento de reserva.
 *
 * - [reservations] (por id) e [response] são programáveis; cada chamada fica registrada ([readCalls], [calls], [runs]).
 * - [outcomes] é o resultado de cada execução, na ordem (`null` = sucesso); sem fila, sucesso.
 * - [requiresApproval]: a execução sem token falha com `ApprovalRequired`; a com token passa.
 * - [holdRead] e [hold] prendem a próxima resposta até o teste completar o portão ([releaseRead], [release]).
 */
class FakeReservationCancelSource : ReservationCancelSource {
    val reservations = mutableMapOf<String, ReservationDetailResponse>()
    var response: ReservationDetailResponse? = null
    var readError: Exception? = null
    var requiresApproval: ApiError.ApprovalRequired? = null
    val outcomes = ArrayDeque<Throwable?>()

    var holdRead = false
    val heldReads = mutableListOf<CompletableDeferred<Unit>>()
    var hold = false
    val held = mutableListOf<CompletableDeferred<Unit>>()

    val readCalls = mutableListOf<String>()
    val calls = mutableListOf<CancelCall>()
    val runs = mutableListOf<CancelRun>()
    val actions = mutableListOf<ApprovableAction<ReservationDetailResponse>>()

    fun releaseRead() {
        heldReads.forEach { it.complete(Unit) }
        heldReads.clear()
        holdRead = false
    }

    fun release() {
        held.forEach { it.complete(Unit) }
        held.clear()
        hold = false
    }

    override suspend fun reservation(id: String): ReservationDetailResponse {
        readCalls += id
        val error = readError
        if (holdRead) CompletableDeferred<Unit>().also { heldReads += it }.await()
        error?.let { throw it }
        return checkNotNull(reservations[id]) { "Reserva $id fora do servidor de mentira" }
    }

    override fun cancelReservation(
        id: String,
        input: CancelInput,
        intent: Intent,
    ): ApprovableAction<ReservationDetailResponse> {
        calls += CancelCall(id, input, intent)
        val action =
            ApprovableAction<ReservationDetailResponse> { token ->
                runs += CancelRun(intent.key, token)
                if (hold) CompletableDeferred<Unit>().also { held += it }.await()
                val approval = requiresApproval
                if (token == null && approval != null) throw approval
                outcomes.removeFirstOrNull()?.let { throw it }
                val body = checkNotNull(response) { "Resposta do cancelamento fora do servidor de mentira" }
                ApiResponse(body, null, 200)
            }
        actions += action
        return action
    }
}
