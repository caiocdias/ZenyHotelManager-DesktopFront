package com.zeny.desktop.app.reception

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.facade.RoomMoveInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.RoomMoveResponse
import com.zeny.desktop.sdk.model.UndoCheckInResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM_FROM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ROOM_TO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val ANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

private val JSON = Json { ignoreUnknownKeys = true }

/** Ataques à porta da troca de UH e do desfazer: o adaptador real contra um servidor local, e o falso compartilhado. */
class StayActionsSourceAtaqueTest {
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
        val body: String,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    private fun room(
        id: String,
        number: String,
    ) = """{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"$number","occupancy":"VACANT",
        "roomId":"$id","version":5}"""

    private fun reservation(status: String) =
        """{"adults":2,"arrival":"2026-11-13","bookingId":"$ANY","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$ANY","guests":[],"id":"$RES","roomId":"$ROOM_TO",
        "roomTypeId":"$ANY","status":"$status","version":3}"""

    private val movedJson =
        """{"difference":"0.00","remainingAfter":"100.00","remainingBefore":"100.00","tariff":"KEEP",
        "fromRoom":${room(
            ROOM_FROM,
            "101",
        )},"toRoom":${room(ROOM_TO, "102")},"reservation":${reservation("IN_HOUSE")}}"""

    private val undoneJson = """{"reservation":${reservation("CONFIRMED")},"room":${room(ROOM_FROM, "101")}}"""

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

    private fun withServer(
        handler: (HttpExchange, Seen) -> Unit,
        block: suspend (ReceptionSource) -> Unit,
    ) = runTest {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val h = exchange.requestHeaders
            val request =
                Seen(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    h.getFirst("Idempotency-Key"),
                    h.getFirst("If-Match"),
                    h.getFirst("X-Approval-Token"),
                    body,
                )
            seen += request
            handler(exchange, request)
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

    private val input = RoomMoveInput(ROOM_TO, "ROOM_ISSUE", "RECALCULATE")

    @Test
    fun moveSendsRoomReasonAndTariffInBodyAndNoApprovalTokenWithoutOne() =
        withServer({ ex, _ -> ex.reply(200, movedJson) }) { source ->
            source.moveRoom(RES, input, "\"3\"", Intent()).run(null)
            val move = seen.single()
            assertEquals("/api/v1/reservations/$RES/room-moves", move.path)
            assertNull(move.token)
            assertTrue(move.body.contains(ROOM_TO), move.body)
            assertTrue(move.body.contains("ROOM_ISSUE"), move.body)
            assertTrue(move.body.contains("RECALCULATE"), move.body)
        }

    @Test
    fun moveWithTokenSendsTokenAndKeepsKeyWhenResultIsUnknown() =
        withServer({ ex, _ -> ex.reply(200, movedJson) }) { source ->
            val intent = Intent()
            source.moveRoom(RES, input, "\"3\"", intent).run("tok-9")
            val move = seen.single()
            assertEquals("tok-9", move.token)
            assertEquals("\"3\"", move.ifMatch)
            assertNotNull(move.key)
        }

    @Test
    fun moveUsesTheEtagGivenAndNotAnyOtherRead() =
        withServer({ ex, _ -> ex.reply(200, movedJson) }) { source ->
            source.moveRoom(RES, input, "W/\"42\"", Intent()).run(null)
            assertEquals(1, seen.size, "a troca não pode reler a reserva por conta própria")
            assertEquals("W/\"42\"", seen.single().ifMatch)
        }

    @Test
    fun movesWithDifferentIntentsUseDifferentKeys() =
        withServer({ ex, _ -> ex.reply(200, movedJson) }) { source ->
            source.moveRoom(RES, input, "\"3\"", Intent()).run(null)
            source.moveRoom(RES, input, "\"3\"", Intent()).run(null)
            assertNotEquals(seen[0].key, seen[1].key)
        }

    @Test
    fun sameIntentRepeatedAfterUnknownResultKeepsTheKey() =
        withServer({ ex, _ -> ex.reply(200, movedJson) }) { source ->
            val intent = Intent()
            val action = source.moveRoom(RES, input, "\"3\"", intent)
            action.run(null)
            action.run(null)
            assertEquals(seen[0].key, seen[1].key)
            assertEquals(intent.key, seen[0].key)
        }

    @Test
    fun move412BecomesPreconditionFailed() =
        withServer({ ex, _ -> ex.reply(412, """{"code":"PRECONDITION_FAILED","detail":"x"}""") }) { source ->
            val error = assertFailsWith<ApiError> { source.moveRoom(RES, input, "\"1\"", Intent()).run(null) }
            assertTrue(error is ApiError.PreconditionFailed, error::class.simpleName)
            assertEquals("Alguém alterou esta reserva. Confira e confirme de novo.", ActionText.moveError(error))
        }

    @Test
    fun move409RoomNotReadyReachesMoveErrorWithOwnText() =
        withServer({ ex, _ -> ex.reply(409, """{"code":"ROOM_NOT_READY","detail":"x"}""") }) { source ->
            val error = assertFailsWith<ApiError> { source.moveRoom(RES, input, "\"1\"", Intent()).run(null) }
            assertEquals(
                "A UH não está pronta. Escolha outra UH da lista ou peça a liberação à governança.",
                ActionText.moveError(error),
            )
        }

    @Test
    fun moveRefusesBlankEtagAndBadUuidAndBadReasonBeforeTheNetwork() =
        withServer({ ex, _ -> ex.reply(200, movedJson) }) { source ->
            assertFailsWith<IllegalArgumentException> { source.moveRoom(RES, input, "  ", Intent()) }
            assertFailsWith<IllegalArgumentException> { source.moveRoom("nao-uuid", input, "\"1\"", Intent()) }
            assertFailsWith<IllegalArgumentException> {
                source.moveRoom(RES, RoomMoveInput(ROOM_TO, "INITIAL", "KEEP"), "\"1\"", Intent())
            }
            assertFailsWith<IllegalArgumentException> {
                source.moveRoom(RES, RoomMoveInput(ROOM_TO, "GUEST_REQUEST", "OUTRA"), "\"1\"", Intent())
            }
            assertEquals(0, seen.size)
        }

    @Test
    fun undoNeverSendsKeyOrIfMatchWithoutToken() =
        withServer({ ex, _ -> ex.reply(200, undoneJson) }) { source ->
            source.undoCheckIn(RES).run(null)
            val undo = seen.single()
            assertEquals("/api/v1/reservations/$RES/undo-check-in", undo.path)
            assertNull(undo.key)
            assertNull(undo.ifMatch)
            assertNull(undo.token)
        }

    @Test
    fun undoRepeatedWithTokenStillSendsNoKey() =
        withServer({ ex, _ -> ex.reply(200, undoneJson) }) { source ->
            val action = source.undoCheckIn(RES)
            action.run(null)
            action.run("tok-2")
            assertNull(seen[0].key)
            assertNull(seen[1].key)
            assertNull(seen[0].token)
            assertEquals("tok-2", seen[1].token)
        }

    @Test
    fun undo403ApprovalRequiredThenApprovedSendsTokenOnly() =
        withServer({ ex, req ->
            if (req.token == null) {
                ex.reply(
                    403,
                    """{"code":"APPROVAL_REQUIRED","detail":"x","action":"UNDO_CHECK_IN",
                    "permission":"frontdesk.stays.undocheckin","resourceRef":"$RES"}""",
                )
            } else {
                ex.reply(200, undoneJson)
            }
        }) { source ->
            val action = source.undoCheckIn(RES)
            val first = runCatching { action.run(null) }.exceptionOrNull()
            assertTrue(first is ApiError.ApprovalRequired || first is ApiError.Problem, first.toString())
            action.run("tok-3")
            assertEquals(listOf(null, "tok-3"), seen.map { it.token })
            assertTrue(seen.all { it.key == null })
        }

    @Test
    fun undoWithBadUuidIsRefusedBeforeTheNetwork() =
        withServer({ ex, _ -> ex.reply(200, undoneJson) }) { source ->
            assertFailsWith<IllegalArgumentException> { source.undoCheckIn("nao-uuid") }
            assertEquals(0, seen.size)
        }

    @Test
    fun reservationVersionWithoutEtagIsIncompatibleResponse() =
        withServer({ ex, _ -> ex.reply(200, """{}""") }) { source ->
            assertFailsWith<ApiError> { source.reservationVersion(RES) }
        }

    // ---- O falso compartilhado, de que as próximas tarefas dependem ----

    private fun fake() =
        FakeReceptionSource().apply {
            moveResponse = JSON.decodeFromString(RoomMoveResponse.serializer(), movedJson)
            undoResponse = JSON.decodeFromString(UndoCheckInResponse.serializer(), undoneJson)
        }

    @Test
    fun fakeRefusesInvalidReasonAndTariffBeforeRecording() {
        val fake = fake()
        for (reason in listOf("INITIAL", "guest_request", "", "OUTRO")) {
            assertFailsWith<IllegalArgumentException>(reason) {
                fake.moveRoom(RES, RoomMoveInput(ROOM_TO, reason, "KEEP"), "\"1\"", Intent())
            }
        }
        for (tariff in listOf("keep", "", "NOVA")) {
            assertFailsWith<IllegalArgumentException>(tariff) {
                fake.moveRoom(RES, RoomMoveInput(ROOM_TO, "GUEST_REQUEST", tariff), "\"1\"", Intent())
            }
        }
        assertEquals(emptyList(), fake.moveCalls)
        assertEquals(emptyList(), fake.moveRuns)
    }

    @Test
    fun fakeAcceptsAllFourRealReasonsAndBothTariffs() {
        val fake = fake()
        for (reason in listOf("GUEST_REQUEST", "OUT_OF_ORDER", "ROOM_ISSUE", "OPERATIONAL")) {
            for (tariff in listOf("KEEP", "RECALCULATE")) {
                fake.moveRoom(RES, RoomMoveInput(ROOM_TO, reason, tariff), "\"1\"", Intent())
            }
        }
        assertEquals(8, fake.moveCalls.size)
    }

    @Test
    fun fakeMoveRunWithSameIntentRecordsSameKeyAndNewIntentRecordsNewKey() =
        runTest {
            val fake = fake()
            val a = Intent()
            val b = Intent()
            fake.moveRoom(RES, input, "\"1\"", a).run(null)
            fake.moveRoom(RES, input, "\"1\"", a).run(null)
            fake.moveRoom(RES, input, "\"2\"", b).run(null)
            assertEquals(listOf(a.key, a.key, b.key), fake.moveRuns.map { it.key })
            assertEquals(listOf("\"1\"", "\"1\"", "\"2\""), fake.moveRuns.map { it.etag })
            assertNotEquals(a.key, b.key)
        }

    @Test
    fun fakeMoveApprovalThrowsOnlyWithoutTokenAndDoesNotConsumeOutcomes() =
        runTest {
            val fake = fake()
            fake.moveRequiresApproval = ApiError.ApprovalRequired("a", "p", "r", null, null)
            fake.moveOutcomes += ApiError.Network(IOException("x"))
            val action = fake.moveRoom(RES, input, "\"1\"", Intent())
            assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
            // a recusa por aprovação não gasta o resultado programado
            assertFailsWith<ApiError.Network> { action.run("t") }
            assertEquals(
                "102",
                action
                    .run("t")
                    .value.toRoom.number,
            )
            assertEquals(listOf<String?>(null, "t", "t"), fake.moveRuns.map { it.token })
        }

    @Test
    fun fakeMoveOutcomesApplyPerRunInOrder() =
        runTest {
            val fake = fake()
            fake.moveOutcomes += ApiError.PreconditionFailed("x")
            fake.moveOutcomes += null
            val action = fake.moveRoom(RES, input, "\"1\"", Intent())
            assertFailsWith<ApiError.PreconditionFailed> { action.run(null) }
            assertEquals(
                "102",
                action
                    .run(null)
                    .value.toRoom.number,
            )
            assertEquals(
                "102",
                action
                    .run(null)
                    .value.toRoom.number,
            )
            assertEquals(3, fake.moveRuns.size)
        }

    @Test
    fun fakeHeldMoveRecordsRunBeforeBlockingAndCompletesOnRelease() =
        runTest {
            val fake = fake()
            fake.holdMove = true
            val job = launch { fake.moveRoom(RES, input, "\"1\"", Intent()).run("t") }
            testScheduler.advanceUntilIdle()
            assertEquals(1, fake.moveRuns.size)
            assertEquals(1, fake.heldMoves.size)
            assertTrue(job.isActive)
            fake.heldMoves.single().complete(Unit)
            testScheduler.advanceUntilIdle()
            assertTrue(job.isCompleted)
        }

    @Test
    fun fakeUndoRecordsNoKeyAndEachTokenPerRun() =
        runTest {
            val fake = fake()
            fake.undoRequiresApproval = ApiError.ApprovalRequired("a", "p", "r", null, null)
            val action = fake.undoCheckIn(RES)
            assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
            assertEquals(
                "CONFIRMED",
                action
                    .run("t1")
                    .value.reservation.status,
            )
            assertEquals(
                "CONFIRMED",
                action
                    .run("t2")
                    .value.reservation.status,
            )
            assertEquals(listOf<String?>(null, "t1", "t2"), fake.undoRuns.map { it.token })
        }

    @Test
    fun fakeUndoOutcomesAreConsumedPerRun() =
        runTest {
            val fake = fake()
            fake.undoOutcomes += ApiError.Network(IOException("x"))
            val action = fake.undoCheckIn(RES)
            assertFailsWith<ApiError.Network> { action.run(null) }
            action.run(null)
            assertEquals(2, fake.undoRuns.size)
        }

    @Test
    fun fakeReservationVersionReturnsConfiguredAndFailsWhenErrorSetOrMissing() =
        runTest {
            val fake = fake()
            val detail = JSON.decodeFromString(ReservationDetailResponse.serializer(), detailJson())
            fake.versions[RES] = ReservationVersion(detail, "\"5\"")
            assertEquals("\"5\"", fake.reservationVersion(RES).etag)
            fake.versionError = ApiError.Network(IOException("x"))
            assertFailsWith<ApiError.Network> { fake.reservationVersion(RES) }
            assertEquals(listOf(RES, RES), fake.versionCalls)
            fake.versionError = null
            assertFailsWith<IllegalStateException> { fake.reservationVersion("outra") }
        }

    @Test
    fun fakeHeldVersionBlocksUntilReleased() =
        runTest {
            val fake = fake()
            val detail = JSON.decodeFromString(ReservationDetailResponse.serializer(), detailJson())
            fake.versions[RES] = ReservationVersion(detail, "\"5\"")
            fake.holdVersion = true
            val gate = CompletableDeferred<ReservationVersion>()
            val job = launch { gate.complete(fake.reservationVersion(RES)) }
            testScheduler.advanceUntilIdle()
            assertTrue(job.isActive)
            fake.heldVersions.single().complete(Unit)
            testScheduler.advanceUntilIdle()
            assertEquals("\"5\"", gate.await().etag)
        }

    private fun detailJson() =
        """{"bookingCode":"B-1","holderGuestId":"$ANY","restrictionAlerts":[],
        "reservation":{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-13","bookingId":"$ANY",
        "cancellationTerms":{"deadlineHours":24,"name":"Flex","penaltyType":"NIGHTS","policyId":"$ANY","version":1},
        "childAges":[],"code":"R-1","createdAt":"2026-11-11T13:00:00Z","dayUse":false,"departure":"2026-11-15",
        "folioId":"$ANY",
        "guaranteeTerms":{"name":"Sem garantia","policyId":"$ANY","type":"NONE","version":1},
        "guests":[],"id":"$RES","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL",
        "ratePlanId":"$ANY","roomTotal":"300.00","roomTypeId":"$ANY","segment":"LEISURE","status":"IN_HOUSE",
        "total":"300.00","version":2}}"""
}
