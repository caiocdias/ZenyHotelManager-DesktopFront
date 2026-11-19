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
 *   multa vem do servidor depois, em [done], com o total de multas da reserva (`penaltiesCharged`) copiado, nunca
 *   somado; a multa que a reserva já tem na conta aparece antes, em [previousPenalty]. [confirm] só vale com os
 *   termos lidos: com a carga em voo ou com falha
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

    /**
     * O aviso da multa de cancelamento que a reserva lida já tem na conta (o `penaltiesCharged` do servidor, maior que
     * zero); `null` sem multa anterior, com o valor ilegível ou até a carga dar certo.
     */
    var previousPenalty by mutableStateOf<String?>(null)
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

    /** Fechado, escrevendo, só para fechar ou concluído: a releitura não roda (apagaria o texto da falha guardada). */
    private val reloadBlocked: Boolean get() = closed || busy || closeOnly || done != null

    /** Lê a reserva e os termos; de novo, tenta outra vez depois de uma falha. */
    fun start() {
        if (reloadBlocked) return
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
                        previousPenalty = previousPenaltyNotice(reservation.penaltiesCharged)
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    if (mine == generation) {
                        terms = null
                        previousPenalty = null
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
        done =
            if (penalty == null) {
                text
            } else {
                "$text ${penaltyText(penalty.amount, penalty.waived, reservation.penaltiesCharged)}".trimEnd()
            }
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
        dropIntent()
        error = failure.userMessage
    }

    private fun dropIntent() {
        intent = null
    }

    override fun toString(): String =
        "ReservationCancelState(reservationId=$reservationId, busy=$busy, unconfirmed=$unconfirmed, done=$done)"
}

/**
 * A multa como o servidor a decidiu ([amount], isenta ou não) e, ao lado, o total de multas da reserva ([charged], o
 * `penaltiesCharged` relido), sem somar nada: isenta, lançada, sem multa nova com a anterior na conta ou sem multa. Um
 * valor ilegível, em qualquer dos dois, não afirma nada.
 */
private fun penaltyText(
    amount: String,
    waived: Boolean,
    charged: String,
): String {
    val owed = positive(amount)
    val hasCharges = positive(charged)
    if (owed == null || hasCharges == null) return ""
    val totalShown = if (waived) hasCharges else owed && exceeds(charged, amount)
    val total = if (totalShown) " Total de multas de cancelamento desta reserva: ${money(charged)}." else ""
    return when {
        waived -> "Multa isenta: ${money(amount)}.$total"
        owed -> "Multa lançada na conta: ${money(amount)}.$total"
        hasCharges -> "Sem multa nova. A conta já tem ${money(charged)} de multa de cancelamento anterior."
        else -> "Sem multa."
    }
}

/** O aviso, antes de cancelar, da multa que a reserva já tem na conta ([charged] maior que zero). */
private fun previousPenaltyNotice(charged: String): String? =
    if (positive(charged) == true) {
        "Esta reserva já tem ${money(charged)} de multa de cancelamento na conta."
    } else {
        null
    }

/** O decimal do servidor é maior que zero; `null` fora do formato. */
private fun positive(decimal: String): Boolean? = runCatching { Money.parse(decimal).amount.signum() > 0 }.getOrNull()

/** O total [charged] passa da multa [amount] deste cancelamento: há multa anterior na conta. */
private fun exceeds(
    charged: String,
    amount: String,
): Boolean = Money.parse(charged).amount > Money.parse(amount).amount

private fun money(decimal: String): String = ReceptionText.money(decimal)
