package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.Money
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
private const val GUEST_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val GUEST_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

/** Check-in da `ReservationsFacade`, contra o `MockEngine`. */
class CheckInFacadeTest {
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

    private fun checkedIn() =
        """{"restrictionAlerts":[],
        "reservation":{"adults":2,"arrival":"2026-11-13","bookingId":"$ANY_ID","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$ANY_ID",
        "guests":[{"guestId":"$GUEST_A","primary":true}],"id":"$RES","roomId":"$ROOM","roomTypeId":"$ANY_ID",
        "status":"CHECKED_IN","version":3},
        "room":{"housekeepingStatus":"INSPECTED","inspectionPending":false,"number":"101","occupancy":"OCCUPIED",
        "roomId":"$ROOM","version":4}}"""

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"frontdesk.checkin","permission":"p","resourceRef":"r"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private fun input(
        guestCount: Int? = 2,
        roomId: String? = ROOM,
        fee: Money? = null,
    ) = CheckInInput(
        guests = listOf(CheckInGuest(GUEST_A, true), CheckInGuest(GUEST_B, false)),
        guestCount = guestCount,
        roomId = roomId,
        earlyCheckInFee = fee,
    )

    @Test
    fun checkInSendsIdempotencyKeyAndBody() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                val response = core.reservations.checkIn(RES, input(), Intent()).run(null)
                assertEquals("CHECKED_IN", response.value.reservation.status)
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/$RES/check-in"), request.url.encodedPath)
                assertTrue(request.headers["Idempotency-Key"]!!.isNotBlank())
                val body = bodyOf(request)
                assertEquals(2, body["guestCount"]!!.jsonPrimitive.content.toInt())
                assertEquals(ROOM, body["roomId"]!!.jsonPrimitive.content)
                val guests = body["guests"]!!.jsonArray.map { it.jsonObject }
                assertEquals(listOf(GUEST_A, GUEST_B), guests.map { it["guestId"]!!.jsonPrimitive.content })
                assertEquals(listOf(true, false), guests.map { it["primary"]!!.jsonPrimitive.boolean })
            }
        }

    @Test
    fun checkInRetrySendsSameKey() =
        runTest {
            var calls = 0
            core {
                if (++calls == 1) throw IOException("sem rede")
                json(checkedIn())
            }.use { core ->
                val action = core.reservations.checkIn(RES, input(), Intent())
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
                    json(checkedIn())
                }
            }.use { core ->
                val action = core.reservations.checkIn(RES, input(), Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                action.run("tok-1")
                assertEquals(listOf("tok-1"), requests[1].headers.getAll("X-Approval-Token"))
                assertEquals(1, requests[1].headers.getAll("Idempotency-Key")?.size)
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun earlyFeeAsDecimalText() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                core.reservations.checkIn(RES, input(fee = Money.parse("50.00")), Intent()).run(null)
                assertEquals("50.00", bodyOf(requests.single())["earlyCheckInFee"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun nullOptionalsOmitted() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                core.reservations.checkIn(RES, input(guestCount = null, roomId = null), Intent()).run(null)
                val body = bodyOf(requests.single())
                assertEquals(setOf("guests"), body.keys)
            }
        }

    @Test
    fun roomNotReadyIsProblem() =
        runTest {
            core { problem(HttpStatusCode.Conflict, "ROOM_NOT_READY") }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> { core.reservations.checkIn(RES, input(), Intent()).run(null) }
                assertEquals("ROOM_NOT_READY", error.code)
                assertEquals("A UH ainda não está pronta.", error.userMessage)
            }
        }

    @Test
    fun approvalRequiredSurfaces() =
        runTest {
            core { json(approvalProblem, HttpStatusCode.Forbidden) }.use { core ->
                val error =
                    assertFailsWith<ApiError.ApprovalRequired> {
                        core.reservations.checkIn(RES, input(), Intent()).run(null)
                    }
                assertEquals("frontdesk.checkin", error.action)
            }
        }

    @Test
    fun invalidIdsFailBeforeNetwork() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                val facade = core.reservations
                val bad = "segredo-nao-uuid"
                val failures =
                    listOf(
                        assertFailsWith<IllegalArgumentException> { facade.checkIn(bad, input(), Intent()) },
                        assertFailsWith<IllegalArgumentException> {
                            facade.checkIn(RES, input(roomId = bad), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.checkIn(
                                RES,
                                input().copy(guests = listOf(CheckInGuest(bad, true))),
                                Intent(),
                            )
                        },
                    )
                failures.forEach { assertTrue(bad !in it.message.orEmpty()) }
                assertTrue(requests.isEmpty())
            }
        }
}
