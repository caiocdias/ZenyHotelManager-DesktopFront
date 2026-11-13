package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

/** Maior página que o contrato aceita: o histórico vem inteiro, com o mínimo de idas ao servidor. */
private const val HISTORY_PAGE_SIZE = 200

/** As listas do dia da recepção. */
enum class DayList { ARRIVALS, DEPARTURES, IN_HOUSE }

/**
 * As reservas do dia: as listas de chegadas, saídas e hospedados, o detalhe, o histórico e as alocações de UH.
 * Um `id` que não é UUID lança `IllegalArgumentException` antes de ir à rede.
 */
class ReservationsFacade internal constructor(
    private val reservations: ReservationsApi,
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

    private fun uuidOf(id: String): Uuid =
        Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de reserva inválido")

    /** Cursor ausente, nulo ou em branco é o fim da lista. */
    private fun Option<String?>.valueOrNull(): String? = (this as? Option.Some)?.value?.takeIf { it.isNotBlank() }
}
