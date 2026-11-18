package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val UNCONFIRMED_TEXT =
    "Não foi possível confirmar a reativação. Conclua de novo sem mudar nada; " +
        "se fechar, confira a reserva na Busca antes de refazer."

private const val SERVER_UNCONFIRMED_TEXT =
    "O servidor não confirmou a reativação. Feche e confira a reserva na Busca antes de refazer."

/** O que a reativação usa do servidor: reativar a reserva (com a aprovação de superior, se o servidor pedir). */
fun interface ReservationReactivateSource {
    fun reactivateReservation(
        id: String,
        intent: Intent,
    ): ApprovableAction<ReservationDetailResponse>
}

/**
 * Estado do diálogo que reativa uma reserva cancelada ou em no-show. A operação não tem entradas: o servidor devolve
 * a reserva a Confirmada, com o preço congelado, e decide se pede a aprovação de um superior.
 *
 * A reativação leva uma `Idempotency-Key` por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido
 * (`classifyWriteFailure`) ou a aprovação foi cancelada; qualquer resposta definitiva gera outra. Depois de um
 * resultado desconhecido o estado fica [unconfirmed] até uma resposta definitiva; repetir a conclusão continua
 * permitido, salvo no `5xx` guardado sob a chave: aí o estado fica [closeOnly], [confirm] vira no-op e só resta fechar.
 *
 * Como o `NewReservationState`: as mutações correm no escopo da UI (uma thread só), [close] cancela só o que é daqui e
 * a resposta que chega depois dele é descartada. [onChanged] só roda quando a reativação deu certo.
 */
class ReservationReactivateState(
    private val source: ReservationReactivateSource,
    scope: CoroutineScope,
    private val reservationId: String,
    val code: String,
    private val approve: suspend (
        ApprovableAction<ReservationDetailResponse>,
    ) -> ApiResponse<ReservationDetailResponse>?,
    private val onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    var busy by mutableStateOf(false)
        private set

    /** O último resultado é desconhecido: só repetir a conclusão (com a mesma chave) ou fechar. */
    var unconfirmed by mutableStateOf(false)
        private set

    /** A falha ficou guardada no servidor sob a chave: repetir não adianta, só fechar e conferir. */
    var closeOnly by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** "Reserva R1 reativada."; depois dele só se fecha. */
    var done by mutableStateOf<String?>(null)
        private set

    private var closed = false
    private var intent: Intent? = null

    /** Fechado, ocupado, só para fechar ou concluído: a reativação vira no-op. */
    private val locked: Boolean get() = closed || busy || closeOnly || done != null

    fun confirm() {
        if (locked) return
        busy = true
        error = null
        val current = intent ?: Intent().also { intent = it }
        ownScope.launch {
            try {
                val approved = approve(source.reactivateReservation(reservationId, current))
                if (approved == null) {
                    // Aprovação cancelada: o servidor respondeu APPROVAL_REQUIRED, nada foi feito sob a chave.
                    // Destrava as entradas e mantém a intenção (a chave não muda).
                    unconfirmed = false
                } else {
                    finish()
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                fail(e as? ApiError ?: ApiError.Network(e))
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        closed = true
        jobs.cancel()
    }

    private fun finish() {
        intent = null
        unconfirmed = false
        error = null
        done = "Reserva $code reativada."
        onChanged()
    }

    private fun fail(failure: ApiError) {
        val outcome = classifyWriteFailure(failure)
        if (outcome != WriteFailure.DEFINITIVE) {
            unconfirmed = true
            closeOnly = outcome == WriteFailure.UNKNOWN_STORED
            error = if (closeOnly) SERVER_UNCONFIRMED_TEXT else UNCONFIRMED_TEXT
            return
        }
        unconfirmed = false
        intent = null
        error = failure.userMessage
    }

    override fun toString(): String = "ReservationReactivateState(busy=$busy, unconfirmed=$unconfirmed, done=$done)"
}
