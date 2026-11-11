package com.zeny.desktop.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServerUrlAttackTest {
    private fun accepted(raw: String): String = assertNotNull(ServerUrl.parse(raw), "deveria aceitar: $raw").value

    @Test
    fun nullInputIsRejected() = assertNull(ServerUrl.parse(null))

    @Test
    fun ipv6LiteralWithPortIsAcceptedAndNormalized() = assertEquals("http://[::1]:8088", accepted("http://[::1]:8088/"))

    @Test
    fun uppercaseSchemeIsAccepted() {
        assertNotNull(ServerUrl.parse("HTTP://h:8088"))
        assertNotNull(ServerUrl.parse("HttpS://h"))
    }

    @Test
    fun pathIsKeptAndOnlyTrailingSlashesRemoved() {
        assertEquals("http://h:8088/api", accepted("http://h:8088/api/"))
        assertEquals("http://h", accepted("http://h///"))
    }

    @Test
    fun tabsAndNewlinesAroundAreTrimmed() = assertEquals("http://h:1", accepted("\t http://h:1 \r\n"))

    @Test
    fun parseIsIdempotent() {
        val once = accepted(" https://demo.local:9/x/ ")
        assertEquals(once, accepted(once))
    }

    @Test
    fun nonHttpSchemesAreRejected() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "ws://h", "mailto:a@h", "//h:8088", "http:h", "h:8088")
            .forEach { assertNull(ServerUrl.parse(it), it) }
    }

    @Test
    fun missingOrBrokenHostIsRejected() {
        listOf("http://:8088", "https:///x", "http://h:abc", "http://[::1", "http:///")
            .forEach { assertNull(ServerUrl.parse(it), it) }
    }

    @Test
    fun internalWhitespaceIsRejected() {
        listOf("http://h ost:8088", "http://h:8088/a b", "ht tp://h")
            .forEach { assertNull(ServerUrl.parse(it), it) }
    }

    @Test
    fun emptyQueryFragmentAndUserInfoAreRejected() {
        listOf("http://h?", "http://h/?", "http://h#", "http://h:1/#x", "http://@h", "http://:@h", "http://u@h")
            .forEach { assertNull(ServerUrl.parse(it), it) }
    }

    @Test
    fun outOfRangePortIsRejected() {
        assertNull(ServerUrl.parse("http://h:99999"), "porta acima de 65535")
        assertNull(ServerUrl.parse("http://h:70000/"), "porta acima de 65535")
    }
}
