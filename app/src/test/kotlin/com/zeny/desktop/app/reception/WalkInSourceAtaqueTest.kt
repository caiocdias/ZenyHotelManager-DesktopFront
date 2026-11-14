package com.zeny.desktop.app.reception

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CheckInGuest
import com.zeny.desktop.core.facade.WalkInInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.WalkInResponse
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val GUEST = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f02"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f03"
private const val ROOM_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f06"
private const val TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f04"
private const val PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f05"

private val JSON = Json { ignoreUnknownKeys = true }

private fun date(text: String) = BusinessDate.parse(text)

private fun input() =
    WalkInInput(
        departure = date("2026-11-15"),
        adults = 1,
        childAges = emptyList(),
        ratePlanId = PLAN,
        roomTypeId = TYPE,
        roomId = ROOM,
        segment = "LEISURE",
        guests = listOf(CheckInGuest(GUEST, true)),
        guestCount = null,
    )

/** Ataques à porta do walk-in: o adaptador real contra um servidor local, e o falso compartilhado. */
class WalkInSourceAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private class Seen(
        val method: String,
        val uri: String,
        val key: String?,
        val body: String,
    ) {
        val path: String get() = uri.substringBefore('?')
        val query: Map<String, String> =
            uri.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
    }

    private val seen = CopyOnWriteArrayList<Seen>()

    private fun item(
        id: String,
        room: String?,
    ): String {
        val assignment =
            room
                ?.let {
                    ""","roomAssignment":{"doNotMove":false,"id":"$ANY","reallocationRequired":false,"roomId":"$it"}"""
                }.orEmpty()
        return """{"adults":1,"arrival":"2026-11-14","arrivalOverdue":false,"bookingCode":"B-1",
        "bookingId":"$ANY","channel":"DIRECT","children":0,"code":"R-1","dayUse":false,"departure":"2026-11-15",
        "departureOverdue":false,"folioId":"$ANY","holderGuestId":"$ANY","id":"$id","origin":"FRONT_DESK",
        "ratePlanCategory":"NORMAL","ratePlanId":"$ANY","roomTypeId":"$ANY","segment":"LEISURE",
        "status":"CONFIRMED","total":"300.00","version":1$assignment}"""
    }

    private val quoteJson =
        """{"adults":1,"arrival":"2026-11-14","channel":"DIRECT","childAges":[],"departure":"2026-11-15",
        "nights":1,"offers":[]}"""

    private val walkedIn =
        """{"bookingCode":"B-1","bookingId":"$ANY","holderGuestId":"$GUEST","restrictionAlerts":[],
        "reservation":{"adults":1,"arrival":"2026-11-14","bookingId":"$ANY","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$ANY",
        "guests":[{"guestId":"$GUEST","primary":true}],"id":"$ANY","roomId":"$ROOM","roomTypeId":"$TYPE",
        "status":"CHECKED_IN","version":3},
        "room":{"housekeepingStatus":"INSPECTED","inspectionPending":false,"number":"101","occupancy":"OCCUPIED",
        "roomId":"$ROOM","version":4},"total":"300.00"}"""

    private fun HttpExchange.reply(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
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
            val request =
                Seen(
                    exchange.requestMethod,
                    exchange.requestURI.toString(),
                    exchange.requestHeaders.getFirst("Idempotency-Key"),
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

    @Test
    fun quoteStaySendsTheRightQuery() =
        withServer({ exchange, _ -> exchange.reply(200, quoteJson) }) { source ->
            val empty = source.quoteStay(date("2026-11-14"), date("2026-11-17"), 2, emptyList())
            assertTrue(empty.offers.isEmpty())
            source.quoteStay(date("2026-11-14"), date("2026-11-17"), 2, listOf(4, 11))

            val noKids = seen[0]
            assertEquals("GET", noKids.method)
            assertEquals("/api/v1/quotes", noKids.path)
            assertEquals("2026-11-14", noKids.query["arrival"])
            assertEquals("2026-11-17", noKids.query["departure"])
            assertEquals("2", noKids.query["adults"])
            assertEquals("DIRECT", noKids.query["channel"])
            assertFalse("childAges" in noKids.query, noKids.uri)
            assertEquals("4,11", seen[1].query["childAges"])
        }

    @Test
    fun quoteStayRefusalSurfacesAsTypedProblem() =
        withServer({ exchange, _ ->
            exchange.reply(400, """{"code":"STAY_INVALID","detail":"x"}""")
        }) { source ->
            val error =
                assertFailsWith<ApiError.Problem> {
                    source.quoteStay(date("2026-11-14"), date("2026-11-14"), 1, emptyList())
                }
            assertEquals("STAY_INVALID", error.code)
            assertEquals(ActionText.error(error), ActionText.walkInError(error))
        }

    @Test
    fun assignedRoomIdsSendsRangeAndStatusAndFollowsPages() =
        withServer({ exchange, request ->
            val first = "cursor" !in request.query
            val body =
                if (first) {
                    """{"items":[${item(ANY, ROOM)},${item(ANY, null)}],"nextCursor":"pg2"}"""
                } else {
                    """{"items":[${item(ANY, ROOM_B)},${item(ANY, ROOM)}]}"""
                }
            exchange.reply(200, body)
        }) { source ->
            val ids = source.assignedRoomIds(date("2026-11-14"), date("2026-11-16"))

            assertEquals(setOf(ROOM, ROOM_B), ids)
            assertEquals(2, seen.size, "deve seguir só até a última página")
            for (request in seen) {
                assertEquals("/api/v1/reservations", request.path)
                assertEquals("2026-11-14", request.query["from"])
                assertEquals("2026-11-16", request.query["to"])
                assertEquals("PROVISIONAL,CONFIRMED", request.query["status"])
            }
            assertNull(seen[0].query["cursor"])
            assertEquals("pg2", seen[1].query["cursor"])
        }

    @Test
    fun assignedRoomIdsWithNoAssignmentsIsEmpty() =
        withServer({ exchange, _ ->
            exchange.reply(200, """{"items":[${item(ANY, null)}]}""")
        }) { source ->
            assertEquals(emptySet(), source.assignedRoomIds(date("2026-11-14"), date("2026-11-14")))
            assertEquals(1, seen.size)
        }

    @Test
    fun assignedRoomIdsRejectsBackwardsPeriodWithoutCallingTheServer() =
        withServer({ exchange, _ -> exchange.reply(200, """{"items":[]}""") }) { source ->
            assertFailsWith<IllegalArgumentException> {
                source.assignedRoomIds(date("2026-11-15"), date("2026-11-14"))
            }
            assertTrue(seen.isEmpty())
        }

    @Test
    fun walkInReusesTheKeyOnlyWhileTheResultIsUnknown() =
        withServer({ exchange, request ->
            when (seen.count { it.path == "/api/v1/walk-ins" }) {
                1 -> exchange.close()

                // sem resposta: resultado desconhecido
                else -> exchange.reply(201, walkedIn)
            }
            assertEquals("POST", request.method)
        }) { source ->
            val intent = Intent()
            val action = source.walkIn(input(), intent)
            assertFailsWith<ApiError.Network> { action.run(null) }
            val result = action.run(null)

            assertEquals("R-1", result.value.reservation.code)
            val keys = seen.filter { it.path == "/api/v1/walk-ins" }.map { it.key }
            assertEquals(2, keys.size)
            assertEquals(intent.key, keys[0])
            assertEquals(keys[0], keys[1], "mesma Intent, mesma chave")
            assertEquals(seen[0].body, seen[1].body)
        }

    @Test
    fun walkInDifferentIntentsUseDifferentKeys() =
        withServer({ exchange, _ -> exchange.reply(201, walkedIn) }) { source ->
            source.walkIn(input(), Intent()).run(null)
            source.walkIn(input(), Intent()).run(null)
            val keys = seen.map { it.key }
            assertTrue(keys.all { !it.isNullOrBlank() })
            assertNotEquals(keys[0], keys[1])
        }

    @Test
    fun walkInSendsTheInputInTheBody() =
        withServer({ exchange, _ -> exchange.reply(201, walkedIn) }) { source ->
            source.walkIn(input(), Intent()).run(null)
            val body = seen.single().body
            for (expected in listOf("\"2026-11-15\"", "\"$PLAN\"", "\"$ROOM\"", "\"$TYPE\"", "\"$GUEST\"", "LEISURE")) {
                assertTrue(expected in body, "$expected em $body")
            }
            assertTrue("\"primary\":true" in body, body)
        }

    @Test
    fun walkInDefinitiveRefusalsAreTypedProblemsWithSpecificText() =
        withServer({ exchange, _ ->
            exchange.reply(409, """{"code":"ROOM_ALREADY_ASSIGNED","detail":"x"}""")
        }) { source ->
            val error = assertFailsWith<ApiError.Problem> { source.walkIn(input(), Intent()).run(null) }
            assertEquals("ROOM_ALREADY_ASSIGNED", error.code)
            assertEquals(
                "A UH já está alocada a outra reserva no período. Escolha outra UH da lista.",
                ActionText.walkInError(error),
            )
        }

    @Test
    fun walkInWithUnknownSegmentFailsBeforeAnyRequest() =
        withServer({ exchange, _ -> exchange.reply(201, walkedIn) }) { source ->
            assertFailsWith<IllegalArgumentException> {
                source.walkIn(input().copy(segment = "NAO_EXISTE"), Intent())
            }
            assertTrue(seen.isEmpty())
        }

    // O falso compartilhado (base das Tasks 4 a 7).

    private fun response() = JSON.decodeFromString(WalkInResponse.serializer(), walkedIn)

    @Test
    fun fakeWalkInOutcomesApplyPerRunInOrderThenSucceed() =
        runTest {
            val fake = FakeReceptionSource()
            fake.walkInResponse = response()
            val problem = ApiError.Problem(409, "ROOM_NOT_READY", "x", emptyList())
            fake.walkInOutcomes += ApiError.Network(IOException("x"))
            fake.walkInOutcomes += problem
            val intent = Intent()
            val action = fake.walkIn(input(), intent)

            assertFailsWith<ApiError.Network> { action.run(null) }
            assertEquals(problem, assertFailsWith<ApiError.Problem> { action.run(null) })
            assertEquals(
                "R-1",
                action
                    .run(null)
                    .value.reservation.code,
            )
            assertEquals(3, fake.walkInRuns.size)
            assertTrue(fake.walkInOutcomes.isEmpty())
        }

    @Test
    fun fakeWalkInRecordsTheRunEvenWhenItFailsAndEachCall() =
        runTest {
            val fake = FakeReceptionSource()
            fake.walkInOutcomes += ApiError.SessionEnded(null)
            val first = Intent()
            val second = Intent()

            assertFailsWith<ApiError.SessionEnded> { fake.walkIn(input(), first).run(null) }
            assertFailsWith<IllegalStateException> { fake.walkIn(input(), second).run("t") }

            assertEquals(listOf(WalkInRun(first.key, null), WalkInRun(second.key, "t")), fake.walkInRuns)
            assertEquals(listOf(first, second), fake.walkInCalls.map { it.intent })
            assertNotEquals(fake.walkInRuns[0].key, fake.walkInRuns[1].key)
        }

    @Test
    fun fakeWalkInApprovalThrowsOnlyWithoutToken() =
        runTest {
            val fake = FakeReceptionSource()
            fake.walkInResponse = response()
            fake.walkInRequiresApproval = ApiError.ApprovalRequired("a", "p", "r", null, null)
            val intent = Intent()
            val action = fake.walkIn(input(), intent)

            assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
            action.run("aprovado")
            assertEquals(listOf(null, "aprovado"), fake.walkInRuns.map { it.token })
        }

    @Test
    fun fakeHeldWalkInStaysInFlightUntilReleasedAndDoesNotConsumeTheOutcome() =
        runTest {
            val fake = FakeReceptionSource()
            fake.walkInResponse = response()
            fake.holdWalkIn = true
            fake.walkInOutcomes += ApiError.Network(IOException("x"))
            val intent = Intent()
            var failure: Throwable? = null
            var done = false
            val job =
                launch {
                    try {
                        fake.walkIn(input(), intent).run(null)
                        done = true
                    } catch (e: ApiError.Network) {
                        failure = e
                    }
                }
            testScheduler.runCurrent()

            assertEquals(1, fake.heldWalkIns.size)
            assertEquals(1, fake.walkInRuns.size, "a execução é registrada ao começar, antes de soltar")
            assertEquals(1, fake.walkInOutcomes.size)
            assertFalse(done)
            assertNull(failure)

            fake.heldWalkIns.single().complete(Unit)
            job.join()
            assertTrue(failure is ApiError.Network)
            assertTrue(fake.walkInOutcomes.isEmpty())
        }

    @Test
    fun fakeHeldWalkInCancellationDoesNotLeakAsAnOutcome() =
        runTest {
            val fake = FakeReceptionSource()
            fake.walkInResponse = response()
            fake.holdWalkIn = true
            val job = launch { fake.walkIn(input(), Intent()).run(null) }
            testScheduler.runCurrent()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(1, fake.walkInRuns.size)
            assertTrue(fake.walkInOutcomes.isEmpty())
        }

    @Test
    fun fakeQuoteStayErrorIsThrownAfterRecordingAndUnconfiguredIsLoud() =
        runTest {
            val fake = FakeReceptionSource()
            assertFailsWith<IllegalStateException> {
                fake.quoteStay(date("2026-11-14"), date("2026-11-15"), 1, emptyList())
            }
            fake.stayQuote = JSON.decodeFromString(QuoteResponse.serializer(), quoteJson)
            fake.stayQuoteError = ApiError.Network(IOException("x"))
            assertFailsWith<ApiError.Network> {
                fake.quoteStay(date("2026-11-14"), date("2026-11-15"), 1, listOf(3))
            }
            assertEquals(2, fake.quoteStayCalls.size)
            assertEquals(listOf(3), fake.quoteStayCalls.last().childAges)
        }

    @Test
    fun fakeAssignedRoomsDefaultsToEmptyAndRecordsHeldCalls() =
        runTest {
            val fake = FakeReceptionSource()
            assertEquals(emptySet(), fake.assignedRoomIds(date("2026-11-14"), date("2026-11-14")))
            fake.assignedRooms = setOf(ROOM)
            fake.holdAssignedRooms = true
            var result: Set<String>? = null
            val job = launch { result = fake.assignedRoomIds(date("2026-11-14"), date("2026-11-15")) }
            testScheduler.runCurrent()
            assertNull(result)
            fake.assignedRooms = setOf(ROOM_B) // o que vale é o retrato da hora da chamada
            fake.heldAssignedRooms.single().complete(Unit)
            job.join()
            assertEquals(setOf(ROOM), result)
        }
}
