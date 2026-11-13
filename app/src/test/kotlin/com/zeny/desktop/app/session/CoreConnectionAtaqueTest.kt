package com.zeny.desktop.app.session

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** O `roomsSource()` do [CoreConnection] repassa ao `core`: as regras da assinatura são as do `RealtimeClient`. */
class CoreConnectionAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    private fun connection() =
        CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemorySettings()))

    @Test
    fun eventsDelegateToRealtimeSubscriptionRules() {
        connection().use { connection ->
            val source = assertNotNull(connection.roomsSource())

            assertFailsWith<IllegalArgumentException> { source.events(emptySet()) }
            assertFailsWith<IllegalArgumentException> { source.events(setOf("rooms,frontdesk")) }
            assertFailsWith<IllegalArgumentException> { source.events(setOf("")) }
        }
    }
}
