package com.zeny.desktop.core

import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@OptIn(RawSdkAccess::class)
class ApisTest {
    private class MemoryOnlySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    @Test
    fun apisExposeRatesAndGuests() {
        val engine = MockEngine { respond("", HttpStatusCode.NoContent) }
        ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemoryOnlySettings(), engine).use { core ->
            val apis = core.apis
            assertNotNull(apis.rates)
            assertNotNull(apis.guests)
            assertSame(apis.rates, apis.rates)
            assertSame(apis.guests, apis.guests)
        }
    }
}
