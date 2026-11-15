package com.zeny.desktop.app.reception

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ID_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val ID_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val PATH_PREFIX = "/api/v1/reservations/"
private const val PATH_SUFFIX = "/registration-card/pdf"

/** Ataques à porta da ficha: o adaptador repassa o id e deixa os erros e o cancelamento passarem intactos. */
class RegistrationCardsSourceAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private fun withServer(
        handler: (HttpExchange) -> Unit,
        block: suspend CoroutineScope.(RegistrationCards, String, CopyOnWriteArrayList<String>) -> Unit,
    ) = runBlocking {
        val uris = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            uris += exchange.requestURI.path
            handler(exchange)
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                block(assertNotNull(connection.registrationCards()), url, uris)
            }
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.pdf(
        body: ByteArray,
        disposition: String? = null,
    ) {
        responseHeaders.add("Content-Type", "application/pdf")
        disposition?.let { responseHeaders.add("Content-Disposition", it) }
        sendResponseHeaders(200, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun HttpExchange.problem(
        status: Int,
        code: String,
    ) {
        val body = """{"status":$status,"code":"$code","title":"t","detail":"d"}""".toByteArray()
        responseHeaders.add("Content-Type", "application/problem+json")
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    @Test
    fun theIdOfEachCallGoesToTheUrlOfThatReservation() =
        withServer({ it.pdf("%PDF-1".toByteArray()) }) { cards, _, uris ->
            cards.registrationCard(ID_A)
            cards.registrationCard(ID_B)

            assertEquals(
                listOf("$PATH_PREFIX$ID_A$PATH_SUFFIX", "$PATH_PREFIX$ID_B$PATH_SUFFIX"),
                uris.toList(),
            )
        }

    @Test
    fun binaryBytesWithHighValuesAndNulsComeBackIdentical() {
        val body = "%PDF-".toByteArray() + ByteArray(256) { it.toByte() } + byteArrayOf(0, 0, -1, -128)
        withServer({ it.pdf(body) }) { cards, _, _ ->
            assertContentEquals(body, cards.registrationCard(ID_A).bytes)
        }
    }

    @Test
    fun missingContentDispositionGivesNullFileName() =
        withServer({ it.pdf("%PDF-1".toByteArray()) }) { cards, _, _ ->
            assertNull(cards.registrationCard(ID_A).fileName)
        }

    @Test
    fun fileNameOfTheServerComesBackUntouched() =
        withServer({ it.pdf("%PDF-1".toByteArray(), "attachment; filename=\"ficha-0042.pdf\"") }) { cards, _, _ ->
            assertEquals("ficha-0042.pdf", cards.registrationCard(ID_A).fileName)
        }

    @Test
    fun conflictPropagatesAsTheSameProblem() =
        withServer({ it.problem(409, "RESERVATION_NOT_IN_HOUSE") }) { cards, _, _ ->
            val error = assertFailsWith<ApiError.Problem> { cards.registrationCard(ID_A) }
            assertEquals(409, error.status)
            assertEquals("RESERVATION_NOT_IN_HOUSE", error.code)
        }

    @Test
    fun notFoundPropagatesAsTheSameProblem() =
        withServer({ it.problem(404, "RESERVATION_NOT_FOUND") }) { cards, _, _ ->
            val error = assertFailsWith<ApiError.Problem> { cards.registrationCard(ID_A) }
            assertEquals(404, error.status)
            assertEquals("RESERVATION_NOT_FOUND", error.code)
        }

    @Test
    fun forbiddenPropagatesAsTheSameProblem() =
        withServer({ it.problem(403, "FORBIDDEN") }) { cards, _, _ ->
            val error = assertFailsWith<ApiError.Problem> { cards.registrationCard(ID_A) }
            assertEquals(403, error.status)
            assertEquals("FORBIDDEN", error.code)
        }

    @Test
    fun deadServerPropagatesAsNetworkError() =
        runBlocking {
            val port =
                HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).let {
                    val p = it.address.port
                    it.stop(0)
                    p
                }
            val url = "http://127.0.0.1:$port"
            CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                val cards = assertNotNull(connection.registrationCards())
                assertFailsWith<ApiError.Network> { cards.registrationCard(ID_A) }
            }
        }

    @Test
    fun nonPdfBodyIsIncompatibleResponseAndNotAFakeDocument() =
        withServer({
            val body = "<html>oi</html>".toByteArray()
            it.responseHeaders.add("Content-Type", "text/html")
            it.sendResponseHeaders(200, body.size.toLong())
            it.responseBody.use { out -> out.write(body) }
        }) { cards, _, _ ->
            assertFailsWith<ApiError.IncompatibleResponse> { cards.registrationCard(ID_A) }
        }

    @Test
    fun invalidIdFailsBeforeTheNetwork() =
        withServer({ it.pdf("%PDF-1".toByteArray()) }) { cards, _, uris ->
            assertFailsWith<IllegalArgumentException> { cards.registrationCard("nao-e-uuid") }
            assertTrue(uris.isEmpty(), "nenhuma chamada de rede para id inválido: $uris")
        }

    @Test
    fun cancellationWhileWaitingIsNotWrappedInApiError() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        withServer({
            arrived.countDown()
            release.await(10, TimeUnit.SECONDS)
            runCatching { it.pdf("%PDF-1".toByteArray()) }
        }) { cards, _, _ ->
            var seen: Throwable? = null
            val call =
                async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        cards.registrationCard(ID_A)
                    } catch (t: Throwable) {
                        seen = t
                        throw t
                    }
                }
            assertTrue(arrived.await(10, TimeUnit.SECONDS), "o servidor recebeu a chamada")
            call.cancel()
            release.countDown()
            call.join()
            val error = assertNotNull(seen, "a chamada cancelada tem de lançar, não devolver documento")
            assertTrue(error is CancellationException, "esperava CancellationException, veio $error")
        }
    }
}
