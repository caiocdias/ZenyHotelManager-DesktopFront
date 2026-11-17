package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
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

private const val KEY_IN_USE = "IDEMPOTENCY_KEY_IN_USE"
private const val SERVER_ERROR = 500
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
 * (rede, resposta incompatível, sessão encerrada, `IDEMPOTENCY_KEY_IN_USE`, erro 5xx) ou a aprovação foi cancelada;
 * qualquer resposta definitiva gera outra. Depois de um resultado desconhecido o estado fica [unconfirmed] até uma
 * resposta definitiva; repetir a conclusão continua permitido.
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
    var error by mutableStateOf<String?>(null)
        private set

    /** "Reserva R1 reativada."; depois dele só se fecha. */
    var done by mutableStateOf<String?>(null)
        private set

    private var closed = false
    private var intent: Intent? = null

    fun confirm() {
        if (closed || busy || done != null) return
        busy = true
        error = null
        val current = intent ?: Intent().also { intent = it }
        ownScope.launch {
            try {
                approve(source.reactivateReservation(reservationId, current))?.let { finish() }
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
        if (resultUnknown(failure)) {
            unconfirmed = true
            // O servidor guarda o 5xx sob a chave e o repete: refazer não adianta; 502/504 de proxy não são guardados.
            val stored = failure is ApiError.Problem && failure.status >= SERVER_ERROR
            error = if (stored) SERVER_UNCONFIRMED_TEXT else UNCONFIRMED_TEXT
            return
        }
        unconfirmed = false
        intent = null
        error = failure.userMessage
    }

    /** O servidor pode ter reativado a reserva sem a resposta chegar: a repetição leva a mesma chave. */
    private fun resultUnknown(failure: ApiError): Boolean =
        failure is ApiError.Network ||
            failure is ApiError.IncompatibleResponse ||
            failure is ApiError.SessionEnded ||
            (failure is ApiError.Problem && (failure.code == KEY_IN_USE || failure.status >= SERVER_ERROR)) ||
            (failure is ApiError.Unexpected && failure.status >= SERVER_ERROR)

    override fun toString(): String = "ReservationReactivateState(busy=$busy, unconfirmed=$unconfirmed, done=$done)"
}
