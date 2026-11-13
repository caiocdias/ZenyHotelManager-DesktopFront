package com.zeny.desktop.core.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseParserTest {
    private val parser = SseParser()

    private fun feedAll(vararg lines: String): List<SseEvent> = lines.mapNotNull { parser.feed(it) }

    @Test
    fun dispatchesOnBlankLine() {
        val payload = """{"type":"rooms.room-status","id":"u1","version":3}"""
        assertNull(parser.feed("event: rooms"))
        assertNull(parser.feed("id: 7-12"))
        assertNull(parser.feed("data: $payload"))
        assertEquals(SseEvent(event = "rooms", id = "7-12", data = payload), parser.feed(""))
    }

    @Test
    fun readyAndResetCarryPosition() {
        val events =
            feedAll(
                "event: ready",
                "id: 7-0",
                "data: {}",
                "",
                "event: reset",
                "id: 8-0",
                "data: {}",
                "",
            )
        assertEquals(
            listOf(SseEvent("ready", "7-0", "{}"), SseEvent("reset", "8-0", "{}")),
            events,
        )
    }

    @Test
    fun heartbeatCommentIgnored() {
        assertEquals(emptyList(), feedAll(": heartbeat", ""))
        assertEquals(listOf(SseEvent("message", null, "x")), feedAll(": heartbeat", "data: x", ": hb", ""))
    }

    @Test
    fun multiLineDataJoined() {
        assertEquals(listOf(SseEvent("message", null, "a\nb\n")), feedAll("data: a", "data: b", "data:", ""))
    }

    @Test
    fun crlfAccepted() {
        assertEquals(
            listOf(SseEvent("rooms", "1", "x")),
            feedAll("event: rooms\r", "id: 1\r", "data: x\r", "\r"),
        )
    }

    @Test
    fun unknownFieldIgnored() {
        assertEquals(listOf(SseEvent("message", null, "x")), feedAll("retry: 3000", "foo: bar", "data: x", ""))
    }

    @Test
    fun leadingSpaceRemovedOnlyOnce() {
        assertEquals(listOf(SseEvent("message", null, "  x")), feedAll("data:   x", ""))
        assertEquals(listOf(SseEvent("message", null, "x")), feedAll("data:x", ""))
    }

    @Test
    fun idPersistsAcrossEvents() {
        val events = feedAll("id: 5", "data: a", "", "data: b", "", "id: 6", "data: c", "")
        assertEquals(listOf("5", "5", "6"), events.map { it.id })
    }

    @Test
    fun idWithNullCharIgnored() {
        val events = feedAll("id: 5", "id: 6\u0000x", "data: a", "")
        assertEquals("5", events.single().id)
    }

    @Test
    fun noDispatchWithoutData() {
        assertEquals(emptyList(), feedAll("event: rooms", "id: 3", ""))
        // o nome do evento não vaza para o seguinte, mas o id persiste
        assertEquals(listOf(SseEvent("message", "3", "x")), feedAll("data: x", ""))
    }

    @Test
    fun resetClearsPartialEvent() {
        feedAll("id: 9", "event: rooms", "data: parcial")
        parser.reset()
        assertEquals(emptyList(), feedAll(""))
        assertEquals(listOf(SseEvent("message", null, "x")), feedAll("data: x", ""))
    }
}
