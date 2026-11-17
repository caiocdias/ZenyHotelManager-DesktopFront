package com.zeny.desktop.app.reservations

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.CancelInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val BOOKING = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val GUEST = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val DOC = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val DATE_BODY = """{"businessDate":"2026-11-16","timezone":"America/Sao_Paulo"}"""

/** Os adaptadores de cancelamento, reativação e pendências do `CoreConnection` contra um servidor HTTP local. */
class LifecycleSourcesTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private class Seen(
        val method: String,
        val path: String,
        val query: String?,
        val idempotencyKey: String?,
        val body: String,
    )

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

    private fun guestJson() =
        """{"createdAt":"2026-11-12T10:00:00Z","documents":[{"createdAt":"2026-11-12T10:00:00Z","id":"$DOC",
        "issuingCountry":"BR","maskedNumber":"***.456.789-**","type":"CPF"}],"fullName":"Fulana Fictícia de Teste",
        "hasSensitiveData":false,"id":"$GUEST","preferences":{},"socialName":"Fulana Social","status":"ACTIVE",
        "updatedAt":"2026-11-12T10:00:00Z","vip":false}"""

    private fun withConnection(block: suspend (CoreConnection) -> Unit) =
        runTest {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                seen +=
                    Seen(
                        exchange.requestMethod,
                        exchange.requestURI.path,
                        exchange.requestURI.rawQuery,
                        exchange.requestHeaders.getFirst("Idempotency-Key"),
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

            path == "/api/v1/reservations/$RES" ||
                path == "/api/v1/reservations/$RES/cancel" ||
                path == "/api/v1/reservations/$RES/reactivate" -> {
                reply(200, detailJson())
            }

            path in
                listOf(
                    "/api/v1/reservations/pending-guarantees",
                    "/api/v1/reservations/deposit-shortfalls",
                    "/api/v1/reservations/expected-no-shows",
                    "/api/v1/room-assignments/reallocation-required",
                ) -> {
                reply(200, """{"items":[]}""")
            }

            path == "/api/v1/guests/$GUEST" -> {
                reply(200, guestJson())
            }

            else -> {
                reply(404, """{"code":"NOT_FOUND","detail":"x"}""")
            }
        }
    }

    private fun HttpExchange.reply(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @Test
    fun sourcesAreTheSameInstanceOnEveryCall() =
        withConnection { connection ->
            assertSame(connection.reservationCancelSource(), connection.reservationCancelSource())
            assertSame(connection.reservationReactivateSource(), connection.reservationReactivateSource())
            assertSame(connection.pendingSource(), connection.pendingSource())
        }

    @Test
    fun cancelSourceReadsTheReservationAndCancelsWithTheIdempotencyKey() =
        withConnection { connection ->
            val source = assertNotNull(connection.reservationCancelSource())

            assertEquals("R-1", source.reservation(RES).reservation.code)
            val input = CancelInput("GUEST_REQUEST", true, "pedido")
            val response = source.cancelReservation(RES, input, Intent()).run(null)

            assertEquals("R-1", response.value.reservation.code)
            val (read, cancel) = seen.toList()
            assertEquals("GET", read.method)
            assertEquals("/api/v1/reservations/$RES", read.path)
            assertEquals("POST", cancel.method)
            assertEquals("/api/v1/reservations/$RES/cancel", cancel.path)
            assertTrue(!cancel.idempotencyKey.isNullOrBlank())
            assertTrue("\"reason\":\"GUEST_REQUEST\"" in cancel.body, cancel.body)
            assertTrue("\"waivePenalty\":true" in cancel.body, cancel.body)
        }

    @Test
    fun reactivateSourcePostsWithoutBodyAndWithTheIdempotencyKey() =
        withConnection { connection ->
            val source = assertNotNull(connection.reservationReactivateSource())

            val response = source.reactivateReservation(RES, Intent()).run(null)

            assertEquals("R-1", response.value.reservation.code)
            val request = seen.single()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/reservations/$RES/reactivate", request.path)
            assertTrue(!request.idempotencyKey.isNullOrBlank())
        }

    @Test
    fun pendingSourceReadsTheFourListsTheBusinessDayAndTheHolder() =
        withConnection { connection ->
            val source = assertNotNull(connection.pendingSource())
            val until = Instant.parse("2028-11-15T12:00:00Z")

            assertEquals(BusinessDate.parse("2026-11-16"), source.businessDay().date)
            assertEquals(emptyList(), source.pendingGuarantees(until, "c1", 25).items)
            assertEquals(emptyList(), source.depositShortfalls())
            assertEquals(emptyList(), source.expectedNoShows("c2", 25).items)
            assertEquals(emptyList(), source.reallocationRequired("c3", 25).items)
            assertEquals(HolderSummary("Fulana Social", "CPF ***.456.789-**"), source.holder(GUEST))

            assertEquals(
                listOf(
                    "/api/v1/hotel/business-date",
                    "/api/v1/reservations/pending-guarantees",
                    "/api/v1/reservations/deposit-shortfalls",
                    "/api/v1/reservations/expected-no-shows",
                    "/api/v1/room-assignments/reallocation-required",
                    "/api/v1/guests/$GUEST",
                ),
                seen.map { it.path },
            )
            assertTrue(seen.all { it.method == "GET" })
            val guarantees = checkNotNull(seen[1].query)
            listOf("to=2028-11-15T12", "cursor=c1", "limit=25").forEach { assertTrue(it in guarantees, guarantees) }
            assertTrue("cursor=c2" in checkNotNull(seen[3].query))
            assertTrue("cursor=c3" in checkNotNull(seen[4].query))
            assertNull(seen[2].query)
        }
}
