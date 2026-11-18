package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.reception.GuestPick
import com.zeny.desktop.app.reception.GuestPickerState
import com.zeny.desktop.app.reception.RESTRICTION_PREFIX
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BookingInput
import com.zeny.desktop.core.facade.NightlyRateInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.BookingResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.ZoneId

/** A estadia que produziu as ofertas da cotação. */
data class QuotedStay(
    val arrival: BusinessDate,
    val departure: BusinessDate,
    val adults: Int,
    val childAges: List<Int>,
)

/** O que a nova reserva usa do servidor: criar a reserva (com a aprovação de superior, se o servidor pedir). */
fun interface NewReservationSource {
    fun createBooking(
        input: BookingInput,
        intent: Intent,
    ): ApprovableAction<BookingResponse>
}

/** A reserva criada, em texto pronto; `status` é o valor cru para o `ReceptionText.status`. `toString` sem [alerts]. */
data class NewReservationDone(
    val reservationId: String,
    val code: String,
    val status: String,
    val period: String,
    val total: String,
    val provisionalUntil: String?,
    val guarantee: String?,
    val cancellation: String,
    val alerts: List<String>,
) {
    override fun toString(): String =
        "NewReservationDone(reservationId=$reservationId, code=$code, status=$status, period=$period, " +
            "total=$total, provisionalUntil=$provisionalUntil, guarantee=$guarantee, cancellation=$cancellation)"
}

/** O que uma `Intent` da nova reserva cobre: mudou qualquer um, é outra intenção. */
private data class NewReservationIntentInputs(
    val holderId: String?,
    val origin: String,
    val segment: String,
    val specialRequests: String,
    val nightlyRateText: String,
)

private const val MAX_REQUESTS_LENGTH = 1000
private const val PROVISIONAL = "PROVISIONAL"
private const val RATE_TEXT = "Informe um valor válido para a diária."
private const val UNCONFIRMED_TEXT =
    "Não foi possível confirmar a reserva. Conclua de novo sem mudar nada; " +
        "se fechar, confira na Busca antes de refazer."

private const val SERVER_UNCONFIRMED_TEXT =
    "O servidor não confirmou a reserva. Feche e confira na Busca antes de refazer."

private const val WITHOUT_RESERVATION_TEXT =
    "O servidor confirmou a criação, mas não devolveu a reserva. Feche e confira na busca de reservas."

/**
 * Estado da nova reserva a partir de uma oferta da cotação: o titular, a origem, o segmento, os pedidos especiais, a
 * diária negociada e a conclusão. A estadia, o tipo e o plano vêm da cotação e não mudam aqui.
 *
 * Como o `WalkInState`: as mutações correm no escopo da UI (uma thread só) e [close] cancela só o que é daqui. A
 * conclusão leva uma `Idempotency-Key` por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido
 * (`classifyWriteFailure`) ou a aprovação foi cancelada; resposta definitiva ou mudança de qualquer entrada gera outra.
 * Depois de um resultado desconhecido o estado fica [unconfirmed] e, como com [busy] ou depois de [done], as entradas
 * viram no-op. No `5xx` guardado sob a chave, e na criação respondida sem reserva, o estado fica [closeOnly]: a
 * conclusão também vira no-op, e só resta fechar e conferir. O front não calcula preço: a diária negociada vai como foi
 * digitada, em todas as noites, e o total mostrado é o do servidor.
 */
@Suppress("LongParameterList", "TooManyFunctions") // dependências do fluxo; entradas, conclusão e a chave
class NewReservationState(
    private val source: NewReservationSource,
    private val guests: ReceptionSource,
    scope: CoroutineScope,
    val offer: QuoteOfferResponse,
    val stay: QuotedStay,
    permissions: Set<String>,
    private val approve: suspend (ApprovableAction<BookingResponse>) -> ApiResponse<BookingResponse>?,
    private val onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    val picker =
        GuestPickerState(
            guests,
            ownScope,
            canSearch = "guests.profiles.read" in permissions,
            canCreate = "guests.profiles.manage" in permissions,
            isLocked = { locked },
        ) { choose(it) }

    var holder by mutableStateOf<GuestPick?>(null)
        private set

    /** Só valores de [BookingText.ORIGINS]. */
    var origin: String
        get() = originValue
        set(value) {
            if (locked || value !in BookingText.ORIGINS) return
            originValue = value
            dropIntentIfInputsChanged()
        }
    private var originValue by mutableStateOf(BookingText.ORIGINS.first())

    /** Só valores de [BookingText.SEGMENTS]. */
    var segment: String
        get() = segmentValue
        set(value) {
            if (locked || value !in BookingText.SEGMENTS) return
            segmentValue = value
            dropIntentIfInputsChanged()
        }
    private var segmentValue by mutableStateOf(BookingText.SEGMENTS.first())

    /** Texto livre de até 1000 caracteres; o excesso é recusado. */
    var specialRequests: String
        get() = requestsValue
        set(value) {
            if (locked || value.length > MAX_REQUESTS_LENGTH) return
            requestsValue = value
            dropIntentIfInputsChanged()
        }
    private var requestsValue by mutableStateOf("")

    /** Vazio = sem diária negociada. */
    var nightlyRateText: String
        get() = rateValue
        set(value) {
            if (locked) return
            rateValue = value
            rateError = null
            dropIntentIfInputsChanged()
        }
    private var rateValue by mutableStateOf("")

    var rateError by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** O último resultado é desconhecido: as entradas ficam travadas até uma resposta definitiva. */
    var unconfirmed by mutableStateOf(false)
        private set

    /**
     * Só resta fechar e conferir na Busca: a falha ficou guardada no servidor sob a chave, ou a criação foi respondida
     * sem a reserva. Repetir não adianta.
     */
    var closeOnly by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Só as mensagens dos `errors[]` do servidor, na ordem dele; a chave do campo nunca vai à tela. */
    var fieldErrors by mutableStateOf<List<String>>(emptyList())
        private set
    var done by mutableStateOf<NewReservationDone?>(null)
        private set

    val canConfirm: Boolean
        get() = holder != null && !busy && !picker.creating && !closeOnly && done == null && !closed

    private var closed = false
    private var intent: Intent? = null
    private var intentInputs: NewReservationIntentInputs? = null

    /** Com o estado ocupado, não confirmado, só para fechar ou concluído, as entradas viram no-op. */
    private val locked: Boolean get() = closed || busy || unconfirmed || closeOnly || done != null

    fun confirm() {
        if (!canConfirm) return
        val chosen = holder
        val rates = nightlyRates()
        if (chosen == null || rates == null) {
            if (rates == null) rateError = RATE_TEXT
            return
        }
        val input = bookingInput(chosen, rates)
        busy = true
        error = null
        fieldErrors = emptyList()
        val current = currentIntent()
        ownScope.launch {
            try {
                val approved = approve(source.createBooking(input, current))
                if (approved == null) {
                    // Aprovação cancelada: o servidor respondeu APPROVAL_REQUIRED, nada foi feito sob a chave.
                    // Destrava as entradas e mantém a intenção (a chave não muda).
                    unconfirmed = false
                } else if (approved.value.reservations.isEmpty()) {
                    createdWithoutReservation()
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
        picker.close()
        jobs.cancel()
    }

    private fun choose(pick: GuestPick) {
        if (locked) return
        holder = pick
        dropIntentIfInputsChanged()
    }

    /** Uma diária por noite, da chegada até a véspera da saída; `null` se o valor é inválido ou não positivo. */
    private fun nightlyRates(): List<NightlyRateInput>? =
        when (val parsed = MoneyInput.parse(rateValue)) {
            InputResult.Empty -> {
                emptyList()
            }

            is InputResult.Invalid -> {
                null
            }

            is InputResult.Valid -> {
                if (parsed.value.amount.signum() <= 0) null else nights().map { NightlyRateInput(it, parsed.value) }
            }
        }

    private fun nights(): List<BusinessDate> =
        generateSequence(stay.arrival) { BusinessDate(it.value.plusDays(1)) }
            .takeWhile { it.value < stay.departure.value }
            .toList()

    private fun bookingInput(
        guest: GuestPick,
        rates: List<NightlyRateInput>,
    ) = BookingInput(
        holderGuestId = guest.id,
        origin = originValue,
        arrival = stay.arrival,
        departure = stay.departure,
        adults = stay.adults,
        childAges = stay.childAges,
        roomTypeId = offer.roomTypeId.toString(),
        ratePlanId = offer.ratePlanId.toString(),
        segment = segmentValue,
        specialRequests = requestsValue.takeIf { it.isNotBlank() },
        nightlyRates = rates,
    )

    private suspend fun finish(response: BookingResponse) {
        val reservation = response.reservations.first()
        val provisional = reservation.status == PROVISIONAL
        val deadline = (reservation.provisionalUntil as? Option.Some)?.value?.takeIf { provisional }
        val zone = if (deadline != null) hotelZone() else null
        dropIntent()
        unconfirmed = false
        error = null
        fieldErrors = emptyList()
        done =
            NewReservationDone(
                reservationId = reservation.id.toString(),
                code = reservation.code,
                status = reservation.status,
                period = ReceptionText.period(reservation.arrival, reservation.departure, reservation.dayUse),
                total = ReceptionText.money(reservation.total),
                provisionalUntil = deadline?.let { ReceptionText.instant(it, zone) },
                guarantee = if (provisional) BookingText.guaranteeTerms(reservation.guaranteeTerms) else null,
                cancellation = BookingText.cancellationTerms(reservation.cancellationTerms),
                alerts = response.restrictionAlerts.map { RESTRICTION_PREFIX + it.reason },
            )
        onChanged()
    }

    /** O fuso do hotel; sem ele (leitura falhou ou o servidor não mandou) o prazo some, e a reserva criada fica. */
    private suspend fun hotelZone(): ZoneId? =
        try {
            guests.businessDay().zone
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            null
        }

    /**
     * A criação respondida com sucesso e sem reserva é uma resposta incompatível (`ApiError.IncompatibleResponse`),
     * tratada aqui como definitiva: o servidor confirmou a escrita e guardou a resposta sob a chave, então repetir só
     * devolveria a mesma resposta vazia. A intenção acaba e o estado fica [closeOnly]; a lista não é avisada, porque a
     * tela não viu reserva.
     */
    private fun createdWithoutReservation() {
        dropIntent()
        unconfirmed = false
        closeOnly = true
        fieldErrors = emptyList()
        error = WITHOUT_RESERVATION_TEXT
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
        fieldErrors = (failure as? ApiError.Problem)?.fieldErrors?.map { it.message }.orEmpty()
        error = failure.userMessage
    }

    private fun currentInputs() =
        NewReservationIntentInputs(
            holderId = holder?.id,
            origin = originValue,
            segment = segmentValue,
            specialRequests = requestsValue,
            nightlyRateText = rateValue.trim(),
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

    override fun toString(): String = "NewReservationState(busy=$busy, unconfirmed=$unconfirmed, done=$done)"
}
