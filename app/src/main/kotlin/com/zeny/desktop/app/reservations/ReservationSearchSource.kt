package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DocumentQuery
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse

/**
 * O que a busca de reservas usa de um servidor: o dia operacional (valor padrão do período), as duas buscas paginadas
 * (por filtros e por documento), o titular de cada linha e os tipos de UH.
 */
interface ReservationSearchSource {
    suspend fun businessDay(): BusinessDay

    suspend fun search(
        filter: ReservationFilter,
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse>

    suspend fun searchByDocument(
        query: DocumentQuery,
        statuses: Set<String>,
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse>

    suspend fun holder(guestId: String): HolderSummary

    suspend fun roomTypes(): List<RoomTypeResponse>
}
