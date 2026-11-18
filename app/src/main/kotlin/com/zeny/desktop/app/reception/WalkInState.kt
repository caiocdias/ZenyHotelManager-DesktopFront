package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.rooms.NaturalOrder
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.CheckInGuest
import com.zeny.desktop.core.facade.WalkInInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.WalkInResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit

/** Uma UH que o walk-in pode receber: pronta, vaga e livre no período. */
data class WalkInRoom(
    val roomId: String,
    val number: String,
)

/** O walk-in feito: o código da reserva, a UH, o período, o total e os alertas de restrição (fora do `toString`). */
data class WalkInDone(
    val reservationCode: String,
    val roomNumber: String,
    val period: String,
    val total: String,
    val alerts: List<String>,
) {
    override fun toString(): String =
        "WalkInDone(reservationCode=$reservationCode, roomNumber=$roomNumber, period=$period, total=$total)"
}

/** O que uma `Intent` do walk-in cobre: mudou qualquer um, é outra intenção. */
private data class WalkInIntentInputs(
    val departure: BusinessDate?,
    val adults: Int,
    val childAges: List<Int>,
    val segment: String,
    val ratePlanId: String?,
    val roomTypeId: String?,
    val roomId: String?,
    val guestId: String?,
)

private const val AVAILABILITY_READ = "reservations.availability.read"
private const val MAX_NIGHTS = 365
private const val MIN_ADULTS = 1
private const val MAX_ADULTS = 40
private const val MAX_CHILD_AGE = 17
private const val MIN_GUESTS = 1
private const val MAX_GUESTS = 40
private const val FORBIDDEN = 403
private val SEGMENTS = setOf("LEISURE", "BUSINESS", "EVENT", "OTHER")

// A UH escolhida não serve mais: a lista é relida. A oferta não existe mais: a cotação é refeita.
private val ROOM_REFUSALS =
    setOf("ROOM_ALREADY_ASSIGNED", "ROOM_NOT_READY", "ROOM_OUT_OF_ORDER", "ROOM_TYPE_MISMATCH", "ROOM_INACTIVE")
private val OFFER_REFUSALS = setOf("ROOM_TYPE_UNAVAILABLE", "UNKNOWN_RATE_PLAN", "UNKNOWN_ROOM_TYPE")

private const val OVERNIGHT_TEXT = "O walk-in é de pernoite: a saída precisa ser depois de hoje."
private const val NIGHTS_TEXT = "A estadia vai de 1 a 365 noites."
private const val DEPARTURE_TEXT = "Informe a saída (dd/mm/aaaa)."
private const val ADULTS_TEXT = "Informe de 1 a 40 adultos."
private const val CHILD_AGE_TEXT = "A idade de cada criança vai de 0 a 17 anos."
private const val NO_STATUS_TEXT = "Sem permissão para ver o status das UHs."
private const val UNCONFIRMED_TEXT =
    "Não foi possível confirmar o walk-in. Conclua de novo sem mudar nada; " +
        "se fechar, confira em Hospedados antes de refazer."

private const val SERVER_UNCONFIRMED_TEXT =
    "O servidor não confirmou o walk-in. Feche e confira em Hospedados antes de refazer."
private const val APPROVAL_CANCELLED_TEXT = "Aprovação cancelada. O walk-in não foi concluído."

/**
 * Estado do walk-in: a estadia, a cotação, a escolha da oferta, da UH e do hóspede principal, e a conclusão.
 *
 * Como o `RoomAllocationState`: as mutações correm no escopo da UI (uma thread só) e [close] cancela só o que é
 * daqui. Cotação e escolha de oferta têm cada uma a sua geração: a mais nova vence. Mudar a estadia descarta tudo
 * o que dela depende; o segmento não entra na cotação e não descarta nada.
 *
 * A conclusão leva uma `Idempotency-Key` por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido
 * (`classifyWriteFailure`) ou a aprovação foi cancelada; qualquer resposta definitiva, ou a troca de estadia, segmento,
 * oferta, UH ou hóspede, gera outra. O walk-in cria a reserva: depois de um resultado desconhecido o estado fica
 * [unconfirmed] e, como com [busy] ou depois de [done], as entradas viram no-op. No `5xx` guardado sob a chave, o
 * estado fica [closeOnly]: a conclusão também vira no-op, e só resta fechar e conferir.
 */
@Suppress("TooManyFunctions") // etapas do fluxo: estadia, oferta, UH, hóspede, conclusão e a chave
class WalkInState(
    private val source: ReceptionSource,
    scope: CoroutineScope,
    private val permissions: Set<String>,
    private val approve: suspend (ApprovableAction<WalkInResponse>) -> ApiResponse<WalkInResponse>?,
    private val onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    val picker =
        GuestPickerState(
            source,
            ownScope,
            canSearch = "guests.profiles.read" in permissions,
            canCreate = "guests.profiles.manage" in permissions,
            isLocked = { locked },
        ) { choose(it) }

    var primary by mutableStateOf<GuestPick?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** O último resultado é desconhecido: as entradas ficam travadas até uma resposta definitiva. */
    var unconfirmed by mutableStateOf(false)
        private set

    /** A falha ficou guardada no servidor sob a chave: repetir não adianta, só fechar e conferir em Hospedados. */
    var closeOnly by mutableStateOf(false)
        private set
    var done by mutableStateOf<WalkInDone?>(null)
        private set

    var arrival by mutableStateOf<BusinessDate?>(null)
        private set

    private var departure by mutableStateOf("")
    private var adultCount by mutableStateOf(MIN_ADULTS)
    private var ages by mutableStateOf<List<Int>>(emptyList())

    var departureText: String
        get() = departure
        set(value) {
            if (locked || value == departure) return
            departure = value
            dropStay()
        }

    var adults: Int
        get() = adultCount
        set(value) {
            if (locked || value == adultCount) return
            adultCount = value
            dropStay()
        }

    var childAges: List<Int>
        get() = ages
        set(value) {
            if (locked || value == ages) return
            ages = value
            dropStay()
        }

    /** `LEISURE`, `BUSINESS`, `EVENT` ou `OTHER`; o segmento não entra na cotação. */
    var segment: String
        get() = segmentValue
        set(value) {
            if (locked || value !in SEGMENTS) return
            segmentValue = value
            dropIntentIfInputsChanged()
        }
    private var segmentValue by mutableStateOf("LEISURE")

    var offers by mutableStateOf<List<QuoteOfferResponse>>(emptyList())
        private set
    var noOffers by mutableStateOf(false)
        private set
    var quoting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var offer by mutableStateOf<QuoteOfferResponse?>(null)
        private set
    var rooms by mutableStateOf<List<WalkInRoom>>(emptyList())
        private set
    var loadingRooms by mutableStateOf(false)
        private set

    /** A oferta tem tipo, mas nenhuma UH pronta e livre. */
    var noRooms by mutableStateOf(false)
        private set
    var room by mutableStateOf<WalkInRoom?>(null)
        private set

    val canQuote: Boolean get() = arrival != null && AVAILABILITY_READ in permissions
    val stepGuestEnabled: Boolean get() = offer != null && room != null

    /** Com cotação, UHs ou cadastro em andamento, a escolha ainda vai mudar: não conclui. */
    val canConfirm: Boolean
        get() =
            offer != null &&
                room != null &&
                primary != null &&
                !busy &&
                !quoting &&
                !loadingRooms &&
                !picker.creating &&
                !closeOnly &&
                done == null

    private var quotedDeparture: BusinessDate? = null
    private var quoteJob: Job? = null
    private var offerJob: Job? = null
    private var quoteGeneration = 0
    private var offerGeneration = 0
    private var closed = false
    private var intent: Intent? = null
    private var intentInputs: WalkInIntentInputs? = null

    /** Com o estado ocupado, não confirmado, só para fechar ou concluído, as entradas viram no-op. */
    private val locked: Boolean get() = closed || busy || unconfirmed || closeOnly || done != null

    fun start() {
        if (closed) return
        ownScope.launch {
            try {
                arrival = source.businessDay().date
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                error = ActionText.error(apiErrorOf(e))
            }
        }
    }

    fun quote() {
        if (locked) return
        requote()
    }

    fun chooseOffer(offer: QuoteOfferResponse) {
        val from = arrival
        val until = quotedDeparture.takeIf { offer in offers }
        if (locked || from == null || until == null) return
        this.offer = offer
        error = null
        loadRooms(offer, from, until)
    }

    fun chooseRoom(room: WalkInRoom) {
        if (locked || offer == null || room !in rooms) return
        this.room = room
        dropIntentIfInputsChanged()
    }

    fun confirm() {
        if (closed || !canConfirm) return
        val input = walkInInput() ?: return
        busy = true
        val current = currentIntent()
        ownScope.launch {
            try {
                val approved = approve(source.walkIn(input, current))
                if (approved == null) approvalCancelled() else finish(approved.value)
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
        picker.close()
        jobs.cancel()
    }

    private fun choose(pick: GuestPick) {
        if (locked) return
        primary = pick
        dropIntentIfInputsChanged()
    }

    /** A conclusão com as escolhas atuais; `null` sem oferta, UH ou hóspede (o [canConfirm] já barra). */
    private fun walkInInput(): WalkInInput? {
        val chosen = offer
        val target = room
        val guest = primary
        if (chosen == null || target == null || guest == null) return null
        return quotedDeparture?.let { until ->
            WalkInInput(
                departure = until,
                adults = adultCount,
                childAges = ages,
                ratePlanId = chosen.ratePlanId.toString(),
                roomTypeId = chosen.roomTypeId.toString(),
                roomId = target.roomId,
                segment = segmentValue,
                guests = listOf(CheckInGuest(guest.id, true)),
                guestCount = (adultCount + ages.size).coerceIn(MIN_GUESTS, MAX_GUESTS),
            )
        }
    }

    private fun finish(response: WalkInResponse) {
        dropIntent()
        unconfirmed = false
        error = null
        val reservation = response.reservation
        done =
            WalkInDone(
                reservationCode = reservation.code,
                roomNumber = response.room.number,
                period = ReceptionText.period(reservation.arrival, reservation.departure, reservation.dayUse),
                total = ReceptionText.money(response.total),
                alerts = response.restrictionAlerts.map { RESTRICTION_PREFIX + it.reason },
            )
        onChanged()
    }

    /**
     * Aprovação cancelada: o servidor respondeu `APPROVAL_REQUIRED` na mesma chave, então nada foi feito. Depois de um
     * resultado desconhecido, destrava as entradas e troca o texto de "não confirmado", que ficaria falso. A intenção
     * fica (a chave não muda).
     */
    private fun approvalCancelled() {
        if (!unconfirmed) return
        unconfirmed = false
        error = APPROVAL_CANCELLED_TEXT
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
        val text = ActionText.walkInError(failure)
        val code = (failure as? ApiError.Problem)?.code
        when (code) {
            in ROOM_REFUSALS -> {
                rereadRooms(text)
            }

            in OFFER_REFUSALS -> {
                clearChoices()
                requote()
            }
        }
        // Por último: a nova cotação limpa o erro.
        error = text
    }

    /** A UH escolhida não serve mais: ela sai e a lista é relida; a falha da releitura não apaga [refusal]. */
    private fun rereadRooms(refusal: String) {
        val chosen = offer
        val from = arrival
        val until = quotedDeparture
        if (chosen != null && from != null && until != null) loadRooms(chosen, from, until, refusal)
    }

    private fun currentInputs() =
        WalkInIntentInputs(
            departure = quotedDeparture,
            adults = adultCount,
            childAges = ages,
            segment = segmentValue,
            ratePlanId = offer?.ratePlanId?.toString(),
            roomTypeId = offer?.roomTypeId?.toString(),
            roomId = room?.roomId,
            guestId = primary?.id,
        )

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

    /** A cotação sem as travas de entrada: também a refaz depois de uma oferta recusada na conclusão. */
    private fun requote() {
        val day = arrival
        if (closed || !canQuote || day == null) return
        val until = validStay(day) ?: return
        clearChoices()
        val mine = ++quoteGeneration
        quoting = true
        quotedDeparture = until
        val adultsNow = adultCount
        val agesNow = ages
        quoteJob?.cancel()
        quoteJob =
            ownScope.launch {
                try {
                    val response = source.quoteStay(day, until, adultsNow, agesNow)
                    if (mine == quoteGeneration) {
                        offers = response.offers
                        noOffers = response.offers.isEmpty()
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    if (mine == quoteGeneration) error = ActionText.error(apiErrorOf(e))
                } finally {
                    if (mine == quoteGeneration) quoting = false
                }
            }
    }

    /** Lê as UHs da oferta; a UH escolhida sai. Não mexe em [error]. */
    private fun loadRooms(
        offer: QuoteOfferResponse,
        from: BusinessDate,
        until: BusinessDate,
        refusal: String? = null,
    ) {
        val mine = ++offerGeneration
        room = null
        rooms = emptyList()
        noRooms = false
        dropIntentIfInputsChanged()
        loadingRooms = true
        offerJob?.cancel()
        offerJob =
            ownScope.launch {
                try {
                    val outcome = readRooms(offer, from, BusinessDate(until.value.minusDays(1)))
                    if (mine == offerGeneration) applyRooms(outcome, refusal)
                } finally {
                    if (mine == offerGeneration) loadingRooms = false
                }
            }
    }

    /** A saída da estadia, ou `null` com [error] posto; sem erro, [error] sai limpo. */
    private fun validStay(day: BusinessDate): BusinessDate? {
        val parsed = DateInput.parse(departure)
        val text =
            when (parsed) {
                is InputResult.Valid -> nightsError(ChronoUnit.DAYS.between(day.value, parsed.value.value))
                is InputResult.Invalid -> parsed.message
                InputResult.Empty -> DEPARTURE_TEXT
            } ?: invalidGuests()
        error = text
        return (parsed as? InputResult.Valid)?.value?.takeIf { text == null }
    }

    private fun nightsError(nights: Long): String? =
        when {
            nights < 1 -> OVERNIGHT_TEXT
            nights > MAX_NIGHTS -> NIGHTS_TEXT
            else -> null
        }

    private fun invalidGuests(): String? =
        when {
            adultCount !in MIN_ADULTS..MAX_ADULTS -> ADULTS_TEXT
            ages.any { it !in 0..MAX_CHILD_AGE } -> CHILD_AGE_TEXT
            else -> null
        }

    private fun dropStay() {
        quoteJob?.cancel()
        quoteGeneration++
        quoting = false
        quotedDeparture = null
        clearChoices()
    }

    private fun clearChoices() {
        offerJob?.cancel()
        offerGeneration++
        loadingRooms = false
        offers = emptyList()
        noOffers = false
        offer = null
        rooms = emptyList()
        noRooms = false
        room = null
        dropIntentIfInputsChanged()
    }

    private suspend fun readRooms(
        offer: QuoteOfferResponse,
        from: BusinessDate,
        to: BusinessDate,
    ): RoomsOutcome =
        coroutineScope {
            val statuses = async { attempt { source.roomStatuses() } }
            val assigned = async { attempt { source.assignedRoomIds(from, to) } }
            val statusResult = statuses.await()
            val assignedResult = assigned.await()
            val statusFailure = statusResult.exceptionOrNull()
            val assignedFailure = assignedResult.exceptionOrNull()
            if (statusFailure != null) {
                RoomsOutcome.Failed(statusFailure, statuses = true)
            } else if (assignedFailure != null) {
                RoomsOutcome.Failed(assignedFailure)
            } else {
                val taken = assignedResult.getOrThrow()
                RoomsOutcome.Listed(
                    statusResult
                        .getOrThrow()
                        .filter { it.isWalkInCandidate(offer) && it.roomId.toString() !in taken }
                        .map { WalkInRoom(it.roomId.toString(), it.number) }
                        .sortedWith(compareBy(NaturalOrder) { it.number }),
                )
            }
        }

    /** Uma falha da leitura vem depois de [refusal], o texto da recusa que mandou reler, quando houver. */
    private fun applyRooms(
        outcome: RoomsOutcome,
        refusal: String?,
    ) {
        when (outcome) {
            is RoomsOutcome.Listed -> {
                rooms = outcome.rooms
                noRooms = outcome.rooms.isEmpty()
            }

            is RoomsOutcome.Failed -> {
                val problem = outcome.cause as? ApiError.Problem
                val readError =
                    if (outcome.statuses && problem?.status == FORBIDDEN) {
                        NO_STATUS_TEXT
                    } else {
                        ActionText.error(apiErrorOf(outcome.cause))
                    }
                error = listOfNotNull(refusal, readError).joinToString(" ")
            }
        }
    }

    private sealed interface RoomsOutcome {
        data class Listed(
            val rooms: List<WalkInRoom>,
        ) : RoomsOutcome

        data class Failed(
            val cause: Throwable,
            val statuses: Boolean = false,
        ) : RoomsOutcome
    }
}

private fun RoomStatusResponse.isWalkInCandidate(offer: QuoteOfferResponse) =
    roomTypeId == offer.roomTypeId &&
        active &&
        occupancy == RoomStatusResponse.Occupancy.VACANT &&
        readyForCheckIn

private suspend fun <T> attempt(call: suspend () -> T): Result<T> =
    try {
        Result.success(call())
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
