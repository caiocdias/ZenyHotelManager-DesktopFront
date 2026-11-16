package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.ReservationChangeInput
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit

private const val IN_HOUSE = "IN_HOUSE"
private const val MAX_NIGHTS = 365L
private const val MIN_ADULTS = 1
private const val MAX_ADULTS = 40
private const val MAX_CHILDREN = 20

// Os textos da validação local são os do `QuoteState`: a mesma estadia, as mesmas regras.
private const val BAD_DATES = "Informe a chegada e a saída no formato dd/mm/aaaa."
private const val DEPARTURE_NOT_AFTER = "A saída precisa ser depois da chegada."
private const val TOO_LONG = "A estadia vai até 365 noites."
private const val BAD_ADULTS = "Informe de 1 a 40 adultos."
private const val BAD_AGES = "Informe as idades das crianças em números, separadas por vírgula."
private const val TOO_MANY_CHILDREN = "Informe até 20 crianças."

private const val PICK_SEGMENT = "Escolha o segmento."
private const val NOTHING_CHANGED = "Nada mudou."
private const val STALE_NOTICE = "Alguém alterou esta reserva. Os dados foram recarregados; confira e tente de novo."

/** O que a alteração de reserva usa de um servidor: o dia operacional, a reserva com `ETag`, a cotação e o PUT. */
interface ReservationChangeSource {
    suspend fun businessDay(): BusinessDay

    suspend fun reservationVersion(id: String): ReservationVersion

    suspend fun quote(
        arrival: BusinessDate,
        departure: BusinessDate,
        adults: Int,
        childAges: List<Int>,
    ): QuoteResponse

    fun changeReservation(
        id: String,
        input: ReservationChangeInput,
        etag: String,
    ): ApprovableAction<ReservationDetailResponse>
}

/** A estadia digitada, já lida; só existe se passou na validação local. */
private class ChangeStay(
    val arrival: BusinessDate,
    val departure: BusinessDate,
    val adults: Int,
    val childAges: List<Int>,
)

/** A estadia lida dos textos, ou o motivo em pt-BR de ela não ir à rede. */
private sealed interface ChangeParsed {
    class Ok(
        val stay: ChangeStay,
    ) : ChangeParsed

    class Refused(
        val message: String,
    ) : ChangeParsed
}

/** A reserva como o servidor a mandou: a versão, o dia operacional e a alteração "nula" que a representa. */
private class Loaded(
    val version: ReservationVersion,
    val today: BusinessDate,
    val unchanged: ReservationChangeInput,
)

/**
 * Estado do diálogo de alteração de reserva: lê a reserva com o `ETag` e o dia operacional, deixa o usuário editar a
 * estadia inteira e confirma com `If-Match`.
 *
 * - O pedido leva a estadia inteira como deve ficar; sem diferença para a reserva lida, não vai à rede ("Nada
 *   mudou."). `nightlyRates` vai sempre vazio: só o servidor sabe quais noites recalcula.
 * - Hospedada: só a saída, o segmento e os pedidos especiais mudam. Chegada, ocupação e opções ficam travadas e o
 *   pedido leva os valores lidos; uma saída alterada tem de ser depois da data operacional.
 * - Um `412` refaz a leitura inteira e mantém o diálogo aberto com [notice]. Todos os campos voltam aos do servidor
 *   e [options] e [option] se perdem, de propósito: o PUT leva a estadia inteira, e reenviar o que o usuário digitou
 *   sobre a versão velha desfaria a mudança do outro.
 * - A repetição depois de um resultado desconhecido leva o mesmo `ETag` e pode receber `412`: o diálogo recarregado
 *   mostra a reserva já alterada. É aceito.
 *
 * Como o `RoomMoveState`: as mutações correm no escopo da UI (uma thread só), [close] cancela só o que é daqui e a
 * resposta de uma carga ou cotação velha, ou que chega depois do [close], é descartada.
 */
@Suppress("TooManyFunctions") // etapas do fluxo: carga, campos, opções e confirmação
class ReservationChangeState(
    private val source: ReservationChangeSource,
    scope: CoroutineScope,
    private val reservationId: String,
    /** `reservations.availability.read`: sem ela não há "Ver opções". */
    val canQuote: Boolean,
    private val approve:
        suspend (ApprovableAction<ReservationDetailResponse>) -> ApiResponse<ReservationDetailResponse>?,
    private val onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    /** A carga ou a recarga está em voo: os campos e a confirmação viram no-op. */
    var loading by mutableStateOf(false)
        private set

    /** O código da reserva, para o título. */
    var code by mutableStateOf<String?>(null)
        private set

    /** Hospedada: chegada, ocupação e opções ficam travadas. */
    var inHouse by mutableStateOf(false)
        private set

    /** As ofertas da cotação da estadia digitada, sem a do tipo e do plano atuais ("manter" já a cobre). */
    var options by mutableStateOf<List<QuoteOfferResponse>>(emptyList())
        private set
    var loadingOptions by mutableStateOf(false)
        private set

    /** A oferta escolhida; `null` mantém o tipo e o plano atuais. */
    var option by mutableStateOf<QuoteOfferResponse?>(null)
        private set

    /** O aviso do `412`: a reserva foi recarregada. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** O motivo, em pt-BR, de a confirmação não ter ido à rede. */
    var validation by mutableStateOf<String?>(null)
        private set

    /** O texto de pessoa da última falha do servidor. */
    var error by mutableStateOf<String?>(null)
        private set

    /** A mensagem do servidor por campo do contrato. */
    var fieldErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var busy by mutableStateOf(false)
        private set

    /** O resumo da alteração feita, com o total do servidor. */
    var done by mutableStateOf<String?>(null)
        private set

    private var arrival by mutableStateOf("")
    private var departure by mutableStateOf("")
    private var adults by mutableStateOf("")
    private var childAges by mutableStateOf("")
    private var segmentChoice by mutableStateOf<String?>(null)
    private var requests by mutableStateOf("")

    var arrivalText: String
        get() = arrival
        set(value) {
            if (stayLocked || value == arrival) return
            arrival = value
            dropOptions()
        }

    var departureText: String
        get() = departure
        set(value) {
            if (locked || value == departure) return
            departure = value
            dropOptions()
        }

    var adultsText: String
        get() = adults
        set(value) {
            if (stayLocked || value == adults) return
            adults = value
            dropOptions()
        }

    /** As idades das crianças, separadas por vírgula; vazio quando não há crianças. */
    var childAgesText: String
        get() = childAges
        set(value) {
            if (stayLocked || value == childAges) return
            childAges = value
            dropOptions()
        }

    /** Um dos `BookingText.SEGMENTS`; `null` se o segmento lido não é um deles (o usuário escolhe). */
    var segment: String?
        get() = segmentChoice
        set(value) {
            if (locked || value !in BookingText.SEGMENTS) return
            segmentChoice = value
        }

    var specialRequests: String
        get() = requests
        set(value) {
            if (locked) return
            requests = value
        }

    val canConfirm: Boolean
        get() = !closed && loaded != null && !loading && !busy && done == null

    private var loaded: Loaded? = null
    private var loadJob: Job? = null
    private var optionsJob: Job? = null
    private var generation = 0
    private var optionsGeneration = 0
    private var closed = false

    private val locked: Boolean get() = closed || loading || busy || done != null
    private val stayLocked: Boolean get() = locked || inHouse

    fun start() {
        if (closed) return
        load(null)
    }

    /** Cota a estadia digitada; no-op para hospedada e sem [canQuote]. */
    fun loadOptions() {
        if (!canQuote || stayLocked || loaded == null) return
        validation = null
        error = null
        fieldErrors = emptyMap()
        val parsed = parse()
        if (parsed is ChangeParsed.Refused) {
            dropOptions()
            validation = parsed.message
            return
        }
        val stay = (parsed as ChangeParsed.Ok).stay
        dropOptions()
        val mine = optionsGeneration
        loadingOptions = true
        optionsJob =
            ownScope.launch {
                try {
                    val response = source.quote(stay.arrival, stay.departure, stay.adults, stay.childAges)
                    if (mine == optionsGeneration) options = response.offers.filterNot(::isCurrentOffer)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    if (mine == optionsGeneration) showFailure(apiErrorOf(e))
                } finally {
                    if (mine == optionsGeneration) loadingOptions = false
                }
            }
    }

    /** Uma das [options], ou `null` para manter o tipo e o plano atuais. */
    fun choose(option: QuoteOfferResponse?) {
        if (stayLocked || (option != null && option !in options)) return
        this.option = option
    }

    fun confirm() {
        val current = loaded
        if (closed || !canConfirm || current == null) return
        validation = null
        error = null
        fieldErrors = emptyMap()
        when (val prepared = prepare(current)) {
            is Prepared.Refused -> validation = prepared.message
            is Prepared.Ready -> send(current, prepared.input)
        }
    }

    private fun send(
        current: Loaded,
        input: ReservationChangeInput,
    ) {
        notice = null
        dropOptionsRequest()
        busy = true
        ownScope.launch {
            try {
                val response = approve(source.changeReservation(reservationId, input, current.version.etag))
                if (response != null) finish(response.value)
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
        optionsGeneration++
        jobs.cancel()
    }

    private sealed interface Prepared {
        class Ready(
            val input: ReservationChangeInput,
        ) : Prepared

        class Refused(
            val message: String,
        ) : Prepared
    }

    /** A alteração a enviar, ou o motivo de ela não ir à rede: estadia inválida, segmento, saída de hospedada, nada. */
    private fun prepare(current: Loaded): Prepared {
        val stay =
            when (val parsed = parse()) {
                is ChangeParsed.Refused -> return Prepared.Refused(parsed.message)
                is ChangeParsed.Ok -> parsed.stay
            }
        val chosen = segmentChoice
        val refusal = if (chosen == null) PICK_SEGMENT else departureRefusal(stay, current)
        return if (chosen == null || refusal != null) {
            Prepared.Refused(refusal ?: PICK_SEGMENT)
        } else {
            val input = inputOf(stay, chosen, current)
            if (input == current.unchanged) Prepared.Refused(NOTHING_CHANGED) else Prepared.Ready(input)
        }
    }

    /** Hospedada: uma saída alterada tem de ser depois da data operacional; a igual à lida passa sempre. */
    private fun departureRefusal(
        stay: ChangeStay,
        current: Loaded,
    ): String? =
        if (inHouse && stay.departure != current.unchanged.departure && stay.departure.value <= current.today.value) {
            "A saída precisa ser depois da data operacional (${DateInput.format(current.today)})."
        } else {
            null
        }

    private fun inputOf(
        stay: ChangeStay,
        chosen: String,
        current: Loaded,
    ) = ReservationChangeInput(
        arrival = stay.arrival,
        departure = stay.departure,
        adults = stay.adults,
        childAges = stay.childAges,
        roomTypeId = option?.roomTypeId?.toString() ?: current.unchanged.roomTypeId,
        ratePlanId = option?.ratePlanId?.toString() ?: current.unchanged.ratePlanId,
        segment = chosen,
        specialRequests = requests.takeIf { it.isNotBlank() },
        nightlyRates = emptyList(),
    )

    private fun finish(response: ReservationDetailResponse) {
        error = null
        fieldErrors = emptyMap()
        val reservation = response.reservation
        done = "Reserva ${reservation.code} alterada. Novo total: ${ReceptionText.money(reservation.total)}."
        onChanged()
    }

    private fun fail(failure: ApiError) {
        if (failure is ApiError.PreconditionFailed) load(STALE_NOTICE) else showFailure(failure)
    }

    private fun showFailure(failure: ApiError) {
        error = failure.userMessage
        fieldErrors = (failure as? ApiError.Problem)?.fieldErrors.orEmpty().associate { it.field to it.message }
    }

    /** A carga inteira; a mais nova vence. Com [notice], ele aparece depois da carga, salvo se ela falhar. */
    private fun load(notice: String?) {
        val mine = ++generation
        dropOptionsRequest()
        loading = true
        loadJob?.cancel()
        loadJob =
            ownScope.launch {
                try {
                    val outcome = attempt { read() }
                    if (mine == generation) outcome.fold({ apply(it, notice) }, { applyFailure(it) })
                } finally {
                    if (mine == generation) loading = false
                }
            }
    }

    private suspend fun read(): Loaded {
        val version = source.reservationVersion(reservationId)
        val today = source.businessDay().date
        return Loaded(version, today, unchangedOf(version.reservation.reservation))
    }

    /** Todos os campos voltam aos do servidor; `options` e `option` se perdem. */
    private fun apply(
        read: Loaded,
        notice: String?,
    ) {
        val reservation = read.version.reservation.reservation
        loaded = read
        code = reservation.code
        inHouse = reservation.status == IN_HOUSE
        arrival = DateInput.format(read.unchanged.arrival)
        departure = DateInput.format(read.unchanged.departure)
        adults = read.unchanged.adults.toString()
        childAges = read.unchanged.childAges.joinToString(", ")
        segmentChoice = read.unchanged.segment.takeIf { it in BookingText.SEGMENTS }
        requests = read.unchanged.specialRequests.orEmpty()
        options = emptyList()
        option = null
        this.notice = notice
        validation = null
        error = null
        fieldErrors = emptyMap()
    }

    private fun applyFailure(cause: Throwable) {
        loaded = null
        notice = null
        showFailure(apiErrorOf(cause))
    }

    /** A estadia inteira da reserva lida, que um pedido sem mudança repetiria. */
    private fun unchangedOf(reservation: ReservationResponse) =
        ReservationChangeInput(
            arrival = BusinessDate.parse(reservation.arrival),
            departure = BusinessDate.parse(reservation.departure),
            adults = reservation.adults,
            childAges = reservation.childAges,
            roomTypeId = reservation.roomTypeId.toString(),
            ratePlanId = reservation.ratePlanId.toString(),
            segment = reservation.segment,
            specialRequests = ((reservation.specialRequests as? Option.Some)?.value)?.takeIf { it.isNotBlank() },
            nightlyRates = emptyList(),
        )

    private fun isCurrentOffer(offer: QuoteOfferResponse): Boolean {
        val current = loaded?.unchanged ?: return false
        return offer.roomTypeId.toString() == current.roomTypeId && offer.ratePlanId.toString() == current.ratePlanId
    }

    /** Descarta a cotação em andamento e devolve o estado sem opções nem escolha. */
    private fun dropOptions() {
        dropOptionsRequest()
        options = emptyList()
        option = null
    }

    private fun dropOptionsRequest() {
        optionsGeneration++
        optionsJob?.cancel()
        loadingOptions = false
    }

    private fun parse(): ChangeParsed {
        val from = (DateInput.parse(arrival) as? InputResult.Valid)?.value
        val to = (DateInput.parse(departure) as? InputResult.Valid)?.value
        val nights = if (from != null && to != null) ChronoUnit.DAYS.between(from.value, to.value) else 0L
        val count = adults.trim().toIntOrNull()
        val ages = parseAges()
        val problem =
            when {
                from == null || to == null -> BAD_DATES
                nights <= 0 -> DEPARTURE_NOT_AFTER
                nights > MAX_NIGHTS -> TOO_LONG
                count == null || count !in MIN_ADULTS..MAX_ADULTS -> BAD_ADULTS
                ages == null -> BAD_AGES
                ages.size > MAX_CHILDREN -> TOO_MANY_CHILDREN
                else -> null
            }
        return if (problem == null) {
            ChangeParsed.Ok(ChangeStay(checkNotNull(from), checkNotNull(to), checkNotNull(count), checkNotNull(ages)))
        } else {
            ChangeParsed.Refused(problem)
        }
    }

    /** As idades separadas por vírgula; `null` se alguma não é um inteiro não negativo. */
    private fun parseAges(): List<Int>? {
        if (childAges.isBlank()) return emptyList()
        val ages = childAges.split(',').map { it.trim().toIntOrNull() }
        return if (ages.any { it == null || it < 0 }) null else ages.filterNotNull()
    }
}

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
