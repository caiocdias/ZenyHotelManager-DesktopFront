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
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RES_1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ANY_ID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val CPF = "529.982.247-25"
private const val CPF_DIGITS = "52998224725"
private const val NAME_TERM = "Demonstração"
private const val NAME_ENCODED = "Demonstra%C3%A7%C3%A3o"

/** A busca de reservas por filtros e por documento contra o `MockEngine`; só dados fictícios. */
class ReservationSearchFacadeTest {
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

    private fun page(next: String? = null) =
        """{"items":[${item(RES_1)}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

    private fun bodyOf(request: HttpRequestData): JsonObject {
        val text =
            when (val body = request.body) {
                is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                else -> error("corpo inesperado: ${body::class}")
            }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun date(text: String) = BusinessDate.parse(text)

    @Test
    fun searchSendsOnlyPresentFilters() =
        runTest {
            core { json(page()) }.use { core ->
                val filter =
                    ReservationFilter(
                        code = " R-2026-000001 ",
                        bookingCode = "  ",
                        from = date("2026-11-01"),
                        to = date("2026-11-30"),
                    )
                val result = core.reservations.search(filter, null, 50)
                assertEquals(
                    RES_1,
                    result.items
                        .single()
                        .id
                        .toString(),
                )
            }
            val request = requests.single()
            assertEquals(HttpMethod.Get, request.method)
            val url = request.url
            assertEquals("/api/v1/reservations", url.encodedPath)
            assertEquals("R-2026-000001", url.parameters["code"])
            assertEquals("2026-11-01", url.parameters["from"])
            assertEquals("2026-11-30", url.parameters["to"])
            assertEquals("50", url.parameters["limit"])
            for (absent in listOf("bookingCode", "externalId", "name", "status", "cursor")) {
                assertFalse(url.parameters.contains(absent), absent)
            }
        }

    @Test
    fun searchJoinsStatusesInContractOrder() =
        runTest {
            core { json(page()) }.use { core ->
                core.reservations.search(ReservationFilter(statuses = setOf("IN_HOUSE", "CONFIRMED")), null, 10)
            }
            assertEquals("CONFIRMED,IN_HOUSE", requests.single().url.parameters["status"])
        }

    @Test
    fun searchPassesCursorAndReturnsNextCursor() =
        runTest {
            var next: String? = "p2"
            core { json(page(next)) }.use { core ->
                val first = core.reservations.search(ReservationFilter(), "p1", 10)
                assertEquals("p2", first.nextCursor)
                next = "  "
                assertNull(core.reservations.search(ReservationFilter(), "p2", 10).nextCursor)
            }
            assertEquals("p1", requests[0].url.parameters["cursor"])
            assertEquals("p2", requests[1].url.parameters["cursor"])
        }

    @Test
    fun searchTrimsName() =
        runTest {
            core { json(page()) }.use { core ->
                core.reservations.search(ReservationFilter(name = "  Fulana  "), null, 10)
            }
            assertEquals("Fulana", requests.single().url.parameters["name"])
        }

    @Test
    fun searchRejectsBadInputBeforeNetwork() =
        runTest {
            core { json(page()) }.use { core ->
                val bad =
                    listOf(
                        ReservationFilter(statuses = setOf("FOO")),
                        ReservationFilter(from = date("2026-11-10"), to = date("2026-11-09")),
                        ReservationFilter(name = " a "),
                    )
                for (filter in bad) {
                    val error = assertFailsWith<IllegalArgumentException> { core.reservations.search(filter, null, 10) }
                    assertFalse("FOO" in error.message.orEmpty())
                }
            }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun documentSearchGoesInBodyNotUrl() =
        runTest {
            core { json(page()) }.use { core ->
                val query = DocumentQuery("CPF", " $CPF ", null)
                val result = core.reservations.searchByDocument(query, setOf("IN_HOUSE", "CONFIRMED"), "c1", 20)
                assertEquals(
                    RES_1,
                    result.items
                        .single()
                        .id
                        .toString(),
                )
            }
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/reservations/search-by-document", request.url.encodedPath)
            val url = request.url.toString()
            assertFalse(CPF in url)
            assertFalse(CPF_DIGITS in url)
            assertEquals("c1", request.url.parameters["cursor"])
            assertEquals("20", request.url.parameters["limit"])
            val body = bodyOf(request)
            assertEquals(CPF, (body["number"] as JsonPrimitive).content)
            assertEquals("CPF", (body["type"] as JsonPrimitive).content)
            assertEquals(
                listOf("CONFIRMED", "IN_HOUSE"),
                body["statuses"]!!.jsonArray.map { (it as JsonPrimitive).content },
            )
        }

    @Test
    fun documentSearchUppercasesCountryAndOmitsBlank() =
        runTest {
            core { json(page()) }.use { core ->
                core.reservations.searchByDocument(DocumentQuery("PASSPORT", "FX1234567", " ar "), emptySet(), null, 10)
                core.reservations.searchByDocument(DocumentQuery("PASSPORT", "FX1234567", "  "), emptySet(), null, 10)
                core.reservations.searchByDocument(DocumentQuery("PASSPORT", "FX1234567", null), emptySet(), null, 10)
            }
            assertEquals("AR", (bodyOf(requests[0])["issuingCountry"] as JsonPrimitive).content)
            assertFalse("issuingCountry" in bodyOf(requests[1]))
            assertFalse("issuingCountry" in bodyOf(requests[2]))
        }

    @Test
    fun documentSearchErrorsCarryNoNumber() =
        runTest {
            for (status in listOf(HttpStatusCode.UnprocessableEntity, HttpStatusCode.BadRequest)) {
                core { problem(status, "DOCUMENT_NUMBER_INVALID") }.use { core ->
                    val error =
                        assertFailsWith<ApiError.Problem> {
                            core.reservations.searchByDocument(DocumentQuery("CPF", CPF, null), emptySet(), null, 10)
                        }
                    for (text in listOf(error.toString(), error.message.orEmpty())) {
                        assertFalse(CPF in text)
                        assertFalse(CPF_DIGITS in text)
                    }
                }
            }
            core { json(page()) }.use { core ->
                val invalidType =
                    assertFailsWith<IllegalArgumentException> {
                        core.reservations.searchByDocument(DocumentQuery("XYZ", CPF, null), emptySet(), null, 10)
                    }
                assertFalse(CPF in invalidType.message.orEmpty())
                val blank =
                    assertFailsWith<IllegalArgumentException> {
                        core.reservations.searchByDocument(DocumentQuery("CPF", "  ", null), emptySet(), null, 10)
                    }
                assertFalse(CPF in blank.message.orEmpty())
                val status =
                    assertFailsWith<IllegalArgumentException> {
                        core.reservations.searchByDocument(DocumentQuery("CPF", CPF, null), setOf("FOO"), null, 10)
                    }
                assertFalse(CPF in status.message.orEmpty())
            }
            assertEquals(2, requests.size)
        }

    @Test
    fun filterAndQueryToStringHideData() {
        assertEquals("ReservationFilter(***)", ReservationFilter(name = NAME_TERM).toString())
        assertEquals("DocumentQuery(***)", DocumentQuery("CPF", CPF, "BR").toString())
    }

    @Test
    fun networkFailureCarriesNoTerm() =
        runTest {
            val outBuffer = ByteArrayOutputStream()
            val errBuffer = ByteArrayOutputStream()
            val oldOut = System.out
            val oldErr = System.err
            System.setOut(PrintStream(outBuffer, true))
            System.setErr(PrintStream(errBuffer, true))
            try {
                val failures = mutableListOf<ApiError.Network>()
                core { throw IOException("falha em ${it.url}") }.use { core ->
                    failures +=
                        assertFailsWith<ApiError.Network> {
                            core.reservations.search(ReservationFilter(name = NAME_TERM), null, 10)
                        }
                    failures +=
                        assertFailsWith<ApiError.Network> {
                            core.reservations.searchByDocument(DocumentQuery("CPF", CPF, null), emptySet(), null, 10)
                        }
                }
                assertTrue(NAME_ENCODED in requests.first().url.toString())
                for (failure in failures) {
                    var link: Throwable? = failure
                    while (link != null) {
                        val text = link.toString() + link.message.orEmpty()
                        for (term in listOf(NAME_TERM, NAME_ENCODED, CPF, CPF_DIGITS)) {
                            assertFalse(term in text, "$term em ${link::class}")
                        }
                        link = link.cause
                    }
                    assertEquals("java.io.IOException", failure.cause?.message)
                }
            } finally {
                System.setOut(oldOut)
                System.setErr(oldErr)
            }
            assertEquals("", outBuffer.toString())
            assertEquals("", errBuffer.toString())
        }
}
