package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.CancelInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** O que o cancelamento usa do servidor: ler a reserva e cancelá-la (com a aprovação de superior, se ele pedir). */
interface ReservationCancelSource {
    suspend fun reservation(id: String): ReservationDetailResponse

    fun cancelReservation(
        id: String,
        input: CancelInput,
        intent: Intent,
    ): ApprovableAction<ReservationDetailResponse>
}

/** Os motivos oferecidos, na ordem da tela, com o rótulo. `EXPIRED` é só do servidor e não é oferecido. */
val CANCEL_REASONS: List<Pair<String, String>> =
    listOf(
        "GUEST_REQUEST" to "Pedido do hóspede",
        "HOTEL_REQUEST" to "Decisão do hotel",
        "DUPLICATE" to "Reserva duplicada",
        "NO_GUARANTEE" to "Sem garantia",
        "OTHER" to "Outro motivo",
    )

private const val MAX_NOTES_LENGTH = 500
private const val UNCONFIRMED_TEXT =
    "Não foi possível confirmar o cancelamento. Conclua de novo sem mudar nada; " +
        "se fechar, confira a reserva na Busca antes de refazer."

private const val SERVER_UNCONFIRMED_TEXT =
    "O servidor não confirmou o cancelamento. Feche e confira a reserva na Busca antes de refazer."

/**
 * Estado do diálogo de cancelamento de reserva: lê a reserva, mostra os termos da política copiada nela ([terms]) e
 * cancela com o motivo, a observação e a isenção escolhidos.
 *
 * - O contrato não tem prévia da multa, e o front não calcula preço nem prazo: antes de cancelar só há os termos, e a
 *   multa vem do servidor depois, em [done]. [confirm] só vale com os termos lidos: com a carga em voo ou com falha
 *   (o [error] mostra, e [start] tenta de novo), nada vai à rede.
 * - Como o `WalkInState`: as mutações correm no escopo da UI (uma thread só) e [close] cancela só o que é daqui. A
 *   conclusão leva uma `Idempotency-Key` por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido
 *   (`classifyWriteFailure`) ou a aprovação foi cancelada; resposta definitiva ou mudança de motivo, observação ou
 *   isenção gera outra. Depois de um resultado desconhecido o estado fica [unconfirmed] e, como com [busy] ou depois de
 *   [done], as entradas viram no-op. No `5xx` guardado sob a chave, o estado fica [closeOnly]: [confirm] também vira
 *   no-op, e só resta fechar e conferir.
 * - A isenção da multa é ação sensível: o `APPROVAL_REQUIRED` vai ao `approve`, e a aprovação cancelada deixa o
 *   diálogo aberto, sem cancelar a reserva.
 */
class ReservationCancelState(
    private val source: ReservationCancelSource,
    scope: CoroutineScope,
    private val reservationId: String,
    private val approve:
        suspend (ApprovableAction<ReservationDetailResponse>) -> ApiResponse<ReservationDetailResponse>?,
    private val onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    /** A carga da reserva está em voo. */
    var loading by mutableStateOf(false)
        private set

    /** O código da reserva, para o título. */
    var code by mutableStateOf<String?>(null)
        private set

    /** Os termos da política de cancelamento da reserva lida; `null` até a carga dar certo. */
    var terms by mutableStateOf<String?>(null)
        private set

    /** Só valores de [CANCEL_REASONS]; o padrão é `GUEST_REQUEST`. */
    var reason: String
        get() = reasonValue
        set(value) {
            if (locked || value == reasonValue || CANCEL_REASONS.none { it.first == value }) return
            reasonValue = value
            dropIntent()
        }
    private var reasonValue by mutableStateOf(CANCEL_REASONS.first().first)

    /** Texto livre de até 500 caracteres; o excesso é recusado. */
    var notes: String
        get() = notesValue
        set(value) {
            if (locked || value == notesValue || value.length > MAX_NOTES_LENGTH) return
            notesValue = value
            dropIntent()
        }
    private var notesValue by mutableStateOf("")

    /** Pede ao servidor para não cobrar a multa; é ação sensível e passa pela aprovação. */
    var waivePenalty: Boolean
        get() = waiveValue
        set(value) {
            if (locked || value == waiveValue) return
            waiveValue = value
            dropIntent()
        }
    private var waiveValue by mutableStateOf(false)

    var busy by mutableStateOf(false)
        private set

    /** O último resultado é desconhecido: as entradas ficam travadas até uma resposta definitiva. */
    var unconfirmed by mutableStateOf(false)
        private set

    /** A falha ficou guardada no servidor sob a chave: repetir não adianta, só fechar e conferir. */
    var closeOnly by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** O resumo do cancelamento feito, com a multa do servidor. */
    var done by mutableStateOf<String?>(null)
        private set

    // `closed` é estado observável: `canConfirm` o lê, e o Compose só recompõe o que leu estado.
    private var closed by mutableStateOf(false)
    private var generation = 0
    private var loadJob: Job? = null
    private var intent: Intent? = null

    val canConfirm: Boolean
        get() = !closed && terms != null && !loading && !busy && !closeOnly && done == null

    /** Com o estado ocupado, não confirmado, só para fechar ou concluído, as entradas viram no-op. */
    private val locked: Boolean get() = closed || busy || unconfirmed || closeOnly || done != null

    /** Lê a reserva e os termos; de novo, tenta outra vez depois de uma falha. */
    fun start() {
        if (closed || busy || done != null) return
        val mine = ++generation
        loading = true
        error = null
        loadJob?.cancel()
        loadJob =
            ownScope.launch {
                try {
                    val reservation = source.reservation(reservationId).reservation
                    if (mine == generation) {
                        code = reservation.code
                        terms = BookingText.cancellationTerms(reservation.cancellationTerms)
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    if (mine == generation) {
                        terms = null
                        error = (e as? ApiError ?: ApiError.Network(e)).userMessage
                    }
                } finally {
                    if (mine == generation) loading = false
                }
            }
    }

    fun confirm() {
        if (!canConfirm) return
        val input = CancelInput(reasonValue, waiveValue, notesValue.takeIf { it.isNotBlank() })
        busy = true
        error = null
        val current = intent ?: Intent().also { intent = it }
        ownScope.launch {
            try {
                val approved = approve(source.cancelReservation(reservationId, input, current))
                if (approved == null) {
                    // Aprovação cancelada: o servidor respondeu APPROVAL_REQUIRED, nada foi feito sob a chave.
                    // Destrava as entradas e mantém a intenção (a chave não muda).
                    unconfirmed = false
                } else {
                    finish(approved.value)
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
        generation++
        jobs.cancel()
    }

    private fun finish(response: ReservationDetailResponse) {
        val reservation = response.reservation
        val penalty = (reservation.cancellation as? Option.Some)?.value?.penalty
        val text = "Reserva ${reservation.code} cancelada."
        dropIntent()
        unconfirmed = false
        error = null
        done = if (penalty == null) text else "$text ${penaltyText(penalty.amount, penalty.waived)}".trimEnd()
        onChanged()
    }

    /** A multa como o servidor a decidiu: isenta, lançada ou sem multa; um valor ilegível não afirma nada. */
    private fun penaltyText(
        amount: String,
        waived: Boolean,
    ): String {
        val owed = runCatching { Money.parse(amount).amount.signum() > 0 }.getOrNull()
        return when {
            waived -> "Multa isenta: ${ReceptionText.money(amount)}."
            owed == true -> "Multa lançada na conta: ${ReceptionText.money(amount)}."
            owed == false -> "Sem multa."
            else -> ""
        }
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
        dropIntent()
        error = failure.userMessage
    }

    private fun dropIntent() {
        intent = null
    }

    override fun toString(): String =
        "ReservationCancelState(reservationId=$reservationId, busy=$busy, unconfirmed=$unconfirmed, done=$done)"
}
