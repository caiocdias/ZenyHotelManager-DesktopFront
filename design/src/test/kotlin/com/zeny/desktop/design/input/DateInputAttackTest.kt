package com.zeny.desktop.design.input

import com.zeny.desktop.core.types.BusinessDate
import java.time.LocalDate
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DateInputAttackTest {
    private val message = "Data inválida. Use dd/mm/aaaa."

    private fun assertInvalid(text: String) {
        val result = DateInput.parse(text)
        assertIs<InputResult.Invalid>(result, "esperava Invalid para '$text' mas veio $result")
        assertEquals(message, result.message)
    }

    private fun date(text: String): LocalDate {
        val result = DateInput.parse(text)
        assertIs<InputResult.Valid<BusinessDate>>(result, "esperava Valid para '$text' mas veio $result")
        return result.value.value
    }

    @Test
    fun rejectsZeroAndOutOfRangeDayAndMonth() {
        listOf("00/00/0000", "00/11/2026", "10/00/2026", "32/01/2026", "31/04/2026").forEach { assertInvalid(it) }
    }

    @Test
    fun leapDayOnlyInLeapYears() {
        assertInvalid("29/02/2025")
        assertInvalid("29/02/2100")
        assertEquals(LocalDate.of(2024, 2, 29), date("29/02/2024"))
        assertEquals(LocalDate.of(2000, 2, 29), date("29/02/2000"))
    }

    @Test
    fun rejectsTwoDigitYearAndOtherSeparators() {
        val samples = listOf("10/11/26", "10-11-2026", "10.11.2026", "10 11 2026")
        val more = listOf("10/11-2026", "10 / 11 / 2026", "10/11/20266", "10112026")
        (samples + more).forEach { assertInvalid(it) }
    }

    @Test
    fun rejectsNonAsciiDigits() {
        listOf("١٠/١١/٢٠٢٦", "１０/１１/２０２６").forEach { assertInvalid(it) }
    }

    @Test
    fun rejectsSignedOrPaddedInternals() {
        listOf("+1/11/2026", "-1/11/2026", "10/11/+026", "10/ 1/2026").forEach { assertInvalid(it) }
    }

    @Test
    fun trimsNonBreakingSpaceAndNewlineAtEnds() {
        assertEquals(LocalDate.of(2026, 11, 10), date(" 10/11/2026 "))
        assertEquals(LocalDate.of(2026, 11, 10), date("10/11/2026\n"))
    }

    @Test
    fun extremeYears() {
        assertEquals(LocalDate.of(9999, 12, 31), date("31/12/9999"))
        assertEquals(LocalDate.of(1, 1, 1), date("01/01/0001"))
        assertEquals("31/12/9999", DateInput.format(BusinessDate(LocalDate.of(9999, 12, 31))))
        assertEquals("01/01/0001", DateInput.format(BusinessDate(LocalDate.of(1, 1, 1))))
        assertEquals(LocalDate.of(0, 1, 1), date("01/01/0000"))
    }

    @Test
    fun formatPadsDayMonthAndYear() {
        assertEquals("05/01/0099", DateInput.format(BusinessDate(LocalDate.of(99, 1, 5))))
    }

    @Test
    fun neverDependsOnDefaultTimeZone() {
        val original = TimeZone.getDefault()
        try {
            listOf("Pacific/Kiritimati", "Pacific/Pago_Pago", "America/Sao_Paulo", "UTC").forEach {
                TimeZone.setDefault(TimeZone.getTimeZone(it))
                assertEquals(LocalDate.of(2026, 3, 1), date("01/03/2026"), it)
                assertEquals("01/03/2026", DateInput.format(BusinessDate(LocalDate.of(2026, 3, 1))), it)
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun roundTripsEveryDayOfLeapYear() {
        var day = LocalDate.of(2024, 1, 1)
        while (day.year == 2024) {
            assertEquals(day, date(DateInput.format(BusinessDate(day))))
            day = day.plusDays(1)
        }
    }
}
