package com.zeny.desktop.app.reception

import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val RESERVATION_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"

/** A porta da ficha de registro: o adaptador `CoreConnection` contra um servidor local. */
class RegistrationCardsSourceTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    @Test
    fun adapterPassesIdAndReturnsDocument() =
        runTest {
            val pdf = "%PDF-1.4\nficha fictícia".toByteArray()
            val uris = CopyOnWriteArrayList<String>()
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                uris += exchange.requestURI.path
                exchange.responseHeaders.add("Content-Type", "application/pdf")
                exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"ficha-teste.pdf\"")
                exchange.sendResponseHeaders(200, pdf.size.toLong())
                exchange.responseBody.use { it.write(pdf) }
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}"
                CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                    val document = assertNotNull(connection.registrationCards()).registrationCard(RESERVATION_ID)

                    assertEquals(listOf("/api/v1/reservations/$RESERVATION_ID/registration-card/pdf"), uris.toList())
                    assertContentEquals(pdf, document.bytes)
                    assertEquals("ficha-teste.pdf", document.fileName)
                }
            } finally {
                server.stop(0)
            }
        }
}
