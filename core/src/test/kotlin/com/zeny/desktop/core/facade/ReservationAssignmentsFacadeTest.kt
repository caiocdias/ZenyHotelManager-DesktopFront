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
import kotlin.test.assertTrue

private const val RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ROOM_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ROOM_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

/** Alocação de UH e cotação de early da `ReservationsFacade`, contra o `MockEngine`. */
class ReservationAssignmentsFacadeTest {
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

    private fun suggestion(
        roomId: String,
        number: String,
        outOfService: Boolean = false,
    ) = """{"current":false,"featureIds":[],"matchedFeatureIds":[],"matchesFloorPreference":true,"number":"$number",
        "outOfService":$outOfService,"roomId":"$roomId"}"""

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

    private fun quote() =
        """{"arrival":"2026-11-13","checkInTime":"14:00","checkOutTime":"12:00","departure":"2026-11-15",
        "earlyCheckIn":{"fee":"50.00","from":"2026-11-13T08:00:00-03:00","limitTime":"08:00","offered":true,
        "until":"2026-11-13T14:00:00-03:00"},"lateCheckOut":{"offered":false},"reservationId":"$RES",
        "timezone":"America/Sao_Paulo"}"""

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    @Test
    fun suggestionsReturnItemsInOrder() =
        runTest {
            val body =
                """{"arrival":"2026-11-13","departure":"2026-11-15","floorPreferenceConsidered":false,
                "items":[${suggestion(ROOM_B, "202")},${suggestion(ROOM_A, "101", true)}],
                "reservationId":"$RES","roomTypeId":"$ANY_ID"}"""
            core { json(body) }.use { core ->
                val items = core.reservations.roomSuggestions(RES)
                assertEquals(listOf("202", "101"), items.map { it.number })
                assertEquals(listOf(true), items.filter { it.outOfService }.map { it.outOfService })
                val request = requests.single()
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/api/v1/reservations/$RES/room-suggestions", request.url.encodedPath)
                assertTrue(request.url.parameters.isEmpty())
            }
        }

    @Test
    fun assignSendsRoomAndDoNotMove() =
        runTest {
            core { json(detail()) }.use { core ->
                core.reservations.assignRoom(RES, ROOM_A, true)
                core.reservations.assignRoom(RES, ROOM_B, null)
                val (withFlag, without) = requests.toList()
                assertEquals(HttpMethod.Post, withFlag.method)
                assertEquals("/api/v1/reservations/$RES/assign-room", withFlag.url.encodedPath)
                val first = bodyOf(withFlag)
                assertEquals(ROOM_A, first.getValue("roomId").jsonPrimitive.content)
                assertTrue(first.getValue("doNotMove").jsonPrimitive.boolean)
                assertFalse(first.containsKey("reason"))
                val second = bodyOf(without)
                assertEquals(ROOM_B, second.getValue("roomId").jsonPrimitive.content)
                assertFalse(second.containsKey("doNotMove"))
                assertFalse(second.containsKey("reason"))
                for (request in requests) assertFalse(request.headers.contains("Idempotency-Key"))
            }
        }

    @Test
    fun unassignAndDoNotMovePostToTheirPaths() =
        runTest {
            core { json(detail()) }.use { core ->
                core.reservations.unassignRoom(RES)
                core.reservations.setDoNotMove(RES, true)
                val (unassign, lock) = requests.toList()
                assertEquals("/api/v1/reservations/$RES/unassign-room", unassign.url.encodedPath)
                assertEquals(HttpMethod.Post, unassign.method)
                assertEquals("/api/v1/reservations/$RES/do-not-move", lock.url.encodedPath)
                assertTrue(bodyOf(lock).getValue("doNotMove").jsonPrimitive.boolean)
            }
        }

    @Test
    fun quoteReadsEarlyOffer() =
        runTest {
            core { json(quote()) }.use { core ->
                val quote = core.reservations.earlyLateQuote(RES)
                val early = quote.earlyCheckIn
                assertEquals("50.00", early.fee.valueOrNull())
                assertEquals("08:00", early.limitTime.valueOrNull())
                assertEquals("2026-11-13T08:00:00-03:00", early.from.valueOrNull())
                assertEquals("2026-11-13T14:00:00-03:00", early.until.valueOrNull())
                assertEquals("America/Sao_Paulo", quote.timezone)
                assertEquals("/api/v1/reservations/$RES/early-late-quote", requests.single().url.encodedPath)
            }
        }

    @Test
    fun invalidIdsFailBeforeNetwork() =
        runTest {
            core { json(detail()) }.use { core ->
                val facade = core.reservations
                val badReservation = assertFailsWith<IllegalArgumentException> { facade.roomSuggestions("x") }
                assertEquals("id de reserva inválido", badReservation.message)
                assertFailsWith<IllegalArgumentException> { facade.unassignRoom("x") }
                assertFailsWith<IllegalArgumentException> { facade.setDoNotMove("x", true) }
                assertFailsWith<IllegalArgumentException> { facade.earlyLateQuote("x") }
                assertFailsWith<IllegalArgumentException> { facade.assignRoom("x", ROOM_A, null) }
                val badRoom = assertFailsWith<IllegalArgumentException> { facade.assignRoom(RES, "x", null) }
                assertEquals("id de UH inválido", badRoom.message)
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun alreadyAssignedMessage() =
        runTest {
            core { problem(HttpStatusCode.Conflict, "ROOM_ALREADY_ASSIGNED") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.reservations.assignRoom(RES, ROOM_A, null) }
                assertEquals("ROOM_ALREADY_ASSIGNED", error.code)
                assertEquals("A UH já está alocada a outra reserva em parte do período.", error.userMessage)
            }
        }

    @Test
    fun notAllowedIsProblem() =
        runTest {
            core { problem(HttpStatusCode.Conflict, "ROOM_ASSIGNMENT_NOT_ALLOWED") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.reservations.assignRoom(RES, ROOM_A, null) }
                assertEquals("ROOM_ASSIGNMENT_NOT_ALLOWED", error.code)
            }
        }

    private fun de.quati.kotlin.util.Option<String?>.valueOrNull(): String? =
        (this as? de.quati.kotlin.util.Option.Some)?.value
}
