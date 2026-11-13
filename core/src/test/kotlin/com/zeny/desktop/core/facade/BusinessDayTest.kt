package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** O dia operacional com o fuso do hotel (`HotelFacade.businessDay`), contra o `MockEngine`. */
class BusinessDayTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private fun core(body: String): ZenyCore =
        ZenyCore(
            checkNotNull(ServerUrl.parse("http://servidor.teste")),
            MemorySettings(),
            MockEngine { request ->
                requests += request
                json(body)
            },
        )

    private fun body(
        date: String,
        zone: String,
    ) = """{"businessDate":"$date","timezone":"$zone"}"""

    @Test
    fun businessDayReadsDateAndZone() =
        runTest {
            core(body("2026-11-13", "America/Sao_Paulo")).use { core ->
                val day = core.hotel.businessDay()
                assertEquals("2026-11-13", day.date.toApi())
                assertEquals(ZoneId.of("America/Sao_Paulo"), day.zone)
            }
            assertEquals(1, requests.size)
        }

    @Test
    fun businessDayInvalidZoneIsNull() =
        runTest {
            for (zone in listOf("Mars/Base", "", "   ")) {
                core(body("2026-11-13", zone)).use { core ->
                    val day = core.hotel.businessDay()
                    assertEquals("2026-11-13", day.date.toApi(), "zona '$zone'")
                    assertNull(day.zone, "zona '$zone'")
                }
            }
        }

    @Test
    fun businessDayBadDateIsIncompatible() =
        runTest {
            core(body("13/11/2026", "America/Sao_Paulo")).use { core ->
                val error = assertFailsWith<ApiError.IncompatibleResponse> { core.hotel.businessDay() }
                assertNull(generateSequence(error as Throwable) { it.cause }.firstOrNull { "13/11/2026" in "$it" })
            }
        }
}
