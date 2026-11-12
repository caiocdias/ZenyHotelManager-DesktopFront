package com.zeny.desktop.core.config

import java.util.UUID
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceIdWriteFailureTest {
    /** Preferências que aceitam ler e gravar em memória, mas falham ao ir para o disco (`flush`). */
    private class FailingNode : AbstractPreferences(null, "") {
        private val data = mutableMapOf<String, String>()

        override fun putSpi(
            key: String,
            value: String,
        ) {
            data[key] = value
        }

        override fun getSpi(key: String): String? = data[key]

        override fun removeSpi(key: String) {
            data.remove(key)
        }

        override fun removeNodeSpi() = Unit

        override fun keysSpi(): Array<String> = data.keys.toTypedArray()

        override fun childrenNamesSpi(): Array<String> = emptyArray()

        override fun childSpi(name: String): AbstractPreferences = throw UnsupportedOperationException()

        override fun syncSpi() = Unit

        override fun flushSpi(): Unit = throw BackingStoreException("disco cheio")
    }

    @Test
    fun deviceIdWriteFailureStillReturnsTheGeneratedId() {
        val id = PreferencesSettings(FailingNode()).deviceId
        assertEquals(id, UUID.fromString(id).toString())
    }
}
