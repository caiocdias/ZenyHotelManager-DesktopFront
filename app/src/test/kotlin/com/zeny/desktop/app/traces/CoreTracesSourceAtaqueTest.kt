package com.zeny.desktop.app.traces

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.TraceChange
import com.zeny.desktop.core.facade.TraceFilter
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val ANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val TRACE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a03"
private const val USER_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val SESSION_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"

private data class Seen(
    val method: String,
    val path: String,
    val query: String?,
    val ifMatch: String?,
    val idempotencyKey: String?,
)

/** O adaptador `CoreConnection.tracesSource()` contra um servidor HTTP local (JDK), pelo `ZenyCore` real. */
class CoreTracesSourceAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private class Reply(
        val status: Int,
        val body: String,
        val etag: String? = null,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    private fun withServer(
        handler: (HttpExchange) -> Reply,
        block: suspend (TracesSource, CoreConnection) -> Unit,
    ) = runTest {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            seen +=
                Seen(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestURI.query,
                    exchange.requestHeaders.getFirst("If-Match"),
                    exchange.requestHeaders.getFirst("Idempotency-Key"),
                )
            val reply = handler(exchange)
            val bytes = reply.body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            reply.etag?.let { exchange.responseHeaders.add("ETag", it) }
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                block(assertNotNull(connection.tracesSource()), connection)
            }
        } finally {
            server.stop(0)
        }
    }

    private fun room(
        number: String,
        active: Boolean,
        id: String = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b0${number.padStart(3, '0').takeLast(3)}",
    ) = """{"active":$active,"changedAt":"2026-11-12T10:00:00Z","housekeepingStatus":"CLEAN",
        "inspectionPending":false,"number":"$number","occupancy":"VACANT","readyForCheckIn":true,"roomId":"$id",
        "roomTypeId":"$ANY","serviceFlag":"NONE","version":1}"""

    private fun roomPage(
        vararg rooms: String,
        next: String? = null,
    ) = """{"items":[${rooms.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

    private fun reservationDetail(code: String) =
        """{"bookingCode":"BOOK-NOT-THE-ONE","holderGuestId":"$ANY","restrictionAlerts":[],"reservation":{
        "additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-13","bookingId":"$ANY",
        "cancellationTerms":{"deadlineHours":24,"name":"x","penaltyType":"NIGHTS","policyId":"$ANY","version":1},
        "childAges":[],"code":"$code","createdAt":"2026-11-12T10:00:00Z","dayUse":false,"departure":"2026-11-15",
        "folioId":"$ANY","guaranteeTerms":{"name":"x","policyId":"$ANY","type":"NONE","version":1},"guests":[],
        "id":"$RES","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL","ratePlanId":"$ANY",
        "roomTotal":"0.00","roomTypeId":"$ANY","segment":"LEISURE","status":"CONFIRMED","total":"0.00","version":1}}"""

    private fun traceJson(
        text: String = "ligar para o hóspede",
        status: String = "OPEN",
    ) = """{"createdAt":"2026-11-14T10:00:00Z","createdBy":"$USER_ID","departmentId":"$ANY",
        "departmentName":"Governança","dueOn":"2026-11-15","id":"$TRACE","status":"$status","text":"$text",
        "version":1}"""

    private fun me() =
        """{"login":"maria.silva","name":"Maria Silva","permissions":[
        {"code":"frontdesk.traces.read","limits":[],"scope":"ALL"},
        {"code":"staff.departments.read","limits":[],"scope":"OWN"}],"roles":[],
        "sessionId":"$SESSION_ID","userId":"$USER_ID"}"""

    private val change = TraceChange(ANY, BusinessDate(LocalDate.of(2026, 11, 15)), "novo texto", null)

    @Test
    fun tracesSourceIsTheSameInstanceOnEveryCall() =
        withServer({ Reply(200, "{}") }) { source, connection ->
            assertSame(source, connection.tracesSource())
        }

    @Test
    fun roomsAreOnlyTheActiveOnesInNaturalNumberOrderWithTheirIds() =
        withServer({
            val rooms =
                arrayOf(room("101", true), room("20", true), room("9", false), room("3", true), room("A1", true))
            Reply(200, roomPage(*rooms))
        }) { source, _ ->
            val rooms = source.rooms()
            assertEquals(listOf("3", "20", "101", "A1"), rooms.map { it.number })
            val twentyId = Regex("\"roomId\":\"([^\"]+)\"").find(room("20", true))!!.groupValues[1]
            assertEquals(twentyId, rooms[1].id)
            assertEquals(listOf("/api/v1/rooms/status"), seen.map { it.path })
        }

    @Test
    fun roomsReadsEveryPageBeforeFilteringAndOrdering() =
        withServer({ exchange ->
            if (exchange.requestURI.query?.contains("cursor=p2") == true) {
                Reply(200, roomPage(room("2", true), room("5", false)))
            } else {
                Reply(200, roomPage(room("10", true), room("1", false), next = "p2"))
            }
        }) { source, _ ->
            assertEquals(listOf("2", "10"), source.rooms().map { it.number })
            assertEquals(2, seen.size)
        }

    @Test
    fun roomsOfAServerWithNoActiveRoomIsEmptyNotAnError() =
        withServer({ Reply(200, roomPage(room("1", false))) }) { source, _ ->
            assertEquals(emptyList(), source.rooms())
        }

    @Test
    fun reservationCodeIsTheReservationCodeNotTheBookingCodeAndOnlyOneRead() =
        withServer({ Reply(200, reservationDetail("R-2026-0042")) }) { source, _ ->
            assertEquals("R-2026-0042", source.reservationCode(RES))
            assertEquals(listOf("GET /api/v1/reservations/$RES"), seen.map { "${it.method} ${it.path}" })
        }

    @Test
    fun reservationCodeOfAMissingReservationKeepsTheServerProblem() =
        withServer({ Reply(404, """{"code":"RESERVATION_NOT_FOUND","detail":"x"}""") }) { source, _ ->
            val error = assertFailsWith<ApiError.Problem> { source.reservationCode(RES) }
            assertEquals(404, error.status)
        }

    @Test
    fun permissionsAreTheCodesOfMe() =
        withServer({ Reply(200, me()) }) { _, connection ->
            // A porta não lê mais permissões (ADR 0013): o conjunto dos recados é o `me` que a casca lê da conexão.
            assertEquals(setOf("frontdesk.traces.read", "staff.departments.read"), connection.me().permissions)
            assertEquals(listOf("/api/v1/auth/me"), seen.map { it.path })
        }

    @Test
    fun updateSendsTheEtagInIfMatchAndReturnsTheNewEtagWithoutIdempotencyKey() =
        withServer({ Reply(200, traceJson("novo texto"), etag = "\"v2\"") }) { source, _ ->
            val result = source.update(TRACE, change, "\"v1\"")
            assertEquals("\"v2\"", result.etag)
            assertEquals("novo texto", result.trace.text)
            val request = seen.single()
            assertEquals("\"v1\"", request.ifMatch)
            assertNull(request.idempotencyKey)
        }

    @Test
    fun a412OnUpdateSurfacesAsPreconditionFailed() =
        withServer({ Reply(412, """{"code":"PRECONDITION_FAILED","detail":"etag"}""") }) { source, _ ->
            assertFailsWith<ApiError.PreconditionFailed> { source.update(TRACE, change, "\"velho\"") }
        }

    @Test
    fun a409OnResolveKeepsTheTraceAlreadyResolvedCodeAndIsNotRetried() =
        withServer({ Reply(409, """{"code":"TRACE_ALREADY_RESOLVED","detail":"x"}""") }) { source, _ ->
            val error = assertFailsWith<ApiError.Problem> { source.resolve(TRACE) }
            assertEquals("TRACE_ALREADY_RESOLVED", error.code)
            assertEquals(1, seen.size)
            assertEquals("Este recado já foi resolvido.", TraceText.message(error))
        }

    @Test
    fun pagePassesFilterAndCursorThrough() =
        withServer({ Reply(200, """{"items":[${traceJson()}],"nextCursor":"c2"}""") }) { source, _ ->
            val page = source.page(TraceFilter(roomId = ANY), "c1", 25)
            assertEquals("c2", page.nextCursor)
            assertEquals(1, page.items.size)
            val query = seen.single().query.orEmpty()
            assertTrue("roomId=$ANY" in query && "cursor=c1" in query && "limit=25" in query, query)
        }

    @Test
    fun createFailureIsNeverRetriedNorGivenAnIdempotencyKey() =
        withServer({ Reply(500, """{"code":"INTERNAL","detail":"x"}""") }) { source, _ ->
            val input =
                com.zeny.desktop.core.facade.NewTrace(
                    ANY,
                    BusinessDate(LocalDate.of(2026, 11, 15)),
                    "t",
                    reservationId = RES,
                    roomId = null,
                )
            assertFailsWith<ApiError.Problem> { source.create(input) }
            assertEquals(1, seen.size)
            assertNull(seen.single().idempotencyKey)
        }
}
