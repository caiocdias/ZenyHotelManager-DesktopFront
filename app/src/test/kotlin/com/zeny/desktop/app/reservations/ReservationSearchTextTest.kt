package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.facade.ReservationFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class ReservationSearchTextTest {
    @Test
    fun numberPrefixesSelectFilter() {
        val text = ReservationSearchText
        assertEquals(ReservationFilter(code = "r-2026-000001"), text.numberFilter(" r-2026-000001 "))
        assertEquals(ReservationFilter(bookingCode = "B-2026-000002"), text.numberFilter("B-2026-000002"))
        assertEquals(ReservationFilter(bookingCode = "b-7"), text.numberFilter("b-7"))
        assertEquals(ReservationFilter(externalId = "ABC123"), text.numberFilter(" ABC123 "))
        assertEquals(ReservationFilter(), text.numberFilter("   "))
        assertEquals(ReservationFilter(), text.numberFilter(""))
    }

    @Test
    fun maskedDocumentShowsLastFour() {
        assertEquals("CPF ***4725", ReservationSearchText.maskedDocument("CPF", "529.982.247-25"))
        assertEquals("CPF ***", ReservationSearchText.maskedDocument("CPF", "12"))
        assertEquals("CPF ***", ReservationSearchText.maskedDocument("CPF", "1-2.3-4"))
        assertEquals("Passaporte ***567Z", ReservationSearchText.maskedDocument("PASSPORT", "AB1234567Z"))
    }
}
