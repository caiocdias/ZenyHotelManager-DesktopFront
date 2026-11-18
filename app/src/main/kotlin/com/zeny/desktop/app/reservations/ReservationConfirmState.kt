package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val DEFAULT_GUARANTEE = "CARD"

/** O que a confirmação da pré-reserva usa de um servidor: a leitura da reserva e a confirmação com a garantia. */
interface ReservationConfirmSource {
    suspend fun reservation(id: String): ReservationDetailResponse

    suspend fun confirmReservation(
        id: String,
        guarantee: String,
        intent: Intent,
    ): ReservationDetailResponse
}

/**
 * Estado do diálogo que confirma uma pré-reserva com a garantia: lê a reserva (política de garantia e depósito) e
 * confirma com `CARD` ou `DEPOSIT`.
 *
 * A confirmação leva uma `Idempotency-Key` por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido
 * (`classifyWriteFailure`); qualquer resposta definitiva, ou trocar a garantia, gera outra. Não há aprovação de
 * superior nesta operação.
 *
 * Como o `UndoCheckInState`: as mutações correm no escopo da UI (uma thread só), [close] cancela só o que é daqui e a
 * resposta que chega depois dele é descartada. [onChanged] só roda quando a confirmação deu certo.
 */
@Suppress("TooManyFunctions") // etapas do fluxo: leitura, garantia, confirmação e a chave
class ReservationConfirmState(
    private val source: ReservationConfirmSource,
    scope: CoroutineScope,
    private val reservationId: String,
    private val onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    /** A leitura da reserva está em voo: a confirmação e a escolha da garantia viram no-op. */
    var loading by mutableStateOf(false)
        private set

    /** O código da reserva, para o título; nulo até a leitura. */
    var code by mutableStateOf<String?>(null)
        private set

    /** "Garantia exigida: …" da política da reserva; nulo se a política não exige garantia. */
    var terms by mutableStateOf<String?>(null)
        private set

    /** "Depósito exigido: R$ …" e, se já houve, " · recebido: R$ …"; nulo sem depósito exigido. */
    var deposit by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** O texto de pessoa da última falha do servidor. */
    var error by mutableStateOf<String?>(null)
        private set

    /** "Reserva R1 confirmada."; depois dele só se fecha. */
    var done by mutableStateOf<String?>(null)
        private set

    private var choice by mutableStateOf(DEFAULT_GUARANTEE)
    private var loaded by mutableStateOf(false)
    private var closed = false
    private var generation = 0
    private var intent: Intent? = null
    private var intentGuarantee: String? = null

    /** `CARD` ou `DEPOSIT`; nasce com o tipo que a política pede, senão `CARD`. */
    var guarantee: String
        get() = choice
        set(value) {
            if (locked || value !in BookingText.GUARANTEES) return
            choice = value
            dropIntentIfChanged()
        }

    val canConfirm: Boolean
        get() = !closed && loaded && !loading && !busy && done == null

    private val locked: Boolean get() = closed || loading || busy || done != null

    fun start() {
        if (closed) return
        val mine = ++generation
        loading = true
        error = null
        ownScope.launch {
            try {
                apply(source.reservation(reservationId), mine)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                if (mine == generation) error = apiErrorOf(e).userMessage
            } finally {
                if (mine == generation) loading = false
            }
        }
    }

    fun confirm() {
        if (closed || !canConfirm) return
        busy = true
        error = null
        val chosen = choice
        val current = currentIntent(chosen)
        ownScope.launch {
            try {
                val response = source.confirmReservation(reservationId, chosen, current)
                currentCoroutineContext().ensureActive()
                finish(response)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                fail(apiErrorOf(e))
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        closed = true
        generation++
        jobs.cancel()
    }

    private fun apply(
        response: ReservationDetailResponse,
        mine: Int,
    ) {
        if (mine != generation) return
        val reservation = response.reservation
        code = reservation.code
        terms = BookingText.guaranteeTerms(reservation.guaranteeTerms)
        deposit = depositLine(response)
        choice = reservation.guaranteeTerms.type.takeIf { it in BookingText.GUARANTEES } ?: DEFAULT_GUARANTEE
        loaded = true
        dropIntentIfChanged()
    }

    private fun finish(response: ReservationDetailResponse) {
        dropIntent()
        error = null
        done = "Reserva ${response.reservation.code} confirmada."
        onChanged()
    }

    private fun fail(failure: ApiError) {
        // Resultado desconhecido: a repetição leva a mesma chave. Resposta definitiva: a próxima é outra intenção.
        if (classifyWriteFailure(failure) == WriteFailure.DEFINITIVE) dropIntent()
        error = failure.userMessage
    }

    private fun currentIntent(chosen: String): Intent {
        dropIntentIfChanged()
        return intent ?: Intent().also {
            intent = it
            intentGuarantee = chosen
        }
    }

    private fun dropIntentIfChanged() {
        if (intentGuarantee != choice) dropIntent()
    }

    private fun dropIntent() {
        intent = null
        intentGuarantee = null
    }

    /** Os valores são os do servidor; o `DEPOSIT_INSUFFICIENT` não os expõe e fica só com o texto do catálogo. */
    private fun depositLine(response: ReservationDetailResponse): String? {
        val reservation = response.reservation
        val required = present((reservation.requiredDeposit as? Option.Some)?.value) ?: return null
        val received =
            ((reservation.guarantee as? Option.Some)?.value?.depositReceived as? Option.Some)
                ?.value
                ?.let(::present)
        val receivedText = received?.let { " · recebido: ${ReceptionText.money(it)}" }.orEmpty()
        return "Depósito exigido: ${ReceptionText.money(required)}$receivedText"
    }

    private fun present(value: String?): String? = value?.takeIf { it.isNotBlank() }
}

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
