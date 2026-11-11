package com.zeny.desktop.core.config

import java.util.UUID
import java.util.prefs.Preferences

/** Preferências locais em disco: só o endereço do servidor, o último login e o id do dispositivo. Nunca tokens. */
interface LocalSettings {
    var serverUrl: String?
    var lastLogin: String?
    val deviceId: String
}

class PreferencesSettings(
    private val node: Preferences = Preferences.userRoot().node("com/zeny/desktop"),
) : LocalSettings {
    override var serverUrl: String?
        get() = node.get(KEY_SERVER_URL, null)
        set(value) = write(KEY_SERVER_URL, value)

    override var lastLogin: String?
        get() = node.get(KEY_LAST_LOGIN, null)
        set(value) = write(KEY_LAST_LOGIN, value)

    override val deviceId: String
        get() {
            val stored = node.get(KEY_DEVICE_ID, null)
            if (stored != null && isUuid(stored)) return stored
            val fresh = UUID.randomUUID().toString()
            write(KEY_DEVICE_ID, fresh)
            return fresh
        }

    private fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) node.remove(key) else node.put(key, value)
        node.flush()
    }

    private fun isUuid(text: String): Boolean =
        try {
            UUID.fromString(text).toString().equals(text, ignoreCase = true)
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            false
        }

    private companion object {
        const val KEY_SERVER_URL = "serverUrl"
        const val KEY_LAST_LOGIN = "lastLogin"
        const val KEY_DEVICE_ID = "deviceId"
    }
}
