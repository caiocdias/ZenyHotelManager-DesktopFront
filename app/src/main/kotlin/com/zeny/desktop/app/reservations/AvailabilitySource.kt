package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.AvailabilityResponse
import com.zeny.desktop.sdk.model.QuoteResponse

/**
 * O que a disponibilidade e a cotação usam de um servidor: o dia operacional (início do calendário), o calendário de
 * disponibilidade e a cotação da estadia, que já traz as ofertas recusadas e o motivo.
 */
interface AvailabilitySource {
    suspend fun businessDay(): BusinessDay

    suspend fun availability(
        from: BusinessDate,
        to: BusinessDate,
    ): AvailabilityResponse

    suspend fun quote(
        arrival: BusinessDate,
        departure: BusinessDate,
        adults: Int,
        childAges: List<Int>,
    ): QuoteResponse
}
