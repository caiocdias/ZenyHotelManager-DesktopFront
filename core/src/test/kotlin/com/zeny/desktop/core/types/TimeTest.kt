package com.zeny.desktop.core.types

import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimeTest {
    @Test
    fun businessDateHasNoZoneConversion() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            assertEquals("2026-11-10", BusinessDate.parse("2026-11-10").toApi())
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun rejectsNonIsoDate() {
        listOf("10/11/2026", "2026-11-10T00:00:00Z", "2026-02-30").forEach {
            assertFailsWith<IllegalArgumentException>("aceitou '$it'") { BusinessDate.parse(it) }
        }
    }

    @Test
    fun instantRoundTrip() {
        assertEquals("2026-11-10T13:00:00Z", parseApiInstant("2026-11-10T13:00:00Z").toApi())
        assertEquals(parseApiInstant("2026-11-10T13:00:00Z"), parseApiInstant("2026-11-10T10:00:00-03:00"))
    }

    @Test
    fun hotelClockShowsHotelZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val local = HotelClock(ZoneId.of("America/Sao_Paulo")).local(parseApiInstant("2026-11-10T13:00:00Z"))
            assertEquals(10, local.hour)
            assertEquals(ZoneOffset.ofHours(-3), local.offset)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
