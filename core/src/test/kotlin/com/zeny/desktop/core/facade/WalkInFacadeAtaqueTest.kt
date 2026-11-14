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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9b01"
private const val GUEST_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val GUEST_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

/** Ataques ao walk-in da `ReservationsFacade`: chave, token, corpo, erros do contrato e ids. */
class WalkInFacadeAtaqueTest {
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
        childAges: List<Int> = emptyList(),
        guests: List<CheckInGuest> = listOf(CheckInGuest(GUEST_A, true)),
        segment: String = "LEISURE",
    ) = WalkInInput(
        departure = BusinessDate.parse("2026-11-15"),
        adults = 1,
        childAges = childAges,
        ratePlanId = PLAN,
        roomTypeId = null,
        roomId = ROOM,
        segment = segment,
        guests = guests,
        guestCount = null,
    )

    private fun key(index: Int) = requests[index].headers["Idempotency-Key"]

    @Test
    fun emptyChildAgesAreStillSentAsEmptyArray() =
        runTest {
            core { json(walkedIn(), HttpStatusCode.Created) }.use { core ->
                core.reservations.walkIn(input(), Intent()).run(null)
                val body = bodyOf(requests.single())
                assertEquals(0, body["childAges"]!!.jsonArray.size)
                assertTrue("roomTypeId" !in body.keys && "guestCount" !in body.keys)
                assertEquals(1, body["guests"]!!.jsonArray.size)
            }
        }

    @Test
    fun walkInPostsToWalkInsWithoutTokenHeader() =
        runTest {
            core { json(walkedIn(), HttpStatusCode.Created) }.use { core ->
                core.reservations.walkIn(input(), Intent()).run(null)
                assertNull(requests.single().headers["X-Approval-Token"])
            }
        }

    @Test
    fun actionIsLazyAndSendsNothingUntilRun() =
        runTest {
            core { json(walkedIn(), HttpStatusCode.Created) }.use { core ->
                core.reservations.walkIn(input(), Intent())
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun okTwoHundredIsAlsoSuccess() =
        runTest {
            core { json(walkedIn(), HttpStatusCode.OK) }.use { core ->
                assertEquals(
                    "R-1",
                    core.reservations
                        .walkIn(input(), Intent())
                        .run(null)
                        .value.reservation.code,
                )
            }
        }

    @Test
    fun sameIntentKeepsKeyAfterIncompatibleResponse() =
        runTest {
            var calls = 0
            core {
                if (++calls ==
                    1
                ) {
                    json("""{"reservation":42}""", HttpStatusCode.Created)
                } else {
                    json(walkedIn(), HttpStatusCode.Created)
                }
            }.use { core ->
                val action = core.reservations.walkIn(input(), Intent())
                assertFailsWith<ApiError.IncompatibleResponse> { action.run(null) }
                action.run(null)
                assertEquals(key(0), key(1))
            }
        }

    @Test
    fun sameIntentKeepsKeyAfterIdempotencyKeyInUse() =
        runTest {
            var calls = 0
            core {
                if (++calls == 1) {
                    problem(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_IN_USE")
                } else {
                    json(walkedIn(), HttpStatusCode.Created)
                }
            }.use { core ->
                val action = core.reservations.walkIn(input(), Intent())
                val error = assertFailsWith<ApiError.Problem> { action.run(null) }
                assertEquals("IDEMPOTENCY_KEY_IN_USE", error.code)
                action.run(null)
                assertEquals(key(0), key(1))
            }
        }

    @Test
    fun repeatedNetworkFailuresKeepSameKeyAndNeverSendAToken() =
        runTest {
            var calls = 0
            core {
                if (++calls < 3) throw IOException("sem rede") else json(walkedIn(), HttpStatusCode.Created)
            }.use { core ->
                val action = core.reservations.walkIn(input(), Intent())
                assertFailsWith<ApiError.Network> { action.run(null) }
                assertFailsWith<ApiError.Network> { action.run(null) }
                action.run(null)
                assertEquals(setOf(key(0)), requests.indices.map { key(it) }.toSet())
                assertTrue(requests.all { it.headers["X-Approval-Token"] == null })
            }
        }

    @Test
    fun runAfterApprovedKeepsApprovedKeyAndToken() =
        runTest {
            var calls = 0
            core {
                when (++calls) {
                    1 -> json(approvalProblem, HttpStatusCode.Forbidden)
                    2 -> throw IOException("sem rede")
                    else -> json(walkedIn(), HttpStatusCode.Created)
                }
            }.use { core ->
                val action = core.reservations.walkIn(input(), Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                assertFailsWith<ApiError.Network> { action.run("tok-1") }
                action.run(null)
                assertNotEquals(key(0), key(1))
                assertEquals(key(1), key(2))
                assertEquals(listOf("tok-1"), requests[2].headers.getAll("X-Approval-Token"))
            }
        }

    @Test
    fun differentIntentsUseDifferentKeys() =
        runTest {
            core { json(walkedIn(), HttpStatusCode.Created) }.use { core ->
                core.reservations.walkIn(input(), Intent()).run(null)
                core.reservations.walkIn(input(), Intent()).run(null)
                assertNotEquals(key(0), key(1))
            }
        }

    @Test
    fun guestsGoInOrderWithPrimary() =
        runTest {
            core { json(walkedIn(), HttpStatusCode.Created) }.use { core ->
                val swapped = input(guests = listOf(CheckInGuest(GUEST_B, false), CheckInGuest(GUEST_A, true)))
                core.reservations.walkIn(swapped, Intent()).run(null)
                val guests = bodyOf(requests.single())["guests"]!!.jsonArray.map { it.jsonObject }
                assertEquals(listOf(GUEST_B, GUEST_A), guests.map { it["guestId"]!!.jsonPrimitive.content })
                assertEquals(listOf(false, true), guests.map { it["primary"]!!.jsonPrimitive.boolean })
            }
        }

    @Test
    fun everySegmentOfTheContractIsAccepted() =
        runTest {
            core { json(walkedIn(), HttpStatusCode.Created) }.use { core ->
                val segments = listOf("LEISURE", "BUSINESS", "EVENT", "OTHER")
                segments.forEach { core.reservations.walkIn(input(segment = it), Intent()).run(null) }
                assertEquals(segments, requests.map { bodyOf(it)["segment"]!!.jsonPrimitive.content })
            }
        }

    @Test
    fun unknownProblemsMapToProblemWithStatusAndCode() =
        runTest {
            listOf("DEPOSIT_INSUFFICIENT" to 422, "ROOM_ALREADY_ASSIGNED" to 409).forEach { (code, status) ->
                core { problem(HttpStatusCode.fromValue(status), code) }.use { core ->
                    val error =
                        assertFailsWith<ApiError.Problem> { core.reservations.walkIn(input(), Intent()).run(null) }
                    assertEquals(status, error.status)
                    assertEquals(code, error.code)
                    assertFalse(code in error.userMessage, code)
                }
            }
        }

    @Test
    fun uncatalogedSaleRefusalShowsServerDetail() =
        runTest {
            core {
                json(
                    """{"code":"CODIGO_NOVO","detail":"Chegada fechada nesta data.","refusals":[{"code":"X"}]}""",
                    HttpStatusCode.UnprocessableEntity,
                )
            }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> { core.reservations.walkIn(input(), Intent()).run(null) }
                assertEquals(422, error.status)
                assertEquals("CODIGO_NOVO", error.code)
                assertEquals("Chegada fechada nesta data.", error.userMessage)
            }
        }

    @Test
    fun incompatibleSuccessBodyIsIncompatibleResponse() =
        runTest {
            core { json("""{"reservation":42}""", HttpStatusCode.Created) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.reservations.walkIn(input(), Intent()).run(null) }
            }
        }

    @Test
    fun invalidIdsInSecondGuestAndBlankValuesFailBeforeNetwork() =
        runTest {
            core { json(walkedIn(), HttpStatusCode.Created) }.use { core ->
                val bad = "segredo-nao-uuid"
                val second = input(guests = listOf(CheckInGuest(GUEST_A, true), CheckInGuest(bad, false)))
                val failures =
                    listOf(
                        assertFailsWith<IllegalArgumentException> { core.reservations.walkIn(second, Intent()) },
                        assertFailsWith<IllegalArgumentException> {
                            core.reservations.walkIn(input().copy(ratePlanId = ""), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.reservations.walkIn(input().copy(roomTypeId = " "), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.reservations.walkIn(input(segment = ""), Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            core.reservations.walkIn(input(segment = "leisure"), Intent())
                        },
                    )
                failures.forEach { assertFalse(bad in it.message.orEmpty()) }
                assertTrue(requests.isEmpty())
            }
        }
}
