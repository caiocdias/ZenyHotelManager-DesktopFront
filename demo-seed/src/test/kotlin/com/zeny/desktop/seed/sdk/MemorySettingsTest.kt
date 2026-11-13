package com.zeny.desktop.seed.sdk

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MemorySettingsTest {
    @Test
    fun memorySettingsNeverTouchesPreferences() {
        val a = MemorySettings()
        val b = MemorySettings()
        assertEquals(a.deviceId, a.deviceId)
        assertNotEquals(a.deviceId, b.deviceId)
        assertEquals(a.deviceId, UUID.fromString(a.deviceId).toString())
        a.lastLogin = "maria"
        a.serverUrl = "http://x"
        assertEquals("maria", a.lastLogin)
        assertNull(b.lastLogin)
        assertNull(b.serverUrl)
        assertNull(MemorySettings().lastLogin)
    }
}
