package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.BusinessDate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val GUEST_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val GUEST_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

/** Walk-in da `ReservationsFacade`, contra o `MockEngine`. */
class WalkInFacadeTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    private fun core(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ZenyCore =
        ZenyCore(
            checkNotNull(ServerUrl.parse("http://servidor.teste")),
            MemorySettings(),
            MockEngine { request ->
                requests += request
                handler(request)
            },
        )

    private fun walkedIn() =
        """{"bookingCode":"B-1","bookingId":"$ANY_ID","holderGuestId":"$GUEST_A","restrictionAlerts":[],
        "reservation":{"adults":2,"arrival":"2026-11-14","bookingId":"$ANY_ID","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$ANY_ID",
        "guests":[{"guestId":"$GUEST_A","primary":true}],"id":"$RES","roomId":"$ROOM","roomTypeId":"$TYPE",
        "status":"CHECKED_IN","version":3},
        "room":{"housekeepingStatus":"INSPECTED","inspectionPending":false,"number":"101","occupancy":"OCCUPIED",
        "roomId":"$ROOM","version":4},
        "total":"300.00"}"""

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"frontdesk.walkin","permission":"p","resourceRef":"r"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private fun input(
        roomTypeId: String? = TYPE,
        guestCount: Int? = 2,
        roomId: String = ROOM,
        segment: String = "LEISURE",
    ) = WalkInInput(
        departure = BusinessDate.parse("2026-11-15"),
        adults = 2,
        childAges = listOf(5, 8),
        ratePlanId = PLAN,
        roomTypeId = roomTypeId,
        roomId = roomId,
        segment = segment,
        guests = listOf(CheckInGuest(GUEST_A, true), CheckInGuest(GUEST_B, false)),
        guestCount = guestCount,
    )

    @Test
    fun walkInSendsKeyAndBody() =
        runTest {
            core { json(walkedIn(), HttpStatusCode.Created) }.use { core ->
                val response = core.reservations.walkIn(input(), Intent()).run(null)
                assertEquals("B-1", response.value.bookingCode)
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/walk-ins"), request.url.encodedPath)
                assertTrue(request.headers["Idempotency-Key"]!!.isNotBlank())
                val body = bodyOf(request)
                assertEquals("2026-11-15", body["departure"]!!.jsonPrimitive.content)
                assertEquals(2, body["adults"]!!.jsonPrimitive.content.toInt())
                assertEquals(listOf(5, 8), body["childAges"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
                assertEquals(PLAN, body["ratePlanId"]!!.jsonPrimitive.content)
                assertEquals(TYPE, body["roomTypeId"]!!.jsonPrimitive.content)
                assertEquals(ROOM, body["roomId"]!!.jsonPrimitive.content)
                assertEquals("LEISURE", body["segment"]!!.jsonPrimitive.content)
                assertEquals(2, body["guestCount"]!!.jsonPrimitive.content.toInt())
                val guests = body["guests"]!!.jsonArray.map { it.jsonObject }
                assertEquals(listOf(GUEST_A, GUEST_B), guests.map { it["guestId"]!!.jsonPrimitive.content })
                assertEquals(listOf(true, false), guests.map { it["primary"]!!.jsonPrimitive.boolean })
                val absent = listOf("holderGuestId", "earlyCheckInFee", "preAuthorization", "specialRequests")
                assertTrue(absent.none { it in body.keys }, body.keys.toString())
            }
        }

    @Test
    fun nullOptionalsOmitted() =
        runTest {
            core { json(walkedIn(), HttpStatusCode.Created) }.use { core ->
                core.reservations.walkIn(input(roomTypeId = null, guestCount = null), Intent()).run(null)
                val body = bodyOf(requests.single())
                assertTrue("roomTypeId" !in body.keys && "guestCount" !in body.keys, body.keys.toString())
            }
        }

    @Test
    fun walkInRetrySendsSameKey() =
        runTest {
            var calls = 0
            core {
                if (++calls == 1) throw IOException("sem rede")
                json(walkedIn(), HttpStatusCode.Created)
            }.use { core ->
                val action = core.reservations.walkIn(input(), Intent())
                assertFailsWith<ApiError.Network> { action.run(null) }
                action.run(null)
                assertEquals(2, requests.size)
                assertEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun approvedRetryCarriesTokenAndNewKey() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(approvalProblem, HttpStatusCode.Forbidden)
                } else {
                    json(walkedIn(), HttpStatusCode.Created)
                }
            }.use { core ->
                val action = core.reservations.walkIn(input(), Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                action.run("tok-1")
                assertEquals(listOf("tok-1"), requests[1].headers.getAll("X-Approval-Token"))
                assertEquals(1, requests[1].headers.getAll("Idempotency-Key")?.size)
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun createdIsSuccess() =
        runTest {
            core {
                json(walkedIn(), HttpStatusCode.Created, "Location" to "/api/v1/reservations/$RES")
            }.use { core ->
                val response = core.reservations.walkIn(input(), Intent()).run(null)
                assertEquals("CHECKED_IN", response.value.reservation.status)
                assertEquals("300.00", response.value.total)
            }
        }

    @Test
    fun roomTypeUnavailableIsProblem() =
        runTest {
            core { problem(HttpStatusCode.Conflict, "ROOM_TYPE_UNAVAILABLE") }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> { core.reservations.walkIn(input(), Intent()).run(null) }
                assertEquals("ROOM_TYPE_UNAVAILABLE", error.code)
                assertEquals("Não há UH disponível deste tipo em todas as noites.", error.userMessage)
            }
        }

    @Test
    fun saleRefusalIsProblem() =
        runTest {
            core {
                json(
                    """{"code":"CLOSED_TO_ARRIVAL","detail":"d","refusals":[{"code":"CLOSED_TO_ARRIVAL"}]}""",
                    HttpStatusCode.UnprocessableEntity,
                )
            }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> { core.reservations.walkIn(input(), Intent()).run(null) }
                assertEquals(422, error.status)
                assertEquals("CLOSED_TO_ARRIVAL", error.code)
            }
        }

    @Test
    fun approvalRequiredSurfaces() =
        runTest {
            core { json(approvalProblem, HttpStatusCode.Forbidden) }.use { core ->
                val error =
                    assertFailsWith<ApiError.ApprovalRequired> { core.reservations.walkIn(input(), Intent()).run(null) }
                assertEquals("frontdesk.walkin", error.action)
            }
        }

    @Test
    fun invalidIdsFailBeforeNetwork() =
        runTest {
            core { json(walkedIn(), HttpStatusCode.Created) }.use { core ->
                val facade = core.reservations
                val bad = "segredo-nao-uuid"
                val failures =
                    listOf(
                        assertFailsWith<IllegalArgumentException> {
                            facade.walkIn(input().copy(ratePlanId = bad), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.walkIn(input(roomTypeId = bad), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> { facade.walkIn(input(roomId = bad), Intent()) },
                        assertFailsWith<IllegalArgumentException> {
                            facade.walkIn(input().copy(guests = listOf(CheckInGuest(bad, true))), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.walkIn(input(segment = "segredo-segmento"), Intent())
                        },
                    )
                failures.forEach { assertTrue(bad !in it.message.orEmpty()) }
                assertTrue(requests.isEmpty())
            }
        }
}
