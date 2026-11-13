package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.config.HousekeepingTransition
import com.zeny.desktop.seed.data.AddressSpec
import com.zeny.desktop.seed.data.CancellationPolicySpec
import com.zeny.desktop.seed.data.GuaranteePolicySpec
import com.zeny.desktop.seed.data.HotelSpec
import com.zeny.desktop.seed.data.RatePlanSpec
import com.zeny.desktop.seed.data.RoomTypeSpec
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

private fun roomStatus(
    id: String,
    number: String,
    active: Boolean = true,
    extra: String = "",
) = """{"active":$active,"changedAt":"$STAMP","housekeepingStatus":"CLEAN","inspectionPending":true,
    |"number":"$number","occupancy":"OCCUPIED","readyForCheckIn":false,"roomId":"$id","roomTypeId":"$ID_C",
    |"serviceFlag":"NONE","version":1$extra}
    """.trimMargin()

private const val PROBLEM_TAIL = """"title":"Conflito","status":409,"detail":"x"}"""

private fun hotelJson(configured: Boolean) =
    """{"configured":$configured,"id":"$ID_A","timezone":"America/Sao_Paulo","updatedAt":"$STAMP"}"""

private fun guaranteeJson(name: String) =
    """{"active":true,"createdAt":"$STAMP","id":"$ID_A","name":"$name","type":"NONE","updatedAt":"$STAMP"}"""

private val HOTEL_SPEC =
    HotelSpec(
        tradeName = "Hotel Zeny",
        legalName = "Zeny Ltda",
        cnpj = "11222333000181",
        taxRegime = "SIMPLES_NACIONAL",
        timezone = "America/Sao_Paulo",
        address = AddressSpec("Rua A", "100", "Centro", "São Paulo", "SP", "01000-000", "3550308"),
    )

class SdkConfigGatewayTest {
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

    @Test
    fun roomsReadAllPages() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        json("""{"items":[${roomStatus(ID_A, "101")}],"nextCursor":"p2"}""")
                    } else {
                        json("""{"items":[${roomStatus(ID_B, "102")}]}""")
                    }
                }
            val rooms = gateway.rooms()
            assertEquals(setOf("101", "102"), rooms.keys)
            assertEquals(2, requests.size)
        }

    @Test
    fun roomsMapStateAndSkipInactive() =
        runTest {
            val overlay =
                ""","outOfOrder":{"endsOn":"2026-11-20","id":"$ID_A","kind":"OUT_OF_ORDER","startsOn":"2026-11-15"}"""
            val items = "${roomStatus(ID_A, "101", extra = overlay)},${roomStatus(ID_B, "102", active = false)}"
            val gateway = gateway { json("""{"items":[$items]}""") }
            val room = gateway.rooms().getValue("101")
            assertEquals(ID_A, room.id)
            assertEquals(ID_C, room.roomTypeId)
            assertTrue(room.occupied)
            assertEquals("CLEAN", room.housekeeping)
            assertTrue(room.inspectionPending)
            assertTrue(room.outOfOrder)
            assertEquals(setOf("101"), gateway.rooms().keys)
        }

    @Test
    fun roomTypeCodesUppercased() =
        runTest {
            val type =
                """{"active":true,"code":"std","createdAt":"$STAMP","displayOrder":1,"featureIds":[],"id":"$ID_A",
                |"maxAdults":2,"maxChildren":1,"name":"Standard","standardAdults":2,"standardChildren":0,"updatedAt":"$STAMP"}
                """.trimMargin()
            val gateway = gateway { json("""{"items":[$type]}""") }
            assertEquals(mapOf("STD" to ID_A), gateway.roomTypeIds())
        }

    @Test
    fun createRoomTypeSendsEmptyFeatureIds() =
        runTest {
            val gateway =
                gateway {
                    json(
                        """{"active":true,"code":"STD","createdAt":"$STAMP","displayOrder":1,"featureIds":[],"id":"$ID_A",
                        |"maxAdults":2,"maxChildren":1,"name":"Standard","standardAdults":2,"standardChildren":0,
                        |"updatedAt":"$STAMP"}
                        """.trimMargin(),
                        HttpStatusCode.Created,
                    )
                }
            val id = gateway.createRoomType(RoomTypeSpec("STD", "Standard", 1, 2, 0, 2, 1))
            assertEquals(ID_A, id)
            val sent = Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals(0, sent.getValue("featureIds").jsonArray.size)
            assertEquals("STD", sent.getValue("code").jsonPrimitive.content)
        }

    @Test
    fun hotelNotConfiguredIsNull() =
        runTest {
            val gateway = gateway { json(hotelJson(false)) }
            assertNull(gateway.configuredHotelName())
        }

    @Test
    fun hotelConfiguredReturnsTradeName() =
        runTest {
            val gateway =
                gateway {
                    json(
                        """{"configured":true,"id":"$ID_A","timezone":"America/Sao_Paulo","updatedAt":"$STAMP",
                        |"tradeName":"Hotel Zeny"}
                        """.trimMargin(),
                    )
                }
            assertEquals("Hotel Zeny", gateway.configuredHotelName())
        }

    @Test
    fun hotelPutUsesEtag() =
        runTest {
            val hotel = hotelJson(false)
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) json(hotel, etag = "\"v1\"") else json(hotel)
                }
            gateway.configureHotel(HOTEL_SPEC)
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Put), requests.map { it.method })
            assertEquals("\"v1\"", requests[1].headers[HttpHeaders.IfMatch])
            val sent = Json.parseToJsonElement(bodies[1]).jsonObject
            assertEquals("Hotel Zeny", sent.getValue("tradeName").jsonPrimitive.content)
            assertEquals(
                "SP",
                sent
                    .getValue("address")
                    .jsonObject
                    .getValue("state")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun hotelBlankEtagFailsBeforeThePut() =
        runTest {
            val hotel = hotelJson(false)
            val gateway = gateway { json(hotel, etag = " ") }
            assertFailsWith<ApiError.IncompatibleResponse> { gateway.configureHotel(HOTEL_SPEC) }
            assertEquals(listOf(HttpMethod.Get), requests.map { it.method })
        }

    @Test
    fun policiesFoundByNameIgnoringCase() =
        runTest {
            val guarantee = guaranteeJson("Sem Garantia")
            val cancellation =
                """{"active":true,"createdAt":"$STAMP","deadlineHours":48,"id":"$ID_B","name":"Flexível",
                |"penaltyNights":1,"penaltyType":"NIGHTS","updatedAt":"$STAMP"}
                """.trimMargin()
            val gateway =
                gateway { request ->
                    if ("guarantee" in request.url.encodedPath) {
                        json("""{"items":[$guarantee]}""")
                    } else {
                        json("""{"items":[$cancellation]}""")
                    }
                }
            assertEquals(ID_A, gateway.guaranteePolicyId("SEM GARANTIA"))
            assertEquals(ID_B, gateway.cancellationPolicyId("flexível"))
            assertNull(gateway.guaranteePolicyId("Outra"))
        }

    @Test
    fun createPoliciesSendTypes() =
        runTest {
            val gateway =
                gateway { request ->
                    if ("guarantee" in request.url.encodedPath) {
                        json(guaranteeJson("G"), HttpStatusCode.Created)
                    } else {
                        json(
                            """{"active":true,"createdAt":"$STAMP","deadlineHours":48,"id":"$ID_B","name":"C",
                            |"penaltyNights":1,"penaltyType":"NIGHTS","updatedAt":"$STAMP"}
                            """.trimMargin(),
                            HttpStatusCode.Created,
                        )
                    }
                }
            assertEquals(ID_A, gateway.createGuaranteePolicy(GuaranteePolicySpec("G")))
            assertEquals(ID_B, gateway.createCancellationPolicy(CancellationPolicySpec("C", 48, 1)))
            val guarantee = Json.parseToJsonElement(bodies[0]).jsonObject
            assertEquals("NONE", guarantee.getValue("type").jsonPrimitive.content)
            val cancellation = Json.parseToJsonElement(bodies[1]).jsonObject
            assertEquals("NIGHTS", cancellation.getValue("penaltyType").jsonPrimitive.content)
            assertEquals(
                1,
                cancellation
                    .getValue("penaltyNights")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(
                48,
                cancellation
                    .getValue("deadlineHours")
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun problemBecomesApiError() =
        runTest {
            val gateway =
                gateway {
                    json("""{"code":"AREA_NAME_ALREADY_IN_USE",$PROBLEM_TAIL""", HttpStatusCode.Conflict)
                }
            val error =
                assertFailsWith<ApiError.Problem> {
                    gateway.createArea(
                        com.zeny.desktop.seed.data
                            .AreaSpec("Piscina", "POOL"),
                    )
                }
            assertEquals("AREA_NAME_ALREADY_IN_USE", error.code)
        }

    @Test
    fun areaNamesReadAllPages() =
        runTest {
            val gateway =
                gateway { request ->
                    val name = if (request.url.parameters["cursor"] == null) "Piscina" else "Recepção"
                    val next = if (request.url.parameters["cursor"] == null) ""","nextCursor":"p2"""" else ""
                    json(
                        """{"items":[{"active":true,"createdAt":"$STAMP","id":"$ID_A","kind":"POOL","name":"$name",
                        |"updatedAt":"$STAMP"}]$next}
                        """.trimMargin(),
                    )
                }
            assertEquals(setOf("Piscina", "Recepção"), gateway.areaNames())
        }

    private fun seasonJson(
        name: String,
        id: String,
    ) = """{"active":true,"createdAt":"$STAMP","endsOn":"2026-12-31","id":"$id","name":"$name",
        |"startsOn":"2026-12-01","updatedAt":"$STAMP"}
        """.trimMargin()

    private fun planJson(
        code: String,
        id: String,
    ) = """{"active":true,"cancellationPolicyId":"$ID_B","category":"NORMAL","channels":["DIRECT"],"code":"$code",
        |"createdAt":"$STAMP","guaranteePolicyId":"$ID_A","id":"$id","mealPlan":"BREAKFAST","name":"Tarifa",
        |"packageIds":[],"roomTypeIds":["$ID_C"],"updatedAt":"$STAMP"}
        """.trimMargin()

    @Test
    fun housekeepingSendsIfMatchFromStatus() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) {
                        json(roomStatus(ID_A, "101"), etag = "\"v7\"")
                    } else {
                        json(roomStatus(ID_A, "101"))
                    }
                }
            gateway.changeHousekeeping(ID_A, HousekeepingTransition.MARK_INSPECTED)
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Post), requests.map { it.method })
            assertTrue(requests[0].url.encodedPath.endsWith("/rooms/$ID_A/status"))
            assertTrue(requests[1].url.encodedPath.endsWith("/rooms/$ID_A/housekeeping-status"))
            assertEquals("\"v7\"", requests[1].headers[HttpHeaders.IfMatch])
            val sent = Json.parseToJsonElement(bodies[1]).jsonObject
            assertEquals("MARK_INSPECTED", sent.getValue("transition").jsonPrimitive.content)
            assertNull(requests[1].headers["Idempotency-Key"])
        }

    @Test
    fun housekeepingWithoutEtagFailsBeforeThePost() =
        runTest {
            val gateway = gateway { json(roomStatus(ID_A, "101")) }
            assertFailsWith<ApiError.IncompatibleResponse> {
                gateway.changeHousekeeping(ID_A, HousekeepingTransition.MARK_DIRTY)
            }
            assertEquals(listOf(HttpMethod.Get), requests.map { it.method })
        }

    @Test
    fun cleanPendingInspectionSetsFlag() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) {
                        json(roomStatus(ID_A, "101"), etag = "\"v1\"")
                    } else {
                        json(roomStatus(ID_A, "101"))
                    }
                }
            gateway.changeHousekeeping(ID_A, HousekeepingTransition.MARK_CLEAN_PENDING_INSPECTION)
            gateway.changeHousekeeping(ID_A, HousekeepingTransition.MARK_CLEAN)
            val pending = Json.parseToJsonElement(bodies[1]).jsonObject
            assertEquals("MARK_CLEAN", pending.getValue("transition").jsonPrimitive.content)
            assertEquals("true", pending.getValue("requiresInspection").jsonPrimitive.content)
            val plain = Json.parseToJsonElement(bodies[3]).jsonObject
            assertEquals("MARK_CLEAN", plain.getValue("transition").jsonPrimitive.content)
            assertTrue(plain["requiresInspection"]?.jsonPrimitive?.content != "true")
        }

    @Test
    fun outOfOrderBodyMatchesContract() =
        runTest {
            val gateway =
                gateway {
                    json(
                        """{"endsOn":"2026-11-14","id":"$ID_B","kind":"OUT_OF_ORDER","reason":"x","responsible":"y",
                        |"returnStatus":"DIRTY","roomId":"$ID_A","startsOn":"2026-11-12","openedAt":"$STAMP",
                        |"updatedAt":"$STAMP","version":1}
                        """.trimMargin(),
                        HttpStatusCode.Created,
                    )
                }
            gateway.openOutOfOrder(ID_A, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 14))
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/rooms/$ID_A/out-of-order"),
            )
            val sent = Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals("OUT_OF_ORDER", sent.getValue("kind").jsonPrimitive.content)
            assertEquals("2026-11-12", sent.getValue("startsOn").jsonPrimitive.content)
            assertEquals("2026-11-14", sent.getValue("endsOn").jsonPrimitive.content)
            assertEquals("Manutenção programada (demo)", sent.getValue("reason").jsonPrimitive.content)
            assertEquals("Equipe de manutenção (demo)", sent.getValue("responsible").jsonPrimitive.content)
            assertTrue(sent["returnStatus"] == null || sent["returnStatus"]?.jsonPrimitive?.content == "null")
        }

    @Test
    fun seasonsAndPlansReadAllPages() =
        runTest {
            val gateway =
                gateway { request ->
                    val first = request.url.parameters["cursor"] == null
                    val next = if (first) ""","nextCursor":"p2"""" else ""
                    if ("seasons" in request.url.encodedPath) {
                        val season = seasonJson(if (first) "Baixa" else "Alta", if (first) ID_A else ID_B)
                        json("""{"items":[$season]$next}""")
                    } else {
                        json("""{"items":[${planJson(if (first) "AAA" else "bar", if (first) ID_A else ID_B)}]$next}""")
                    }
                }
            assertEquals(mapOf("Baixa" to ID_A, "Alta" to ID_B), gateway.seasonIds())
            assertEquals(ID_B, gateway.ratePlanId("BAR"))
            assertNull(gateway.ratePlanId("ZZZ"))
        }

    @Test
    fun createSeasonSendsDates() =
        runTest {
            val gateway = gateway { json(seasonJson("Baixa", ID_A), HttpStatusCode.Created) }
            val id = gateway.createSeason(SeasonSpec("Baixa", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)))
            assertEquals(ID_A, id)
            val sent = Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals("Baixa", sent.getValue("name").jsonPrimitive.content)
            assertEquals("2026-12-01", sent.getValue("startsOn").jsonPrimitive.content)
            assertEquals("2026-12-31", sent.getValue("endsOn").jsonPrimitive.content)
        }

    @Test
    fun createRatePlanSendsReferences() =
        runTest {
            val gateway = gateway { json(planJson("BAR", ID_A), HttpStatusCode.Created) }
            val spec = RatePlanSpec("BAR", "Tarifa balcão", "BREAKFAST", listOf("DIRECT", "OTA"), listOf("STD"))
            assertEquals(ID_A, gateway.createRatePlan(spec, listOf(ID_C), ID_A, ID_B))
            val sent = Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals("BAR", sent.getValue("code").jsonPrimitive.content)
            assertEquals("NORMAL", sent.getValue("category").jsonPrimitive.content)
            assertEquals("BREAKFAST", sent.getValue("mealPlan").jsonPrimitive.content)
            assertEquals(listOf("DIRECT", "OTA"), sent.getValue("channels").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf(ID_C), sent.getValue("roomTypeIds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(ID_A, sent.getValue("guaranteePolicyId").jsonPrimitive.content)
            assertEquals(ID_B, sent.getValue("cancellationPolicyId").jsonPrimitive.content)
            assertEquals(0, sent.getValue("packageIds").jsonArray.size)
        }

    @Test
    fun pricesSendBigDecimalStrings() =
        runTest {
            val gateway =
                gateway {
                    json(
                        """{"changed":0,"created":1,"derivedRatePlanIds":[],"matchedDates":1,"ratePlanId":"$ID_A",
                        |"removed":0}
                        """.trimMargin(),
                    )
                }
            gateway.updatePrices(ID_A, ID_C, ID_B, BigDecimal("2.8E+2"), BigDecimal("350"), BigDecimal("1E+2"))
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/rate-plans/$ID_A/prices/bulk-update"),
            )
            val sent = Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals(ID_B, sent.getValue("seasonId").jsonPrimitive.content)
            assertEquals(listOf(ID_C), sent.getValue("roomTypeIds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(0, sent.getValue("weekdays").jsonArray.size)
            val prices = sent.getValue("prices").jsonObject
            assertEquals("280.00", prices.getValue("oneAdult").jsonPrimitive.content)
            assertEquals("350.00", prices.getValue("twoAdults").jsonPrimitive.content)
            assertEquals("100.00", prices.getValue("extraAdult").jsonPrimitive.content)
            assertEquals(0, prices.getValue("children").jsonArray.size)
        }
}
