package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BookingInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.BookingResponse
import kotlinx.coroutines.CompletableDeferred

/** Uma execução de `createBooking` registrada pelo falso: a `Intent.key` em uso e o token de aprovação (se houve). */
data class BookingRun(
    val key: String,
    val token: String?,
)

/** Uma chamada de `createBooking` (a criação da ação) registrada pelo falso; a `Intent` é a da tela. */
data class BookingCall(
    val input: BookingInput,
    val intent: Intent,
)

/**
 * Servidor de mentira da nova reserva. Grava cada `BookingInput`, a chave da `Intent` e o token de cada execução, e
 * devolve a resposta ou a falha programada. Com [hold] ligado, a execução só responde quando o teste solta o portão.
 */
class FakeNewReservationSource : NewReservationSource {
    var response: BookingResponse? = null

    /** A primeira execução sem token falha com `ApprovalRequired`; a seguinte, com token, passa. */
    var requiresApproval: ApiError.ApprovalRequired? = null

    /** O resultado de cada execução, na ordem (`null` = sucesso); acabando a fila, sucesso. */
    val outcomes = ArrayDeque<Throwable?>()
    var hold = false
    val held = mutableListOf<CompletableDeferred<Unit>>()
    val calls = mutableListOf<BookingCall>()
    val runs = mutableListOf<BookingRun>()
    val actions = mutableListOf<ApprovableAction<BookingResponse>>()

    fun release() {
        held.forEach { it.complete(Unit) }
        held.clear()
        hold = false
    }

    override fun createBooking(
        input: BookingInput,
        intent: Intent,
    ): ApprovableAction<BookingResponse> {
        calls += BookingCall(input, intent)
        val action =
            ApprovableAction<BookingResponse> { token ->
                runs += BookingRun(intent.key, token)
                if (hold) CompletableDeferred<Unit>().also { held += it }.await()
                val approval = requiresApproval
                if (token == null && approval != null) throw approval
                outcomes.removeFirstOrNull()?.let { throw it }
                ApiResponse(checkNotNull(response) { "Resposta da reserva fora do servidor de mentira" }, null, 201)
            }
        actions += action
        return action
    }
}
