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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val GUEST = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f02"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f03"
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

/** A porta do walk-in: o adaptador do `core` contra um servidor HTTP local e o falso compartilhado. */
class WalkInSourceTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private class Seen(
        val method: String,
        val uri: String,
        val key: String?,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    private val quoteJson =
        """{"adults":1,"arrival":"2026-11-14","channel":"DIRECT","childAges":[],"departure":"2026-11-15",
        "nights":1,"offers":[{"accommodationTotal":"300.00","additionalPackagesTotal":"0.00","availableUnits":3,
        "cancellationPolicyId":"$ANY","category":"NORMAL","guaranteePolicyId":"$ANY","includedPackagesTotal":"0.00",
        "mealPlan":"BREAKFAST","nights":[],"ratePlanCode":"BAR","ratePlanId":"$PLAN","ratePlanName":"Tarifa",
        "roomTotal":"300.00","roomTypeCode":"STD","roomTypeId":"$TYPE","roomTypeName":"Standard",
        "total":"300.00"}]}"""

    private val listedJson =
        """{"items":[{"adults":1,"arrival":"2026-11-14","arrivalOverdue":false,"bookingCode":"B-1",
        "bookingId":"$ANY","channel":"DIRECT","children":0,"code":"R-1","dayUse":false,"departure":"2026-11-15",
        "departureOverdue":false,"folioId":"$ANY","holderGuestId":"$ANY","id":"$ANY","origin":"FRONT_DESK",
        "ratePlanCategory":"NORMAL","ratePlanId":"$ANY","roomTypeId":"$ANY","segment":"LEISURE",
        "status":"CONFIRMED","total":"300.00","version":1,
        "roomAssignment":{"doNotMove":false,"id":"$ANY","reallocationRequired":false,"roomId":"$ROOM"}}]}"""

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

    @Test
    fun coreConnectionDelegatesQuoteAndWalkIn() =
        runTest {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                exchange.requestBody.readBytes()
                seen +=
                    Seen(
                        exchange.requestMethod,
                        exchange.requestURI.toString(),
                        exchange.requestHeaders.getFirst("Idempotency-Key"),
                    )
                when (exchange.requestURI.path) {
                    "/api/v1/quotes" -> exchange.reply(200, quoteJson)
                    "/api/v1/reservations" -> exchange.reply(200, listedJson)
                    "/api/v1/walk-ins" -> exchange.reply(201, walkedIn)
                    else -> exchange.reply(404, """{"code":"NOT_FOUND","detail":"x"}""")
                }
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}"
                CoreConnection(ZenyCore(checkNotNull(ServerUrl.parse(url)), MemorySettings())).use { connection ->
                    val source = assertNotNull(connection.receptionSource())

                    val quote = source.quoteStay(date("2026-11-14"), date("2026-11-15"), 1, emptyList())
                    assertEquals("300.00", quote.offers.single().total)

                    assertEquals(setOf(ROOM), source.assignedRoomIds(date("2026-11-14"), date("2026-11-14")))

                    val intent = Intent()
                    val result = source.walkIn(input(), intent).run(null)
                    assertEquals("R-1", result.value.reservation.code)

                    val walkIn = seen.single { it.uri.startsWith("/api/v1/walk-ins") }
                    assertEquals("POST", walkIn.method)
                    assertEquals(intent.key, walkIn.key)
                    val earlier = seen.take(2).map { it.uri.substringBefore('?') }
                    assertEquals(listOf("/api/v1/quotes", "/api/v1/reservations"), earlier)
                }
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun fakeRecordsEachWalkInRunWithKeyAndToken() =
        runTest {
            val fake = FakeReceptionSource()
            fake.walkInOutcomes += ApiError.Network(java.io.IOException("x"))
            fake.walkInResponse = JSON.decodeFromString(WalkInResponse.serializer(), walkedIn)
            val intent = Intent()
            val action = fake.walkIn(input(), intent)

            assertFailsWith<ApiError.Network> { action.run(null) }
            action.run("token-1")

            assertEquals(listOf(WalkInRun(intent.key, null), WalkInRun(intent.key, "token-1")), fake.walkInRuns)
            assertEquals(1, fake.walkInCalls.size)
            assertEquals(intent, fake.walkInCalls.single().intent)
        }

    @Test
    fun fakeQuoteAndAssignedRoomsAreRecordedAndCanBeHeld() =
        runTest {
            val fake = FakeReceptionSource()
            fake.stayQuote = JSON.decodeFromString(QuoteResponse.serializer(), quoteJson)
            fake.assignedRooms = setOf(ROOM)
            fake.holdQuoteStay = true

            var answered = false
            val job =
                launch {
                    fake.quoteStay(date("2026-11-14"), date("2026-11-15"), 2, listOf(4))
                    answered = true
                }
            testScheduler.runCurrent()
            assertTrue(!answered)
            fake.heldQuoteStays.single().complete(Unit)
            job.join()

            val call = QuoteStayCall(date("2026-11-14"), date("2026-11-15"), 2, listOf(4))
            assertEquals(listOf(call), fake.quoteStayCalls)
            assertEquals(setOf(ROOM), fake.assignedRoomIds(date("2026-11-14"), date("2026-11-14")))
            assertEquals(listOf(date("2026-11-14") to date("2026-11-14")), fake.assignedRoomsCalls)
            fake.assignedRoomsError = ApiError.Network(java.io.IOException("x"))
            assertFailsWith<ApiError.Network> { fake.assignedRoomIds(date("2026-11-14"), date("2026-11-14")) }
        }
}
