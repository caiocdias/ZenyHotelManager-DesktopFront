package com.zeny.desktop.app.reception

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.RoomMoveInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.RoomMoveResponse
import com.zeny.desktop.sdk.model.UndoCheckInResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM_FROM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ROOM_TO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val ANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val ETAG = "\"7\""

private val JSON = Json { ignoreUnknownKeys = true }

/** A porta da troca de UH e do desfazer check-in: o adaptador do `core` contra um servidor HTTP local e o falso. */
class StayActionsSourceTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private class Seen(
        val method: String,
        val path: String,
        val key: String?,
        val ifMatch: String?,
        val token: String?,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    private fun room(
        id: String,
        occupancy: String,
        number: String,
    ) = """{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"$number","occupancy":"$occupancy",
        "roomId":"$id","version":5}"""

    private fun stayReservation(
        status: String,
        roomId: String,
    ) = """{"adults":2,"arrival":"2026-11-13","bookingId":"$ANY","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$ANY","guests":[],"id":"$RES","roomId":"$roomId",
        "roomTypeId":"$ANY","status":"$status","version":3}"""

    private val detailJson =
        """{"bookingCode":"B-1","holderGuestId":"$ANY","restrictionAlerts":[],
        "reservation":{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-13","bookingId":"$ANY",
        "cancellationTerms":{"deadlineHours":24,"name":"Flex","penaltyType":"NIGHTS","policyId":"$ANY","version":1},
        "childAges":[],"code":"R-1","createdAt":"2026-11-11T13:00:00Z","dayUse":false,"departure":"2026-11-15",
        "folioId":"$ANY",
        "guaranteeTerms":{"name":"Sem garantia","policyId":"$ANY","type":"NONE","version":1},
        "guests":[],"id":"$RES","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL",
        "ratePlanId":"$ANY","roomTotal":"300.00","roomTypeId":"$ANY","segment":"LEISURE","status":"IN_HOUSE",
        "total":"300.00","version":2}}"""

    private val movedJson =
        """{"difference":"-10.00","remainingAfter":"90.00","remainingBefore":"100.00","tariff":"RECALCULATE",
        "fromRoom":${room(ROOM_FROM, "VACANT", "101")},"toRoom":${room(ROOM_TO, "OCCUPIED", "102")},
        "reservation":${stayReservation("IN_HOUSE", ROOM_TO)}}"""

    private val undoneJson =
        """{"reservation":${stayReservation("CONFIRMED", ROOM_FROM)},
        "room":${room(ROOM_FROM, "VACANT", "101")}}"""

    private fun HttpExchange.reply(
        status: Int,
        body: String,
        etag: String? = null,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        etag?.let { responseHeaders.add("ETag", it) }
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @Test
    fun coreConnectionDelegatesMoveAndUndo() =
        runTest {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                exchange.requestBody.readBytes()
                val headers = exchange.requestHeaders
                seen +=
                    Seen(
                        exchange.requestMethod,
                        exchange.requestURI.path,
                        headers.getFirst("Idempotency-Key"),
                        headers.getFirst("If-Match"),
                        headers.getFirst("X-Approval-Token"),
                    )
                when (exchange.requestURI.path) {
                    "/api/v1/reservations/$RES" -> exchange.reply(200, detailJson, ETAG)
                    "/api/v1/reservations/$RES/room-moves" -> exchange.reply(200, movedJson)
                    "/api/v1/reservations/$RES/undo-check-in" -> exchange.reply(200, undoneJson)
                    else -> exchange.reply(404, """{"code":"NOT_FOUND","detail":"x"}""")
                }
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}"
                CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                    val source = assertNotNull(connection.receptionSource())

                    val version = source.reservationVersion(RES)
                    assertEquals(ETAG, version.etag)

                    val intent = Intent()
                    val input = RoomMoveInput(ROOM_TO, "GUEST_REQUEST", "RECALCULATE")
                    val moved = source.moveRoom(RES, input, ETAG, intent).run(null)
                    assertEquals("102", moved.value.toRoom.number)
                    val move = seen.single { it.path.endsWith("/room-moves") }
                    assertEquals("POST", move.method)
                    assertEquals(ETAG, move.ifMatch)
                    assertEquals(intent.key, move.key)

                    val undone = source.undoCheckIn(RES).run("tok-1")
                    assertEquals("CONFIRMED", undone.value.reservation.status)
                    val undo = seen.single { it.path.endsWith("/undo-check-in") }
                    assertEquals("POST", undo.method)
                    assertNull(undo.key)
                    assertEquals("tok-1", undo.token)
                }
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun fakeRefusesBlankEtagBeforeRecording() {
        val fake = FakeReceptionSource()
        val input = RoomMoveInput(ROOM_TO, "GUEST_REQUEST", "KEEP")
        assertFailsWith<IllegalArgumentException> { fake.moveRoom(RES, input, " ", Intent()) }
        assertEquals(emptyList(), fake.moveCalls)
        assertEquals(emptyList(), fake.moveRuns)
    }

    @Test
    fun fakeRecordsEachMoveRunWithKeyEtagAndToken() =
        runTest {
            val fake = FakeReceptionSource()
            fake.moveOutcomes += ApiError.Network(java.io.IOException("x"))
            fake.moveResponse = JSON.decodeFromString(RoomMoveResponse.serializer(), movedJson)
            val intent = Intent()
            val input = RoomMoveInput(ROOM_TO, "GUEST_REQUEST", "KEEP")
            val action = fake.moveRoom(RES, input, ETAG, intent)

            assertFailsWith<ApiError.Network> { action.run(null) }
            action.run("token-1")

            assertEquals(
                listOf(MoveRun(intent.key, ETAG, null), MoveRun(intent.key, ETAG, "token-1")),
                fake.moveRuns,
            )
            assertEquals(listOf(MoveCall(RES, input, ETAG, intent)), fake.moveCalls)
        }

    @Test
    fun fakeRecordsEachUndoRunWithToken() =
        runTest {
            val fake = FakeReceptionSource()
            fake.undoRequiresApproval = ApiError.ApprovalRequired("UNDO", "p", "r", null, null)
            fake.undoResponse = JSON.decodeFromString(UndoCheckInResponse.serializer(), undoneJson)
            val action = fake.undoCheckIn(RES)

            assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
            action.run("token-1")

            assertEquals(listOf(UndoRun(null), UndoRun("token-1")), fake.undoRuns)
            assertEquals(listOf(RES), fake.undoCalls)
        }
}
