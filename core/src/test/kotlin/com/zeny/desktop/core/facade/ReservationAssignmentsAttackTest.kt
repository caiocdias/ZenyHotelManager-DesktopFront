package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

/** Ataques à alocação de UH e à cotação de early da `ReservationsFacade`. */
class ReservationAssignmentsAttackTest {
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
        "ratePlanId":"$ANY_ID","roomTotal":"300.00","roomTypeId":"$ANY_ID","segment":"LEISURE","status":"CONFIRMED",
        "total":"300.00","version":2}}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private suspend fun ReservationsFacade.runAll(
        id: String = RES,
        roomId: String = ROOM,
    ): List<suspend () -> Any> =
        listOf(
            { roomSuggestions(id) },
            { assignRoom(id, roomId, null) },
            { unassignRoom(id) },
            { setDoNotMove(id, true) },
            { earlyLateQuote(id) },
        )

    @Test
    fun exactMethodsAndPathsOfTheFiveOperations() =
        runTest {
            core { request ->
                when {
                    request.url.encodedPath.endsWith("room-suggestions") -> {
                        json(
                            """{"arrival":"2026-11-13","departure":"2026-11-15","floorPreferenceConsidered":false,
                            "items":[],"reservationId":"$RES","roomTypeId":"$ANY_ID"}""",
                        )
                    }

                    request.url.encodedPath.endsWith("early-late-quote") -> {
                        json(
                            """{"arrival":"2026-11-13","checkInTime":"14:00","checkOutTime":"12:00",
                            "departure":"2026-11-15","earlyCheckIn":{"offered":false},"lateCheckOut":{"offered":false},
                            "reservationId":"$RES","timezone":"America/Sao_Paulo"}""",
                        )
                    }

                    else -> {
                        json(detail())
                    }
                }
            }.use { core ->
                val facade = core.reservations
                facade.runAll().forEach { it() }
                val seen = requests.map { it.method to it.url.encodedPath }
                assertEquals(
                    listOf(
                        HttpMethod.Get to "/api/v1/reservations/$RES/room-suggestions",
                        HttpMethod.Post to "/api/v1/reservations/$RES/assign-room",
                        HttpMethod.Post to "/api/v1/reservations/$RES/unassign-room",
                        HttpMethod.Post to "/api/v1/reservations/$RES/do-not-move",
                        HttpMethod.Get to "/api/v1/reservations/$RES/early-late-quote",
                    ),
                    seen,
                )
                for (request in requests) {
                    assertFalse(request.headers.contains("Idempotency-Key"), request.url.encodedPath)
                    assertTrue(request.url.parameters.isEmpty(), request.url.encodedPath)
                }
            }
        }

    @Test
    fun assignWithDoNotMoveFalseSendsTheFalse() =
        runTest {
            core { json(detail()) }.use { core ->
                core.reservations.assignRoom(RES, ROOM, false)
                val body = bodyOf(requests.single())
                assertEquals(setOf("roomId", "doNotMove"), body.keys)
                assertFalse(body.getValue("doNotMove").jsonPrimitive.boolean)
            }
        }

    @Test
    fun assignWithoutDoNotMoveSendsOnlyTheRoom() =
        runTest {
            core { json(detail()) }.use { core ->
                core.reservations.assignRoom(RES, ROOM, null)
                assertEquals(setOf("roomId"), bodyOf(requests.single()).keys)
            }
        }

    @Test
    fun setDoNotMoveFalseSendsOnlyTheFalse() =
        runTest {
            core { json(detail()) }.use { core ->
                core.reservations.setDoNotMove(RES, false)
                val body = bodyOf(requests.single())
                assertEquals(setOf("doNotMove"), body.keys)
                assertFalse(body.getValue("doNotMove").jsonPrimitive.boolean)
            }
        }

    @Test
    fun blankAndSpacedIdsFailBeforeNetworkWithoutEchoingTheId() =
        runTest {
            core { json(detail()) }.use { core ->
                val badIds = listOf("", " ", "\t", " $RES", "$RES ", " $RES ", "id-secreto-123", "{$RES}", "$RES/x")
                for (bad in badIds) {
                    for (call in core.reservations.runAll(id = bad)) {
                        val error = assertFailsWith<IllegalArgumentException>("id='$bad'") { call() }
                        assertEquals("id de reserva inválido", error.message)
                    }
                    val room =
                        assertFailsWith<IllegalArgumentException>("room='$bad'") {
                            core.reservations.assignRoom(RES, bad, null)
                        }
                    assertEquals("id de UH inválido", room.message)
                    assertFalse(room.message.orEmpty().contains("secreto"))
                }
                assertTrue(requests.isEmpty(), "nenhuma requisição deveria sair")
            }
        }

    @Test
    fun invalidReservationIsCheckedBeforeInvalidRoom() =
        runTest {
            core { json(detail()) }.use { core ->
                val error = assertFailsWith<IllegalArgumentException> { core.reservations.assignRoom("x", "y", null) }
                assertEquals("id de reserva inválido", error.message)
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun contractErrorsBecomeProblemWithTheirStatusAndCode() =
        runTest {
            val cases =
                listOf(
                    HttpStatusCode.Conflict to "ROOM_ALREADY_ASSIGNED",
                    HttpStatusCode.Conflict to "ROOM_ASSIGNMENT_LOCKED",
                    HttpStatusCode.Conflict to "ROOM_OUT_OF_ORDER",
                    HttpStatusCode.Conflict to "ROOM_TYPE_MISMATCH",
                    HttpStatusCode.Conflict to "ROOM_INACTIVE",
                    HttpStatusCode.Conflict to "RESERVATION_STAY_ENDED",
                    HttpStatusCode.Conflict to "ROOM_ASSIGNMENT_NOT_ALLOWED",
                    HttpStatusCode.Forbidden to "ACCESS_DENIED",
                )
            for ((status, code) in cases) {
                core { problem(status, code) }.use { core ->
                    for (call in core.reservations.runAll()) {
                        val error = assertFailsWith<ApiError.Problem>("$status $code") { call() }
                        assertEquals(status.value, error.status)
                        assertEquals(code, error.code)
                        assertFalse(error.userMessage.contains(code), "código cru na mensagem: ${error.userMessage}")
                        assertFalse(error.userMessage.contains("{"), error.userMessage)
                    }
                }
            }
        }

    @Test
    fun lockedOutOfOrderAndMismatchHaveDistinctReadableMessages() =
        runTest {
            val codes =
                listOf("ROOM_ASSIGNMENT_LOCKED", "ROOM_OUT_OF_ORDER", "ROOM_TYPE_MISMATCH", "RESERVATION_STAY_ENDED")
            val messages =
                codes.map { code ->
                    core { problem(HttpStatusCode.Conflict, code) }.use { core ->
                        assertFailsWith<ApiError.Problem> { core.reservations.assignRoom(RES, ROOM, null) }.userMessage
                    }
                }
            assertEquals(codes.size, messages.toSet().size, messages.toString())
            for (message in messages) assertFalse(message.contains("detalhe"), message)
        }

    @Test
    fun incompatibleBodiesBecomeIncompatibleResponse() =
        runTest {
            for (body in listOf("{}", "[]", "null", "não é json", """{"items":"x"}""", "")) {
                core { json(body) }.use { core ->
                    for (call in core.reservations.runAll()) {
                        assertFailsWith<ApiError.IncompatibleResponse>("corpo='$body'") { call() }
                    }
                }
            }
        }

    @Test
    fun quoteWithOfferedFalseAndNoOtherFieldsIsAccepted() =
        runTest {
            val body =
                """{"arrival":"2026-11-13","checkInTime":"14:00","checkOutTime":"12:00","departure":"2026-11-15",
                "earlyCheckIn":{"offered":false},"lateCheckOut":{"offered":false},"reservationId":"$RES",
                "timezone":"America/Sao_Paulo"}"""
            core { json(body) }.use { core ->
                val early = core.reservations.earlyLateQuote(RES).earlyCheckIn
                assertFalse(early.offered)
                assertNull((early.fee as? de.quati.kotlin.util.Option.Some)?.value)
                assertNull((early.from as? de.quati.kotlin.util.Option.Some)?.value)
            }
        }

    @Test
    fun quoteWithExplicitNullsKeepsThemNull() =
        runTest {
            val body =
                """{"arrival":"2026-11-13","checkInTime":"14:00","checkOutTime":"12:00","departure":"2026-11-15",
                "earlyCheckIn":{"fee":null,"from":null,"limitTime":null,"offered":false,"until":null},
                "lateCheckOut":{"fee":null,"offered":false},"reservationId":"$RES","timezone":"America/Sao_Paulo"}"""
            core { json(body) }.use { core ->
                val early = core.reservations.earlyLateQuote(RES).earlyCheckIn
                assertFalse(early.offered)
                assertEquals(
                    de.quati.kotlin.util.Option
                        .Some(null),
                    early.fee,
                )
                assertEquals(
                    de.quati.kotlin.util.Option
                        .Some(null),
                    early.until,
                )
            }
        }

    @Test
    fun quoteWithoutOfferedIsIncompatible() =
        runTest {
            val body =
                """{"arrival":"2026-11-13","checkInTime":"14:00","checkOutTime":"12:00","departure":"2026-11-15",
                "earlyCheckIn":{"fee":"50.00"},"lateCheckOut":{"offered":false},"reservationId":"$RES",
                "timezone":"America/Sao_Paulo"}"""
            core { json(body) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.reservations.earlyLateQuote(RES) }
            }
        }

    @Test
    fun emptySuggestionsReturnAnEmptyList() =
        runTest {
            val body =
                """{"arrival":"2026-11-13","departure":"2026-11-15","floorPreferenceConsidered":true,
                "items":[],"reservationId":"$RES","roomTypeId":"$ANY_ID"}"""
            core { json(body) }.use { core ->
                assertTrue(core.reservations.roomSuggestions(RES).isEmpty())
            }
        }

    @Test
    fun suggestionKeepsCurrentAndFloorPreferenceFlags() =
        runTest {
            val body =
                """{"arrival":"2026-11-13","departure":"2026-11-15","floorPreferenceConsidered":true,
                "items":[{"current":true,"featureIds":[],"matchedFeatureIds":[],"matchesFloorPreference":false,
                "number":"301","outOfService":false,"roomId":"$ROOM"}],"reservationId":"$RES","roomTypeId":"$ANY_ID"}"""
            core { json(body) }.use { core ->
                val item = core.reservations.roomSuggestions(RES).single()
                assertTrue(item.current)
                assertFalse(item.matchesFloorPreference)
                assertEquals(ROOM, item.roomId.toString())
                assertNotNull(item.number)
            }
        }
}
