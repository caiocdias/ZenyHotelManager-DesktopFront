package com.zeny.desktop.core.types

import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TimeAttackTest {
    private fun <T> withDefaultZone(
        id: String,
        block: () -> T,
    ): T {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun assertDateRejected(text: String) {
        val error = assertFailsWith<IllegalArgumentException>("aceitou: $text") { BusinessDate.parse(text) }
        if (text.isNotBlank()) assertFalse(error.message.orEmpty().contains(text.trim()))
    }

    private fun assertInstantRejected(text: String) {
        val error = assertFailsWith<IllegalArgumentException>("aceitou: $text") { parseApiInstant(text) }
        if (text.isNotBlank()) assertFalse(error.message.orEmpty().contains(text.trim()))
    }

    @Test
    fun businessDateRejectsNonCanonicalShapes() {
        listOf(
            "",
            " 2026-11-10",
            "2026-11-10 ",
            "2026-11-10\n",
            "2026-1-10",
            "2026-11-1",
            "26-11-10",
            "2026/11/10",
            "20261110",
            "2026-13-01",
            "2026-00-10",
            "2026-11-00",
            "2026-11-31",
            "٢٠٢٦-11-10",
        ).forEach { assertDateRejected(it) }
    }

    @Test
    fun businessDateRejectsSignOrFiveDigitYear() {
        assertDateRejected("+12026-11-10")
        assertDateRejected("-0001-11-10")
        assertDateRejected("+2026-11-10")
        assertDateRejected("12026-11-10")
    }

    @Test
    fun businessDateLeapYears() {
        assertEquals("2024-02-29", BusinessDate.parse("2024-02-29").toApi())
        assertEquals("2000-02-29", BusinessDate.parse("2000-02-29").toApi())
        assertDateRejected("2026-02-29")
        assertDateRejected("1900-02-29")
    }

    @Test
    fun businessDateStableUnderAnyDefaultZone() {
        listOf("Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC").forEach { zone ->
            withDefaultZone(zone) { assertEquals("2026-12-31", BusinessDate.parse("2026-12-31").toApi()) }
        }
    }

    @Test
    fun instantRejectsMissingOffsetAndJunk() {
        assertInstantRejected("2026-11-10T13:00:00")
        assertInstantRejected("2026-11-10T13:00")
        assertInstantRejected("2026-11-10")
        assertInstantRejected("")
        assertInstantRejected("2026-11-10 13:00:00Z")
        assertInstantRejected(" 2026-11-10T13:00:00Z")
        assertInstantRejected("2026-11-10T13:00:00Z\n")
        assertInstantRejected("2026-11-10T13:00:00Zjunk")
    }

    @Test
    fun instantRejectsInvalidFields() {
        assertInstantRejected("2026-02-30T10:00:00Z")
        assertInstantRejected("2026-11-10T25:00:00Z")
        assertInstantRejected("2026-11-10T10:61:00Z")
        assertInstantRejected("2026-11-10T10:00:00+25:00")
        assertInstantRejected("٢٠٢٦-11-10T10:00:00Z")
    }

    @Test
    fun instantExtremeYearsNeverLeakNonIllegalArgumentExceptions() {
        listOf("+999999999-01-01T00:00:00Z", "-999999999-01-01T00:00:00+18:00", "+9999999999-01-01T00:00:00Z").forEach {
            try {
                parseApiInstant(it)
            } catch (_: IllegalArgumentException) {
                // recusa limpa é aceitável
            }
        }
    }

    @Test
    fun instantKeepsFractionalSeconds() {
        val instant = parseApiInstant("2026-11-10T13:00:00.123456789Z")
        assertEquals(123_456_789, instant.nano)
        assertEquals("2026-11-10T13:00:00.123456789Z", instant.toApi())
        assertEquals(Instant.parse("2026-11-10T13:00:00.5Z"), parseApiInstant("2026-11-10T10:00:00.500-03:00"))
    }

    @Test
    fun instantOmitsZeroFractionOnOutput() {
        assertEquals("2026-11-10T13:00:00Z", parseApiInstant("2026-11-10T13:00:00.000Z").toApi())
    }

    @Test
    fun instantLeapDayAndOffsetsAcrossDateBoundary() {
        assertEquals("2024-02-29T02:30:00Z", parseApiInstant("2024-02-28T21:30:00-05:00").toApi())
        assertEquals("2026-12-31T22:30:00Z", parseApiInstant("2027-01-01T04:00:00+05:30").toApi())
        assertEquals("2026-03-01T00:00:00Z", parseApiInstant("2026-02-28T23:00:00-01:00").toApi())
    }

    @Test
    fun instantParsingIgnoresJvmDefaultZone() {
        withDefaultZone("Pacific/Kiritimati") {
            assertEquals("2026-11-10T13:00:00Z", parseApiInstant("2026-11-10T13:00:00Z").toApi())
            assertEquals("2026-11-10T13:00:00Z", parseApiInstant("2026-11-10T10:00:00-03:00").toApi())
        }
    }

    @Test
    fun hotelClockAcrossDayBoundaryAndDst() {
        val sp = HotelClock(ZoneId.of("America/Sao_Paulo"))
        withDefaultZone("Pacific/Kiritimati") {
            val local = sp.local(parseApiInstant("2026-11-10T02:00:00Z"))
            assertEquals(9, local.dayOfMonth)
            assertEquals(23, local.hour)
        }
        val ny = HotelClock(ZoneId.of("America/New_York"))
        assertEquals("-05:00", ny.local(parseApiInstant("2026-03-08T06:59:59Z")).offset.id)
        assertEquals("-04:00", ny.local(parseApiInstant("2026-03-08T07:00:00Z")).offset.id)
    }

    @Test
    fun hotelClockPreservesInstant() {
        val instant = parseApiInstant("2024-02-29T23:59:59.999Z")
        assertEquals(instant, HotelClock(ZoneId.of("Asia/Kolkata")).local(instant).toInstant())
    }
}
