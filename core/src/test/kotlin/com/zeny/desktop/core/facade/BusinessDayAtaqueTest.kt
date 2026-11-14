package com.zeny.desktop.core.facade

import com.zeny.desktop.core.FakeZenyServer
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.session.SessionState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val SECRET_DATE = "SEGREDO-13/11"

/** Ataque a `HotelFacade.businessDay()`: fusos estranhos, data ilegível sem eco, campos ausentes e `401`. */
class BusinessDayAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private val serverUrl = checkNotNull(ServerUrl.parse("http://servidor.teste"))

    private fun core(body: String): ZenyCore = ZenyCore(serverUrl, MemorySettings(), MockEngine { json(body) })

    private fun body(
        date: String,
        zone: String,
    ) = """{"businessDate":"$date","timezone":"$zone"}"""

    private fun zoneOf(zone: String): ZoneId? = core(body("2026-11-13", zone)).use { it.zoneBlocking() }

    private fun ZenyCore.zoneBlocking(): ZoneId? = kotlinx.coroutines.runBlocking { hotel.businessDay().zone }

    @Test
    fun zoneWithSurroundingSpacesIsNullNotAnException() {
        // Contrato: o servidor manda o id exato; o que não é um id de fuso válido vira null (nunca o da JVM).
        assertNull(zoneOf(" America/Sao_Paulo"))
        assertNull(zoneOf("America/Sao_Paulo "))
    }

    @Test
    fun lowercaseZoneIdIsNullBecauseIdsAreCaseSensitive() {
        assertNull(zoneOf("america/sao_paulo"))
    }

    @Test
    fun offsetStyleZonesAreValidJavaZonesAndKeepTheirOffset() {
        assertEquals(ZoneOffset.ofHours(3), zoneOf("+03:00"))
        assertEquals(ZoneId.of("GMT-3"), zoneOf("GMT-3"))
        assertEquals(ZoneOffset.UTC, zoneOf("Z"))
    }

    @Test
    fun otherGarbageZonesAreNull() {
        for (zone in listOf("Mars/Base", "America/", "/", "UTC+99", "+25:00", "null", "<x>")) {
            assertNull(zoneOf(zone), "fuso '$zone'")
        }
    }

    @Test
    fun invalidZoneNeverFallsBackToTheJvmZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertNull(zoneOf("Mars/Base"))
            assertNull(zoneOf(""))
            assertNotEquals(ZoneId.of("Asia/Tokyo"), zoneOf("America/Sao_Paulo"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun missingOrNullTimezoneFieldIsIncompatibleBecauseTheContractRequiresIt() =
        runTest {
            core("""{"businessDate":"2026-11-13"}""").use {
                assertFailsWith<ApiError.IncompatibleResponse> { it.hotel.businessDay() }
            }
            core("""{"businessDate":"2026-11-13","timezone":null}""").use {
                assertFailsWith<ApiError.IncompatibleResponse> { it.hotel.businessDay() }
            }
        }

    @Test
    fun unreadableDatesAreIncompatibleWithoutEchoingTheText() =
        runTest {
            val dates = listOf(SECRET_DATE, "", "2026-13-40", "2026-02-30", " 2026-11-13", "2026-11-13T00:00:00Z")
            for (date in dates) {
                core(body(date, "America/Sao_Paulo")).use { core ->
                    val error =
                        assertFailsWith<ApiError.IncompatibleResponse>("data '$date'") {
                            core.hotel.businessDay()
                        }
                    val echoes = generateSequence(error as Throwable) { it.cause }.any { SECRET_DATE in "$it" }
                    assertFalse(echoes, "a causa ecoa a data")
                    assertFalse(SECRET_DATE in error.userMessage)
                }
            }
        }

    @Test
    fun malformedBodyIsIncompatibleWithoutEchoingTheBody() =
        runTest {
            core("""<html>$SECRET_DATE</html>""").use { core ->
                val error = assertFailsWith<ApiError.IncompatibleResponse> { core.hotel.businessDay() }
                assertFalse(generateSequence(error as Throwable) { it.cause }.any { SECRET_DATE in "$it" })
            }
        }

    @Test
    fun businessDateAndBusinessDayAgreeOnTheSameBody() =
        runTest {
            core(body("2026-11-13", "America/Sao_Paulo")).use { core ->
                assertEquals(core.hotel.businessDate(), core.hotel.businessDay().date)
            }
        }

    @Test
    fun revokedSessionIsSessionEnded() =
        runTest {
            val server = FakeZenyServer()
            server.routes = { json(body("2026-11-13", "America/Sao_Paulo")) }
            ZenyCore(serverUrl, MemorySettings(), server.engine).use { core ->
                core.session.login("maria", "segredo")
                val day = core.hotel.businessDay()
                assertEquals("2026-11-13", day.date.toApi())
                server.rejectAllWith = "SESSION_REVOKED"
                val ended = assertFailsWith<ApiError.SessionEnded> { core.hotel.businessDay() }
                assertEquals("SESSION_REVOKED", ended.code)
                assertIs<SessionState.Ended>(core.session.state.value)
            }
        }

    @Test
    fun forbiddenIsProblem() =
        runTest {
            val server = FakeZenyServer()
            server.routes = { problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED") }
            ZenyCore(serverUrl, MemorySettings(), server.engine).use { core ->
                core.session.login("maria", "segredo")
                assertEquals(403, assertFailsWith<ApiError.Problem> { core.hotel.businessDay() }.status)
            }
        }
}
