package com.zeny.desktop.design.input

import com.zeny.desktop.core.types.BusinessDate
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DateInputTest {
    @Test
    fun parsesBrazilianDate() {
        val result = DateInput.parse("10/11/2026")
        assertIs<InputResult.Valid<BusinessDate>>(result)
        assertEquals(LocalDate.of(2026, 11, 10), result.value.value)
        assertEquals("2026-11-10", result.value.toApi())
        val padded = DateInput.parse(" 10/11/2026 ")
        assertIs<InputResult.Valid<BusinessDate>>(padded)
        assertEquals(LocalDate.of(2026, 11, 10), padded.value.value)
    }

    @Test
    fun rejectsInvalid() {
        listOf("31/02/2026", "2026-11-10", "1/1/26", "abc", "10/13/2026").forEach {
            val result = DateInput.parse(it)
            assertIs<InputResult.Invalid>(result, "esperava Invalid para '$it'")
            assertEquals("Data inválida. Use dd/mm/aaaa.", result.message)
        }
        assertEquals(InputResult.Empty, DateInput.parse("  "))
    }

    @Test
    fun formatsAndRoundTrips() {
        val date = BusinessDate(LocalDate.of(2026, 1, 5))
        assertEquals("05/01/2026", DateInput.format(date))
        val back = DateInput.parse(DateInput.format(date))
        assertIs<InputResult.Valid<BusinessDate>>(back)
        assertEquals(date.value, back.value.value)
    }
}
