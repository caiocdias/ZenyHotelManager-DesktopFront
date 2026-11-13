package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.config.HousekeepingTransition
import com.zeny.desktop.seed.data.RatePlanSpec
import com.zeny.desktop.seed.data.SeasonSpec
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SERVER = checkNotNull(ServerUrl.parse("http://servidor.teste"))
private const val ID_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val ID_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val ID_C = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c03"
private const val STAMP = "2026-11-12T10:00:00Z"
private val CONTRACT_TRANSITIONS = setOf("MARK_CLEAN", "MARK_INSPECTED", "REJECT_INSPECTION", "MARK_DIRTY")

private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    etag: String? = null,
): HttpResponseData {
    val headers =
        if (etag == null) {
            headersOf(HttpHeaders.ContentType, "application/json")
        } else {
            headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(etag))
        }
    return respond(body, status, headers)
}

private fun status() =
    """{"active":true,"changedAt":"$STAMP","housekeepingStatus":"DIRTY","inspectionPending":false,
    |"number":"101","occupancy":"VACANT","readyForCheckIn":false,"roomId":"$ID_A","roomTypeId":"$ID_C",
    |"serviceFlag":"NONE","version":1}
    """.trimMargin()

private fun season(
    name: String,
    id: String,
) = """{"active":true,"createdAt":"$STAMP","endsOn":"2026-12-31","id":"$id","name":"$name",
    |"startsOn":"2026-12-01","updatedAt":"$STAMP"}
    """.trimMargin()

private fun plan(
    code: String,
    id: String,
) = """{"active":true,"cancellationPolicyId":"$ID_B","category":"NORMAL","channels":["DIRECT"],"code":"$code",
    |"createdAt":"$STAMP","guaranteePolicyId":"$ID_A","id":"$id","mealPlan":"BREAKFAST","name":"Tarifa",
    |"packageIds":[],"roomTypeIds":["$ID_C"],"updatedAt":"$STAMP"}
    """.trimMargin()

private fun overlayResponse() =
    """{"endsOn":"2026-11-14","id":"$ID_B","kind":"OUT_OF_ORDER","reason":"x","responsible":"y",
    |"returnStatus":"DIRTY","roomId":"$ID_A","startsOn":"2026-11-12","openedAt":"$STAMP","updatedAt":"$STAMP",
    |"version":1}
    """.trimMargin()

private const val PRICE_OK =
    """{"changed":0,"created":1,"derivedRatePlanIds":[],"matchedDates":1,"ratePlanId":"$ID_A","removed":0}"""

private fun problem(
    status: Int,
    code: String,
) = """{"code":"$code","title":"T","status":$status,"detail":"d"}"""

class SdkConfigStatusRatesAtaqueTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val bodies = CopyOnWriteArrayList<String>()

    private fun gateway(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SdkConfigGateway(
            SeedCore(
                SERVER,
                MockEngine { request ->
                    requests += request
                    bodies += request.body.toByteArray().decodeToString()
                    handler(request)
                },
            ),
        )

    private fun sent(index: Int): JsonObject = Json.parseToJsonElement(bodies[index]).jsonObject

    private fun statusGateway(
        etags: List<String?> = listOf("\"v1\""),
        postStatus: HttpStatusCode = HttpStatusCode.OK,
        postBody: String = status(),
    ): SdkConfigGateway {
        var gets = 0
        return gateway { request ->
            if (request.method == HttpMethod.Get) {
                json(status(), etag = etags[minOf(gets++, etags.size - 1)])
            } else {
                json(postBody, postStatus)
            }
        }
    }

    // ---------- governança: If-Match ----------

    @Test
    fun housekeepingRepeatsTheWeakEtagExactly() =
        runTest {
            statusGateway(listOf("W/\"7\"")).changeHousekeeping(ID_A, HousekeepingTransition.MARK_DIRTY)
            assertEquals("W/\"7\"", requests[1].headers[HttpHeaders.IfMatch])
        }

    @Test
    fun housekeepingRereadsTheEtagOnEveryTransition() =
        runTest {
            val gateway = statusGateway(listOf("\"v1\"", "\"v2\"", "\"v3\""))
            gateway.changeHousekeeping(ID_A, HousekeepingTransition.MARK_CLEAN)
            gateway.changeHousekeeping(ID_A, HousekeepingTransition.MARK_INSPECTED)
            gateway.changeHousekeeping(ID_A, HousekeepingTransition.MARK_DIRTY)
            assertEquals(
                listOf(
                    HttpMethod.Get,
                    HttpMethod.Post,
                    HttpMethod.Get,
                    HttpMethod.Post,
                    HttpMethod.Get,
                    HttpMethod.Post,
                ),
                requests.map { it.method },
            )
            val ifMatch = requests.filter { it.method == HttpMethod.Post }.map { it.headers[HttpHeaders.IfMatch] }
            assertEquals(listOf("\"v1\"", "\"v2\"", "\"v3\""), ifMatch)
        }

    @Test
    fun housekeepingPreconditionFailedIsNotRetried() =
        runTest {
            val gateway =
                statusGateway(
                    postStatus = HttpStatusCode.PreconditionFailed,
                    postBody = problem(412, "PRECONDITION_FAILED"),
                )
            assertFailsWith<ApiError.PreconditionFailed> {
                gateway.changeHousekeeping(ID_A, HousekeepingTransition.MARK_DIRTY)
            }
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Post), requests.map { it.method })
        }

    @Test
    fun housekeepingPreconditionRequiredKeepsStatus428() =
        runTest {
            val gateway =
                statusGateway(
                    postStatus = HttpStatusCode.PreconditionRequired,
                    postBody = problem(428, "IF_MATCH_REQUIRED"),
                )
            val error =
                assertFailsWith<ApiError.Problem> {
                    gateway.changeHousekeeping(
                        ID_A,
                        HousekeepingTransition.MARK_DIRTY,
                    )
                }
            assertEquals(428, error.status)
            assertEquals("IF_MATCH_REQUIRED", error.code)
            assertEquals(2, requests.size)
        }

    @Test
    fun housekeepingTransitionNotAllowedKeepsCode() =
        runTest {
            val gateway =
                statusGateway(
                    postStatus = HttpStatusCode.UnprocessableEntity,
                    postBody = problem(422, "HOUSEKEEPING_TRANSITION_NOT_ALLOWED"),
                )
            val error =
                assertFailsWith<ApiError.Problem> {
                    gateway.changeHousekeeping(
                        ID_A,
                        HousekeepingTransition.MARK_INSPECTED,
                    )
                }
            assertEquals(422, error.status)
            assertEquals("HOUSEKEEPING_TRANSITION_NOT_ALLOWED", error.code)
        }

    @Test
    fun housekeepingStatusGetFailureMeansNoPost() =
        runTest {
            val gateway = gateway { json(problem(404, "ROOM_NOT_FOUND"), HttpStatusCode.NotFound) }
            assertFailsWith<ApiError.Problem> { gateway.changeHousekeeping(ID_A, HousekeepingTransition.MARK_DIRTY) }
            assertEquals(listOf(HttpMethod.Get), requests.map { it.method })
        }

    @Test
    fun housekeepingUnauthorizedBecomesSessionEnded() =
        runTest {
            val gateway = gateway { json(problem(401, "TOKEN_EXPIRED"), HttpStatusCode.Unauthorized) }
            assertFailsWith<ApiError.SessionEnded> {
                gateway.changeHousekeeping(
                    ID_A,
                    HousekeepingTransition.MARK_DIRTY,
                )
            }
        }

    @Test
    fun housekeepingMalformedRoomIdFailsBeforeAnyRequest() =
        runTest {
            val gateway = statusGateway()
            assertFailsWith<IllegalArgumentException> {
                gateway.changeHousekeeping("nao-e-uuid", HousekeepingTransition.MARK_DIRTY)
            }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun housekeepingBlankEtagIsNotSentAsIfMatch() =
        runTest {
            val gateway = statusGateway(listOf(""))
            runCatching { gateway.changeHousekeeping(ID_A, HousekeepingTransition.MARK_DIRTY) }
            val post = requests.firstOrNull { it.method == HttpMethod.Post }
            assertTrue(post == null || !post.headers[HttpHeaders.IfMatch].isNullOrBlank(), "POST com If-Match vazio")
        }

    // ---------- governança: corpo ----------

    @Test
    fun onlyMarkCleanPendingInspectionSetsTheFlagAndNothingLeaksTheLocalName() =
        runTest {
            val gateway = statusGateway()
            for (transition in HousekeepingTransition.entries) gateway.changeHousekeeping(ID_A, transition)
            val posts = bodies.indices.filter { requests[it].method == HttpMethod.Post }
            assertEquals(HousekeepingTransition.entries.size, posts.size)
            for ((transition, index) in HousekeepingTransition.entries.zip(posts)) {
                val body = sent(index)
                val name = body.getValue("transition").jsonPrimitive.content
                assertTrue(name in CONTRACT_TRANSITIONS, "transição fora do contrato: $name")
                val flag = body["requiresInspection"]?.jsonPrimitive?.content
                val expected = transition == HousekeepingTransition.MARK_CLEAN_PENDING_INSPECTION
                assertEquals(expected, flag == "true", "requiresInspection errado em $transition")
            }
        }

    @Test
    fun housekeepingNeverSendsIdempotencyKeyOrApprovalToken() =
        runTest {
            statusGateway().changeHousekeeping(ID_A, HousekeepingTransition.MARK_CLEAN_PENDING_INSPECTION)
            assertTrue(requests.all { it.headers["Idempotency-Key"] == null && it.headers["X-Approval-Token"] == null })
        }

    // ---------- OOO ----------

    @Test
    fun outOfOrderBodyHasExactlyTheContractKeys() =
        runTest {
            val gateway = gateway { json(overlayResponse(), HttpStatusCode.Created) }
            gateway.openOutOfOrder(ID_A, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9))
            val body = sent(0)
            assertEquals(setOf("endsOn", "kind", "reason", "responsible", "startsOn"), body.keys)
            assertEquals("2026-01-05", body.getValue("startsOn").jsonPrimitive.content)
            assertEquals("2026-01-09", body.getValue("endsOn").jsonPrimitive.content)
            assertEquals("OUT_OF_ORDER", body.getValue("kind").jsonPrimitive.content)
        }

    @Test
    fun outOfOrderOneDayPeriodKeepsBothDatesEqual() =
        runTest {
            val gateway = gateway { json(overlayResponse(), HttpStatusCode.Created) }
            val day = LocalDate.of(2026, 11, 12)
            gateway.openOutOfOrder(ID_A, day, day)
            assertEquals("2026-11-12", sent(0).getValue("startsOn").jsonPrimitive.content)
            assertEquals("2026-11-12", sent(0).getValue("endsOn").jsonPrimitive.content)
        }

    @Test
    fun outOfOrderIsASinglePostWithoutIfMatchOrKeys() =
        runTest {
            val gateway = gateway { json(overlayResponse(), HttpStatusCode.Created) }
            gateway.openOutOfOrder(ID_A, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 14))
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertNull(request.headers[HttpHeaders.IfMatch])
            assertNull(request.headers["Idempotency-Key"])
            assertNull(request.headers["X-Approval-Token"])
        }

    @Test
    fun outOfOrderOverlapIsAProblem409AndIsNotRetried() =
        runTest {
            val gateway = gateway { json(problem(409, "OUT_OF_ORDER_OVERLAP"), HttpStatusCode.Conflict) }
            val error =
                assertFailsWith<ApiError.Problem> {
                    gateway.openOutOfOrder(ID_A, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 14))
                }
            assertEquals(409, error.status)
            assertEquals("OUT_OF_ORDER_OVERLAP", error.code)
            assertEquals(1, requests.size)
        }

    @Test
    fun outOfOrderPeriodInThePastIsAProblem422() =
        runTest {
            val gateway =
                gateway { json(problem(422, "OUT_OF_ORDER_PERIOD_IN_PAST"), HttpStatusCode.UnprocessableEntity) }
            val error =
                assertFailsWith<ApiError.Problem> {
                    gateway.openOutOfOrder(ID_A, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2))
                }
            assertEquals(422, error.status)
            assertEquals("OUT_OF_ORDER_PERIOD_IN_PAST", error.code)
        }

    @Test
    fun outOfOrderMalformedRoomIdFailsBeforeAnyRequest() =
        runTest {
            val gateway = gateway { json(overlayResponse(), HttpStatusCode.Created) }
            assertFailsWith<IllegalArgumentException> {
                gateway.openOutOfOrder("x", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 14))
            }
            assertTrue(requests.isEmpty())
        }

    // ---------- temporadas e planos ----------

    @Test
    fun seasonsReadThreePagesSendingEachCursorAndNoActiveFilter() =
        runTest {
            val gateway =
                gateway { request ->
                    when (request.url.parameters["cursor"]) {
                        null -> json("""{"items":[${season("Baixa", ID_A)}],"nextCursor":"c2"}""")
                        "c2" -> json("""{"items":[${season("Alta", ID_B)}],"nextCursor":"c3"}""")
                        else -> json("""{"items":[${season("Feriado ção", ID_C)}]}""")
                    }
                }
            assertEquals(mapOf("Baixa" to ID_A, "Alta" to ID_B, "Feriado ção" to ID_C), gateway.seasonIds())
            assertEquals(listOf(null, "c2", "c3"), requests.map { it.url.parameters["cursor"] })
            assertTrue(requests.all { it.url.parameters["active"] == null })
        }

    @Test
    fun seasonsErrorOnSecondPageFailsTheWholeListing() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        json("""{"items":[${season("Baixa", ID_A)}],"nextCursor":"c2"}""")
                    } else {
                        json(problem(500, "INTERNAL"), HttpStatusCode.InternalServerError)
                    }
                }
            assertFailsWith<ApiError> { gateway.seasonIds() }
        }

    @Test
    fun ratePlanOnLastPageIsFoundIgnoringCaseAndListingHasNoFilters() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        json("""{"items":[${plan("OUTRO", ID_A)}],"nextCursor":"p2"}""")
                    } else {
                        json("""{"items":[${plan("Bar", ID_B)}]}""")
                    }
                }
            assertEquals(ID_B, gateway.ratePlanId("bAR"))
            assertEquals(listOf(null, "p2"), requests.map { it.url.parameters["cursor"] })
            val keys = requests.flatMap { it.url.parameters.names() }.toSet() - setOf("cursor", "limit")
            assertEquals(
                emptySet(),
                keys,
                "o plano é procurado sem filtro: o servidor recusa código repetido de inativo",
            )
        }

    @Test
    fun ratePlanAbsentReturnsNullAfterReadingEveryPage() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        json("""{"items":[${plan("A", ID_A)}],"nextCursor":"p2"}""")
                    } else {
                        json("""{"items":[]}""")
                    }
                }
            assertNull(gateway.ratePlanId("BAR"))
            assertEquals(2, requests.size)
        }

    @Test
    fun createSeasonDuplicateNameIsAProblem409() =
        runTest {
            val gateway = gateway { json(problem(409, "SEASON_NAME_ALREADY_IN_USE"), HttpStatusCode.Conflict) }
            val error =
                assertFailsWith<ApiError.Problem> {
                    gateway.createSeason(SeasonSpec("Baixa", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)))
                }
            assertEquals("SEASON_NAME_ALREADY_IN_USE", error.code)
            assertEquals(1, requests.size)
        }

    @Test
    fun createRatePlanWithUnknownMealPlanOrChannelFailsBeforeAnyRequest() =
        runTest {
            val gateway = gateway { json(plan("BAR", ID_A), HttpStatusCode.Created) }
            val badMeal = RatePlanSpec("BAR", "T", "NAO_EXISTE", listOf("DIRECT"), listOf("STD"))
            val badChannel = RatePlanSpec("BAR", "T", "BREAKFAST", listOf("NAO_EXISTE"), listOf("STD"))
            assertFailsWith<IllegalArgumentException> { gateway.createRatePlan(badMeal, listOf(ID_C), ID_A, ID_B) }
            assertFailsWith<IllegalArgumentException> { gateway.createRatePlan(badChannel, listOf(ID_C), ID_A, ID_B) }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun createRatePlanBodyKeepsAllRoomTypeIdsInOrder() =
        runTest {
            val gateway = gateway { json(plan("BAR", ID_A), HttpStatusCode.Created) }
            val spec = RatePlanSpec("BAR", "T", "BREAKFAST", listOf("DIRECT"), listOf("STD", "LUX"))
            gateway.createRatePlan(spec, listOf(ID_C, ID_B, ID_A), ID_A, ID_B)
            val ids = sent(0).getValue("roomTypeIds").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf(ID_C, ID_B, ID_A), ids)
        }

    // ---------- preços ----------

    private fun priceBody(
        oneAdult: String,
        twoAdults: String = "1",
        extraAdult: String = "1",
    ): JsonObject {
        requests.clear()
        bodies.clear()
        val gateway = gateway { json(PRICE_OK) }
        kotlinx.coroutines.test.runTest {
            gateway.updatePrices(ID_A, ID_C, ID_B, BigDecimal(oneAdult), BigDecimal(twoAdults), BigDecimal(extraAdult))
        }
        return sent(0).getValue("prices").jsonObject
    }

    @Test
    fun priceScalesAreNormalisedToTwoDecimalPlaces() {
        val expected =
            mapOf(
                "280" to "280.00",
                "280.0" to "280.00",
                "280.5" to "280.50",
                "280.50" to "280.50",
                "280.500" to "280.50",
                "280.00000" to "280.00",
                "0" to "0.00",
                "0.00" to "0.00",
                "0E-10" to "0.00",
                "2.8E+2" to "280.00",
                "1E+6" to "1000000.00",
                "1000000" to "1000000.00",
                "1E+20" to "100000000000000000000.00",
                "0.01" to "0.01",
                "-5" to "-5.00",
            )
        for ((input, output) in expected) {
            assertEquals(output, priceBody(input).getValue("oneAdult").jsonPrimitive.content, "entrada $input")
        }
    }

    @Test
    fun noPriceValueEverUsesScientificNotation() {
        for (input in listOf("1E+3", "1E-2", "0E+5", "123E+1", "5E-1")) {
            val prices = priceBody(input, input, input)
            for (key in listOf("oneAdult", "twoAdults", "extraAdult")) {
                val text = prices.getValue(key).jsonPrimitive.content
                assertTrue(Regex("^-?\\d+\\.\\d{2}$").matches(text), "$key de $input virou '$text'")
            }
        }
    }

    @Test
    fun eachPriceFieldCarriesItsOwnValue() {
        val prices = priceBody("111", "222.5", "3.33")
        assertEquals("111.00", prices.getValue("oneAdult").jsonPrimitive.content)
        assertEquals("222.50", prices.getValue("twoAdults").jsonPrimitive.content)
        assertEquals("3.33", prices.getValue("extraAdult").jsonPrimitive.content)
    }

    @Test
    fun moreThanTwoDecimalPlacesIsRefusedBeforeAnyRequest() =
        runTest {
            val gateway = gateway { json(PRICE_OK) }
            assertFailsWith<ArithmeticException> {
                gateway.updatePrices(ID_A, ID_C, ID_B, BigDecimal("280.005"), BigDecimal("1"), BigDecimal("1"))
            }
            assertFailsWith<ArithmeticException> {
                gateway.updatePrices(ID_A, ID_C, ID_B, BigDecimal("280"), BigDecimal("1"), BigDecimal("0.001"))
            }
            assertTrue(requests.isEmpty(), "dinheiro não pode ser arredondado em silêncio nem enviado pela metade")
        }

    @Test
    fun priceBodyUsesSeasonIdWithoutFromToAndWithEmptyWeekdaysAndChildren() =
        runTest {
            val gateway = gateway { json(PRICE_OK) }
            gateway.updatePrices(ID_A, ID_C, ID_B, BigDecimal("280"), BigDecimal("350"), BigDecimal("100"))
            val body = sent(0)
            assertEquals(setOf("prices", "roomTypeIds", "seasonId", "weekdays"), body.keys)
            assertEquals(ID_B, body.getValue("seasonId").jsonPrimitive.content)
            assertEquals(emptyList(), body.getValue("weekdays").jsonArray.toList())
            assertEquals(listOf(ID_C), body.getValue("roomTypeIds").jsonArray.map { it.jsonPrimitive.content })
            val prices = body.getValue("prices").jsonObject
            assertEquals(setOf("children", "extraAdult", "oneAdult", "twoAdults"), prices.keys)
            assertEquals(emptyList(), prices.getValue("children").jsonArray.toList())
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/rate-plans/$ID_A/prices/bulk-update"),
            )
            assertTrue(Regex("^-?\\d+\\.\\d{2}$").matches(prices.getValue("oneAdult").jsonPrimitive.content))
        }

    @Test
    fun priceUpdateApprovalRequiredRisesOnceWithoutRepeating() =
        runTest {
            val body =
                """{"code":"APPROVAL_REQUIRED","title":"T","status":403,"detail":"d","action":"rates.prices.override",
                |"permission":"rates.prices.override","resourceRef":"$ID_A",
                |"limit":{"key":"max","amount":"500.00"}}
                """.trimMargin()
            val gateway = gateway { json(body, HttpStatusCode.Forbidden) }
            val error =
                assertFailsWith<ApiError.ApprovalRequired> {
                    gateway.updatePrices(ID_A, ID_C, ID_B, BigDecimal("280"), BigDecimal("350"), BigDecimal("100"))
                }
            assertEquals("rates.prices.override", error.permission)
            assertEquals("500.00", error.limit?.amount)
            assertEquals(1, requests.size)
            assertNull(requests.single().headers["X-Approval-Token"])
        }

    @Test
    fun priceUpdateHasNoIdempotencyKeyOrIfMatch() =
        runTest {
            val gateway = gateway { json(PRICE_OK) }
            gateway.updatePrices(ID_A, ID_C, ID_B, BigDecimal("280"), BigDecimal("350"), BigDecimal("100"))
            val request = requests.single()
            assertNull(request.headers["Idempotency-Key"])
            assertNull(request.headers[HttpHeaders.IfMatch])
        }

    @Test
    fun priceUpdateValidationProblemKeepsFieldErrors() =
        runTest {
            val body =
                """{"code":"VALIDATION_FAILED","title":"T","status":400,"detail":"x",
                |"errors":[{"field":"prices.oneAdult","code":"PRICE_OUT_OF_RANGE","message":"fora da faixa"}]}
                """.trimMargin()
            val gateway = gateway { json(body, HttpStatusCode.BadRequest) }
            val error =
                assertFailsWith<ApiError.Problem> {
                    gateway.updatePrices(ID_A, ID_C, ID_B, BigDecimal("-1"), BigDecimal("1"), BigDecimal("1"))
                }
            assertEquals(400, error.status)
            assertEquals("prices.oneAdult", error.fieldErrors.single().field)
        }

    @Test
    fun priceUpdateMalformedSuccessBodyIsIncompatibleResponse() =
        runTest {
            val gateway = gateway { json("""{"nada":true}""") }
            assertFailsWith<ApiError.IncompatibleResponse> {
                gateway.updatePrices(ID_A, ID_C, ID_B, BigDecimal("280"), BigDecimal("350"), BigDecimal("100"))
            }
        }

    @Test
    fun priceUpdateWithMalformedIdsFailsBeforeAnyRequest() =
        runTest {
            val gateway = gateway { json(PRICE_OK) }
            assertFailsWith<IllegalArgumentException> {
                gateway.updatePrices("x", ID_C, ID_B, BigDecimal("1"), BigDecimal("1"), BigDecimal("1"))
            }
            assertFailsWith<IllegalArgumentException> {
                gateway.updatePrices(ID_A, ID_C, "x", BigDecimal("1"), BigDecimal("1"), BigDecimal("1"))
            }
            assertTrue(requests.isEmpty())
        }
}
