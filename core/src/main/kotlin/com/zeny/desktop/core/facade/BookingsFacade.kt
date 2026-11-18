package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.model.BookingRequest
import com.zeny.desktop.sdk.model.BookingResponse
import com.zeny.desktop.sdk.model.NightlyRateRequest
import com.zeny.desktop.sdk.model.ReservationCancelRequest
import com.zeny.desktop.sdk.model.ReservationChangeRequest
import com.zeny.desktop.sdk.model.ReservationConfirmRequest
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationRequest
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

/** Uma diária negociada: o valor da UH numa noite, como o usuário digitou. O servidor decide se é desconto. */
data class NightlyRateInput(
    val date: BusinessDate,
    val roomAmount: Money,
)

/**
 * Uma reserva nova, de uma UH. `origin` e `segment` são os textos dos enums do contrato; valor desconhecido lança
 * `IllegalArgumentException` antes da rede. `specialRequests` nulo ou em branco não vai. O `toString` não mostra
 * `specialRequests`: é texto livre.
 */
data class BookingInput(
    val holderGuestId: String,
    val origin: String,
    val arrival: BusinessDate,
    val departure: BusinessDate,
    val adults: Int,
    val childAges: List<Int>,
    val roomTypeId: String,
    val ratePlanId: String,
    val segment: String,
    val specialRequests: String?,
    val nightlyRates: List<NightlyRateInput>,
) {
    override fun toString(): String =
        "BookingInput(arrival=$arrival, departure=$departure, adults=$adults, childAges=$childAges, " +
            "roomTypeId=$roomTypeId, ratePlanId=$ratePlanId, origin=$origin, segment=$segment, " +
            "nightlyRates=$nightlyRates)"
}

/**
 * A estadia inteira como deve ficar; `specialRequests` nulo ou em branco apaga (vai como `null` explícito). O
 * `toString` não mostra `specialRequests`.
 */
data class ReservationChangeInput(
    val arrival: BusinessDate,
    val departure: BusinessDate,
    val adults: Int,
    val childAges: List<Int>,
    val roomTypeId: String,
    val ratePlanId: String,
    val segment: String,
    val specialRequests: String?,
    val nightlyRates: List<NightlyRateInput>,
) {
    override fun toString(): String =
        "ReservationChangeInput(arrival=$arrival, departure=$departure, adults=$adults, childAges=$childAges, " +
            "roomTypeId=$roomTypeId, ratePlanId=$ratePlanId, segment=$segment, nightlyRates=$nightlyRates)"
}

/**
 * O pedido de cancelamento. `reason` é o texto do enum do contrato; `EXPIRED` é do servidor e é recusado antes da
 * rede, como qualquer valor fora do enum. `notes` nulo ou em branco não vai. O `toString` não mostra `notes`: é texto
 * livre.
 */
data class CancelInput(
    val reason: String,
    val waivePenalty: Boolean,
    val notes: String?,
) {
    override fun toString(): String = "CancelInput(reason=$reason, waivePenalty=$waivePenalty)"
}

/**
 * Criar, ler, alterar, confirmar, cancelar e reativar reservas. Um id que não é UUID, uma origem, um segmento, um
 * motivo ou uma garantia fora do contrato lançam `IllegalArgumentException` antes da rede, sem o valor na mensagem.
 */
class BookingsFacade internal constructor(
    private val reservations: ReservationsApi,
    private val executor: ApiExecutor,
) {
    /**
     * Cria a reserva (`201` é sucesso). `run(null)` tenta sem token; `run(token)` repete com o token e chave nova
     * (decisão D2).
     */
    fun createBooking(
        input: BookingInput,
        intent: Intent,
    ): ApprovableAction<BookingResponse> {
        val request = bookingRequestOf(input)
        return executor.approvable(intent) {
            reservations.createBooking(it.requireIdempotencyKey(), bookingRequest = request, block = it.headers)
        }
    }

    suspend fun booking(id: String): BookingResponse {
        val bookingId = idOf(id, "reserva")
        return executor.execute { reservations.getBooking(bookingId, it.headers) }.value
    }

    /**
     * Altera a estadia com `If-Match = etag`, sem `Idempotency-Key`; `412` vira `ApiError.PreconditionFailed`. O token
     * de aprovação vai só na repetição aprovada. `etag` em branco lança `IllegalArgumentException`. A resposta traz o
     * `ETag` novo.
     */
    fun changeReservation(
        id: String,
        input: ReservationChangeInput,
        etag: String,
    ): ApprovableAction<ReservationDetailResponse> {
        val reservationId = idOf(id, "reserva")
        require(etag.isNotBlank()) { "etag inválido" }
        val request = changeRequestOf(input)
        return ApprovableAction { token ->
            executor.execute { reservations.changeReservation(reservationId, etag, token, request, it.headers) }
        }
    }

    /** Confirma a pré-reserva com a garantia `CARD` ou `DEPOSIT`; uma `Intent` por intenção. */
    suspend fun confirmReservation(
        id: String,
        guarantee: String,
        intent: Intent,
    ): ReservationDetailResponse {
        val reservationId = idOf(id, "reserva")
        val request =
            ReservationConfirmRequest(
                guarantee =
                    ReservationConfirmRequest.Guarantee.fromSerialOrNull(guarantee)
                        ?: throw IllegalArgumentException("garantia inválida"),
            )
        return executor
            .execute(intent) {
                reservations.confirmReservation(reservationId, it.requireIdempotencyKey(), request, it.headers)
            }.value
    }

    /**
     * Cancela a reserva, sem `If-Match`. O servidor lança a multa conforme a política copiada na reserva; com
     * `waivePenalty` pode pedir aprovação (`run(null)` tenta sem token, `run(token)` repete com chave nova).
     */
    fun cancelReservation(
        id: String,
        input: CancelInput,
        intent: Intent,
    ): ApprovableAction<ReservationDetailResponse> {
        val reservationId = idOf(id, "reserva")
        val request = cancelRequestOf(input)
        return executor.approvable(intent) {
            reservations.cancelReservation(
                reservationId,
                it.requireIdempotencyKey(),
                reservationCancelRequest = request,
                block = it.headers,
            )
        }
    }

    /** Reativa a reserva cancelada ou em no-show, sem corpo; é ação sensível e pode pedir aprovação. */
    fun reactivateReservation(
        id: String,
        intent: Intent,
    ): ApprovableAction<ReservationDetailResponse> {
        val reservationId = idOf(id, "reserva")
        return executor.approvable(intent) {
            reservations.reactivateReservation(reservationId, it.requireIdempotencyKey(), block = it.headers)
        }
    }

    private fun cancelRequestOf(input: CancelInput): ReservationCancelRequest {
        val reason =
            ReservationCancelRequest.Reason
                .fromSerialOrNull(input.reason)
                ?.takeIf { it != ReservationCancelRequest.Reason.EXPIRED }
                ?: throw IllegalArgumentException("motivo inválido")
        return ReservationCancelRequest(
            notes = input.notes?.takeIf { it.isNotBlank() }?.let { Option.Some(it) } ?: Option.Undefined,
            reason = reason,
            waivePenalty = input.waivePenalty,
        )
    }

    private fun bookingRequestOf(input: BookingInput): BookingRequest {
        val origin =
            BookingRequest.Origin.fromSerialOrNull(input.origin) ?: throw IllegalArgumentException("origem inválida")
        val segment =
            ReservationRequest.Segment.fromSerialOrNull(input.segment)
                ?: throw IllegalArgumentException("segmento inválido")
        return BookingRequest(
            holderGuestId = idOf(input.holderGuestId, "hóspede"),
            origin = origin,
            reservations =
                listOf(
                    ReservationRequest(
                        adults = input.adults,
                        arrival = input.arrival.toApi(),
                        childAges = input.childAges,
                        departure = input.departure.toApi(),
                        guests = emptyList(),
                        nightlyRates = input.nightlyRates.map(::nightlyRateOf),
                        ratePlanId = idOf(input.ratePlanId, "tarifa"),
                        roomTypeId = idOf(input.roomTypeId, "tipo"),
                        segment = segment,
                        specialRequests =
                            input.specialRequests?.takeIf { it.isNotBlank() }?.let { Option.Some(it) }
                                ?: Option.Undefined,
                    ),
                ),
        )
    }

    private fun changeRequestOf(input: ReservationChangeInput): ReservationChangeRequest {
        val segment =
            ReservationChangeRequest.Segment.fromSerialOrNull(input.segment)
                ?: throw IllegalArgumentException("segmento inválido")
        return ReservationChangeRequest(
            adults = input.adults,
            arrival = input.arrival.toApi(),
            childAges = input.childAges,
            departure = input.departure.toApi(),
            nightlyRates = input.nightlyRates.map(::nightlyRateOf),
            ratePlanId = idOf(input.ratePlanId, "tarifa"),
            roomTypeId = idOf(input.roomTypeId, "tipo"),
            segment = segment,
            specialRequests = Option.Some(input.specialRequests?.takeIf { it.isNotBlank() }),
        )
    }

    private fun nightlyRateOf(rate: NightlyRateInput) =
        NightlyRateRequest(date = rate.date.toApi(), roomAmount = rate.roomAmount.toApi())

    private fun idOf(
        id: String,
        what: String,
    ): Uuid = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de $what inválido")
}
