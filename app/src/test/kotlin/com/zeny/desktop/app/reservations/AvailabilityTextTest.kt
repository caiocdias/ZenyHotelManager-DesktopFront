package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.AvailabilityDayResponse
import com.zeny.desktop.sdk.model.QuoteRefusalResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AvailabilityTextTest {
    private fun refusal(
        code: String,
        message: String = "",
        dates: List<String> = emptyList(),
    ) = QuoteRefusalResponse(code = code, dates = dates, message = message)

    @Test
    fun dayHeaderIsLowercaseWeekdayAndDayMonth() {
        assertEquals("seg 16/11", AvailabilityText.dayHeader(BusinessDate.parse("2026-11-16")))
        assertEquals("dom 22/11", AvailabilityText.dayHeader(BusinessDate.parse("2026-11-22")))
    }

    @Test
    fun mealPlanHasPortugueseLabels() {
        assertEquals("Só hospedagem", AvailabilityText.mealPlan("ROOM_ONLY"))
        assertEquals("Café da manhã", AvailabilityText.mealPlan("BREAKFAST"))
        assertEquals("Meia pensão", AvailabilityText.mealPlan("HALF_BOARD"))
        assertEquals("Outro (ALL_INCLUSIVE)", AvailabilityText.mealPlan("ALL_INCLUSIVE"))
    }

    @Test
    fun refusalUsesServerMessage() {
        val text = AvailabilityText.refusal(refusal("MIN_STAY_NOT_MET", "Mínimo de 3 noites."))
        assertEquals("Mínimo de 3 noites.", text)
    }

    @Test
    fun refusalFallsBackWithoutRawCode() {
        val dates = listOf("2026-11-20", "2026-11-21")
        for (message in listOf("", "   ", "{\"code\":\"X\"}", "MIN_STAY_NOT_MET")) {
            val text = AvailabilityText.refusal(refusal("MIN_STAY_NOT_MET", message, dates))
            assertEquals("A estadia é mais curta que o mínimo do plano. (20/11/2026, 21/11/2026)", text)
            assertFalse("MIN_STAY_NOT_MET" in text)
        }
        val catalog = AvailabilityText.refusal(refusal("ROOM_TYPE_UNAVAILABLE", "", listOf("2026-11-20")))
        assertEquals("Não há UH disponível deste tipo em todas as noites. (20/11/2026)", catalog)
        val unknown = AvailabilityText.refusal(refusal("NOVO_CODIGO", "NOVO_CODIGO", listOf("2026-11-20")))
        assertEquals("Esta combinação não está disponível para essa estadia. (20/11/2026)", unknown)
        assertEquals(
            "Esta combinação não está disponível para essa estadia.",
            AvailabilityText.refusal(refusal("NOVO_CODIGO")),
        )
    }

    @Test
    fun refusalCodesHavePortugueseText() {
        val expected =
            mapOf(
                "SALES_CHANNEL_NOT_ALLOWED" to "O plano não é vendido neste canal.",
                "OCCUPANCY_EXCEEDS_ROOM_TYPE" to "O número de hóspedes passa do que o tipo de UH recebe.",
                "ARRIVAL_BEFORE_BUSINESS_DATE" to "A chegada é antes da data operacional do hotel.",
                "MIN_ADVANCE_NOT_MET" to "A chegada exige mais antecedência.",
                "MAX_ADVANCE_EXCEEDED" to "A chegada está longe demais para reservar agora.",
                "CLOSED_TO_ARRIVAL" to "A data está fechada para chegada.",
                "CLOSED_TO_DEPARTURE" to "A data está fechada para saída.",
                "MIN_STAY_NOT_MET" to "A estadia é mais curta que o mínimo do plano.",
                "MAX_STAY_EXCEEDED" to "A estadia é mais longa que o máximo do plano.",
                "STAY_DATE_CLOSED" to "Há data fechada para venda na estadia.",
                "RATE_NOT_PRICED" to "O plano não tem preço para essa estadia.",
            )
        assertEquals(11, expected.size)
        for ((code, text) in expected) {
            assertEquals(text, AvailabilityText.refusal(refusal(code)))
        }
    }

    @Test
    fun inventoryListsTheDayCounters() {
        val day =
            AvailabilityDayResponse(
                available = 9,
                blocked = 0,
                date = "2026-11-16",
                outOfOrder = 0,
                overbooked = false,
                overbookingLimit = 0,
                sold = 5,
                total = 14,
            )
        assertEquals(
            "Total 14 · Vendidas 5 · Bloqueadas 0 · Fora de ordem 0 · Limite de overbooking 0 · Disponíveis 9",
            AvailabilityText.inventory(day),
        )
    }

    @Test
    fun unitsPluralize() {
        assertEquals("1 UH disponível", AvailabilityText.units(1))
        assertEquals("0 UHs disponíveis", AvailabilityText.units(0))
        assertEquals("9 UHs disponíveis", AvailabilityText.units(9))
    }
}
