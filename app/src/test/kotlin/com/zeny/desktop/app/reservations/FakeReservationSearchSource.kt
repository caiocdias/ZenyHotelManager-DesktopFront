package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DocumentQuery
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlin.uuid.Uuid

/** Uma reserva fictícia da lista: só os campos que a busca do falso lê variam. */
fun searchItemOf(
    code: String = "R-1",
    status: String = "CONFIRMED",
    arrival: String = "2026-11-13",
    departure: String = "2026-11-15",
    holderGuestId: String = Uuid.random().toString(),
) = ReservationListItemResponse(
    adults = 2,
    arrival = arrival,
    arrivalOverdue = false,
    bookingCode = "B-1",
    bookingId = Uuid.random(),
    channel = "DIRECT",
    children = 0,
    code = code,
    dayUse = false,
    departure = departure,
    departureOverdue = false,
    folioId = Uuid.random(),
    holderGuestId = Uuid.parse(holderGuestId),
    id = Uuid.random(),
    origin = "FRONT_DESK",
    ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
    ratePlanId = Uuid.random(),
    roomTypeId = Uuid.random(),
    segment = "LEISURE",
    status = status,
    total = "300.00",
    version = 1,
)

/** Uma chamada de `search` registrada pelo falso. O `toString` do filtro já não mostra o nome. */
data class SearchCall(
    val filter: ReservationFilter,
    val cursor: String?,
    val limit: Int,
)

/** Uma chamada de `searchByDocument` registrada pelo falso; o `toString` não mostra a consulta (o número é pessoal). */
data class DocumentCall(
    val query: DocumentQuery,
    val statuses: Set<String>,
    val cursor: String?,
    val limit: Int,
) {
    override fun toString(): String = "DocumentCall(***)"
}

/**
 * Servidor de mentira para a busca de reservas.
 *
 * - [reservations] é a base em memória, na ordem em que a página sai. `search` filtra por código, código da reserva,
 *   id externo, nome do titular (em [holders]), período (que cruza o da reserva) e situações; `searchByDocument` acha
 *   as reservas cujo titular está em [documentHolders] sob o par (tipo, número), com o mesmo filtro de situações.
 * - A paginação é por índice: o cursor é o índice do próximo item, e o `limit` recebido vale.
 * - Cada chamada fica registrada ([searchCalls], [documentCalls], [holderCalls], [businessDayCalls], [roomTypesCalls]).
 * - [hold] prende a próxima resposta de uma operação até o teste completar o portão devolvido; cada operação tem o
 *   seu portão. [failures] e [holderFailures] programam o erro (lançado depois do portão, se houver).
 */
class FakeReservationSearchSource : ReservationSearchSource {
    /** As operações da porta que o falso segura ou falha por conta própria. */
    enum class Op { BUSINESS_DAY, SEARCH, SEARCH_BY_DOCUMENT, HOLDER, ROOM_TYPES }

    var businessDay: BusinessDay = BusinessDay(BusinessDate.parse("2026-11-13"), null)
    val reservations = mutableListOf<ReservationListItemResponse>()
    val holders = mutableMapOf<String, HolderSummary>()

    /** Os hóspedes (ids) que têm o documento (tipo, número); o país emissor não conta. */
    val documentHolders = mutableMapOf<Pair<String, String>, Set<String>>()
    val roomTypes = mutableListOf<RoomTypeResponse>()

    /** Erro programado por operação; vale para toda chamada dela até ser removido. */
    val failures = mutableMapOf<Op, Exception>()

    /** Erro programado por titular (pelo id), por exemplo o 403 ou o 404; vale além de `failures[HOLDER]`. */
    val holderFailures = mutableMapOf<String, Exception>()

    val searchCalls = mutableListOf<SearchCall>()
    val documentCalls = mutableListOf<DocumentCall>()
    val holderCalls = mutableListOf<String>()
    var businessDayCalls = 0
        private set
    var roomTypesCalls = 0
        private set

    private val gates = mutableMapOf<Op, CompletableDeferred<Unit>>()

    /** Prende as próximas chamadas de [op] até o portão devolvido ser completado (o mesmo portão vale para todas). */
    fun hold(op: Op): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[op] = it }

    private suspend fun reached(op: Op) {
        gates[op]?.await()
        failures[op]?.let { throw it }
    }

    override suspend fun businessDay(): BusinessDay {
        businessDayCalls++
        reached(Op.BUSINESS_DAY)
        return businessDay
    }

    override suspend fun search(
        filter: ReservationFilter,
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse> {
        searchCalls += SearchCall(filter, cursor, limit)
        reached(Op.SEARCH)
        return pageOf(reservations.filter { matches(it, filter) }, cursor, limit)
    }

    override suspend fun searchByDocument(
        query: DocumentQuery,
        statuses: Set<String>,
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse> {
        documentCalls += DocumentCall(query, statuses, cursor, limit)
        reached(Op.SEARCH_BY_DOCUMENT)
        val guests = documentHolders[query.type to query.number].orEmpty()
        val found = reservations.filter { it.holderGuestId.toString() in guests && matchesStatus(it, statuses) }
        return pageOf(found, cursor, limit)
    }

    override suspend fun holder(guestId: String): HolderSummary {
        holderCalls += guestId
        reached(Op.HOLDER)
        holderFailures[guestId]?.let { throw it }
        return holders.getValue(guestId)
    }

    override suspend fun roomTypes(): List<RoomTypeResponse> {
        roomTypesCalls++
        reached(Op.ROOM_TYPES)
        return roomTypes.toList()
    }

    // Dado pessoal: nunca em `toString`.
    override fun toString(): String = "FakeReservationSearchSource(***)"

    private fun pageOf(
        items: List<ReservationListItemResponse>,
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse> {
        val start = cursor?.toInt() ?: 0
        val end = minOf(start + limit, items.size)
        return Page(items.subList(minOf(start, end), end), if (end < items.size) end.toString() else null)
    }

    private fun matchesStatus(
        item: ReservationListItemResponse,
        statuses: Set<String>,
    ) = statuses.isEmpty() || item.status in statuses

    private fun matches(
        item: ReservationListItemResponse,
        filter: ReservationFilter,
    ): Boolean {
        val externalId = (item.externalId as? Option.Some)?.value
        val holderName = holders[item.holderGuestId.toString()]?.name.orEmpty()
        val name = filter.name?.trim().orEmpty()
        val code = filter.code?.trim().orEmpty()
        val bookingCode = filter.bookingCode?.trim().orEmpty()
        val wantedExternalId = filter.externalId?.trim().orEmpty()
        val from = filter.from?.toApi()
        val to = filter.to?.toApi()
        return (code.isEmpty() || item.code.equals(code, ignoreCase = true)) &&
            (bookingCode.isEmpty() || item.bookingCode.equals(bookingCode, ignoreCase = true)) &&
            (wantedExternalId.isEmpty() || externalId == wantedExternalId) &&
            (name.isEmpty() || holderName.contains(name, ignoreCase = true)) &&
            (from == null || item.departure >= from) &&
            (to == null || item.arrival <= to) &&
            matchesStatus(item, filter.statuses)
    }
}
