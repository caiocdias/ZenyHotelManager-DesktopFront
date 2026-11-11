package com.zeny.desktop.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerUrlTest {
    @Test
    fun acceptsAndNormalizes() {
        assertEquals("http://192.168.0.20:8088", ServerUrl.parse("http://192.168.0.20:8088/")?.value)
        assertEquals("https://demo.local", ServerUrl.parse(" https://demo.local ")?.value)
    }

    @Test
    fun rejectsInvalidInput() {
        listOf(
            null,
            "",
            "   ",
            "192.168.0.20:8088",
            "ftp://x",
            "http://",
            "http://h:8088?x=1",
            "http://h:8088#frag",
            "http://u:p@h:8088",
        ).forEach { assertNull(ServerUrl.parse(it), "deveria recusar: $it") }
    }
}
