package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.CallOptions
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.client.FrontdeskApi
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.model.CheckInGuestRequest
import com.zeny.desktop.sdk.model.CheckInRequest
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.DoNotMoveRequest
import com.zeny.desktop.sdk.model.EarlyLateQuoteResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.QuoteStayParamChannel
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomAssignRequest
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomSuggestionResponse
import com.zeny.desktop.sdk.model.WalkInRequest
import com.zeny.desktop.sdk.model.WalkInResponse
import de.quati.kotlin.util.Option
import de.quati.ogen.client.ktor.HttpResponseTyped
import kotlin.uuid.Uuid

/** Maior página que o contrato aceita: o histórico vem inteiro, com o mínimo de idas ao servidor. */
private const val HISTORY_PAGE_SIZE = 200

/** As listas do dia da recepção. */
enum class DayList { ARRIVALS, DEPARTURES, IN_HOUSE }

/** Um hóspede do check-in; `primary` marca o titular da estadia. */
data class CheckInGuest(
    val guestId: String,
    val primary: Boolean,
)

/** O corpo do check-in; `guestCount`, `roomId` e `earlyCheckInFee` nulos não vão. A taxa é a da cotação de early. */
data class CheckInInput(
    val guests: List<CheckInGuest>,
    val guestCount: Int?,
    val roomId: String?,
    val earlyCheckInFee: Money?,
)

/**
 * O corpo do walk-in; `roomTypeId` e `guestCount` nulos não vão. `segment` é o texto do enum do contrato; um valor
 * desconhecido lança `IllegalArgumentException` antes da rede.
 */
data class WalkInInput(
    val departure: BusinessDate,
    val adults: Int,
    val childAges: List<Int>,
    val ratePlanId: String,
    val roomTypeId: String?,
    val roomId: String,
    val segment: String,
    val guests: List<CheckInGuest>,
    val guestCount: Int?,
)

/**
 * As reservas do dia: as listas de chegadas, saídas e hospedados, o detalhe, o histórico e as alocações de UH.
 * Um `id` que não é UUID lança `IllegalArgumentException` antes de ir à rede.
 */
@Suppress("TooManyFunctions") // uma função por operação de reservas que a recepção usa
class ReservationsFacade internal constructor(
    private val reservations: ReservationsApi,
    private val frontdesk: FrontdeskApi,
    private val executor: ApiExecutor,
) {
    /** Uma página da lista do dia; a data de negócio vai sempre, sem conversão de fuso. */
    suspend fun dayPage(
        list: DayList,
        date: BusinessDate,
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse> {
        val day = date.toApi()
        val page =
            executor
                .execute {
                    when (list) {
                        DayList.ARRIVALS -> reservations.listArrivals(day, cursor, limit, it.headers)
                        DayList.DEPARTURES -> reservations.listDepartures(day, cursor, limit, it.headers)
                        DayList.IN_HOUSE -> reservations.listInHouse(day, cursor, limit, it.headers)
                    }
                }.value
        return Page(page.items, page.nextCursor.valueOrNull())
    }

    suspend fun reservation(id: String): ReservationDetailResponse {
        val reservationId = uuidOf(id)
        return executor.execute { reservations.getReservation(reservationId, it.headers) }.value
    }

    /** Todas as páginas, na ordem do servidor. */
    suspend fun history(id: String): List<ReservationHistoryResponse> {
        val reservationId = uuidOf(id)
        val pager =
            CursorPager(HISTORY_PAGE_SIZE) { cursor, limit ->
                val page =
                    executor
                        .execute {
                            reservations.listReservationHistory(reservationId, cursor, limit, it.headers)
                        }.value
                Page(page.items, page.nextCursor.valueOrNull())
            }
        val all = mutableListOf<ReservationHistoryResponse>()
        while (pager.hasMore) all += pager.next()
        return all
    }

    suspend fun roomAssignments(id: String): List<RoomAssignmentResponse> {
        val reservationId = uuidOf(id)
        return executor.execute { reservations.listReservationRoomAssignments(reservationId, it.headers) }.value.items
    }

    /** As UHs sugeridas, na ordem do servidor (as fora de serviço por último), sem filtro de características. */
    suspend fun roomSuggestions(id: String): List<RoomSuggestionResponse> {
        val reservationId = uuidOf(id)
        return executor
            .execute { reservations.listReservationRoomSuggestions(reservationId, null, it.headers) }
            .value.items
    }

    /** Aloca a UH; o motivo não vai (o servidor decide entre `INITIAL` e `OPERATIONAL`). */
    suspend fun assignRoom(
        id: String,
        roomId: String,
        doNotMove: Boolean?,
    ): ReservationDetailResponse {
        val reservationId = uuidOf(id)
        val room = Uuid.parseOrNull(roomId) ?: throw IllegalArgumentException("id de UH inválido")
        val request =
            RoomAssignRequest(
                doNotMove = doNotMove?.let { Option.Some(it) } ?: Option.Undefined,
                roomId = room,
            )
        return executor.execute { reservations.assignReservationRoom(reservationId, request, it.headers) }.value
    }

    suspend fun unassignRoom(id: String): ReservationDetailResponse {
        val reservationId = uuidOf(id)
        return executor.execute { reservations.unassignReservationRoom(reservationId, it.headers) }.value
    }

    suspend fun setDoNotMove(
        id: String,
        doNotMove: Boolean,
    ): ReservationDetailResponse {
        val reservationId = uuidOf(id)
        val request = DoNotMoveRequest(doNotMove)
        return executor.execute { reservations.setReservationRoomDoNotMove(reservationId, request, it.headers) }.value
    }

    suspend fun earlyLateQuote(id: String): EarlyLateQuoteResponse {
        val reservationId = uuidOf(id)
        return executor.execute { frontdesk.getEarlyLateQuote(reservationId, it.headers) }.value
    }

    /**
     * O check-in da reserva. A `intent` da tela vale na tentativa e na repetição aprovada (decisão D2): sem token,
     * `execute`; com token, `executeApproved`, que renova a chave.
     */
    fun checkIn(
        id: String,
        input: CheckInInput,
        intent: Intent,
    ): ApprovableAction<CheckInResponse> {
        val reservationId = uuidOf(id)
        val room = input.roomId?.let { Uuid.parseOrNull(it) ?: throw IllegalArgumentException("id de UH inválido") }
        val guests =
            input.guests.map {
                val guest = Uuid.parseOrNull(it.guestId) ?: throw IllegalArgumentException("id de hóspede inválido")
                CheckInGuestRequest(guestId = guest, primary = it.primary)
            }
        val request =
            CheckInRequest(
                earlyCheckInFee = input.earlyCheckInFee?.let { Option.Some(it.toApi()) } ?: Option.Undefined,
                guestCount = input.guestCount?.let { Option.Some(it) } ?: Option.Undefined,
                guests = guests,
                roomId = room?.let { Option.Some(it) } ?: Option.Undefined,
            )
        val call: suspend (CallOptions) -> HttpResponseTyped<CheckInResponse> = {
            frontdesk.checkInReservation(reservationId, it.requireIdempotencyKey(), request, it.headers)
        }
        return ApprovableAction { token ->
            if (token == null) executor.execute(intent, call = call) else executor.executeApproved(intent, token, call)
        }
    }

    /**
     * O walk-in: reserva, aloca a UH e faz o check-in numa transação. Mesma regra do `checkIn` para `intent` e token
     * (D2). O servidor responde `201`, que é sucesso.
     */
    fun walkIn(
        input: WalkInInput,
        intent: Intent,
    ): ApprovableAction<WalkInResponse> {
        val request = walkInRequestOf(input)
        val call: suspend (CallOptions) -> HttpResponseTyped<WalkInResponse> = {
            frontdesk.createWalkIn(it.requireIdempotencyKey(), request, it.headers)
        }
        return ApprovableAction { token ->
            if (token == null) executor.execute(intent, call = call) else executor.executeApproved(intent, token, call)
        }
    }

    /**
     * A cotação da estadia no canal `DIRECT`, a resposta como veio (ofertas na ordem do servidor, vazia não é erro).
     * Sem crianças, `childAges` não vai; sem filtro de tipo, de tarifa nem de indisponíveis.
     */
    suspend fun quoteStay(
        arrival: BusinessDate,
        departure: BusinessDate,
        adults: Int,
        childAges: List<Int>,
    ): QuoteResponse =
        executor
            .execute { call ->
                reservations.quoteStay(
                    arrival = arrival.toApi(),
                    departure = departure.toApi(),
                    adults = adults,
                    childAges = childAges.takeIf { it.isNotEmpty() }?.joinToString(","),
                    channel = QuoteStayParamChannel.DIRECT,
                    block = call.headers,
                )
            }.value

    /**
     * Os ids das UHs com alocação em reservas `PROVISIONAL` ou `CONFIRMED` que tocam de `from` a `to` (inclusive),
     * todas as páginas. Reserva sem alocação não conta. `to` antes de `from` lança `IllegalArgumentException`.
     */
    suspend fun assignedRoomIds(
        from: BusinessDate,
        to: BusinessDate,
    ): Set<String> {
        require(!to.value.isBefore(from.value)) { "período inválido" }
        val pager =
            CursorPager(HISTORY_PAGE_SIZE) { cursor, limit ->
                val page =
                    executor
                        .execute {
                            reservations.searchReservations(
                                from = from.toApi(),
                                to = to.toApi(),
                                status = "PROVISIONAL,CONFIRMED",
                                cursor = cursor,
                                limit = limit,
                                block = it.headers,
                            )
                        }.value
                Page(page.items, page.nextCursor.valueOrNull())
            }
        val rooms = linkedSetOf<String>()
        while (pager.hasMore) {
            for (item in pager.next()) {
                val assignment = (item.roomAssignment as? Option.Some)?.value ?: continue
                rooms += assignment.roomId.toString()
            }
        }
        return rooms
    }

    private fun walkInRequestOf(input: WalkInInput): WalkInRequest {
        val segment =
            WalkInRequest.Segment.fromSerialOrNull(input.segment) ?: throw IllegalArgumentException("segmento inválido")
        return WalkInRequest(
            adults = input.adults,
            childAges = input.childAges,
            departure = input.departure.toApi(),
            guestCount = input.guestCount?.let { Option.Some(it) } ?: Option.Undefined,
            guests =
                input.guests.map {
                    CheckInGuestRequest(
                        guestId = idOf(it.guestId, "hóspede"),
                        primary = it.primary,
                    )
                },
            ratePlanId = idOf(input.ratePlanId, "tarifa"),
            roomId = idOf(input.roomId, "UH"),
            roomTypeId = input.roomTypeId?.let { Option.Some(idOf(it, "tipo")) } ?: Option.Undefined,
            segment = segment,
        )
    }

    private fun idOf(
        id: String,
        what: String,
    ): Uuid = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de $what inválido")

    private fun uuidOf(id: String): Uuid =
        Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de reserva inválido")

    /** Cursor ausente, nulo ou em branco é o fim da lista. */
    private fun Option<String?>.valueOrNull(): String? = (this as? Option.Some)?.value?.takeIf { it.isNotBlank() }
}
