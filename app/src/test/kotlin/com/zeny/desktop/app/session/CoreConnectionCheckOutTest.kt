package com.zeny.desktop.app.session

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CheckOutResult
import com.zeny.desktop.core.facade.FolioDue
import com.zeny.desktop.core.facade.LateCheckOutQuote
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val RES = "20000000-0000-0000-0000-0000000000e1"
private const val FOLIO_A = "30000000-0000-0000-0000-0000000000a1"
private const val FOLIO_B = "30000000-0000-0000-0000-0000000000b2"
private const val ROOM = "40000000-0000-0000-0000-000000000001"

/** O que o servidor local recebeu: o método, o caminho, a chave, o tipo e o corpo. */
private data class Seen(
    val method: String,
    val path: String,
    val key: String?,
    val contentType: String?,
    val body: String,
)

/**
 * A `CoreConnection.checkOutSource()` sobre um servidor HTTP local (o motor de verdade, não o `MockEngine`): o pedido
 * que sai pela porta do `app` e o desfecho tipado que volta, inclusive o `409 FOLIO_BALANCE_NOT_ZERO`.
 */
class CoreConnectionCheckOutTest {
    private val seen = CopyOnWriteArrayList<Seen>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private val checkedOut =
        """{"reservation":{"adults":2,"arrival":"2026-11-17","bookingId":"$RES","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-19","folioId":"$FOLIO_A","guests":[],"id":"$RES","roomId":"$ROOM",
        "roomTypeId":"$ROOM","status":"CHECKED_OUT","version":4},
        "room":{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"101","occupancy":"VACANT",
        "roomId":"$ROOM","version":5},"closedFolios":[],"releasedPreAuthorizations":[],"stayCharges":[]}"""

    private val quote =
        """{"arrival":"2026-11-17","checkInTime":"14:00:00","checkOutTime":"12:00:00","departure":"2026-11-19",
        "earlyCheckIn":{"offered":false},"lateCheckOut":{"offered":true,"fee":"80.50","limitTime":"15:30:00"},
        "reservationId":"$RES","timezone":"America/Sao_Paulo"}"""

    private val balanceDue =
        """{"code":"FOLIO_BALANCE_NOT_ZERO","detail":"saldo","status":409,
        "folios":[{"folioId":"$FOLIO_A","number":"F-1","status":"OPEN","balance":"150.00"},
        {"folioId":"$FOLIO_B","number":"F-2","status":"OPEN","balance":"-20.00"}],"total":"130.00"}"""

    private fun HttpExchange.reply(
        status: Int,
        body: String,
        type: String,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun served(
        checkOutStatus: Int,
        checkOutBody: String,
        block: suspend (ServerConnection) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/reservations/$RES/check-out") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().decodeToString() }
            seen +=
                Seen(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Idempotency-Key"),
                    exchange.requestHeaders.getFirst("Content-Type"),
                    body,
                )
            val type = if (checkOutStatus >= 400) "application/problem+json" else "application/json"
            exchange.reply(checkOutStatus, checkOutBody, type)
        }
        server.createContext("/api/v1/reservations/$RES/early-late-quote") { it.reply(200, quote, "application/json") }
        server.start()
        try {
            runTest {
                val url = checkNotNull(ServerUrl.parse("http://127.0.0.1:${server.address.port}"))
                CoreConnection(ZenyCore(url, MemorySettings())).use { block(it) }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun checkOutWithoutLateGoesWithKeyAndNoBody() =
        served(200, checkedOut) { connection ->
            val source = assertNotNull(connection.checkOutSource())

            assertSame(CheckOutResult.Done, source.checkOut(RES, null, Intent()))

            val request = seen.single()
            assertEquals("POST", request.method)
            assertTrue(!request.key.isNullOrBlank(), "a Idempotency-Key vai no pedido")
            assertEquals("", request.body)
            assertNull(request.contentType, "sem corpo, sem tipo")
        }

    @Test
    fun checkOutWithLateSendsTheExactDecimal() =
        served(200, checkedOut) { connection ->
            val source = assertNotNull(connection.checkOutSource())

            source.checkOut(RES, Money.parse("80.50"), Intent())

            val request = seen.single()
            assertTrue(request.contentType.orEmpty().startsWith("application/json"), "tipo: ${request.contentType}")
            assertEquals("""{"lateCheckOutFee":"80.50"}""", request.body.replace(" ", ""))
        }

    @Test
    fun sameIntentRepeatsTheKeyThroughTheConnection() =
        served(200, checkedOut) { connection ->
            val source = assertNotNull(connection.checkOutSource())
            val intent = Intent()

            source.checkOut(RES, null, intent)
            source.checkOut(RES, null, intent)
            source.checkOut(RES, null, Intent())

            assertEquals(3, seen.size)
            assertEquals(seen[0].key, seen[1].key)
            assertTrue(seen[1].key != seen[2].key, "outra intenção, outra chave")
        }

    @Test
    fun balanceNotZeroComesBackAsTheServerDue() =
        served(409, balanceDue) { connection ->
            val source = assertNotNull(connection.checkOutSource())

            val result = source.checkOut(RES, null, Intent())

            assertEquals(
                CheckOutResult.BalanceDue(
                    listOf(
                        FolioDue(FOLIO_A, "F-1", Money.parse("150.00")),
                        FolioDue(FOLIO_B, "F-2", Money.parse("-20.00")),
                    ),
                    Money.parse("130.00"),
                ),
                result,
            )
        }

    @Test
    fun otherConflictComesBackAsTheProblemWithTheCatalogText() =
        served(409, """{"code":"RESERVATION_CHANGED","detail":"mudou","status":409}""") { connection ->
            val source = assertNotNull(connection.checkOutSource())

            val error = assertFailsWith<ApiError.Problem> { source.checkOut(RES, null, Intent()) }

            assertEquals("RESERVATION_CHANGED", error.code)
            assertEquals("A reserva mudou enquanto você concluía. Confira e tente de novo.", error.userMessage)
        }

    @Test
    fun quoteComesThroughTheConnection() =
        served(200, checkedOut) { connection ->
            val source = assertNotNull(connection.checkOutSource())

            assertEquals(LateCheckOutQuote("15:30", Money.parse("80.50")), source.lateCheckOutQuote(RES))
        }
}
