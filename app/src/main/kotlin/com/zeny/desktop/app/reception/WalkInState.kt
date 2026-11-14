package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
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

private const val AVAILABILITY_READ = "reservations.availability.read"
private const val MAX_NIGHTS = 365
private const val MIN_ADULTS = 1
private const val MAX_ADULTS = 40
private const val MAX_CHILD_AGE = 17
private const val FORBIDDEN = 403

private const val OVERNIGHT_TEXT = "O walk-in é de pernoite: a saída precisa ser depois de hoje."
private const val NIGHTS_TEXT = "A estadia vai de 1 a 365 noites."
private const val DEPARTURE_TEXT = "Informe a saída (dd/mm/aaaa)."
private const val ADULTS_TEXT = "Informe de 1 a 40 adultos."
private const val CHILD_AGE_TEXT = "A idade de cada criança vai de 0 a 17 anos."
private const val NO_STATUS_TEXT = "Sem permissão para ver o status das UHs."

/**
 * Estado do walk-in: a estadia, a cotação, a escolha da oferta e da UH. O hóspede e a confirmação vêm depois.
 *
 * Como o `RoomAllocationState`: as mutações correm no escopo da UI (uma thread só) e [close] cancela só o que é
 * daqui. Cotação e escolha de oferta têm cada uma a sua geração: a mais nova vence. Mudar a estadia descarta tudo
 * o que dela depende; o segmento não entra na cotação e não descarta nada.
 * `approve` e `onChanged` são guardados na parte da confirmação do walk-in.
 */
@Suppress("UNUSED_PARAMETER", "UnusedPrivateProperty", "TooManyFunctions")
class WalkInState(
    private val source: ReceptionSource,
    scope: CoroutineScope,
    private val permissions: Set<String>,
    approve: suspend (ApprovableAction<WalkInResponse>) -> ApiResponse<WalkInResponse>?,
    onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    var arrival by mutableStateOf<BusinessDate?>(null)
        private set

    private var departure by mutableStateOf("")
    private var adultCount by mutableStateOf(MIN_ADULTS)
    private var ages by mutableStateOf<List<Int>>(emptyList())

    var departureText: String
        get() = departure
        set(value) {
            if (closed || value == departure) return
            departure = value
            dropStay()
        }

    var adults: Int
        get() = adultCount
        set(value) {
            if (closed || value == adultCount) return
            adultCount = value
            dropStay()
        }

    var childAges: List<Int>
        get() = ages
        set(value) {
            if (closed || value == ages) return
            ages = value
            dropStay()
        }

    /** `LEISURE`, `BUSINESS`, `EVENT` ou `OTHER`; o segmento não entra na cotação. */
    var segment by mutableStateOf("LEISURE")

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

    private var quotedDeparture: BusinessDate? = null
    private var quoteJob: Job? = null
    private var offerJob: Job? = null
    private var quoteGeneration = 0
    private var offerGeneration = 0
    private var closed = false

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

    fun chooseOffer(offer: QuoteOfferResponse) {
        val from = arrival
        val until = quotedDeparture
        if (closed || from == null || until == null) return
        val mine = ++offerGeneration
        this.offer = offer
        room = null
        rooms = emptyList()
        noRooms = false
        error = null
        loadingRooms = true
        offerJob?.cancel()
        offerJob =
            ownScope.launch {
                try {
                    val outcome = readRooms(offer, from, BusinessDate(until.value.minusDays(1)))
                    if (mine == offerGeneration) applyRooms(outcome)
                } finally {
                    if (mine == offerGeneration) loadingRooms = false
                }
            }
    }

    fun chooseRoom(room: WalkInRoom) {
        if (closed || offer == null || room !in rooms) return
        this.room = room
    }

    fun close() {
        closed = true
        jobs.cancel()
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
                        .sortedBy { it.number },
                )
            }
        }

    private fun applyRooms(outcome: RoomsOutcome) {
        when (outcome) {
            is RoomsOutcome.Listed -> {
                rooms = outcome.rooms
                noRooms = outcome.rooms.isEmpty()
            }

            is RoomsOutcome.Failed -> {
                val problem = outcome.cause as? ApiError.Problem
                error =
                    if (outcome.statuses && problem?.status == FORBIDDEN) {
                        NO_STATUS_TEXT
                    } else {
                        ActionText.error(apiErrorOf(outcome.cause))
                    }
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
