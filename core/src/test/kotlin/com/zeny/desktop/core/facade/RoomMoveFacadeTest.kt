package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM_FROM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ROOM_TO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val ETAG = "\"7\""

/** Versão da reserva e troca de UH da `ReservationsFacade`, contra o `MockEngine`. */
class RoomMoveFacadeTest {
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

    private fun detail() =
        """{"bookingCode":"B-1","holderGuestId":"$ANY_ID","restrictionAlerts":[],
        "reservation":{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-13","bookingId":"$ANY_ID",
        "cancellationTerms":{"deadlineHours":24,"name":"Flex","penaltyType":"NIGHTS","policyId":"$ANY_ID","version":1},
        "childAges":[],"code":"R-1","createdAt":"2026-11-11T13:00:00Z","dayUse":false,"departure":"2026-11-15",
        "folioId":"$ANY_ID",
        "guaranteeTerms":{"name":"Sem garantia","policyId":"$ANY_ID","type":"NONE","version":1},
        "guests":[],"id":"$RES","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL",
        "ratePlanId":"$ANY_ID","roomTotal":"300.00","roomTypeId":"$ANY_ID","segment":"LEISURE","status":"IN_HOUSE",
        "total":"300.00","version":2}}"""

    private fun room(
        id: String,
        occupancy: String,
        number: String,
    ) = """{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"$number","occupancy":"$occupancy",
        "roomId":"$id","version":5}"""

    private fun moved() =
        """{"difference":"0.00","remainingAfter":"0.00","remainingBefore":"0.00","tariff":"KEEP",
        "fromRoom":${room(ROOM_FROM, "VACANT", "101")},"toRoom":${room(ROOM_TO, "OCCUPIED", "102")},
        "reservation":{"adults":2,"arrival":"2026-11-13","bookingId":"$ANY_ID","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$ANY_ID","guests":[],"id":"$RES","roomId":"$ROOM_TO",
        "roomTypeId":"$ANY_ID","status":"IN_HOUSE","version":3}}"""

    private val approvalProblem =
        """{"code":"APPROVAL_REQUIRED","detail":"d","action":"frontdesk.move","permission":"p","resourceRef":"r"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private fun input(
        roomId: String = ROOM_TO,
        reason: String = "GUEST_REQUEST",
        tariff: String = "KEEP",
    ) = RoomMoveInput(roomId, reason, tariff)

    @Test
    fun versionCarriesEtag() =
        runTest {
            core { json(detail(), HttpStatusCode.OK, "ETag" to ETAG) }.use { core ->
                val version = core.reservations.reservationVersion(RES)
                assertEquals(ETAG, version.etag)
                assertEquals("IN_HOUSE", version.reservation.reservation.status)
                assertEquals(HttpMethod.Get, requests.single().method)
            }
        }

    @Test
    fun versionWithoutEtagIsIncompatible() =
        runTest {
            core { json(detail()) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.reservations.reservationVersion(RES) }
            }
        }

    @Test
    fun moveSendsIfMatch() =
        runTest {
            core { json(moved()) }.use { core ->
                val response = core.reservations.moveRoom(RES, input(), ETAG, Intent()).run(null)
                assertEquals(
                    ROOM_TO,
                    response.value.toRoom.roomId
                        .toString(),
                )
                val request = requests.single()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/$RES/room-moves"), request.url.encodedPath)
                assertEquals(ETAG, request.headers["If-Match"])
                assertTrue(request.headers["Idempotency-Key"]!!.isNotBlank())
                assertNull(request.headers["X-Approval-Token"])
                val body = bodyOf(request)
                assertEquals(ROOM_TO, body["roomId"]!!.jsonPrimitive.content)
                assertEquals("GUEST_REQUEST", body["reason"]!!.jsonPrimitive.content)
                assertEquals("KEEP", body["tariff"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun moveRetrySendsSameKey() =
        runTest {
            var calls = 0
            core {
                if (++calls == 1) throw IOException("sem rede")
                json(moved())
            }.use { core ->
                val action = core.reservations.moveRoom(RES, input(), ETAG, Intent())
                assertFailsWith<ApiError.Network> { action.run(null) }
                action.run(null)
                assertEquals(2, requests.size)
                assertEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
                assertEquals(ETAG, requests[1].headers["If-Match"])
            }
        }

    @Test
    fun approvedMoveCarriesTokenAndNewKey() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(approvalProblem, HttpStatusCode.Forbidden)
                } else {
                    json(moved())
                }
            }.use { core ->
                val action = core.reservations.moveRoom(RES, input(), ETAG, Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                action.run("tok-1")
                assertEquals(listOf("tok-1"), requests[1].headers.getAll("X-Approval-Token"))
                assertEquals(1, requests[1].headers.getAll("Idempotency-Key")?.size)
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
                assertEquals(ETAG, requests[1].headers["If-Match"])
            }
        }

    @Test
    fun preconditionFailedIsTyped() =
        runTest {
            core { problem(HttpStatusCode.PreconditionFailed, "PRECONDITION_FAILED") }.use { core ->
                assertFailsWith<ApiError.PreconditionFailed> {
                    core.reservations.moveRoom(RES, input(), ETAG, Intent()).run(null)
                }
            }
        }

    @Test
    fun notReadyIsProblem() =
        runTest {
            core { problem(HttpStatusCode.Conflict, "ROOM_NOT_READY") }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.moveRoom(RES, input(), ETAG, Intent()).run(null)
                    }
                assertEquals("ROOM_NOT_READY", error.code)
            }
        }

    @Test
    fun sameRoomIsProblem() =
        runTest {
            core { problem(HttpStatusCode.UnprocessableEntity, "ROOM_MOVE_SAME_ROOM") }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.moveRoom(RES, input(), ETAG, Intent()).run(null)
                    }
                assertEquals("ROOM_MOVE_SAME_ROOM", error.code)
                assertEquals("A UH de destino é a mesma da origem.", error.userMessage)
            }
        }

    @Test
    fun invalidInputsFailBeforeNetwork() =
        runTest {
            core { json(moved()) }.use { core ->
                val facade = core.reservations
                val bad = "segredo-nao-uuid"
                val failures =
                    listOf(
                        assertFailsWith<IllegalArgumentException> { facade.moveRoom(bad, input(), ETAG, Intent()) },
                        assertFailsWith<IllegalArgumentException> {
                            facade.moveRoom(RES, input(roomId = bad), ETAG, Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.moveRoom(RES, input(reason = bad), ETAG, Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.moveRoom(RES, input(reason = "INITIAL"), ETAG, Intent())
                        },
                        assertFailsWith<IllegalArgumentException> {
                            facade.moveRoom(RES, input(tariff = bad), ETAG, Intent())
                        },
                        assertFailsWith<IllegalArgumentException> { facade.reservationVersion(bad) },
                    )
                failures.forEach { assertTrue(bad !in it.message.orEmpty()) }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun blankEtagFailsBeforeNetwork() =
        runTest {
            core { json(moved()) }.use { core ->
                listOf("", "   ").forEach { blank ->
                    val error =
                        assertFailsWith<IllegalArgumentException> {
                            core.reservations.moveRoom(RES, input(), blank, Intent())
                        }
                    assertEquals("etag inválido", error.message)
                }
                assertTrue(requests.isEmpty())
            }
        }
}
