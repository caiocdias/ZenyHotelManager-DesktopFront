package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.types.BusinessDate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val RES_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val RES_2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e02"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val NOVEMBER_13 = "2026-11-13"

/** A fachada das reservas do dia contra o `MockEngine`, pelo `ZenyCore` real. */
class ReservationsFacadeTest {
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

    private fun item(id: String) =
        """{"adults":2,"arrival":"2026-11-13","arrivalOverdue":false,"bookingCode":"B-1","bookingId":"$ANY_ID",
        "channel":"DIRECT","children":0,"code":"R-1","dayUse":false,"departure":"2026-11-15","departureOverdue":false,
        "folioId":"$ANY_ID","holderGuestId":"$ANY_ID","id":"$id","origin":"FRONT_DESK","ratePlanCategory":"NORMAL",
        "ratePlanId":"$ANY_ID","roomTypeId":"$ANY_ID","segment":"LEISURE","status":"CONFIRMED","total":"300.00",
        "version":1}"""

    private fun state() =
        """{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-13","cancellationPolicyId":"$ANY_ID",
        "cancellationPolicyVersion":1,"childAges":[],"departure":"2026-11-15","guaranteePolicyId":"$ANY_ID",
        "guaranteePolicyVersion":1,"hasSpecialRequests":false,"nights":[],"ratePlanId":"$ANY_ID","roomTotal":"300.00",
        "roomTypeId":"$ANY_ID","segment":"LEISURE","status":"CONFIRMED","stayGuests":[],"total":"300.00"}"""

    private fun entry(
        id: String,
        kind: String,
    ) = """{"after":${state()},"before":${state()},"businessDate":"2026-11-13","changedFields":["status"],"id":"$id",
        "kind":"$kind","occurredAt":"2026-11-13T12:00:00Z","version":2}"""

    private fun assignment(id: String) =
        """{"active":true,"assignedAt":"2026-11-13T12:00:00Z","dayUse":false,"doNotMove":false,"endsOn":"2026-11-15",
        "id":"$id","reason":"INITIAL","reservationId":"$RES_1","roomId":"$ANY_ID","startsOn":"2026-11-13"}"""

    private fun page(
        items: List<String>,
        next: String?,
    ) = """{"items":[${items.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

    @Test
    fun dayPageSendsDateCursorAndLimit() =
        runTest {
            core { json(page(listOf(item(RES_1)), "p2")) }.use { core ->
                val date = BusinessDate.parse(NOVEMBER_13)
                for (list in DayList.entries) {
                    val result = core.reservations.dayPage(list, date, "c1", 200)
                    assertEquals(listOf(RES_1), result.items.map { it.id.toString() })
                    assertEquals("p2", result.nextCursor)
                }
            }
            assertEquals(
                listOf(
                    "/api/v1/reservations/arrivals",
                    "/api/v1/reservations/departures",
                    "/api/v1/reservations/in-house",
                ),
                requests.map { it.url.encodedPath },
            )
            for (request in requests) {
                assertEquals(NOVEMBER_13, request.url.parameters["date"])
                assertEquals("c1", request.url.parameters["cursor"])
                assertEquals("200", request.url.parameters["limit"])
            }
        }

    @Test
    fun dayPageEndsWithoutCursor() =
        runTest {
            core { json(page(listOf(item(RES_1)), null)) }.use { core ->
                val result = core.reservations.dayPage(DayList.ARRIVALS, BusinessDate.parse(NOVEMBER_13), null, 50)
                assertNull(result.nextCursor)
                assertNull(requests.single().url.parameters["cursor"])
                assertEquals("50", requests.single().url.parameters["limit"])
            }
        }

    @Test
    fun historyReadsAllPages() =
        runTest {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(entry(RES_1, "CONFIRM"), entry(RES_2, "CHECK_IN")), "p2"))
                    "p2" -> json(page(listOf(entry(ANY_ID, "CHECK_OUT")), null))
                    else -> problem(HttpStatusCode.BadRequest, "INVALID_CURSOR")
                }
            }.use { core ->
                val history = core.reservations.history(RES_1)
                assertEquals(listOf("CONFIRM", "CHECK_IN", "CHECK_OUT"), history.map { it.kind })
            }
            assertEquals(2, requests.size)
            assertEquals(listOf("200", "200"), requests.map { it.url.parameters["limit"] })
            assertEquals(listOf(null, "p2"), requests.map { it.url.parameters["cursor"] })
            assertEquals("/api/v1/reservations/$RES_1/history", requests.first().url.encodedPath)
        }

    @Test
    fun assignmentsReturnItems() =
        runTest {
            core { json("""{"items":[${assignment(RES_2)}]}""") }.use { core ->
                assertEquals(listOf(RES_2), core.reservations.roomAssignments(RES_1).map { it.id.toString() })
            }
            assertEquals("/api/v1/reservations/$RES_1/room-assignments", requests.single().url.encodedPath)
        }

    @Test
    fun invalidIdFailsBeforeNetwork() =
        runTest {
            core { problem(HttpStatusCode.NotFound, "RESERVATION_NOT_FOUND") }.use { core ->
                for (id in listOf("", "R-1", "../reservations", "$RES_1/history")) {
                    assertFailsWith<IllegalArgumentException>("id $id") { core.reservations.reservation(id) }
                    assertFailsWith<IllegalArgumentException>("id $id") { core.reservations.history(id) }
                    assertFailsWith<IllegalArgumentException>("id $id") { core.reservations.roomAssignments(id) }
                }
            }
            assertEquals(0, requests.size)
        }

    @Test
    fun notFoundIsProblem() =
        runTest {
            core { problem(HttpStatusCode.NotFound, "RESERVATION_NOT_FOUND") }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.reservations.reservation(RES_1) }
                assertEquals(404, error.status)
                assertEquals("RESERVATION_NOT_FOUND", error.code)
                assertEquals("A reserva não foi encontrada.", error.userMessage)
            }
        }
}
