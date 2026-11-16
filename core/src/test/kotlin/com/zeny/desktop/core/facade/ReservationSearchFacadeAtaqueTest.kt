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
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ATK_RES = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01"
private const val ATK_ANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val ATK_CPF = "529.982.247-25"
private const val ATK_CPF_DIGITS = "52998224725"
private const val ATK_NAME = "Fulana Ficticia"
private const val ATK_NAME_ENCODED = "Fulana%20Ficticia"

/** Ataques à busca de reservas: validação antes da rede, ordem dos status, vazamento de termo e troca de causa. */
class ReservationSearchFacadeAtaqueTest {
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

    private fun page(next: String? = null) =
        """{"items":[{"adults":2,"arrival":"2026-11-13","arrivalOverdue":false,"bookingCode":"B-1",
        "bookingId":"$ATK_ANY","channel":"DIRECT","children":0,"code":"R-1","dayUse":false,"departure":"2026-11-15",
        "departureOverdue":false,"folioId":"$ATK_ANY","holderGuestId":"$ATK_ANY","id":"$ATK_RES","origin":"FRONT_DESK",
        "ratePlanCategory":"NORMAL","ratePlanId":"$ATK_ANY","roomTypeId":"$ATK_ANY","segment":"LEISURE",
        "status":"CONFIRMED","total":"300.00","version":1}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

    private fun bodyOf(request: HttpRequestData): JsonObject {
        val text =
            when (val body = request.body) {
                is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                else -> error("corpo inesperado: ${body::class}")
            }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun date(text: String) = BusinessDate.parse(text)

    private fun chain(error: Throwable): List<Throwable> = generateSequence(error) { it.cause }.toList()

    private fun assertNoTerm(
        error: Throwable,
        terms: List<String>,
    ) {
        for (link in chain(error)) {
            val text = link.toString() + link.message.orEmpty()
            for (term in terms) assertFalse(term in text, "$term em ${link::class}")
        }
    }

    private val docQuery = DocumentQuery("CPF", ATK_CPF, null)

    @Test
    fun searchSortsAllStatusesInContractOrderRegardlessOfInsertion() =
        runTest {
            core { json(page()) }.use { core ->
                val shuffled = setOf("NO_SHOW", "CANCELLED", "IN_HOUSE", "PROVISIONAL", "CHECKED_OUT", "CONFIRMED")
                core.reservations.search(ReservationFilter(statuses = shuffled), null, 10)
            }
            assertEquals(
                "PROVISIONAL,CONFIRMED,IN_HOUSE,CHECKED_OUT,CANCELLED,NO_SHOW",
                requests.single().url.parameters["status"],
            )
        }

    @Test
    fun searchRejectsLowercaseBlankAndCompositeStatusesBeforeNetwork() =
        runTest {
            core { json(page()) }.use { core ->
                for (bad in listOf("confirmed", "", " CONFIRMED", "CONFIRMED,IN_HOUSE")) {
                    assertFailsWith<IllegalArgumentException>(bad) {
                        core.reservations.search(ReservationFilter(statuses = setOf(bad)), null, 10)
                    }
                    assertFailsWith<IllegalArgumentException>(bad) {
                        core.reservations.searchByDocument(docQuery, setOf(bad), null, 10)
                    }
                }
                assertFailsWith<IllegalArgumentException> {
                    core.reservations.search(ReservationFilter(statuses = setOf("CONFIRMED", "FOO")), null, 10)
                }
            }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun searchAcceptsSameDayPeriodAndOpenEndedPeriods() =
        runTest {
            core { json(page()) }.use { core ->
                val sameDay = ReservationFilter(from = date("2026-11-10"), to = date("2026-11-10"))
                core.reservations.search(sameDay, null, 10)
                core.reservations.search(ReservationFilter(from = date("2026-11-10")), null, 10)
                core.reservations.search(ReservationFilter(to = date("2026-11-10")), null, 10)
            }
            assertEquals("2026-11-10", requests[0].url.parameters["from"])
            assertEquals("2026-11-10", requests[0].url.parameters["to"])
            assertNull(requests[1].url.parameters["to"])
            assertNull(requests[2].url.parameters["from"])
        }

    @Test
    fun searchNameBoundaryTwoCharsGoesAndBlankIsOmittedNotRejected() =
        runTest {
            core { json(page()) }.use { core ->
                core.reservations.search(ReservationFilter(name = " Jo "), null, 10)
                core.reservations.search(ReservationFilter(name = "   "), null, 10)
                assertFailsWith<IllegalArgumentException> {
                    core.reservations.search(ReservationFilter(name = " é "), null, 10)
                }
            }
            assertEquals(2, requests.size)
            assertEquals("Jo", requests[0].url.parameters["name"])
            assertFalse(requests[1].url.parameters.contains("name"))
        }

    @Test
    fun validationMessagesCarryNeitherNameNorDatesNorStatusNorType() =
        runTest {
            core { json(page()) }.use { core ->
                val shortName =
                    assertFailsWith<IllegalArgumentException> {
                        core.reservations.search(ReservationFilter(name = " Q "), null, 10)
                    }
                assertFalse("Q" in shortName.toString().replace("IllegalArgumentException", ""))
                val period =
                    assertFailsWith<IllegalArgumentException> {
                        core.reservations.search(
                            ReservationFilter(from = date("2026-11-10"), to = date("2026-11-09")),
                            null,
                            10,
                        )
                    }
                assertFalse("2026" in period.toString())
                assertFalse("11-10" in period.toString())
                val status =
                    assertFailsWith<IllegalArgumentException> {
                        core.reservations.search(ReservationFilter(statuses = setOf("SEGREDO")), null, 10)
                    }
                assertFalse("SEGREDO" in status.toString())
                val type =
                    assertFailsWith<IllegalArgumentException> {
                        val query = DocumentQuery("TIPOSECRETO", ATK_CPF, null)
                        core.reservations.searchByDocument(query, emptySet(), null, 10)
                    }
                assertFalse("TIPOSECRETO" in type.toString())
                assertFalse(ATK_CPF in type.toString())
            }
        }

    @Test
    fun searchByDocumentRejectsLowercaseTypeAndBlankNumberVariantsBeforeNetwork() =
        runTest {
            core { json(page()) }.use { core ->
                val bad =
                    listOf(
                        DocumentQuery("cpf", ATK_CPF, null),
                        DocumentQuery("", ATK_CPF, null),
                        DocumentQuery("CPF", "", null),
                        DocumentQuery("CPF", " \t\n ", null),
                    )
                for (query in bad) {
                    assertFailsWith<IllegalArgumentException> {
                        core.reservations.searchByDocument(query, emptySet(), null, 10)
                    }
                }
            }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun searchByDocumentSendsStatusesInContractOrderAndNumberOnlyInBody() =
        runTest {
            core { json(page()) }.use { core ->
                core.reservations.searchByDocument(
                    DocumentQuery("CPF", ATK_CPF, "br"),
                    setOf("NO_SHOW", "PROVISIONAL", "CANCELLED"),
                    "cur",
                    10,
                )
            }
            val request = requests.single()
            assertEquals(
                listOf("PROVISIONAL", "CANCELLED", "NO_SHOW"),
                bodyOf(request)["statuses"]!!.jsonArray.map { (it as JsonPrimitive).content },
            )
            assertEquals("BR", (bodyOf(request)["issuingCountry"] as JsonPrimitive).content)
            val raw = request.url.toString() + request.url.encodedPath + request.url.encodedQuery
            assertFalse(ATK_CPF_DIGITS in raw)
            assertTrue(request.headers.entries().none { (_, values) -> values.any { ATK_CPF_DIGITS in it } })
        }

    @Test
    fun searchEmptyStatusesNeverSendsStatusParameter() =
        runTest {
            core { json(page()) }.use { core ->
                core.reservations.search(ReservationFilter(statuses = emptySet()), null, 10)
            }
            assertFalse(
                requests
                    .single()
                    .url.parameters
                    .contains("status"),
            )
        }

    @Test
    fun nextCursorBlankOrAbsentIsNullAndRepeatedCursorIsReturnedAsIs() =
        runTest {
            var next: String? = null
            core { json(page(next)) }.use { core ->
                assertNull(core.reservations.search(ReservationFilter(), null, 10).nextCursor)
                next = ""
                assertNull(core.reservations.search(ReservationFilter(), null, 10).nextCursor)
                next = "same"
                assertEquals("same", core.reservations.search(ReservationFilter(), "same", 10).nextCursor)
                next = " "
                assertNull(core.reservations.searchByDocument(docQuery, emptySet(), null, 10).nextCursor)
                next = "same"
                assertEquals("same", core.reservations.searchByDocument(docQuery, emptySet(), "same", 10).nextCursor)
            }
        }

    @Test
    fun networkFailureCauseIsReplacedForEveryTransportFailureKind() =
        runTest {
            val terms = listOf(ATK_NAME, ATK_NAME_ENCODED, ATK_CPF, ATK_CPF_DIGITS)
            val failures: List<() -> Throwable> =
                listOf(
                    { IOException("falha em http://servidor.teste/api/v1/reservations?name=$ATK_NAME_ENCODED") },
                    { UnresolvedAddressException() },
                )
            for (make in failures) {
                core { throw make() }.use { core ->
                    val byName =
                        assertFailsWith<ApiError.Network> {
                            core.reservations.search(ReservationFilter(name = ATK_NAME), null, 10)
                        }
                    val byDoc =
                        assertFailsWith<ApiError.Network> {
                            core.reservations.searchByDocument(docQuery, emptySet(), null, 10)
                        }
                    for (error in listOf(byName, byDoc)) {
                        assertNoTerm(error, terms)
                        assertNotNull(error.cause)
                        assertTrue(chain(error).none { it is IOException }, "cadeia ainda tem a exceção original")
                    }
                }
            }
        }

    @Test
    fun incompatibleResponseCauseCarriesNoBodyOrTerm() =
        runTest {
            val terms = listOf(ATK_NAME, ATK_CPF, ATK_CPF_DIGITS)
            val bodies =
                listOf(
                    """{"items":"$ATK_NAME $ATK_CPF""",
                    """{"items":[{"id":"$ATK_NAME","code":"$ATK_CPF_DIGITS"}]}""",
                    """não é json $ATK_NAME $ATK_CPF""",
                )
            for (body in bodies) {
                core { json(body) }.use { core ->
                    val byName =
                        assertFailsWith<ApiError.IncompatibleResponse> {
                            core.reservations.search(ReservationFilter(name = ATK_NAME), null, 10)
                        }
                    val byDoc =
                        assertFailsWith<ApiError.IncompatibleResponse> {
                            core.reservations.searchByDocument(docQuery, emptySet(), null, 10)
                        }
                    for (error in listOf(byName, byDoc)) assertNoTerm(error, terms)
                }
            }
        }

    @Test
    fun problemFromDocumentSearchKeepsTypedCodeWithoutNumber() =
        runTest {
            core { problem(HttpStatusCode.UnprocessableEntity, "DOCUMENT_NUMBER_INVALID") }.use { core ->
                val error =
                    assertFailsWith<ApiError.Problem> {
                        core.reservations.searchByDocument(docQuery, emptySet(), null, 10)
                    }
                assertEquals("DOCUMENT_NUMBER_INVALID", error.code)
                assertNoTerm(error, listOf(ATK_CPF, ATK_CPF_DIGITS))
            }
        }

    @Test
    fun filterAndQueryToStringHideDataEvenWhenEmptyOrInCollections() {
        val filter = ReservationFilter(name = ATK_NAME, code = "R-9")
        val query = DocumentQuery("CPF", ATK_CPF, "BR")
        assertEquals("ReservationFilter(***)", ReservationFilter().toString())
        for (text in listOf("$filter", listOf(filter).toString(), mapOf(1 to filter).toString())) {
            assertFalse(ATK_NAME in text)
            assertFalse("R-9" in text)
        }
        for (text in listOf("$query", listOf(query).toString(), "${query.copy(number = ATK_CPF_DIGITS)}")) {
            assertFalse(ATK_CPF in text)
            assertFalse(ATK_CPF_DIGITS in text)
        }
    }
}
