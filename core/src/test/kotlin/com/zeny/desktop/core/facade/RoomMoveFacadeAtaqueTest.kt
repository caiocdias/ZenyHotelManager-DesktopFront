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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM_FROM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ROOM_TO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val ETAG = "\"7\""

/** Ataques à versão da reserva e à troca de UH: ETag, chave, token, corpo, erros do contrato e entradas. */
class RoomMoveFacadeAtaqueTest {
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

    private fun key(index: Int) = requests[index].headers["Idempotency-Key"]

    @Test
    fun weakEtagIsReturnedAsReceived() =
        runTest {
            core { json(detail(), HttpStatusCode.OK, "ETag" to "W/\"abc\"") }.use { core ->
                assertEquals("W/\"abc\"", core.reservations.reservationVersion(RES).etag)
            }
        }

    @Test
    fun weakEtagGoesToIfMatchUnchanged() =
        runTest {
            core { json(moved()) }.use { core ->
                core.reservations.moveRoom(RES, input(), "W/\"9\"", Intent()).run(null)
                assertEquals("W/\"9\"", requests.single().headers["If-Match"])
            }
        }

    @Test
    fun blankEtagHeaderIsIncompatible() =
        runTest {
            core { json(detail(), HttpStatusCode.OK, "ETag" to " ") }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.reservations.reservationVersion(RES) }
            }
        }

    @Test
    fun versionWithIncompatibleBodyIsIncompatible() =
        runTest {
            core { json("""{"reservation":42}""", HttpStatusCode.OK, "ETag" to ETAG) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.reservations.reservationVersion(RES) }
            }
        }

    @Test
    fun versionNotFoundIsProblemWithStatus() =
        runTest {
            core { problem(HttpStatusCode.NotFound, "RESERVATION_NOT_FOUND") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.reservations.reservationVersion(RES) }
                assertEquals(404, error.status)
                assertEquals("RESERVATION_NOT_FOUND", error.code)
            }
        }

    @Test
    fun versionRejectsEmptyIdBeforeNetwork() =
        runTest {
            core { json(detail(), HttpStatusCode.OK, "ETag" to ETAG) }.use { core ->
                assertFailsWith<IllegalArgumentException> { core.reservations.reservationVersion("") }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun moveBodyHasExactlyRoomReasonAndTariff() =
        runTest {
            core { json(moved()) }.use { core ->
                core.reservations
                    .moveRoom(RES, input(reason = "OPERATIONAL", tariff = "RECALCULATE"), ETAG, Intent())
                    .run(null)
                val body = bodyOf(requests.single())
                assertEquals(setOf("roomId", "reason", "tariff"), body.keys)
                assertEquals("OPERATIONAL", body["reason"]!!.jsonPrimitive.content)
                assertEquals("RECALCULATE", body["tariff"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun everyAllowedReasonAndTariffIsAccepted() =
        runTest {
            core { json(moved()) }.use { core ->
                val reasons = listOf("GUEST_REQUEST", "OUT_OF_ORDER", "ROOM_ISSUE", "OPERATIONAL")
                reasons.forEach { core.reservations.moveRoom(RES, input(reason = it), ETAG, Intent()).run(null) }
                listOf("KEEP", "RECALCULATE").forEach {
                    core.reservations.moveRoom(RES, input(tariff = it), ETAG, Intent()).run(null)
                }
                val bodies = requests.map { bodyOf(it) }
                assertEquals(reasons, bodies.take(4).map { it["reason"]!!.jsonPrimitive.content })
                assertEquals(listOf("KEEP", "RECALCULATE"), bodies.drop(4).map { it["tariff"]!!.jsonPrimitive.content })
            }
        }

    @Test
    fun outOfOptionsValuesFailBeforeNetworkWithoutEchoingThem() =
        runTest {
            core { json(moved()) }.use { core ->
                val facade = core.reservations
                listOf("", "guest_request", "GUEST_REQUEST ", "XPTO-SEGREDO").forEach { reason ->
                    val error =
                        assertFailsWith<IllegalArgumentException>("motivo '$reason'") {
                            facade.moveRoom(RES, input(reason = reason), ETAG, Intent())
                        }
                    assertFalse(reason.isNotBlank() && reason in error.message.orEmpty())
                }
                listOf("", "keep", "RECALCULATE ", "TARIFA-SEGREDO").forEach { tariff ->
                    val error =
                        assertFailsWith<IllegalArgumentException>("tarifa '$tariff'") {
                            facade.moveRoom(RES, input(tariff = tariff), ETAG, Intent())
                        }
                    assertFalse(tariff.isNotBlank() && tariff in error.message.orEmpty())
                }
                assertFailsWith<IllegalArgumentException> { facade.moveRoom(RES, input(roomId = ""), ETAG, Intent()) }
                assertFailsWith<IllegalArgumentException> { facade.moveRoom("", input(), ETAG, Intent()) }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun moveActionIsLazy() =
        runTest {
            core { json(moved()) }.use { core ->
                core.reservations.moveRoom(RES, input(), ETAG, Intent())
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun differentIntentsUseDifferentKeys() =
        runTest {
            core { json(moved()) }.use { core ->
                core.reservations.moveRoom(RES, input(), ETAG, Intent()).run(null)
                core.reservations.moveRoom(RES, input(), ETAG, Intent()).run(null)
                assertNotEquals(key(0), key(1))
            }
        }

    @Test
    fun sameIntentKeepsKeyAfterIncompatibleResponse() =
        runTest {
            var calls = 0
            core { if (++calls == 1) json("""{"fromRoom":42}""") else json(moved()) }.use { core ->
                val action = core.reservations.moveRoom(RES, input(), ETAG, Intent())
                assertFailsWith<ApiError.IncompatibleResponse> { action.run(null) }
                action.run(null)
                assertEquals(key(0), key(1))
                assertEquals(ETAG, requests[1].headers["If-Match"])
            }
        }

    @Test
    fun sameIntentKeepsKeyAfterIdempotencyKeyInUse() =
        runTest {
            var calls = 0
            core {
                if (++calls == 1) problem(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_IN_USE") else json(moved())
            }.use { core ->
                val action = core.reservations.moveRoom(RES, input(), ETAG, Intent())
                val error = assertFailsWith<ApiError.Problem> { action.run(null) }
                assertEquals("IDEMPOTENCY_KEY_IN_USE", error.code)
                action.run(null)
                assertEquals(key(0), key(1))
            }
        }

    @Test
    fun runAfterApprovedKeepsApprovedKeyTokenAndIfMatch() =
        runTest {
            var calls = 0
            core {
                when (++calls) {
                    1 -> json(approvalProblem, HttpStatusCode.Forbidden)
                    2 -> throw IOException("sem rede")
                    else -> json(moved())
                }
            }.use { core ->
                val action = core.reservations.moveRoom(RES, input(), ETAG, Intent())
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                assertFailsWith<ApiError.Network> { action.run("tok-1") }
                action.run(null)
                assertEquals(3, requests.size)
                assertNotEquals(key(0), key(1))
                assertEquals(key(1), key(2))
                assertEquals(listOf("tok-1"), requests[2].headers.getAll("X-Approval-Token"))
                assertNull(requests[0].headers["X-Approval-Token"])
                assertTrue(requests.all { it.headers["If-Match"] == ETAG })
            }
        }

    @Test
    fun eachApprovedRunRotatesKeyAndCarriesItsOwnToken() =
        runTest {
            core { json(moved()) }.use { core ->
                val action = core.reservations.moveRoom(RES, input(), ETAG, Intent())
                action.run("tok-1")
                action.run("tok-2")
                assertEquals(listOf("tok-1"), requests[0].headers.getAll("X-Approval-Token"))
                assertEquals(listOf("tok-2"), requests[1].headers.getAll("X-Approval-Token"))
                assertNotEquals(key(0), key(1))
            }
        }

    @Test
    fun preconditionFailedKeepsStatusAndIsNotAProblem() =
        runTest {
            core { problem(HttpStatusCode.PreconditionFailed, "PRECONDITION_FAILED") }.use { core ->
                val error =
                    assertFailsWith<ApiError> { core.reservations.moveRoom(RES, input(), ETAG, Intent()).run(null) }
                assertTrue(error is ApiError.PreconditionFailed, error.toString())
                assertFalse("PRECONDITION_FAILED" in error.userMessage)
            }
        }

    @Test
    fun preconditionRequiredIsAnErrorWithStatus428() =
        runTest {
            core { problem(HttpStatusCode.PreconditionRequired, "PRECONDITION_REQUIRED") }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.moveRoom(RES, input(), ETAG, Intent()).run(null)
                    }
                assertEquals(428, error.status)
                assertEquals("PRECONDITION_REQUIRED", error.code)
            }
        }

    @Test
    fun contractRefusalsAreProblemsWithStatusAndCode() =
        runTest {
            val cases =
                listOf(
                    HttpStatusCode.Conflict to "ROOM_NOT_READY",
                    HttpStatusCode.Conflict to "ROOM_ALREADY_ASSIGNED",
                    HttpStatusCode.UnprocessableEntity to "ROOM_MOVE_SAME_ROOM",
                    HttpStatusCode.UnprocessableEntity to "ROOM_TYPE_MISMATCH",
                    HttpStatusCode.UnprocessableEntity to "RESERVATION_STAY_ENDED",
                )
            cases.forEach { (status, code) ->
                core { problem(status, code) }.use { core ->
                    val error =
                        assertFailsWith<ApiError.Problem>(code) {
                            core.reservations.moveRoom(RES, input(), ETAG, Intent()).run(null)
                        }
                    assertEquals(status.value, error.status, code)
                    assertEquals(code, error.code)
                    assertFalse(code in error.userMessage, code)
                }
            }
        }

    @Test
    fun incompatibleSuccessBodyIsIncompatibleResponse() =
        runTest {
            core { json("""{"fromRoom":"nao-e-objeto"}""") }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> {
                    core.reservations.moveRoom(RES, input(), ETAG, Intent()).run(null)
                }
            }
        }
}
