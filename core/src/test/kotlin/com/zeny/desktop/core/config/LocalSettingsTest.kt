package com.zeny.desktop.core.config

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class LocalSettingsTest {
    private val node: Preferences = Preferences.userRoot().node("zeny-test-${UUID.randomUUID()}")

    @AfterTest
    fun cleanUp() {
        node.removeNode()
        Preferences.userRoot().flush()
    }

    @Test
    fun deviceIdIsStableUuid() {
        val first = PreferencesSettings(node).deviceId
        val second = PreferencesSettings(node).deviceId

        assertEquals(first, second)
        assertEquals(first, UUID.fromString(first).toString())
    }

    @Test
    fun invalidStoredDeviceIdIsReplaced() {
        node.put("deviceId", "not-a-uuid")

        val id = PreferencesSettings(node).deviceId

        assertNotEquals("not-a-uuid", id)
        UUID.fromString(id)
        assertEquals(id, node.get("deviceId", null))
    }

    @Test
    fun onlyThreeKeysAreWritten() {
        val settings = PreferencesSettings(node)
        settings.serverUrl = "http://h:8088"
        settings.lastLogin = "maria"
        settings.deviceId

        assertEquals(setOf("serverUrl", "lastLogin", "deviceId"), node.keys().toSet())
        assertEquals("http://h:8088", settings.serverUrl)
        assertEquals("maria", settings.lastLogin)
    }

    @Test
    fun nullRemovesKey() {
        val settings = PreferencesSettings(node)
        settings.serverUrl = "http://h:8088"
        settings.lastLogin = "maria"

        settings.serverUrl = null
        settings.lastLogin = null

        assertNull(settings.serverUrl)
        assertNull(settings.lastLogin)
        assertEquals(emptySet(), node.keys().toSet())
    }
}
