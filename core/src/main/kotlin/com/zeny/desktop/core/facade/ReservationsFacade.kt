package com.zeny.desktop.core.facade

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.http.ResponseFormatException
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.client.FrontdeskApi
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.model.AvailabilityResponse
import com.zeny.desktop.sdk.model.CheckInGuestRequest
import com.zeny.desktop.sdk.model.CheckInRequest
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.DoNotMoveRequest
import com.zeny.desktop.sdk.model.EarlyLateQuoteResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.QuoteStayParamChannel
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationDocumentSearchRequest
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomAssignRequest
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomMoveRequest
import com.zeny.desktop.sdk.model.RoomMoveResponse
import com.zeny.desktop.sdk.model.RoomSuggestionResponse
import com.zeny.desktop.sdk.model.UndoCheckInResponse
import com.zeny.desktop.sdk.model.WalkInRequest
import com.zeny.desktop.sdk.model.WalkInResponse
import de.quati.kotlin.util.Option
import java.time.temporal.ChronoUnit
import kotlin.uuid.Uuid

/** Maior página que o contrato aceita: o histórico vem inteiro, com o mínimo de idas ao servidor. */
private const val HISTORY_PAGE_SIZE = 200
private const val MAX_AVAILABILITY_DAYS = 366L

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

/** A reserva com o `ETag` da leitura, que a troca de UH devolve no `If-Match`. */
data class ReservationVersion(
    val reservation: ReservationDetailResponse,
    val etag: String,
)

/**
 * O corpo da troca de UH. `reason` e `tariff` são os textos dos enums do contrato; um valor desconhecido (ou o
 * motivo `INITIAL`, que é só do servidor) lança `IllegalArgumentException` antes da rede.
 */
data class RoomMoveInput(
    val roomId: String,
    val reason: String,
    val tariff: String,
)

private val MOVE_REASONS =
    setOf(
        RoomMoveRequest.Reason.GUEST_REQUEST,
        RoomMoveRequest.Reason.OUT_OF_ORDER,
        RoomMoveRequest.Reason.ROOM_ISSUE,
        RoomMoveRequest.Reason.OPERATIONAL,
    )

/** As situações da reserva no contrato, na ordem em que a busca as envia. */
val RESERVATION_STATUSES: List<String> =
    listOf("PROVISIONAL", "CONFIRMED", "IN_HOUSE", "CHECKED_OUT", "CANCELLED", "NO_SHOW")

/** Menor termo de nome que a busca aceita, já aparado. */
private const val MIN_NAME_LENGTH = 2

/**
 * Os filtros da busca de reservas; `null` ou em branco não vão. O `toString` não mostra nada: o nome é dado pessoal.
 */
data class ReservationFilter(
    val code: String? = null,
    val bookingCode: String? = null,
    val externalId: String? = null,
    val name: String? = null,
    val from: BusinessDate? = null,
    val to: BusinessDate? = null,
    val statuses: Set<String> = emptySet(),
) {
    override fun toString(): String = "ReservationFilter(***)"
}

/** O documento da busca por documento; o `toString` não mostra nada: o número é dado pessoal. */
data class DocumentQuery(
    val type: String,
    val number: String,
    val issuingCountry: String?,
) {
    override fun toString(): String = "DocumentQuery(***)"
}

/** Causa que guarda só o nome da classe da falha original: a mensagem dela pode trazer a URL com o termo buscado. */
private class SearchFailure(
    className: String?,
) : Exception(className)

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

    /** A reserva com o `ETag` da resposta; sem `ETag`, o servidor quebrou o contrato: `IncompatibleResponse`. */
    suspend fun reservationVersion(id: String): ReservationVersion {
        val reservationId = uuidOf(id)
        val response = executor.execute { reservations.getReservation(reservationId, it.headers) }
        val etag =
            response.etag?.takeIf { it.isNotBlank() }
                ?: throw ApiError.IncompatibleResponse(ResponseFormatException(IllegalStateException("sem ETag")))
        return ReservationVersion(response.value, etag)
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
     * O check-in da reserva, pelo ajudante comum `approvable`: a `intent` da tela vale na tentativa e na repetição
     * aprovada (decisão D2), e a repetição aprovada renova a chave.
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
        return executor.approvable(intent) {
            frontdesk.checkInReservation(reservationId, it.requireIdempotencyKey(), request, it.headers)
        }
    }

    /**
     * A troca de UH, com o `ETag` lido em `reservationVersion` no `If-Match`. Mesma regra do `checkIn` para `intent` e
     * token (D2); outro `etag`, UH, motivo ou tarifa pede outra `intent` (o servidor inclui o `If-Match` no hash).
     */
    fun moveRoom(
        id: String,
        input: RoomMoveInput,
        etag: String,
        intent: Intent,
    ): ApprovableAction<RoomMoveResponse> {
        val reservationId = uuidOf(id)
        val request = roomMoveRequestOf(input)
        require(etag.isNotBlank()) { "etag inválido" }
        return executor.approvable(intent) {
            frontdesk.moveReservationRoom(reservationId, etag, it.requireIdempotencyKey(), request, it.headers)
        }
    }

    /**
     * Desfaz o check-in (ação sensível). Nenhuma execução envia `Idempotency-Key`; o token de aprovação vai só na
     * repetição aprovada, pelo parâmetro do `sdk`.
     */
    fun undoCheckIn(id: String): ApprovableAction<UndoCheckInResponse> {
        val reservationId = uuidOf(id)
        return ApprovableAction { token ->
            executor.execute { frontdesk.undoCheckInReservation(reservationId, token, it.headers) }
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
        return executor.approvable(intent) {
            frontdesk.createWalkIn(it.requireIdempotencyKey(), request, it.headers)
        }
    }

    /**
     * A cotação da estadia no canal `DIRECT`, a resposta como veio (ofertas na ordem do servidor, vazia não é erro).
     * Sem crianças, `childAges` não vai; sem filtro de tipo nem de tarifa. Com `includeUnavailable`, as combinações
     * recusadas vêm com os motivos; sem ele, o parâmetro não vai.
     */
    suspend fun quoteStay(
        arrival: BusinessDate,
        departure: BusinessDate,
        adults: Int,
        childAges: List<Int>,
        includeUnavailable: Boolean = false,
    ): QuoteResponse =
        executor
            .execute { call ->
                reservations.quoteStay(
                    arrival = arrival.toApi(),
                    departure = departure.toApi(),
                    adults = adults,
                    childAges = childAges.takeIf { it.isNotEmpty() }?.joinToString(","),
                    channel = QuoteStayParamChannel.DIRECT,
                    includeUnavailable = true.takeIf { includeUnavailable },
                    block = call.headers,
                )
            }.value

    /**
     * A disponibilidade de `from` a `to` (inclusive), sem filtro de tipo nem de tarifa. `to` antes de `from` ou mais
     * de 366 dias (contando as duas pontas) lança `IllegalArgumentException` antes da rede.
     */
    suspend fun availability(
        from: BusinessDate,
        to: BusinessDate,
    ): AvailabilityResponse {
        val days = ChronoUnit.DAYS.between(from.value, to.value) + 1
        require(days in 1..MAX_AVAILABILITY_DAYS) { "período de disponibilidade inválido" }
        return executor
            .execute { call ->
                reservations.getAvailabilityCalendar(from = from.toApi(), to = to.toApi(), block = call.headers)
            }.value
    }

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

    /**
     * Uma página da busca por filtros. Entrada inválida (situação desconhecida, período invertido, nome com menos de
     * 2 caracteres) lança `IllegalArgumentException` antes da rede, sem o valor. Os textos vão aparados e as situações
     * em ordem do contrato. A falha de rede ou de formato não leva a mensagem original: a URL traz o termo buscado.
     */
    suspend fun search(
        filter: ReservationFilter,
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse> {
        val from = filter.from
        val to = filter.to
        require(from == null || to == null || !to.value.isBefore(from.value)) { "período inválido" }
        val name = filter.name.presentOrNull()
        require(name == null || name.length >= MIN_NAME_LENGTH) { "nome curto demais" }
        val status = statusesOf(filter.statuses).takeIf { it.isNotEmpty() }?.joinToString(",")
        val page =
            withoutTermInFailure {
                executor.execute {
                    reservations.searchReservations(
                        code = filter.code.presentOrNull(),
                        bookingCode = filter.bookingCode.presentOrNull(),
                        externalId = filter.externalId.presentOrNull(),
                        name = name,
                        from = from?.toApi(),
                        to = to?.toApi(),
                        status = status,
                        cursor = cursor,
                        limit = limit,
                        block = it.headers,
                    )
                }
            }.value
        return Page(page.items, page.nextCursor.valueOrNull())
    }

    /**
     * Uma página da busca por documento: o número vai só no corpo (`POST`), nunca na URL, e as mensagens de erro não o
     * trazem. Tipo ou situação desconhecidos e número em branco lançam `IllegalArgumentException` antes da rede.
     */
    suspend fun searchByDocument(
        query: DocumentQuery,
        statuses: Set<String>,
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse> {
        val type =
            ReservationDocumentSearchRequest.Type.fromSerialOrNull(query.type)
                ?: throw IllegalArgumentException("tipo de documento inválido")
        val number = query.number.presentOrNull() ?: throw IllegalArgumentException("número de documento inválido")
        val country = query.issuingCountry.presentOrNull()?.uppercase()
        val request =
            ReservationDocumentSearchRequest(
                issuingCountry = country?.let { Option.Some(it) } ?: Option.Undefined,
                number = number,
                statuses = statusesOf(statuses).map { ReservationDocumentSearchRequest.StatusesItem.fromSerial(it) },
                type = type,
            )
        val page =
            withoutTermInFailure {
                executor.execute { reservations.searchReservationsByDocument(cursor, limit, request, it.headers) }
            }.value
        return Page(page.items, page.nextCursor.valueOrNull())
    }

    /** As situações em ordem do contrato; uma fora dele lança `IllegalArgumentException` sem o valor. */
    private fun statusesOf(statuses: Set<String>): List<String> {
        require(RESERVATION_STATUSES.containsAll(statuses)) { "situação inválida" }
        return RESERVATION_STATUSES.filter { it in statuses }
    }

    private fun String?.presentOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** Troca a causa de `Network` e `IncompatibleResponse` por uma que guarda só o nome da classe original. */
    @Suppress("SwallowedException") // a troca é proposital: a mensagem e a causa originais trazem o termo buscado
    private suspend fun <T> withoutTermInFailure(block: suspend () -> T): T =
        try {
            block()
        } catch (e: ApiError.Network) {
            throw ApiError.Network(SearchFailure(e.cause?.let { it::class.qualifiedName }))
        } catch (e: ApiError.IncompatibleResponse) {
            throw ApiError.IncompatibleResponse(SearchFailure(e.cause?.message))
        }

    private fun roomMoveRequestOf(input: RoomMoveInput): RoomMoveRequest {
        val room = idOf(input.roomId, "UH")
        val reason =
            RoomMoveRequest.Reason.fromSerialOrNull(input.reason)?.takeIf { it in MOVE_REASONS }
                ?: throw IllegalArgumentException("motivo inválido")
        val tariff =
            RoomMoveRequest.Tariff.fromSerialOrNull(input.tariff) ?: throw IllegalArgumentException("tarifa inválida")
        return RoomMoveRequest(reason = reason, roomId = room, tariff = Option.Some(tariff))
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
