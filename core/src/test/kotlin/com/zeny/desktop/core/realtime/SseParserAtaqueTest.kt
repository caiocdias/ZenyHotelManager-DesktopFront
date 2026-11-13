package com.zeny.desktop.core.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseParserAtaqueTest {
    private val parser = SseParser()

    private fun feedAll(vararg lines: String): List<SseEvent> = lines.mapNotNull { parser.feed(it) }

    @Test
    fun dataWithoutColonIsFieldWithEmptyValue() {
        assertEquals(listOf(SseEvent("message", null, "")), feedAll("data", ""))
        assertEquals(listOf(SseEvent("message", null, "\nx")), feedAll("data", "data: x", ""))
    }

    @Test
    fun colonInsideValueIsKept() {
        assertEquals(listOf(SseEvent("message", null, "a:b: c")), feedAll("data: a:b: c", ""))
        assertEquals(listOf(SseEvent("a:b", null, "x")), feedAll("event: a:b", "data: x", ""))
    }

    @Test
    fun onlyOneSpaceStrippedTabKept() {
        assertEquals(listOf(SseEvent("message", null, "\tx")), feedAll("data:\tx", ""))
        assertEquals(listOf(SseEvent("message", null, " \tx")), feedAll("data:  \tx", ""))
    }

    @Test
    fun trailingSpacesInValueKept() {
        assertEquals(listOf(SseEvent("message", null, "x  ")), feedAll("data: x  ", ""))
    }

    @Test
    fun fieldNamesAreCaseSensitive() {
        assertEquals(emptyList(), feedAll("Data: x", "DATA: y", ""))
        assertEquals(listOf(SseEvent("message", null, "x")), feedAll("Event: rooms", "ID: 3", "data: x", ""))
    }

    @Test
    fun fieldNameWithSpaceBeforeColonIsUnknown() {
        assertEquals(emptyList(), feedAll("data : x", ""))
    }

    @Test
    fun retryIsIgnored() {
        assertEquals(emptyList(), feedAll("retry: 3000", ""))
        assertEquals(listOf(SseEvent("message", null, "x")), feedAll("retry", "data: x", ""))
    }

    @Test
    fun lineWithOnlyColonIsComment() {
        assertEquals(emptyList(), feedAll(":", ":data: x", ""))
    }

    @Test
    fun emptyEventNameFallsBackToMessage() {
        assertEquals(listOf(SseEvent("message", null, "x")), feedAll("event:", "data: x", ""))
        assertEquals(listOf(SseEvent("message", null, "x")), feedAll("event", "data: x", ""))
    }

    @Test
    fun lastEventLineWins() {
        assertEquals(listOf(SseEvent("b", null, "x")), feedAll("event: a", "event: b", "data: x", ""))
        assertEquals(listOf(SseEvent("message", null, "x")), feedAll("event: a", "event:", "data: x", ""))
    }

    @Test
    fun emptyIdSetsPositionToEmptyPerSpec() {
        assertEquals(listOf("5", ""), feedAll("id: 5", "data: a", "", "id:", "data: b", "").map { it.id })
        val other = SseParser()
        assertEquals(
            listOf(""),
            listOf("id", "data: a", "").mapNotNull { other.feed(it) }.map { it.id },
        )
    }

    @Test
    fun idAppliesToEventDispatchedAfterItEvenIfDeclaredLast() {
        assertEquals("9", feedAll("data: a", "id: 9", "").single().id)
    }

    @Test
    fun idChangedByCommentOrUnknownFieldNever() {
        val events = feedAll("id: 1", "data: a", "", ": id: 2", "ID: 3", "data: b", "")
        assertEquals(listOf("1", "1"), events.map { it.id })
    }

    @Test
    fun partialEventDoesNotLeakIntoNext_whenBlankAfterNoData() {
        assertEquals(emptyList(), feedAll("event: rooms", ""))
        assertEquals(listOf(SseEvent("message", null, "x")), feedAll("data: x", ""))
    }

    @Test
    fun dataDoesNotLeakAfterDispatch() {
        feedAll("data: a", "")
        assertNull(parser.feed(""))
    }

    @Test
    fun consecutiveBlankLinesDispatchOnce() {
        assertEquals(listOf(SseEvent("message", null, "a")), feedAll("data: a", "", "", ""))
    }

    @Test
    fun crOnlyLineIsBlankAndDispatches() {
        assertEquals(listOf(SseEvent("message", null, "a")), feedAll("data: a", "\r"))
    }

    @Test
    fun onlyOneTrailingCrRemoved() {
        assertEquals(listOf(SseEvent("message", null, "a\r")), feedAll("data: a\r\r", ""))
    }

    @Test
    fun serverSequenceReadyMessagesHeartbeatReset() {
        val json = """{"type":"rooms.room-status","id":"u1","version":3}"""
        val events =
            feedAll(
                "event: ready",
                "id: 7-0",
                "data: {}",
                "",
                ": heartbeat",
                "",
                "event: rooms",
                "id: 7-1",
                "data: $json",
                "",
                ": heartbeat",
                "",
                "event: rooms",
                "id: 7-2",
                "data: $json",
                "",
                "event: reset",
                "id: 8-0",
                "data: {}",
                "",
            )
        assertEquals(
            listOf(
                SseEvent("ready", "7-0", "{}"),
                SseEvent("rooms", "7-1", json),
                SseEvent("rooms", "7-2", json),
                SseEvent("reset", "8-0", "{}"),
            ),
            events,
        )
    }

    @Test
    fun heartbeatBetweenFieldsOfSameEventDoesNotBreakIt() {
        assertEquals(
            listOf(SseEvent("rooms", "1", "x")),
            feedAll("event: rooms", ": heartbeat", "id: 1", ": heartbeat", "data: x", ""),
        )
    }

    @Test
    fun heartbeatAloneDoesNotDispatchPendingEmpty() {
        assertEquals(emptyList(), feedAll(": heartbeat", ""))
    }

    @Test
    fun largeDataLineIsPreserved() {
        val big = "x".repeat(2_000_000)
        assertEquals(listOf(SseEvent("message", null, big)), feedAll("data: $big", ""))
    }

    @Test
    fun manyDataLinesJoinedInOrder() {
        val lines = (1..5000).map { "data: l$it" }.toTypedArray()
        val event = feedAll(*lines, "").single()
        assertEquals((1..5000).joinToString("\n") { "l$it" }, event.data)
    }

    @Test
    fun unicodeAndNulInDataPreserved() {
        val text = "ação 日本語 😀 a\u0000b"
        assertEquals(listOf(SseEvent("message", null, text)), feedAll("data: $text", ""))
    }

    @Test
    fun jsonWithEscapedNewlineStaysOneLine() {
        val json = """{"a":"x\ny"}"""
        assertEquals(json, feedAll("data: $json", "").single().data)
    }

    @Test
    fun nulInEventNameDoesNotBreak() {
        assertEquals(listOf(SseEvent("a\u0000b", null, "x")), feedAll("event: a\u0000b", "data: x", ""))
    }

    @Test
    fun resetAlsoClearsPositionAndName() {
        feedAll("id: 9", "data: a", "")
        feedAll("event: rooms")
        parser.reset()
        assertEquals(listOf(SseEvent("message", null, "x")), feedAll("data: x", ""))
    }

    @Test
    fun parserWorksAfterReset() {
        feedAll("data: parcial")
        parser.reset()
        assertEquals(
            listOf(SseEvent("ready", "1-0", "{}")),
            feedAll("event: ready", "id: 1-0", "data: {}", ""),
        )
    }

    @Test
    fun resetOnFreshParserIsHarmless() {
        parser.reset()
        assertEquals(emptyList(), feedAll(""))
    }

    @Test
    fun idWithNulIgnoredKeepsPreviousNotEmpty() {
        assertEquals("5", feedAll("id: 5", "id: \u0000", "data: a", "").single().id)
        assertNull(SseParser().let { p -> listOf("id: \u0000", "data: a", "").mapNotNull { p.feed(it) } }.single().id)
    }
}
