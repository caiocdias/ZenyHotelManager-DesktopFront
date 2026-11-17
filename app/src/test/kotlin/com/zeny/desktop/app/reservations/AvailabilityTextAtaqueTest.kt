package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.AvailabilityDayResponse
import com.zeny.desktop.sdk.model.QuoteRefusalResponse
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvailabilityTextAtaqueTest {
    private val default = "Esta combinação não está disponível para essa estadia."

    private fun refusal(
        code: String,
        message: String = "",
        dates: List<String> = emptyList(),
    ) = QuoteRefusalResponse(code = code, dates = dates, message = message)

    @Test
    fun dayHeaderCoversTheSevenWeekdaysInPortuguese() {
        // 2026-11-16 é segunda-feira.
        val expected = listOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom")
        expected.forEachIndexed { i, name ->
            val day = (16 + i).toString()
            assertEquals("$name $day/11", AvailabilityText.dayHeader(BusinessDate.parse("2026-11-$day")))
        }
    }

    @Test
    fun dayHeaderHasNoDotAndNoUppercase() {
        for (i in 0L until 400L) {
            val text = AvailabilityText.dayHeader(BusinessDate(LocalDate.of(2026, 1, 1).plusDays(i)))
            assertFalse('.' in text, text)
            assertEquals(text.lowercase(), text)
        }
    }

    @Test
    fun dayHeaderPadsDayAndMonthAndCrossesMonthAndYear() {
        assertEquals("qui 31/12", AvailabilityText.dayHeader(BusinessDate.parse("2026-12-31")))
        assertEquals("sex 01/01", AvailabilityText.dayHeader(BusinessDate.parse("2027-01-01")))
        assertEquals("dom 01/02", AvailabilityText.dayHeader(BusinessDate.parse("2026-02-01")))
        assertEquals("seg 30/11", AvailabilityText.dayHeader(BusinessDate.parse("2026-11-30")))
        assertEquals("ter 01/12", AvailabilityText.dayHeader(BusinessDate.parse("2026-12-01")))
    }

    @Test
    fun mealPlanWithEmptyOrStrangeValueNeverThrows() {
        assertEquals("Outro ()", AvailabilityText.mealPlan(""))
        assertEquals("Outro (roomonly)", AvailabilityText.mealPlan("roomonly"))
        assertEquals("Outro ( ROOM_ONLY)", AvailabilityText.mealPlan(" ROOM_ONLY"))
    }

    @Test
    fun inventoryShowsNegativeNumbersFromOversale() {
        val day =
            AvailabilityDayResponse(
                available = -2,
                blocked = 0,
                date = "2026-11-16",
                outOfOrder = 1,
                overbooked = true,
                overbookingLimit = 3,
                sold = 16,
                total = 14,
            )
        val text = AvailabilityText.inventory(day)
        assertTrue(text.endsWith("Disponíveis -2"), text)
        assertTrue("Vendidas 16" in text, text)
    }

    @Test
    fun unitsHandlesZeroOneAndMany() {
        assertEquals("0 UHs disponíveis", AvailabilityText.units(0))
        assertEquals("1 UH disponível", AvailabilityText.units(1))
        assertEquals("2 UHs disponíveis", AvailabilityText.units(2))
    }

    @Test
    fun refusalNeverShowsTheRawCodeForJsonArrayOrWhitespaceMessages() {
        for (message in listOf("[1,2]", "  \t ", "\n", "  {x}", "MIN_STAY_NOT_MET  ")) {
            val text = AvailabilityText.refusal(refusal("MIN_STAY_NOT_MET", message))
            assertEquals("A estadia é mais curta que o mínimo do plano.", text, "message=[$message]")
        }
    }

    @Test
    fun refusalWithLowercaseCodeNeverShowsTheCode() {
        for (code in listOf("min_stay_not_met", "Min_Stay_Not_Met")) {
            for (message in listOf("", code)) {
                val text = AvailabilityText.refusal(refusal(code, message, listOf("2026-11-20")))
                assertFalse(code in text, text)
                assertEquals("$default (20/11/2026)", text)
            }
        }
    }

    @Test
    fun refusalMessageDoesNotGetDatesAppended() {
        val text = AvailabilityText.refusal(refusal("MIN_STAY_NOT_MET", "Mínimo de 3 noites.", listOf("2026-11-20")))
        assertEquals("Mínimo de 3 noites.", text)
    }

    @Test
    fun refusalUnknownCodeWithUnrelatedRawIdentifierMessageUsesDefault() {
        assertEquals(default, AvailabilityText.refusal(refusal("NOVO", "OUTRO_CODIGO")))
    }

    @Test
    fun refusalFormatsManyDatesAndSurvivesMalformedOnes() {
        val dates = listOf("2026-11-20", "lixo", "2026-13-45", "", "2026-02-30", "2026-11-22")
        val text = AvailabilityText.refusal(refusal("CLOSED_TO_ARRIVAL", "", dates))
        assertTrue(text.startsWith("A data está fechada para chegada. ("), text)
        assertTrue("20/11/2026" in text && "22/11/2026" in text, text)
        assertFalse("lixo" in text || "2026-13-45" in text || "2026-02-30" in text, text)
    }

    @Test
    fun refusalWithOnlyMalformedDateNeverThrows() {
        val text = AvailabilityText.refusal(refusal("RATE_NOT_PRICED", "", listOf("20/11/2026")))
        assertTrue(text.startsWith("O plano não tem preço para essa estadia."), text)
    }

    @Test
    fun refusalCodeFromErrorCatalogGetsItsCatalogText() {
        val text = AvailabilityText.refusal(refusal("ROOM_TYPE_UNAVAILABLE", "ROOM_TYPE_UNAVAILABLE"))
        assertEquals("Não há UH disponível deste tipo em todas as noites.", text)
    }

    @Test
    fun refusalMessageThatIsTheCodeInAnotherCaseIsNotShown() {
        val text = AvailabilityText.refusal(refusal("MIN_STAY_NOT_MET", "min_stay_not_met"))
        assertFalse("min_stay_not_met" in text, text)
    }
}
