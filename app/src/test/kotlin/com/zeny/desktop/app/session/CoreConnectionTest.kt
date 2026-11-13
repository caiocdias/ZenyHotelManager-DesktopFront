package com.zeny.desktop.app.session

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.realtime.RealtimeStatus
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class CoreConnectionTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    @Test
    fun roomsSourceUsesCoreRoomsAndRealtime() =
        runTest {
            val core = ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemorySettings())
            CoreConnection(core).use { connection ->
                val source = assertNotNull(connection.roomsSource())

                assertSame(core.realtime.status, source.realtimeStatus)
                assertEquals(RealtimeStatus.Idle, source.realtimeStatus.value)
                // O id é validado pela fachada do `core`, antes de ir à rede.
                assertFailsWith<IllegalArgumentException> { source.room("não-é-uuid") }
                assertFailsWith<IllegalArgumentException> { source.status("não-é-uuid") }
            }
        }
}
