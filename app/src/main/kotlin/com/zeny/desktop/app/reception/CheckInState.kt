package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.CheckInGuest
import com.zeny.desktop.core.facade.CheckInInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.core.types.parseApiInstant
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.EarlyLateQuoteResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Instant

private const val MIN_GUESTS = 1
private const val MAX_GUESTS = 40
internal const val RESTRICTION_PREFIX = "Hóspede com restrição: "
private const val EARLY_WINDOW_ENDED = "A janela do early check-in terminou. Confira e confirme de novo."
private const val FEE_CHANGED = "EARLY_LATE_FEE_CHANGED"
private const val TRANSITION_NOT_ALLOWED = "RESERVATION_TRANSITION_NOT_ALLOWED"
private const val SERVER_UNCONFIRMED_TEXT =
    "O servidor não confirmou o check-in. Feche e confira a reserva antes de refazer."
private val HOLDER_UNAVAILABLE = setOf(403, 404)

/** O check-in feito: a UH e os alertas de restrição dos hóspedes (dado sensível, fora do `toString`). */
data class CheckInDone(
    val roomNumber: String,
    val alerts: List<String>,
) {
    override fun toString(): String = "CheckInDone(roomNumber=$roomNumber)"
}

/** O que uma `Intent` cobre: mudou a UH, o hóspede principal ou a cobrança do early, é outra intenção. */
private data class IntentInputs(
    val roomId: String?,
    val guestId: String?,
    val chargeEarly: Boolean,
)

/**
 * Estado do diálogo de check-in: compõe a alocação de UH e a escolha do hóspede principal, oferece o early check-in
 * pela cotação e confirma com uma `Idempotency-Key` por intenção. A mesma `Intent` vale enquanto o resultado é
 * desconhecido (`classifyWriteFailure`; no `5xx` guardado, o texto pede fechar e conferir) ou a aprovação foi
 * cancelada; qualquer resposta definitiva, ou a troca de UH, hóspede ou cobrança do early, gera outra.
 *
 * Como o `RoomAllocationState`: as mutações correm no escopo da UI (uma thread só); [close] cancela só o que é daqui.
 */
@Suppress("LongParameterList", "TooManyFunctions") // dependências do fluxo; etapas do fluxo e da chave
class CheckInState(
    private val source: ReceptionSource,
    scope: CoroutineScope,
    private val seed: CheckInSeed,
    permissions: Set<String>,
    private val approve: suspend (ApprovableAction<CheckInResponse>) -> ApiResponse<CheckInResponse>?,
    private val now: () -> Instant,
    private val onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    val allocation =
        RoomAllocationState(source, ownScope, seed, "reservations.assignments.manage" in permissions) {
            dropIntentIfInputsChanged()
            onChanged()
        }

    val picker =
        GuestPickerState(
            source,
            ownScope,
            canSearch = "guests.profiles.read" in permissions,
            canCreate = "guests.profiles.manage" in permissions,
        ) { choose(it) }

    var primary by mutableStateOf<GuestPick?>(null)
        private set
    var quote by mutableStateOf<EarlyLateQuoteResponse?>(null)
        private set
    var earlyText by mutableStateOf<String?>(null)
        private set
    private var charge by mutableStateOf(false)

    /** Só liga com o early oferecido ([earlyText]); mudar a cobrança é outra intenção. */
    var chargeEarly: Boolean
        get() = charge
        set(value) {
            if (value && earlyText == null) return
            charge = value
            dropIntentIfInputsChanged()
        }
    var busy by mutableStateOf(false)
        private set

    /** Quantas vezes o recepcionista escolheu (ou cadastrou) um hóspede principal. */
    var pickCount by mutableStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var done by mutableStateOf<CheckInDone?>(null)
        private set

    /** Com uma alocação ou um cadastro em andamento, a UH ou o hóspede ainda vão mudar: não confirma. */
    val canConfirm: Boolean
        get() =
            allocation.currentRoomId != null &&
                primary != null &&
                !busy &&
                done == null &&
                !allocation.busy &&
                !picker.creating

    private var intent: Intent? = null
    private var intentInputs: IntentInputs? = null
    private var picked = false
    private var holderErrorShown = false
    private var closed = false

    fun start() {
        if (closed) return
        allocation.load()
        ownScope.launch { loadHolder() }
        ownScope.launch { applyQuote(fetchQuote()) }
    }

    fun confirm() {
        val guest = primary
        if (!canConfirm || closed || guest == null) return
        if (chargeEarly && quote?.let(::inEarlyWindow) != true) {
            endEarlyWindow()
            return
        }
        busy = true
        error = null
        val count = (seed.adults + seed.children).coerceIn(MIN_GUESTS, MAX_GUESTS)
        val fee = if (chargeEarly) (quote?.earlyCheckIn?.fee as? Option.Some)?.value else null
        val current = currentIntent()
        ownScope.launch {
            try {
                val input = CheckInInput(listOf(CheckInGuest(guest.id, true)), count, null, fee?.let(Money::parse))
                approve(source.checkIn(seed.reservationId, input, current))?.let { finish(it.value) }
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
        allocation.close()
        picker.close()
        jobs.cancel()
    }

    private fun choose(pick: GuestPick) {
        picked = true
        pickCount++
        primary = pick
        if (holderErrorShown) {
            holderErrorShown = false
            error = null
        }
        dropIntentIfInputsChanged()
    }

    /** O titular 403/404 fica sem hóspede principal, sem erro: o recepcionista escolhe outro. */
    private suspend fun loadHolder() {
        try {
            val summary = source.holder(seed.holderGuestId)
            if (!picked) primary = GuestPick(seed.holderGuestId, summary)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            val failure = apiErrorOf(e)
            val unavailable = failure is ApiError.Problem && failure.status in HOLDER_UNAVAILABLE
            if (!picked && !unavailable) {
                error = ActionText.error(failure)
                holderErrorShown = true
            }
        }
    }

    /** Sem cotação, sem early e sem erro. */
    private suspend fun fetchQuote(): EarlyLateQuoteResponse? =
        try {
            source.earlyLateQuote(seed.reservationId)
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            null
        }

    private fun applyQuote(fresh: EarlyLateQuoteResponse?) {
        quote = fresh
        earlyText = fresh?.takeIf(::inEarlyWindow)?.let(ActionText::earlyOffer)
        if (earlyText == null) chargeEarly = false
    }

    private fun inEarlyWindow(quote: EarlyLateQuoteResponse): Boolean {
        val until = (quote.earlyCheckIn.until as? Option.Some)?.value?.let(::instantOrNull) ?: return false
        return now().isBefore(until)
    }

    private fun endEarlyWindow() {
        earlyText = null
        chargeEarly = false
        error = EARLY_WINDOW_ENDED
    }

    private fun finish(response: CheckInResponse) {
        dropIntent()
        done = CheckInDone(response.room.number, response.restrictionAlerts.map { RESTRICTION_PREFIX + it.reason })
        onChanged()
    }

    /** Na falha desconhecida, a intenção fica: o check-in pode ter sido feito, e a repetição leva a mesma chave. */
    private suspend fun fail(failure: ApiError) {
        val outcome = classifyWriteFailure(failure)
        if (outcome == WriteFailure.DEFINITIVE) dropIntent()
        error = if (outcome == WriteFailure.UNKNOWN_STORED) SERVER_UNCONFIRMED_TEXT else ActionText.error(failure)
        // Só a recusa definitiva mexe nas escolhas: um 5xx com esses códigos é desconhecido, e a intenção fica.
        if (outcome != WriteFailure.DEFINITIVE) return
        when ((failure as? ApiError.Problem)?.code) {
            FEE_CHANGED -> {
                chargeEarly = false
                applyQuote(fetchQuote())
            }

            TRANSITION_NOT_ALLOWED -> {
                onChanged()
            }
        }
    }

    private fun currentInputs() = IntentInputs(allocation.currentRoomId, primary?.id, chargeEarly)

    private fun currentIntent(): Intent {
        dropIntentIfInputsChanged()
        return intent ?: Intent().also {
            intent = it
            intentInputs = currentInputs()
        }
    }

    private fun dropIntentIfInputsChanged() {
        if (intentInputs != currentInputs()) dropIntent()
    }

    private fun dropIntent() {
        intent = null
        intentInputs = null
    }
}

private fun instantOrNull(text: String): Instant? =
    try {
        parseApiInstant(text)
    } catch (_: IllegalArgumentException) {
        null
    }

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
