package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.json
import com.zeny.desktop.core.problem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

/** As quatro listas de pendências de reservas contra o `MockEngine`; só dados fictícios. */
class PendingFacadeTest {
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

    private fun next(cursor: String?) = cursor?.let { ""","nextCursor":"$it"""" }.orEmpty()

    private fun guarantees(cursor: String? = null) =
        """{"items":[{"arrival":"2026-11-13","bookingId":"$ANY_ID","code":"R-1","departure":"2026-11-15",
        "dueAt":"2026-11-14T12:00:00Z","folioId":"$ANY_ID","guaranteePolicyId":"$ANY_ID","guaranteeType":"CARD",
        "holderGuestId":"$ANY_ID","id":"$RES_1","kind":"PROVISIONAL","overdue":true,
        "provisionalUntil":"2026-11-14T12:00:00Z","ratePlanId":"$ANY_ID","roomTypeId":"$ANY_ID",
        "status":"PROVISIONAL","total":"300.00"}]${next(cursor)}}"""

    private fun shortfalls() =
        """{"items":[{"depositReceived":"50.00","guaranteedAt":"2026-11-10T12:00:00Z","missing":"100.00",
        "requiredDeposit":"150.00","reservation":${item(RES_1)}}]}"""

    private fun noShows(cursor: String? = null) = """{"items":[${item(RES_1)}]${next(cursor)}}"""

    private fun reallocations(cursor: String? = null) =
        """{"items":[{"arrival":"2026-11-13","departure":"2026-11-15","reservationCode":"R-1","reservationId":"$RES_1",
        "status":"CONFIRMED","assignment":{"active":true,"assignedAt":"2026-11-12T10:00:00Z","dayUse":false,
        "doNotMove":false,"endsOn":"2026-11-15","id":"$ANY_ID","reason":"INITIAL","reservationId":"$RES_1",
        "roomId":"$ANY_ID","startsOn":"2026-11-13"}}]${next(cursor)}}"""

    @Test
    fun pendingGuaranteesSendsToInUtcAndNoFrom() =
        runTest {
            val to = Instant.parse("2028-11-15T12:00:00Z")
            core { json(guarantees("g2")) }.use { core ->
                val page = core.pending.pendingGuarantees(to, "g1", 25)
                assertEquals(
                    RES_1,
                    page.items
                        .single()
                        .id
                        .toString(),
                )
                assertEquals("g2", page.nextCursor)
            }
            val request = requests.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/reservations/pending-guarantees", request.url.encodedPath)
            assertEquals("2028-11-15T12:00:00Z", request.url.parameters["to"])
            assertFalse(request.url.parameters.contains("from"))
            assertEquals("g1", request.url.parameters["cursor"])
            assertEquals("25", request.url.parameters["limit"])
        }

    @Test
    fun depositShortfallsHasNoCursorOrParameters() =
        runTest {
            core { json(shortfalls()) }.use { core ->
                val items = core.pending.depositShortfalls()
                assertEquals("100.00", items.single().missing)
                assertEquals(
                    RES_1,
                    items
                        .single()
                        .reservation.id
                        .toString(),
                )
            }
            val request = requests.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/reservations/deposit-shortfalls", request.url.encodedPath)
            assertTrue(request.url.parameters.isEmpty())
        }

    @Test
    fun expectedNoShowsSendsNoDate() =
        runTest {
            core { json(noShows("n2")) }.use { core ->
                val page = core.pending.expectedNoShows("n1", 30)
                assertEquals(
                    RES_1,
                    page.items
                        .single()
                        .id
                        .toString(),
                )
                assertEquals("n2", page.nextCursor)
            }
            val request = requests.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/reservations/expected-no-shows", request.url.encodedPath)
            assertFalse(request.url.parameters.contains("date"))
            assertEquals("n1", request.url.parameters["cursor"])
            assertEquals("30", request.url.parameters["limit"])
        }

    @Test
    fun reallocationRequiredPassesCursorAndLimit() =
        runTest {
            core { json(reallocations("r2")) }.use { core ->
                val page = core.pending.reallocationRequired("r1", 40)
                assertEquals(
                    RES_1,
                    page.items
                        .single()
                        .reservationId
                        .toString(),
                )
                assertEquals("r2", page.nextCursor)
            }
            val request = requests.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/room-assignments/reallocation-required", request.url.encodedPath)
            assertEquals("r1", request.url.parameters["cursor"])
            assertEquals("40", request.url.parameters["limit"])
        }

    @Test
    fun blankCursorEndsList() =
        runTest {
            val to = Instant.parse("2028-11-15T12:00:00Z")
            for (blank in listOf("  ", "")) {
                core { json(guarantees(blank)) }.use {
                    assertNull(it.pending.pendingGuarantees(to, null, 10).nextCursor)
                }
                core { json(noShows(blank)) }.use { assertNull(it.pending.expectedNoShows(null, 10).nextCursor) }
                core { json(reallocations(blank)) }.use {
                    assertNull(it.pending.reallocationRequired(null, 10).nextCursor)
                }
            }
            core { json(guarantees()) }.use { assertNull(it.pending.pendingGuarantees(to, null, 10).nextCursor) }
        }

    @Test
    fun problemBecomesApiError() =
        runTest {
            val to = Instant.parse("2028-11-15T12:00:00Z")
            core { problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED") }.use { core ->
                assertFailsWith<ApiError.Problem> { core.pending.pendingGuarantees(to, null, 10) }
                assertFailsWith<ApiError.Problem> { core.pending.depositShortfalls() }
                assertFailsWith<ApiError.Problem> { core.pending.expectedNoShows(null, 10) }
                assertFailsWith<ApiError.Problem> { core.pending.reallocationRequired(null, 10) }
            }
        }
}
