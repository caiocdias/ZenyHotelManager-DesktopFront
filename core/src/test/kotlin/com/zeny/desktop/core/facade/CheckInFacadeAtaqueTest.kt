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
private const val GUEST_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val GUEST_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

/** Ataques ao check-in da `ReservationsFacade`: chave, token, corpo, erros do contrato e ids. */
class CheckInFacadeAtaqueTest {
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
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"frontdesk.checkin","permission":"frontdesk.stays.checkin",
        "resourceRef":"reservation:$RES"}"""

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

    private fun key(index: Int) = requests[index].headers["Idempotency-Key"]

    @Test
    fun repeatedRunsWithoutTokenKeepTheSameKeyAndNeverSendAToken() =
        runTest {
            var calls = 0
            core {
                if (++calls < 3) throw IOException("sem rede") else json(checkedIn())
            }.use { core ->
                val action = core.reservations.checkIn(RES, input(), Intent())
                assertFailsWith<ApiError.Network> { action.run(null) }
                assertFailsWith<ApiError.Network> { action.run(null) }
                action.run(null)
                assertEquals(3, requests.size)
                assertEquals(setOf(key(0)), requests.indices.map { key(it) }.toSet())
                assertTrue(requests.all { it.headers["X-Approval-Token"] == null })
            }
        }

    @Test
    fun sameIntentKeepsKeyAfterIncompatibleResponse() =
        runTest {
            var calls = 0
            core {
                if (++calls == 1) json("""{"reservation":42}""") else json(checkedIn())
            }.use { core ->
                val action = core.reservations.checkIn(RES, input(), Intent())
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
                if (++calls == 1) problem(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_IN_USE") else json(checkedIn())
            }.use { core ->
                val action = core.reservations.checkIn(RES, input(), Intent())
                val error = assertFailsWith<ApiError.Problem> { action.run(null) }
                assertEquals("IDEMPOTENCY_KEY_IN_USE", error.code)
                assertEquals(409, error.status)
                action.run(null)
                assertEquals(key(0), key(1))
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
                    else -> json(checkedIn())
                }
            }.use { core ->
                val action = core.reservations.checkIn(RES, input(), Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                assertFailsWith<ApiError.Network> { action.run("tok-1") }
                action.run(null)
                assertEquals(3, requests.size)
                assertNotEquals(key(0), key(1))
                assertEquals(key(1), key(2))
                assertEquals(listOf("tok-1"), requests[2].headers.getAll("X-Approval-Token"))
                assertNull(requests[0].headers["X-Approval-Token"])
            }
        }

    @Test
    fun eachApprovedRunRotatesKeyAndCarriesItsOwnToken() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                val action = core.reservations.checkIn(RES, input(), Intent())
                action.run("tok-1")
                action.run("tok-2")
                assertEquals(listOf("tok-1"), requests[0].headers.getAll("X-Approval-Token"))
                assertEquals(listOf("tok-2"), requests[1].headers.getAll("X-Approval-Token"))
                assertNotEquals(key(0), key(1))
            }
        }

    @Test
    fun differentIntentsUseDifferentKeys() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                core.reservations.checkIn(RES, input(), Intent()).run(null)
                core.reservations.checkIn(RES, input(), Intent()).run(null)
                assertNotEquals(key(0), key(1))
            }
        }

    @Test
    fun actionIsLazyAndSendsNothingUntilRun() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                core.reservations.checkIn(RES, input(), Intent())
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun guestsGoWithGuestIdAndPrimaryInOrder() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                val swapped =
                    input().copy(guests = listOf(CheckInGuest(GUEST_B, false), CheckInGuest(GUEST_A, true)))
                core.reservations.checkIn(RES, swapped, Intent()).run(null)
                val guests = bodyOf(requests.single())["guests"]!!.jsonArray.map { it.jsonObject }
                assertEquals(listOf(GUEST_B, GUEST_A), guests.map { it["guestId"]!!.jsonPrimitive.content })
                assertEquals(listOf(false, true), guests.map { it["primary"]!!.jsonPrimitive.boolean })
                assertTrue(guests.all { it.keys == setOf("guestId", "primary") })
            }
        }

    @Test
    fun feeWithoutDecimalsIsNotReformatted() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                core.reservations.checkIn(RES, input(fee = Money.parse("50")), Intent()).run(null)
                assertEquals("50", bodyOf(requests.single())["earlyCheckInFee"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun feeIsSentAsJsonString() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                core.reservations.checkIn(RES, input(fee = Money.parse("50.00")), Intent()).run(null)
                val fee = bodyOf(requests.single())["earlyCheckInFee"]!!.jsonPrimitive
                assertTrue(fee.isString)
            }
        }

    @Test
    fun zeroFeeAndZeroGuestCountAreNotOmitted() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                core.reservations.checkIn(RES, input(guestCount = 0, fee = Money.parse("0.00")), Intent()).run(null)
                val body = bodyOf(requests.single())
                assertEquals("0.00", body["earlyCheckInFee"]!!.jsonPrimitive.content)
                assertEquals("0", body["guestCount"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun eachOptionalIsOmittedIndependently() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                val action: suspend (CheckInInput) -> Unit = { i ->
                    core.reservations.checkIn(RES, i, Intent()).run(null)
                }
                action(input(guestCount = 3, roomId = null))
                action(input(guestCount = null, roomId = ROOM))
                action(input(guestCount = null, roomId = null, fee = Money.parse("10.00")))
                assertEquals(setOf("guests", "guestCount"), bodyOf(requests[0]).keys)
                assertEquals(setOf("guests", "roomId"), bodyOf(requests[1]).keys)
                assertEquals(setOf("guests", "earlyCheckInFee"), bodyOf(requests[2]).keys)
            }
        }

    @Test
    fun contractRefusalsAreProblemsWithStatusAndCode() =
        runTest {
            val cases =
                listOf(
                    HttpStatusCode.Conflict to "ROOM_NOT_READY",
                    HttpStatusCode.UnprocessableEntity to "GUEST_DATA_INCOMPLETE",
                    HttpStatusCode.Conflict to "EARLY_LATE_FEE_CHANGED",
                    HttpStatusCode.Conflict to "RESERVATION_TRANSITION_NOT_ALLOWED",
                    HttpStatusCode.Conflict to "IDEMPOTENCY_KEY_IN_USE",
                )
            cases.forEach { (status, code) ->
                core { problem(status, code) }.use { core ->
                    val error =
                        assertFailsWith<ApiError.Problem>(code) {
                            core.reservations.checkIn(RES, input(), Intent()).run(null)
                        }
                    assertEquals(status.value, error.status, code)
                    assertEquals(code, error.code)
                    assertFalse(code in error.userMessage, code)
                    assertFalse("detalhe" in error.userMessage, code)
                }
            }
        }

    @Test
    fun approvalRequiredCarriesActionPermissionAndResource() =
        runTest {
            core { json(approvalProblem, HttpStatusCode.Forbidden) }.use { core ->
                val error =
                    assertFailsWith<ApiError.ApprovalRequired> {
                        core.reservations.checkIn(RES, input(), Intent()).run(null)
                    }
                assertEquals("frontdesk.checkin", error.action)
                assertEquals("frontdesk.stays.checkin", error.permission)
                assertEquals("reservation:$RES", error.resourceRef)
            }
        }

    @Test
    fun incompatibleSuccessBodyIsIncompatibleResponse() =
        runTest {
            core { json("""{"reservation":"nao-e-objeto"}""") }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> {
                    core.reservations.checkIn(RES, input(), Intent()).run(null)
                }
            }
        }

    @Test
    fun serverErrorIsUnexpectedWithStatus() =
        runTest {
            core { json("{}", HttpStatusCode.InternalServerError) }.use { core ->
                val error =
                    assertFailsWith<ApiError> { core.reservations.checkIn(RES, input(), Intent()).run(null) }
                assertTrue(error is ApiError.Unexpected || error is ApiError.Problem, error.toString())
            }
        }

    @Test
    fun invalidIdsNeverLeakIntoMessageNorReachTheNetwork() =
        runTest {
            core { json(checkedIn()) }.use { core ->
                val facade = core.reservations
                val secret = "id-secreto-123"
                val attempts: List<() -> Unit> =
                    listOf(
                        { facade.checkIn(secret, input(), Intent()) },
                        { facade.checkIn("", input(), Intent()) },
                        { facade.checkIn(RES, input(roomId = secret), Intent()) },
                        { facade.checkIn(RES, input(roomId = ""), Intent()) },
                        {
                            facade.checkIn(
                                RES,
                                input().copy(guests = listOf(CheckInGuest(GUEST_A, true), CheckInGuest(secret, false))),
                                Intent(),
                            )
                        },
                        {
                            facade.checkIn(RES, input().copy(guests = listOf(CheckInGuest("", true))), Intent())
                        },
                    )
                attempts.forEachIndexed { i, attempt ->
                    val error = assertFailsWith<IllegalArgumentException>("tentativa $i") { attempt() }
                    assertFalse(secret in error.message.orEmpty(), "tentativa $i")
                }
                assertTrue(requests.isEmpty())
            }
        }
}
