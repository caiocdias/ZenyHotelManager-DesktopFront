package com.zeny.desktop.seed.sdk

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemorySettingsAtaqueTest {
    private fun snapshot(): Map<String, String>? {
        val root = Preferences.userRoot()
        if (!root.nodeExists("com/zeny/desktop")) return null
        val node = root.node("com/zeny/desktop")
        return node.keys().associateWith { node.get(it, "") }
    }

    @Test
    fun usingMemorySettingsLeavesJavaPreferencesUntouched() {
        val before = snapshot()
        val s = MemorySettings()
        s.deviceId
        s.serverUrl = "http://x:1"
        s.lastLogin = "maria"
        s.lastLogin = null
        assertEquals(before, snapshot())
    }

    @Test
    fun deviceIdIsAlwaysAValidLowercaseUuidAndStable() {
        val s = MemorySettings()
        val first = s.deviceId
        repeat(5) { assertEquals(first, s.deviceId) }
        assertEquals(first, UUID.fromString(first).toString())
        s.lastLogin = "x"
        s.serverUrl = "y"
        assertEquals(first, s.deviceId)
    }

    @Test
    fun manyInstancesGetDistinctDeviceIds() {
        val ids = List(200) { MemorySettings().deviceId }.toSet()
        assertEquals(200, ids.size)
    }

    @Test
    fun valuesCanBeOverwrittenAndClearedPerInstance() {
        val a = MemorySettings()
        val b = MemorySettings()
        a.serverUrl = "http://a"
        b.serverUrl = "http://b"
        a.serverUrl = "http://a2"
        assertEquals("http://a2", a.serverUrl)
        assertEquals("http://b", b.serverUrl)
        a.serverUrl = null
        assertNull(a.serverUrl)
        assertEquals("http://b", b.serverUrl)
    }

    @Test
    fun freshInstanceStartsEmptyEvenAfterAnotherWasWritten() {
        MemorySettings().apply {
            lastLogin = "maria"
            serverUrl = "http://z"
        }
        val fresh = MemorySettings()
        assertNull(fresh.lastLogin)
        assertNull(fresh.serverUrl)
        assertTrue(fresh.deviceId.isNotBlank())
        assertNotEquals(fresh.deviceId, MemorySettings().deviceId)
    }
}
