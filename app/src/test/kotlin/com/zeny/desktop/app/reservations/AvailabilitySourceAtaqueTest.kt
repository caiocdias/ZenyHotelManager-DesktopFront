package com.zeny.desktop.app.reservations

import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Ataque ao adaptador de cotação: `childAges` e `includeUnavailable` chegam ao servidor. */
class AvailabilitySourceAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private val queries = CopyOnWriteArrayList<String>()

    private fun withSource(block: suspend (AvailabilitySource) -> Unit) =
        runTest {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                exchange.requestBody.readBytes()
                queries += exchange.requestURI.rawQuery.orEmpty()
                val bytes =
                    """{"adults":1,"arrival":"2026-11-20","channel":"DIRECT","childAges":[],
                    "departure":"2026-11-22","nights":2,"offers":[]}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}"
                CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                    block(assertNotNull(connection.availabilitySource()))
                }
            } finally {
                server.stop(0)
            }
        }

    private fun params(query: String) = query.split("&").map { URLDecoder.decode(it, "UTF-8") }

    @Test
    fun quoteForwardsEveryChildAgeAndIncludeUnavailable() =
        withSource { source ->
            source.quote(BusinessDate.parse("2026-11-20"), BusinessDate.parse("2026-11-22"), 2, listOf(3, 11))

            val sent = params(queries.single())
            assertTrue("includeUnavailable=true" in sent, sent.toString())
            assertTrue("adults=2" in sent, sent.toString())
            assertTrue("childAges=3,11" in sent, sent.toString())
        }

    @Test
    fun quoteWithoutChildrenStillAsksForUnavailableOffers() =
        withSource { source ->
            source.quote(BusinessDate.parse("2026-11-20"), BusinessDate.parse("2026-11-22"), 1, emptyList())

            val sent = params(queries.single())
            assertTrue("includeUnavailable=true" in sent, sent.toString())
            assertTrue(sent.none { it.startsWith("childAges=") && it.length > "childAges=".length }, sent.toString())
        }
}
