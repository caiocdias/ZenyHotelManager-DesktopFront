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

private const val FROM = "2026-11-16"
private const val ARRIVAL = "2026-11-20"
private const val DEPARTURE = "2026-11-22"

/** A leitura da disponibilidade e a cotação com recusas contra o `MockEngine`; só dados fictícios. */
class AvailabilityFacadeTest {
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

    private val quote =
        """{"adults":2,"arrival":"$ARRIVAL","channel":"DIRECT","childAges":[],"departure":"$DEPARTURE","nights":2,"offers":[]}"""

    @Test
    fun availabilitySendsPeriodOnly() =
        runTest {
            core { json(calendar(FROM, "2026-12-15")) }.use { core ->
                val result = core.reservations.availability(date(FROM), date("2026-12-15"))
                assertEquals(FROM, result.from)
            }
            val url = requests.single().url
            assertEquals("/api/v1/availability", url.encodedPath)
            assertEquals("from=$FROM&to=2026-12-15", url.encodedQuery)
        }

    @Test
    fun availabilityPeriodBoundary() =
        runTest {
            val last = date(FROM).value.plusDays(365)
            core { json(calendar(FROM, last.toString())) }.use { core ->
                core.reservations.availability(date(FROM), BusinessDate(last))
                core.reservations.availability(date(FROM), date(FROM))
                assertEquals(2, requests.size)
                val tooLong = BusinessDate(last.plusDays(1))
                val before = date("2026-11-15")
                val messages =
                    listOf(date(FROM) to tooLong, date(FROM) to before).map { (a, b) ->
                        assertFailsWith<IllegalArgumentException> { core.reservations.availability(a, b) }.message
                    }
                assertTrue(messages.all { it == "período de disponibilidade inválido" }, messages.toString())
            }
            assertEquals(2, requests.size)
        }

    @Test
    fun availabilityMapsProblem() =
        runTest {
            core { problem(HttpStatusCode.BadRequest, "INVENTORY_PERIOD_TOO_LONG") }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.availability(date(FROM), date("2026-12-15"))
                    }
                assertEquals("INVENTORY_PERIOD_TOO_LONG", error.code)
                assertEquals(ErrorCatalog.message("INVENTORY_PERIOD_TOO_LONG"), error.message)
            }
        }

    @Test
    fun quoteWithUnavailableSendsFlag() =
        runTest {
            core { json(quote) }.use { core ->
                core.reservations.quoteStay(date(ARRIVAL), date(DEPARTURE), 2, emptyList(), includeUnavailable = true)
            }
            assertEquals("true", requests.single().url.parameters["includeUnavailable"])
        }

    @Test
    fun quoteWithoutFlagOmitsIt() =
        runTest {
            core { json(quote) }.use { core ->
                core.reservations.quoteStay(date(ARRIVAL), date(DEPARTURE), 2, emptyList())
                core.reservations.quoteStay(date(ARRIVAL), date(DEPARTURE), 2, emptyList(), includeUnavailable = false)
            }
            assertEquals(2, requests.size)
            for (request in requests) {
                assertFalse(request.url.parameters.contains("includeUnavailable"), request.url.toString())
            }
        }
}
