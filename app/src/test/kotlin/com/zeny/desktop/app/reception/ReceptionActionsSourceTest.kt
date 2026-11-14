package com.zeny.desktop.app.reception

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CheckInGuest
import com.zeny.desktop.core.facade.CheckInInput
import com.zeny.desktop.core.facade.NewGuest
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.GuestDocumentResponse
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
import kotlin.test.assertTrue

private const val RESERVATION_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val ROOM_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val GUEST_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d03"
private const val DOC_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d04"
private const val DOCUMENT_NUMBER = "52998224725"

/** As ações da porta da recepção: o resumo do hóspede e o adaptador `CoreConnection` contra um servidor local. */
class ReceptionActionsSourceTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private class Seen(
        val method: String,
        val uri: String,
        val idempotencyKey: String?,
        val body: String,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    private fun guestJson() =
        """{"createdAt":"2026-11-12T10:00:00Z","documents":[{"createdAt":"2026-11-12T10:00:00Z","id":"$DOC_ID",
        "issuingCountry":"BR","maskedNumber":"***.982.247-**","type":"CPF"}],"fullName":"Fulana Fictícia de Teste",
        "hasSensitiveData":false,"id":"$GUEST_ID","preferences":{},"status":"ACTIVE",
        "updatedAt":"2026-11-12T10:00:00Z","vip":false}"""

    private fun withConnection(block: suspend (ReceptionSource) -> Unit) =
        runTest {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                seen +=
                    Seen(
                        exchange.requestMethod,
                        exchange.requestURI.toString(),
                        exchange.requestHeaders.getFirst("Idempotency-Key"),
                        body,
                    )
                val path = exchange.requestURI.path
                when {
                    path == "/api/v1/guests/search-by-document" -> exchange.reply(200, """{"items":[${guestJson()}]}""")
                    path == "/api/v1/guests" && exchange.requestMethod == "POST" -> exchange.reply(201, guestJson())
                    else -> exchange.reply(409, """{"code":"ROOM_ALREADY_ASSIGNED","detail":"x"}""")
                }
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}"
                CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                    block(assertNotNull(connection.receptionSource()))
                }
            } finally {
                server.stop(0)
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
    fun pickUsesSummary() {
        val guest =
            guestOf(
                "Fulana Fictícia",
                "Fulana Social",
                listOf(GuestDocumentResponse.Type.CPF to "***.982.247-**"),
            )

        val pick = guest.toGuestPick()

        assertEquals(guest.id.toString(), pick.id)
        assertEquals(guest.toHolderSummary(), pick.summary)
        assertEquals("Fulana Social", pick.summary.name)
    }

    @Test
    fun pickToStringHidesData() {
        val text =
            guestOf(
                "Fulana Fictícia",
                null,
                listOf(GuestDocumentResponse.Type.CPF to "***.982.247-**"),
            ).toGuestPick().toString()

        assertEquals("GuestPick(***)", text)
    }

    @Test
    fun coreConnectionDelegatesActions() =
        withConnection { source ->
            assertFailsWith<ApiError.Problem> { source.assignRoom(RESERVATION_ID, ROOM_ID, true) }
            val assign = seen.single()
            assertEquals("POST", assign.method)
            assertEquals("/api/v1/reservations/$RESERVATION_ID/assign-room", assign.uri)
            assertTrue("\"$ROOM_ID\"" in assign.body && "\"doNotMove\":true" in assign.body, assign.body)
            seen.clear()

            val intent = Intent()
            val input = CheckInInput(listOf(CheckInGuest(GUEST_ID, true)), 1, ROOM_ID, null)
            assertFailsWith<ApiError.Problem> { source.checkIn(RESERVATION_ID, input, intent).run(null) }
            val checkIn = seen.single()
            assertEquals("/api/v1/reservations/$RESERVATION_ID/check-in", checkIn.uri)
            assertEquals(intent.key, checkIn.idempotencyKey)
            seen.clear()

            val found = source.searchGuestsByDocument("CPF", DOCUMENT_NUMBER, null)
            val search = seen.single()
            assertEquals("POST", search.method)
            assertEquals("/api/v1/guests/search-by-document", search.uri)
            assertFalse(DOCUMENT_NUMBER in search.uri)
            assertTrue(DOCUMENT_NUMBER in search.body)
            assertEquals(listOf(GUEST_ID), found.map { it.id })
            seen.clear()

            val created =
                source.createGuest(
                    NewGuest(
                        "Fulana Fictícia de Teste",
                        "CPF",
                        DOCUMENT_NUMBER,
                        null,
                        "BR",
                        BusinessDate.parse("1990-05-20"),
                    ),
                )
            assertEquals("/api/v1/guests", seen.single().uri)
            assertEquals(GuestPick(GUEST_ID, HolderSummary("Fulana Fictícia de Teste", "CPF ***.982.247-**")), created)
        }

    @Test
    fun panelExposesPermissions() =
        runTest {
            val source = FakeReceptionSource().apply { permissions = setOf("rooms.status.read") }
            val panel = DayPanelState(source, backgroundScope)

            assertEquals(emptySet(), panel.permissions)
            runCurrent()

            assertEquals(setOf("rooms.status.read"), panel.permissions)
            assertEquals(1, source.permissionsCalls)
        }
}
