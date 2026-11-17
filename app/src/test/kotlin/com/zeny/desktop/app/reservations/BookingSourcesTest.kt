package com.zeny.desktop.app.reservations

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.BookingInput
import com.zeny.desktop.core.facade.ReservationChangeInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val BOOKING = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val GUEST = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val DATE_BODY = """{"businessDate":"2026-11-16","timezone":"America/Sao_Paulo"}"""
private val PDF_BYTES = "%PDF-1.7\nconteudo\n%%EOF".encodeToByteArray()

/** Os adaptadores de reserva do `CoreConnection` contra um servidor HTTP local de verdade. */
class BookingSourcesTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private class Seen(
        val method: String,
        val path: String,
        val query: String?,
        private val headers: Map<String, String?>,
        val body: String,
    ) {
        val ifMatch get() = headers["If-Match"]
        val idempotencyKey get() = headers["Idempotency-Key"]
        val accept get() = headers["Accept"]
    }

    private val seen = CopyOnWriteArrayList<Seen>()

    private fun reservationJson() =
        """{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-12-10","bookingId":"$BOOKING",
        "cancellationTerms":{"deadlineHours":24,"name":"Flex","penaltyType":"NIGHTS","policyId":"$BOOKING","version":1},
        "childAges":[],"code":"R-1","createdAt":"2026-11-16T10:00:00Z","dayUse":false,"departure":"2026-12-12",
        "folioId":"$BOOKING",
        "guaranteeTerms":{"name":"Sem garantia","policyId":"$BOOKING","type":"NONE","version":1},
        "guests":[],"id":"$RES","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL",
        "ratePlanId":"$PLAN","roomTotal":"361.00","roomTypeId":"$TYPE","segment":"LEISURE","status":"CONFIRMED",
        "total":"361.00","version":1}"""

    private fun detailJson() =
        """{"bookingCode":"B-1","holderGuestId":"$GUEST","reservation":${reservationJson()},"restrictionAlerts":[]}"""

    private fun bookingJson() =
        """{"channel":"DIRECT","code":"B-1","createdAt":"2026-11-16T10:00:00Z","holderGuestId":"$GUEST",
        "id":"$BOOKING","origin":"FRONT_DESK","reservations":[${reservationJson()}],"restrictionAlerts":[],
        "version":1}"""

    private fun withConnection(block: suspend (CoreConnection) -> Unit) =
        runTest {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                val headers = exchange.requestHeaders
                seen +=
                    Seen(
                        exchange.requestMethod,
                        exchange.requestURI.path,
                        exchange.requestURI.rawQuery,
                        listOf("If-Match", "Idempotency-Key", "Accept").associateWith { headers.getFirst(it) },
                        body,
                    )
                exchange.answer()
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}"
                CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { block(it) }
            } finally {
                server.stop(0)
            }
        }

    private fun HttpExchange.answer() {
        val path = requestURI.path
        when {
            path == "/api/v1/hotel/business-date" -> {
                reply(200, DATE_BODY)
            }

            path == "/api/v1/bookings" && requestMethod == "POST" -> {
                reply(201, bookingJson())
            }

            path == "/api/v1/quotes" -> {
                reply(
                    200,
                    """{"adults":2,"arrival":"2026-12-10","channel":"DIRECT","childAges":[],
                    "departure":"2026-12-12","nights":2,"offers":[]}""",
                )
            }

            path == "/api/v1/reservations/$RES" && requestMethod == "GET" -> {
                responseHeaders.add("ETag", "\"v7\"")
                reply(200, detailJson())
            }

            path == "/api/v1/reservations/$RES" && requestMethod == "PUT" -> {
                responseHeaders.add("ETag", "\"v8\"")
                reply(200, detailJson())
            }

            path == "/api/v1/reservations/$RES/confirm" -> {
                reply(200, detailJson())
            }

            path == "/api/v1/reservations/$RES/confirmation/pdf" -> {
                responseHeaders.add("Content-Disposition", "attachment; filename=\"confirmacao-R-1.pdf\"")
                reply(200, PDF_BYTES, "application/pdf")
            }

            else -> {
                reply(404, """{"code":"NOT_FOUND","detail":"x"}""".toByteArray(), "application/json")
            }
        }
    }

    private fun HttpExchange.reply(
        status: Int,
        body: String,
    ) = reply(status, body.toByteArray(), "application/json")

    private fun HttpExchange.reply(
        status: Int,
        bytes: ByteArray,
        contentType: String,
    ) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun date(text: String) = BusinessDate.parse(text)

    private fun bookingInput() =
        BookingInput(
            holderGuestId = GUEST,
            origin = "FRONT_DESK",
            arrival = date("2026-12-10"),
            departure = date("2026-12-12"),
            adults = 2,
            childAges = emptyList(),
            roomTypeId = TYPE,
            ratePlanId = PLAN,
            segment = "LEISURE",
            specialRequests = null,
            nightlyRates = emptyList(),
        )

    private fun changeInput() =
        ReservationChangeInput(
            arrival = date("2026-12-10"),
            departure = date("2026-12-14"),
            adults = 2,
            childAges = emptyList(),
            roomTypeId = TYPE,
            ratePlanId = PLAN,
            segment = "LEISURE",
            specialRequests = null,
            nightlyRates = emptyList(),
        )

    @Test
    fun sourcesAreTheSameInstanceOnEveryCall() =
        withConnection { connection ->
            assertSame(connection.newReservationSource(), connection.newReservationSource())
            assertSame(connection.reservationChangeSource(), connection.reservationChangeSource())
            assertSame(connection.reservationConfirmSource(), connection.reservationConfirmSource())
            assertSame(connection.reservationConfirmations(), connection.reservationConfirmations())
        }

    @Test
    fun createBookingPostsToBookingsWithTheIdempotencyKey() =
        withConnection { connection ->
            val source = assertNotNull(connection.newReservationSource())
            val intent = Intent()

            val response = source.createBooking(bookingInput(), intent).run(null)

            assertEquals("B-1", response.value.code)
            val request = seen.single()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/bookings", request.path)
            assertTrue(!request.idempotencyKey.isNullOrBlank())
            assertTrue("\"roomTypeId\":\"$TYPE\"" in request.body, request.body)
        }

    @Test
    fun changeReservationPutsWithIfMatchAndNoIdempotencyKey() =
        withConnection { connection ->
            val source = assertNotNull(connection.reservationChangeSource())

            val response = source.changeReservation(RES, changeInput(), "\"v7\"").run(null)

            assertEquals("R-1", response.value.reservation.code)
            assertEquals("\"v8\"", response.etag)
            val request = seen.single()
            assertEquals("PUT", request.method)
            assertEquals("/api/v1/reservations/$RES", request.path)
            assertEquals("\"v7\"", request.ifMatch)
            assertNull(request.idempotencyKey)
            assertTrue("\"departure\":\"2026-12-14\"" in request.body, request.body)
        }

    @Test
    fun changeSourceReadsBusinessDayVersionAndQuoteWithoutUnavailableOffers() =
        withConnection { connection ->
            val source = assertNotNull(connection.reservationChangeSource())

            assertEquals(date("2026-11-16"), source.businessDay().date)
            val version = source.reservationVersion(RES)
            val quote = source.quote(date("2026-12-10"), date("2026-12-12"), 2, emptyList())

            assertEquals("\"v7\"", version.etag)
            assertEquals(2, quote.nights)
            assertEquals(
                listOf("/api/v1/hotel/business-date", "/api/v1/reservations/$RES", "/api/v1/quotes"),
                seen.map { it.path },
            )
            val query = checkNotNull(seen.last().query)
            assertTrue("arrival=2026-12-10" in query, query)
            assertFalse("includeUnavailable=true" in query, query)
        }

    @Test
    fun confirmSourceReadsTheReservationAndConfirmsWithTheGuarantee() =
        withConnection { connection ->
            val source = assertNotNull(connection.reservationConfirmSource())

            assertEquals("R-1", source.reservation(RES).reservation.code)
            val confirmed = source.confirmReservation(RES, "CARD", Intent())

            assertEquals("R-1", confirmed.reservation.code)
            val (read, confirm) = seen.toList()
            assertEquals("GET", read.method)
            assertEquals("/api/v1/reservations/$RES", read.path)
            assertEquals("POST", confirm.method)
            assertEquals("/api/v1/reservations/$RES/confirm", confirm.path)
            assertTrue(!confirm.idempotencyKey.isNullOrBlank())
            assertTrue("\"guarantee\":\"CARD\"" in confirm.body, confirm.body)
        }

    @Test
    fun confirmationsFetchTheReservationPdf() =
        withConnection { connection ->
            val document = assertNotNull(connection.reservationConfirmations()).confirmation(RES)

            assertContentEquals(PDF_BYTES, document.bytes)
            assertEquals("confirmacao-R-1.pdf", document.fileName)
            val request = seen.single()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/reservations/$RES/confirmation/pdf", request.path)
            assertTrue("application/pdf" in checkNotNull(request.accept), request.accept)
        }
}
