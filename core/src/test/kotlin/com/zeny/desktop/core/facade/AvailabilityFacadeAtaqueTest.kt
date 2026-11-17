package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ErrorCatalog
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.BusinessDate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ataque a disponibilidade e à cotação com recusas: bordas de calendário, query exata e sem validação na cotação. */
class AvailabilityFacadeAtaqueTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private fun core(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ZenyCore =
        ZenyCore(
            checkNotNull(ServerUrl.parse("http://servidor.teste")),
            MemorySettings(),
            MockEngine { request ->
                requests += request
                handler(request)
            },
        )

    private fun date(text: String) = BusinessDate.parse(text)

    private fun calendar(
        from: String,
        to: String,
    ) = """{"from":"$from","to":"$to","roomTypes":[]}"""

    private fun quote(
        arrival: String,
        departure: String,
    ) = """{"adults":2,"arrival":"$arrival","channel":"DIRECT","childAges":[],"departure":"$departure",
        "nights":2,"offers":[]}"""

    @Test
    fun leapYearFullSpanPassesAndOneMoreDayFails() =
        runTest {
            core { json(calendar("2028-01-01", "2028-12-31")) }.use { core ->
                core.reservations.availability(date("2028-01-01"), date("2028-12-31"))
                assertEquals(1, requests.size)
                assertFailsWith<IllegalArgumentException> {
                    core.reservations.availability(date("2028-01-01"), date("2029-01-01"))
                }
                assertEquals(1, requests.size)
            }
        }

    @Test
    fun spanAcrossYearEndWithTwoEndpointsPasses() =
        runTest {
            core { json(calendar("2026-12-31", "2027-12-31")) }.use { core ->
                // 2026-12-31 a 2027-12-31: 366 dias contando as duas pontas.
                core.reservations.availability(date("2026-12-31"), date("2027-12-31"))
                assertFailsWith<IllegalArgumentException> {
                    core.reservations.availability(date("2026-12-31"), date("2028-01-01"))
                }
            }
            assertEquals(1, requests.size)
            assertEquals("from=2026-12-31&to=2027-12-31", requests.single().url.encodedQuery)
        }

    @Test
    fun invalidPeriodMessageNeverEchoesDates() =
        runTest {
            core { json(calendar("2026-11-16", "2026-11-16")) }.use { core ->
                val error =
                    assertFailsWith<IllegalArgumentException> {
                        core.reservations.availability(date("2031-05-17"), date("2026-11-16"))
                    }
                val text = error.message.orEmpty()
                assertEquals("período de disponibilidade inválido", text)
                assertFalse(text.contains("2031") || text.contains("2026"), text)
            }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun availabilityMapsUnprocessableRefusalToCatalogText() =
        runTest {
            core { problem(HttpStatusCode.UnprocessableEntity, "INVENTORY_PERIOD_INVALID") }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.availability(date("2026-11-16"), date("2026-11-20"))
                    }
                assertEquals(ErrorCatalog.message("INVENTORY_PERIOD_INVALID"), error.message)
            }
        }

    @Test
    fun quoteQueryDiffersOnlyByTheFlag() =
        runTest {
            core { json(quote("2026-11-20", "2026-11-22")) }.use { core ->
                core.reservations.quoteStay(date("2026-11-20"), date("2026-11-22"), 2, listOf(5, 9))
                core.reservations.quoteStay(date("2026-11-20"), date("2026-11-22"), 2, listOf(5, 9), true)
            }
            val without = requests[0].url
            val with = requests[1].url
            assertEquals("/api/v1/quotes", without.encodedPath)
            assertEquals("/api/v1/quotes", with.encodedPath)
            assertFalse(without.encodedQuery.contains("includeUnavailable"), without.encodedQuery)
            assertEquals(listOf("true"), with.parameters.getAll("includeUnavailable"))
            assertEquals(without.parameters.names() + "includeUnavailable", with.parameters.names())
            for (name in without.parameters.names()) {
                assertEquals(without.parameters.getAll(name), with.parameters.getAll(name), name)
            }
        }

    @Test
    fun quoteStillSendsInvalidStayToServerEvenWithFlag() =
        runTest {
            core { problem(HttpStatusCode.UnprocessableEntity, "STAY_INVALID") }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.quoteStay(date("2026-11-22"), date("2026-11-20"), 0, emptyList(), true)
                    }
                assertEquals("STAY_INVALID", error.code)
            }
            assertEquals(1, requests.size)
            assertEquals("2026-11-22", requests.single().url.parameters["arrival"])
        }
}
