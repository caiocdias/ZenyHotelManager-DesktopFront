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
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e02"
private const val GUEST = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e03"
private const val DOC = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e04"
private const val NUMBER = "52998224725"
private const val FULL_NAME = "Beltrana Fictícia de Teste"

/** Ataques à porta `CoreConnection.receptionSource()` (ações) contra um servidor HTTP local de verdade. */
class ReceptionActionsAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private class Seen(
        val method: String,
        val uri: String,
        val key: String?,
        val token: String?,
        val body: String,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    /** Resposta por caminho; o que não está aqui é 409 `ROOM_ALREADY_ASSIGNED`. */
    private val ok = mutableMapOf<String, String>()
    private var failStatus = 409
    private var failBody = """{"code":"ROOM_ALREADY_ASSIGNED","detail":"x"}"""

    private fun guestJson(
        id: String = GUEST,
        masked: String = "***.982.247-**",
    ) = """{"createdAt":"2026-11-12T10:00:00Z","documents":[{"createdAt":"2026-11-12T10:00:00Z","id":"$DOC",
        "issuingCountry":"BR","maskedNumber":"$masked","type":"CPF"}],"fullName":"$FULL_NAME",
        "hasSensitiveData":false,"id":"$id","preferences":{},"status":"ACTIVE",
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
                        exchange.requestHeaders.getFirst("X-Approval-Token"),
                        body,
                    )
                val reply = ok[exchange.requestURI.path]
                if (reply != null) exchange.reply(200, reply) else exchange.reply(failStatus, failBody)
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

    private fun newGuest() = NewGuest(FULL_NAME, "CPF", NUMBER, "BR", "BR", BusinessDate.parse("1990-05-20"))

    private fun checkInInput() = CheckInInput(listOf(CheckInGuest(GUEST, true)), 1, ROOM, null)

    @Test
    fun suggestionsStatusesQuoteAndUnassignHitTheRightEndpoints() =
        withConnection { source ->
            failStatus = 409
            assertFailsWith<ApiError.Problem> { source.roomSuggestions(RES) }
            assertFailsWith<ApiError.Problem> { source.earlyLateQuote(RES) }
            assertFailsWith<ApiError.Problem> { source.unassignRoom(RES) }
            assertFailsWith<ApiError.Problem> { source.setDoNotMove(RES, false) }
            assertFailsWith<ApiError.Problem> { source.roomStatuses() }

            val calls = seen.map { it.method to it.uri.substringBefore('?') }
            assertEquals(
                listOf(
                    "GET" to "/api/v1/reservations/$RES/room-suggestions",
                    "GET" to "/api/v1/reservations/$RES/early-late-quote",
                    "POST" to "/api/v1/reservations/$RES/unassign-room",
                    "POST" to "/api/v1/reservations/$RES/do-not-move",
                    "GET" to "/api/v1/rooms/status",
                ),
                calls,
            )
            assertTrue("\"doNotMove\":false" in seen[3].body, seen[3].body)
        }

    @Test
    fun roomSuggestionsAndStatusesDecodeEmptyListsFromTheWire() =
        withConnection { source ->
            ok["/api/v1/reservations/$RES/room-suggestions"] =
                """{"arrival":"2026-11-13","departure":"2026-11-15","floorPreferenceConsidered":false,"items":[],
                "reservationId":"$RES","roomTypeId":"$ROOM"}"""
            ok["/api/v1/rooms/status"] = """{"items":[],"nextCursor":null}"""

            assertEquals(emptyList(), source.roomSuggestions(RES))
            assertEquals(emptyList(), source.roomStatuses())
        }

    @Test
    fun assignWithoutDoNotMoveOmitsTheFieldFromTheBody() =
        withConnection { source ->
            assertFailsWith<ApiError.Problem> { source.assignRoom(RES, ROOM, null) }

            val body = seen.single().body
            assertTrue(ROOM in body, body)
            assertFalse("doNotMove" in body, body)
        }

    @Test
    fun assignWithDoNotMoveFalseSendsFalseNotOmitted() =
        withConnection { source ->
            assertFailsWith<ApiError.Problem> { source.assignRoom(RES, ROOM, false) }

            assertTrue("\"doNotMove\":false" in seen.single().body, seen.single().body)
        }

    @Test
    fun invalidIdsAreRejectedBeforeAnyRequestByTheNewMembers() =
        withConnection { source ->
            assertFailsWith<IllegalArgumentException> { source.roomSuggestions("x") }
            assertFailsWith<IllegalArgumentException> { source.assignRoom("x", ROOM, null) }
            assertFailsWith<IllegalArgumentException> { source.assignRoom(RES, "x", null) }
            assertFailsWith<IllegalArgumentException> { source.unassignRoom("x") }
            assertFailsWith<IllegalArgumentException> { source.setDoNotMove("x", true) }
            assertFailsWith<IllegalArgumentException> { source.earlyLateQuote("x") }
            assertFailsWith<IllegalArgumentException> { source.checkIn("x", checkInInput(), Intent()) }
            assertEquals(0, seen.size)
        }

    @Test
    fun checkInRunTwiceWithTheSameIntentSendsTheSameKeyAndNoToken() =
        withConnection { source ->
            val intent = Intent()
            val action = source.checkIn(RES, checkInInput(), intent)

            assertFailsWith<ApiError.Problem> { action.run(null) }
            assertFailsWith<ApiError.Problem> { action.run(null) }

            assertEquals(2, seen.size)
            assertEquals(intent.key, seen[0].key)
            assertEquals(seen[0].key, seen[1].key)
            assertNull(seen[0].token)
            assertNull(seen[1].token)
        }

    @Test
    fun checkInApprovedRunCarriesTheTokenAndRenewsTheKey() =
        withConnection { source ->
            val intent = Intent()
            val first = intent.key
            val action = source.checkIn(RES, checkInInput(), intent)

            assertFailsWith<ApiError.Problem> { action.run(null) }
            assertFailsWith<ApiError.Problem> { action.run("tok-aprovacao") }

            assertEquals(first, seen[0].key)
            assertEquals("tok-aprovacao", seen[1].token)
            assertNotEquals(first, seen[1].key)
            assertEquals(intent.key, seen[1].key)
        }

    @Test
    fun checkInBodyCarriesGuestsRoomAndCount() =
        withConnection { source ->
            assertFailsWith<ApiError.Problem> { source.checkIn(RES, checkInInput(), Intent()).run(null) }

            val body = seen.single().body
            assertTrue(GUEST in body && ROOM in body && "\"primary\":true" in body && "\"guestCount\":1" in body, body)
        }

    @Test
    fun searchByNameUsesLimit20AndTheTrimmedNameAndReturnsPicks() =
        withConnection { source ->
            ok["/api/v1/guests"] = """{"items":[${guestJson()},${guestJson("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e05")}]}"""

            val found = source.searchGuests("  Beltrana  ")

            val uri = seen.single().uri
            assertEquals("GET", seen.single().method)
            assertTrue("limit=20" in uri, uri)
            assertTrue("name=Beltrana&" in "$uri&" || uri.endsWith("name=Beltrana"), uri)
            assertEquals(2, found.size)
            assertEquals(GuestPick(GUEST, HolderSummary(FULL_NAME, "CPF ***.982.247-**")), found.first())
            assertFalse(found.first().toString().let { "Beltrana" in it || "982" in it })
        }

    @Test
    fun searchByDocumentKeepsNumberOutOfUriAndSendsCountryInBody() =
        withConnection { source ->
            ok["/api/v1/guests/search-by-document"] = """{"items":[${guestJson()}]}"""

            val found = source.searchGuestsByDocument("CPF", NUMBER, "BR")

            val call = seen.single()
            assertFalse(NUMBER in call.uri, call.uri)
            assertTrue(NUMBER in call.body && "\"BR\"" in call.body && "CPF" in call.body, call.body)
            assertEquals(listOf(HolderSummary(FULL_NAME, "CPF ***.982.247-**")), found.map { it.summary })
        }

    @Test
    fun searchByDocumentWithoutCountryOmitsIt() =
        withConnection { source ->
            ok["/api/v1/guests/search-by-document"] = """{"items":[]}"""

            assertEquals(emptyList(), source.searchGuestsByDocument("CPF", NUMBER, null))

            assertFalse("issuingCountry" in seen.single().body, seen.single().body)
        }

    @Test
    fun searchByDocumentWithUnknownTypeFailsBeforeAnyRequestWithoutEchoingTheNumber() =
        withConnection { source ->
            val e = assertFailsWith<IllegalArgumentException> { source.searchGuestsByDocument("XYZ", NUMBER, null) }

            assertFalse(NUMBER in e.toString())
            assertEquals(0, seen.size)
        }

    @Test
    fun serverFailureOnDocumentSearchAndCreateNeverEchoesNumberOrName() =
        withConnection { source ->
            failStatus = 422
            failBody =
                """{"code":"VALIDATION_ERROR","detail":"x","errors":[{"field":"documents[0].number","code":"INVALID","message":"inválido"}]}"""

            val search = assertFailsWith<ApiError> { source.searchGuestsByDocument("CPF", NUMBER, "BR") }
            val create = assertFailsWith<ApiError> { source.createGuest(newGuest()) }

            listOf(search, create).forEach {
                val text = "${it.message} $it"
                assertFalse(NUMBER in text || "Beltrana" in text, text)
            }
            assertFalse(NUMBER in seen.joinToString { it.uri })
        }

    @Test
    fun createGuestReturnsOnlyThePickAndSendsNumberBirthAndNationalityInTheBody() =
        withConnection { source ->
            ok["/api/v1/guests"] = guestJson()

            val created = source.createGuest(newGuest())

            val call = seen.single()
            assertEquals("POST", call.method)
            assertFalse(NUMBER in call.uri)
            assertTrue(NUMBER in call.body && "1990-05-20" in call.body && "BR" in call.body, call.body)
            assertEquals(GuestPick(GUEST, HolderSummary(FULL_NAME, "CPF ***.982.247-**")), created)
            assertEquals("GuestPick(***)", created.toString())
            assertFalse(
                NUMBER in created.summary.toString() &&
                    created.summary.document
                        .orEmpty()
                        .contains(NUMBER),
            )
        }

    @Test
    fun picksNeverCarryTheUnmaskedNumberEvenIfTheServerSentOnlyMasked() =
        withConnection { source ->
            ok["/api/v1/guests/search-by-document"] = """{"items":[${guestJson()}]}"""

            val found = source.searchGuestsByDocument("CPF", NUMBER, null)

            assertFalse(found.any { NUMBER in it.summary.document.orEmpty() })
            assertEquals("CPF ***.982.247-**", found.single().summary.document)
        }
}
