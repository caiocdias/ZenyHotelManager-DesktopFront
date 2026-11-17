package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.facade.ReservationFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReservationSearchTextAtaqueTest {
    private val text = ReservationSearchText

    @Test
    fun lowercaseAndSpacedPrefixesStillSelectTheFilter() {
        assertEquals(ReservationFilter(code = "r-1"), text.numberFilter("r-1"))
        assertEquals(ReservationFilter(bookingCode = "b-1"), text.numberFilter("  b-1  "))
        assertEquals(ReservationFilter(code = "R-"), text.numberFilter("R-"))
        assertEquals(ReservationFilter(bookingCode = "b-"), text.numberFilter(" b- "))
    }

    @Test
    fun prefixOnlyCountsAtTheStart() {
        assertEquals(ReservationFilter(externalId = "XR-1"), text.numberFilter("XR-1"))
        assertEquals(ReservationFilter(externalId = "R 1"), text.numberFilter("R 1"))
        assertEquals(ReservationFilter(externalId = "R1"), text.numberFilter("R1"))
        assertEquals(ReservationFilter(externalId = "B1"), text.numberFilter("B1"))
        assertEquals(ReservationFilter(externalId = "X B-2"), text.numberFilter("X B-2"))
    }

    @Test
    fun numberFilterKeepsInnerSpacesAndCase() {
        assertEquals(ReservationFilter(externalId = "Ab c-9"), text.numberFilter("  Ab c-9\t"))
    }

    @Test
    fun maskedDocumentBoundaryAtFourAndFiveSignificantChars() {
        assertEquals("CPF ***", text.maskedDocument("CPF", ""))
        assertEquals("CPF ***", text.maskedDocument("CPF", "1234"))
        assertEquals("CPF ***", text.maskedDocument("CPF", "12.3-4"))
        assertEquals("CPF ***2345", text.maskedDocument("CPF", "12345"))
        assertEquals("CPF ***", text.maskedDocument("CPF", " . - / "))
    }

    @Test
    fun maskedDocumentIgnoresPunctuationInTheTail() {
        assertEquals("CPF ***4725", text.maskedDocument("CPF", "529.982.247-25"))
        assertEquals("CPF ***4725", text.maskedDocument("CPF", "529.982.247-25 "))
        assertEquals("Passaporte ***AB12", text.maskedDocument("PASSPORT", "xx-AB.12"))
    }

    @Test
    fun maskedDocumentNeverRevealsMoreThanFour() {
        val masked = text.maskedDocument("CPF", "52998224725")
        assertFalse("5299" in masked)
        assertFalse("52998224725" in masked)
        assertEquals(4, masked.takeLastWhile { it.isLetterOrDigit() }.length)
    }
}
