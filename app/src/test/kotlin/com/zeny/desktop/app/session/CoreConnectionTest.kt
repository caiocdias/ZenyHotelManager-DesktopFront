package com.zeny.desktop.app.session

import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.CaptureInput
import com.zeny.desktop.core.facade.PreAuthorizationInput
import com.zeny.desktop.core.facade.RefundInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun paymentSourceIsWiredToThePaymentsFacade() =
        runTest {
            val core = ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemorySettings())
            CoreConnection(core).use { connection ->
                val source = assertNotNull(connection.paymentSource())

                // O id é validado pela fachada do `core`, antes de ir à rede.
                assertFailsWith<IllegalArgumentException> { source.payments("não-é-uuid") }
                assertFailsWith<IllegalArgumentException> {
                    source.refund("não-é-uuid", "não-é-uuid", RefundInput(Money.parse("1.00"), "motivo"), Intent())
                }
            }
        }

    @Test
    fun paymentSourceExposesThePreAuthorizationOperations() =
        runTest {
            val core = ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemorySettings())
            CoreConnection(core).use { connection ->
                val source = assertNotNull(connection.paymentSource())
                val register = PreAuthorizationInput(Money.parse("1.00"), Instant.EPOCH, "AUT1")
                val capture = CaptureInput(Money.parse("1.00"))

                // O id é validado pela fachada do `core`, antes de ir à rede.
                assertFailsWith<IllegalArgumentException> { source.preAuthorizations("não-é-uuid") }
                assertFailsWith<IllegalArgumentException> {
                    source.registerPreAuthorization("não-é-uuid", register, Intent())
                }
                assertFailsWith<IllegalArgumentException> {
                    source.capturePreAuthorization("não-é-uuid", "não-é-uuid", capture, Intent())
                }
                assertFailsWith<IllegalArgumentException> {
                    source.releasePreAuthorization("não-é-uuid", "não-é-uuid", Intent())
                }
            }
        }

    @Test
    fun checkOutSourceIsWiredToTheCheckOutFacade() =
        runTest {
            val core = ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemorySettings())
            CoreConnection(core).use { connection ->
                val source = assertNotNull(connection.checkOutSource())

                // O id é validado pela fachada do `core`, antes de ir à rede.
                assertFailsWith<IllegalArgumentException> { source.lateCheckOutQuote("não-é-uuid") }
                assertFailsWith<IllegalArgumentException> {
                    source.checkOut("não-é-uuid", Money.parse("10.00"), Intent())
                }
            }
        }

    @Test
    fun paymentSourceReadsTheBusinessDayFromTheHotel() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/hotel/business-date") { exchange ->
            val body = """{"businessDate":"2026-11-18","timezone":"America/Sao_Paulo"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            runTest {
                val url = checkNotNull(ServerUrl.parse("http://127.0.0.1:${server.address.port}"))
                CoreConnection(ZenyCore(url, MemorySettings())).use { connection ->
                    val day = assertNotNull(connection.paymentSource()).businessDay()
                    assertEquals(BusinessDate.parse("2026-11-18"), day.date)
                    assertEquals(ZoneId.of("America/Sao_Paulo"), day.zone)
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun folioCorrectionSourceIsTheFacade() {
        val folio = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
        val posting = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f04"
        val keys = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/folios/$folio/postings/$posting/remove-service-charge") { exchange ->
            keys += exchange.requestHeaders.getFirst("Idempotency-Key")
            val code = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f06"
            val body =
                (
                    """{"amount":"-5.00","businessDate":"2026-11-18","description":"Taxa","folioId":"$folio",""" +
                        """"id":"$posting","postedAt":"2026-11-18T10:00:00Z","quantity":1.0,"source":"MANUAL",""" +
                        """"transactionCodeId":"$code","type":"REVERSAL","unitPrice":"5.00"}"""
                ).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(201, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            runTest {
                val url = checkNotNull(ServerUrl.parse("http://127.0.0.1:${server.address.port}"))
                CoreConnection(ZenyCore(url, MemorySettings())).use { connection ->
                    val source = connection.folioCorrectionSource()
                    val done = source.removeServiceCharge(folio, posting, Intent())
                    assertEquals(posting, done.id.toString())
                    assertEquals(1, keys.size)
                    assertTrue(!keys.single().isNullOrBlank())
                    // O id é validado pela fachada do `core`, antes de ir à rede.
                    assertFailsWith<IllegalArgumentException> {
                        source.removeServiceCharge("não-é-uuid", posting, Intent())
                    }
                    assertEquals(1, keys.size)
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
