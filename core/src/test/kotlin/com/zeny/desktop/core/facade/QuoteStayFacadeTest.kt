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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val TYPE_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val TYPE_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val ROOM_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ROOM_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02"
private const val ROOM_C = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a03"
private const val ARRIVAL = "2026-11-13"
private const val DEPARTURE = "2026-11-15"

/** Cotação da estadia e UHs alocadas da `ReservationsFacade`, contra o `MockEngine`. */
class QuoteStayFacadeTest {
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

    private fun offer(
        typeId: String,
        code: String,
    ) = """{"accommodationTotal":"300.00","additionalPackagesTotal":"0.00","availableUnits":3,
        "cancellationPolicyId":"$ANY_ID","category":"NORMAL","guaranteePolicyId":"$ANY_ID",
        "includedPackagesTotal":"0.00","mealPlan":"BED_AND_BREAKFAST","nights":[],"ratePlanCode":"$code",
        "ratePlanId":"$ANY_ID","ratePlanName":"Tarifa $code","roomTotal":"300.00","roomTypeCode":"STD",
        "roomTypeId":"$typeId","roomTypeName":"Standard","total":"300.00"}"""

    private fun quote(offers: List<String>) =
        """{"adults":2,"arrival":"$ARRIVAL","channel":"DIRECT","childAges":[5,8],"departure":"$DEPARTURE",
        "nights":2,"offers":[${offers.joinToString(",")}]}"""

    private fun listed(roomId: String?): String {
        val assignment =
            if (roomId == null) {
                ""
            } else {
                ""","roomAssignment":{"doNotMove":false,"id":"$ANY_ID","reallocationRequired":false,"roomId":"$roomId"}"""
            }
        return """{"adults":2,"arrival":"$ARRIVAL","arrivalOverdue":false,"bookingCode":"B-1","bookingId":"$ANY_ID",
        "channel":"DIRECT","children":0,"code":"R-1","dayUse":false,"departure":"$DEPARTURE","departureOverdue":false,
        "folioId":"$ANY_ID","holderGuestId":"$ANY_ID","id":"$ANY_ID","origin":"FRONT_DESK",
        "ratePlanCategory":"NORMAL","ratePlanId":"$ANY_ID","roomTypeId":"$ANY_ID","segment":"LEISURE",
        "status":"CONFIRMED","total":"300.00","version":1$assignment}"""
    }

    private fun page(
        items: List<String>,
        next: String?,
    ) = """{"items":[${items.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

    private fun date(text: String) = BusinessDate.parse(text)

    @Test
    fun quoteSendsStayAndDirectChannel() =
        runTest {
            core { json(quote(listOf(offer(TYPE_A, "BAR")))) }.use { core ->
                core.reservations.quoteStay(date(ARRIVAL), date(DEPARTURE), 2, listOf(5, 8))
                val request = requests.single()
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/api/v1/quotes", request.url.encodedPath)
                val query = request.url.parameters
                assertEquals(ARRIVAL, query["arrival"])
                assertEquals(DEPARTURE, query["departure"])
                assertEquals("2", query["adults"])
                assertEquals("5,8", query["childAges"])
                assertEquals("DIRECT", query["channel"])
                assertNull(query["roomTypeIds"])
                assertNull(query["ratePlanIds"])
                assertNull(query["includeUnavailable"])
            }
        }

    @Test
    fun noChildrenOmitsChildAges() =
        runTest {
            core { json(quote(emptyList())) }.use { core ->
                core.reservations.quoteStay(date(ARRIVAL), date(DEPARTURE), 1, emptyList())
                assertNull(requests.single().url.parameters["childAges"])
            }
        }

    @Test
    fun offersReturnedInOrder() =
        runTest {
            core { json(quote(listOf(offer(TYPE_B, "B2"), offer(TYPE_A, "A1")))) }.use { core ->
                val result = core.reservations.quoteStay(date(ARRIVAL), date(DEPARTURE), 2, emptyList())
                assertEquals(listOf("B2", "A1"), result.offers.map { it.ratePlanCode })
                assertEquals("300.00", result.offers.first().total)
            }
        }

    @Test
    fun emptyOffersIsNotAnError() =
        runTest {
            core { json(quote(emptyList())) }.use { core ->
                val result = core.reservations.quoteStay(date(ARRIVAL), date(DEPARTURE), 2, emptyList())
                assertTrue(result.offers.isEmpty())
            }
        }

    @Test
    fun stayInvalidIsProblem() =
        runTest {
            val body =
                """{"code":"STAY_INVALID","detail":"d","errors":[{"field":"departure","code":"STAY_INVALID",
                "message":"Saída inválida."}]}"""
            core { json(body, HttpStatusCode.BadRequest) }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.quoteStay(date(ARRIVAL), date(ARRIVAL), 2, emptyList())
                    }
                assertEquals(400, error.status)
                assertEquals("STAY_INVALID", error.code)
            }
        }

    @Test
    fun assignedRoomIdsReadsAllPagesWithFilters() =
        runTest {
            core { request ->
                when (request.url.parameters["cursor"]) {
                    null -> json(page(listOf(listed(ROOM_A), listed(ROOM_B)), "p2"))
                    "p2" -> json(page(listOf(listed(ROOM_B), listed(ROOM_C)), null))
                    else -> problem(HttpStatusCode.BadRequest, "INVALID_CURSOR")
                }
            }.use { core ->
                val ids = core.reservations.assignedRoomIds(date(ARRIVAL), date("2026-11-14"))
                assertEquals(setOf(ROOM_A, ROOM_B, ROOM_C), ids)
            }
            assertEquals(2, requests.size)
            for (request in requests) {
                assertEquals("/api/v1/reservations", request.url.encodedPath)
                assertEquals(ARRIVAL, request.url.parameters["from"])
                assertEquals("2026-11-14", request.url.parameters["to"])
                assertEquals("PROVISIONAL,CONFIRMED", request.url.parameters["status"])
                assertEquals("200", request.url.parameters["limit"])
            }
            assertEquals(listOf(null, "p2"), requests.map { it.url.parameters["cursor"] })
        }

    @Test
    fun assignedRoomIdsSkipsMissingAssignment() =
        runTest {
            val nulled = listed(null).dropLast(1) + ""","roomAssignment":null}"""
            core { json(page(listOf(listed(null), nulled, listed(ROOM_A)), null)) }.use { core ->
                assertEquals(setOf(ROOM_A), core.reservations.assignedRoomIds(date(ARRIVAL), date(ARRIVAL)))
            }
        }

    @Test
    fun assignedRoomIdsRejectsReversedRangeBeforeNetwork() =
        runTest {
            core { json(page(emptyList(), null)) }.use { core ->
                assertFailsWith<IllegalArgumentException> {
                    core.reservations.assignedRoomIds(date(DEPARTURE), date(ARRIVAL))
                }
                assertTrue(requests.isEmpty())
            }
        }
}
