package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.data.AddressSpec
import com.zeny.desktop.seed.data.CancellationPolicySpec
import com.zeny.desktop.seed.data.GuaranteePolicySpec
import com.zeny.desktop.seed.data.HotelSpec
import com.zeny.desktop.seed.data.RoomTypeSpec
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
}
