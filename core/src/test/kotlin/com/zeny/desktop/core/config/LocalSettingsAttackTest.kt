package com.zeny.desktop.core.config

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class LocalSettingsAttackTest {
    private val node: Preferences = Preferences.userRoot().node("zeny-test-${UUID.randomUUID()}")

    @AfterTest
    fun cleanUp() {
        node.removeNode()
        Preferences.userRoot().flush()
    }

    @Test
    fun emptyAndBlankStoredDeviceIdAreReplaced() {
        listOf("", "   ", " 123e4567-e89b-12d3-a456-426614174000 ").forEach { bad ->
            node.put("deviceId", bad)
            val id = PreferencesSettings(node).deviceId
            assertNotEquals(bad, id, "valor salvo '$bad' deveria ser substituído")
            assertEquals(id, UUID.fromString(id).toString())
        }
    }

    @Test
    fun shortFormStoredDeviceIdIsNotAcceptedAsUuid() {
        // UUID.fromString("1-1-1-1-1") não lança, mas o resultado não é um UUID canônico.
        node.put("deviceId", "1-1-1-1-1")
        val id = PreferencesSettings(node).deviceId
        assertEquals(36, id.length, "deviceId deveria ser um UUID canônico, veio '$id'")
    }

    @Test
    fun deviceIdIsStableAcrossManyReads() {
        val settings = PreferencesSettings(node)
        val first = settings.deviceId
        repeat(5) { assertEquals(first, settings.deviceId) }
        assertEquals(first, node.get("deviceId", null))
    }

    @Test
    fun canonicalStoredDeviceIdIsKeptUntouched() {
        val stored = "123e4567-e89b-12d3-a456-426614174000"
        node.put("deviceId", stored)
        assertEquals(stored, PreferencesSettings(node).deviceId)
    }

    @Test
    fun writingOtherKeysDoesNotCreateDeviceId() {
        val settings = PreferencesSettings(node)
        settings.lastLogin = "maria"
        assertEquals(setOf("lastLogin"), node.keys().toSet())
    }

    @Test
    fun removingAbsentKeyIsHarmlessAndDeviceIdSurvivesNulls() {
        val settings = PreferencesSettings(node)
        val id = settings.deviceId
        settings.serverUrl = null
        settings.lastLogin = null
        assertNull(settings.serverUrl)
        assertEquals(id, settings.deviceId)
    }

    @Test
    fun valuesRoundTripAndOverwriteWithUnicode() {
        val settings = PreferencesSettings(node)
        settings.lastLogin = "joão"
        settings.lastLogin = "recepção ção"
        settings.serverUrl = "http://h:1"
        assertEquals("recepção ção", PreferencesSettings(node).lastLogin)
        assertEquals("http://h:1", PreferencesSettings(node).serverUrl)
    }

    @Test
    fun emptyStringIsStoredAsEmptyNotNull() {
        val settings = PreferencesSettings(node)
        settings.lastLogin = ""
        assertEquals("", settings.lastLogin)
    }
}
