package com.zeny.desktop.app.reservations

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val USER_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val SESSION_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val DATE_BODY = """{"businessDate":"2026-11-16","timezone":"America/Sao_Paulo"}"""

/** O adaptador `CoreConnection.availabilitySource()` contra um servidor HTTP local de verdade, e o falso da porta. */
class AvailabilitySourceTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private class Seen(
        val path: String,
        val query: String?,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    private fun withSource(block: suspend (AvailabilitySource, CoreConnection) -> Unit) =
        runTest {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                exchange.requestBody.readBytes()
                seen += Seen(exchange.requestURI.path, exchange.requestURI.rawQuery)
                exchange.reply(
                    when (exchange.requestURI.path) {
                        "/api/v1/hotel/business-date" -> {
                            DATE_BODY
                        }

                        "/api/v1/auth/me" -> {
                            me()
                        }

                        "/api/v1/availability" -> {
                            """{"from":"2026-11-16","to":"2026-11-22","roomTypes":[]}"""
                        }

                        "/api/v1/quotes" -> {
                            """{"adults":2,"arrival":"2026-11-20","channel":"DIRECT","childAges":[5],
                            "departure":"2026-11-22","nights":2,"offers":[]}"""
                        }

                        else -> {
                            null
                        }
                    },
                )
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}"
                CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                    block(assertNotNull(connection.availabilitySource()), connection)
                }
            } finally {
                server.stop(0)
            }
        }

    private fun HttpExchange.reply(body: String?) {
        val bytes = (body ?: """{"code":"NOT_FOUND","detail":"x"}""").toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(if (body == null) 404 else 200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun me() =
        """{"login":"maria.silva","name":"Maria Silva","permissions":[
        {"code":"reservations.reservations.read","limits":[],"scope":"ALL"},
        {"code":"reservations.reservations.create","limits":[],"scope":"ALL"}],"roles":[],
        "sessionId":"$SESSION_ID","userId":"$USER_ID"}"""

    private fun date(text: String) = BusinessDate.parse(text)

    @Test
    fun sourceIsTheSameInstanceOnEveryCall() =
        withSource { source, connection -> assertSame(source, connection.availabilitySource()) }

    @Test
    fun businessDayReadsTheHotelBusinessDate() =
        withSource { source, _ ->
            assertEquals(date("2026-11-16"), source.businessDay().date)
            assertEquals("/api/v1/hotel/business-date", seen.single().path)
        }

    @Test
    fun availabilityReadsTheCalendarForThePeriod() =
        withSource { source, _ ->
            assertEquals("2026-11-22", source.availability(date("2026-11-16"), date("2026-11-22")).to)
            val request = seen.single()
            assertEquals("/api/v1/availability", request.path)
            assertEquals("from=2026-11-16&to=2026-11-22", request.query)
        }

    @Test
    fun quoteAlwaysAsksForUnavailableOffers() =
        withSource { source, _ ->
            val result = source.quote(date("2026-11-20"), date("2026-11-22"), 2, listOf(5))

            assertEquals(2, result.nights)
            val request = seen.single()
            assertEquals("/api/v1/quotes", request.path)
            val query = checkNotNull(request.query)
            assertTrue("includeUnavailable=true" in query, query)
            assertTrue("arrival=2026-11-20" in query && "adults=2" in query, query)
        }

    @Test
    fun invalidInputIsRejectedByTheFacadeBeforeAnyRequest() =
        withSource { source, _ ->
            assertFailsWith<IllegalArgumentException> { source.availability(date("2026-11-16"), date("2028-11-16")) }
            assertEquals(emptyList(), seen.map { it.path })
        }

    // O falso da porta: o que as Tasks 3 a 7 usam.

    @Test
    fun fakeRecordsCallsAndReturnsTheCurrentValues() =
        runTest {
            val fake = FakeAvailabilitySource()

            fake.availability(date("2026-11-16"), date("2026-11-18"))
            fake.quote(date("2026-11-20"), date("2026-11-22"), 2, listOf(4))
            fake.businessDay()

            assertEquals(1, fake.businessDayCalls)
            assertEquals(listOf(AvailabilityCall(date("2026-11-16"), date("2026-11-18"))), fake.availabilityCalls)
            assertEquals(
                listOf(QuoteCall(date("2026-11-20"), date("2026-11-22"), 2, listOf(4))),
                fake.quoteCalls,
            )
        }

    /** Substitui o ataque do testador feito sobre a porta `permissions()` do falso, que saiu (ADR 0013). */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun heldCallsReturnTheValueAtReleaseTimeAndGateHoldsWithoutConsuming() =
        runTest {
            val fake = FakeAvailabilitySource()
            val before = BusinessDay(date("2026-11-16"), null)
            val after = BusinessDay(date("2026-11-17"), null)
            fake.businessDay = before
            val gate = fake.hold(FakeAvailabilitySource.Op.BUSINESS_DAY)
            val results = mutableListOf<BusinessDay>()
            val first = launch { results += fake.businessDay() }
            val second = launch { results += fake.businessDay() }
            runCurrent()
            assertEquals(2, fake.businessDayCalls)
            assertTrue(results.isEmpty())

            fake.businessDay = after
            gate.complete(Unit)
            first.join()
            second.join()

            assertEquals(listOf(after, after), results)
            assertEquals(after, fake.businessDay())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fakeHoldsAnOperationUntilItsGateIsReleased() =
        runTest {
            val fake = FakeAvailabilitySource()
            val gate = fake.hold(FakeAvailabilitySource.Op.AVAILABILITY)
            var done = false
            val job =
                launch {
                    fake.availability(date("2026-11-16"), date("2026-11-18"))
                    done = true
                }
            runCurrent()

            assertEquals(1, fake.availabilityCalls.size)
            assertFalse(done)

            gate.complete(Unit)
            job.join()

            assertTrue(done)
        }

    @Test
    fun fakeFailsPerOperationWithTheScheduledError() =
        runTest {
            val fake = FakeAvailabilitySource()
            val error = ApiError.Unexpected(500)
            fake.failures[FakeAvailabilitySource.Op.QUOTE] = error

            assertSame(
                error,
                assertFailsWith<ApiError.Unexpected> {
                    fake.quote(date("2026-11-20"), date("2026-11-22"), 2, emptyList())
                },
            )
            assertEquals(1, fake.quoteCalls.size)
            // As outras operações seguem de pé.
            fake.availability(date("2026-11-16"), date("2026-11-18"))
        }
}
