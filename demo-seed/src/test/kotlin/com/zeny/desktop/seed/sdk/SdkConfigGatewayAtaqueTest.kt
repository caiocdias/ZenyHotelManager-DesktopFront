package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.data.AddressSpec
import com.zeny.desktop.seed.data.AreaSpec
import com.zeny.desktop.seed.data.CancellationPolicySpec
import com.zeny.desktop.seed.data.GuaranteePolicySpec
import com.zeny.desktop.seed.data.HotelSpec
import com.zeny.desktop.seed.data.HousekeepingTarget
import com.zeny.desktop.seed.data.RoomSpec
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SERVER = checkNotNull(ServerUrl.parse("http://servidor.teste"))
private const val ID_A = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val ID_B = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val ID_C = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c03"
private const val ID_D = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c04"
private const val STAMP = "2026-11-12T10:00:00Z"
private const val SECRET = "SEGREDO-DO-SERVIDOR-123"

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

@Suppress("LongParameterList") // montador de JSON de teste, um parâmetro por campo variável
private fun room(
    id: String,
    number: String,
    active: Boolean = true,
    housekeeping: String = "CLEAN",
    occupancy: String = "VACANT",
    overlay: String = "",
) = """{"active":$active,"changedAt":"$STAMP","housekeepingStatus":"$housekeeping","inspectionPending":false,
    |"number":"$number","occupancy":"$occupancy","readyForCheckIn":false,"roomId":"$id","roomTypeId":"$ID_C",
    |"serviceFlag":"NONE","version":1$overlay}
    """.trimMargin()

private fun overlay(kind: String) =
    ""","outOfOrder":{"endsOn":"2026-11-20","id":"$ID_D","kind":"$kind","startsOn":"2026-11-15"}"""

private fun createdRoom(
    id: String,
    number: String,
) = """{"active":true,"createdAt":"$STAMP","featureIds":[],"id":"$id","number":"$number","roomTypeId":"$ID_C",
    |"updatedAt":"$STAMP"}
    """.trimMargin()

private fun roomType(
    id: String,
    code: String,
) = """{"active":true,"code":"$code","createdAt":"$STAMP","displayOrder":1,"featureIds":[],"id":"$id",
    |"maxAdults":2,"maxChildren":1,"name":"N","standardAdults":2,"standardChildren":0,"updatedAt":"$STAMP"}
    """.trimMargin()

private fun guarantee(
    id: String,
    name: String,
) = """{"active":true,"createdAt":"$STAMP","id":"$id","name":"$name","type":"NONE","updatedAt":"$STAMP"}"""

private fun area(
    id: String,
    name: String,
) = """{"active":true,"createdAt":"$STAMP","id":"$id","kind":"POOL","name":"$name","updatedAt":"$STAMP"}"""

private fun hotel(
    configured: Boolean,
    tradeName: String? = null,
) = """{"configured":$configured,"id":"$ID_A","timezone":"America/Sao_Paulo","updatedAt":"$STAMP"""" +
    (if (tradeName == null) "" else ""","tradeName":"$tradeName"""") + "}"

private val HOTEL_SPEC =
    HotelSpec(
        tradeName = "Hotel Zeny",
        legalName = "Zeny Ltda",
        cnpj = "11222333000181",
        taxRegime = "SIMPLES_NACIONAL",
        timezone = "America/Sao_Paulo",
        address = AddressSpec("Rua A", "100", "Centro", "São Paulo", "SP", "01000-000", "3550308"),
    )

private fun problem(
    status: Int,
    code: String,
    detail: String = "x",
) = """{"code":"$code","title":"T","status":$status,"detail":"$detail"}"""

private fun allMessages(failure: Throwable): List<String> =
    generateSequence(failure) { it.cause }.flatMap { listOf(it.message.orEmpty(), it.toString()) }.toList()

class SdkConfigGatewayAtaqueTest {
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

    // ---------- paginação ----------

    @Test
    fun roomsReadThreePagesAndSendEachCursorBack() =
        runTest {
            val gateway =
                gateway { request ->
                    when (request.url.parameters["cursor"]) {
                        null -> json("""{"items":[${room(ID_A, "101")}],"nextCursor":"c2"}""")
                        "c2" -> json("""{"items":[${room(ID_B, "102")}],"nextCursor":"c3"}""")
                        else -> json("""{"items":[${room(ID_C, "103")}],"nextCursor":null}""")
                    }
                }
            assertEquals(setOf("101", "102", "103"), gateway.rooms().keys)
            assertEquals(listOf(null, "c2", "c3"), requests.map { it.url.parameters["cursor"] })
        }

    @Test
    fun emptyMiddlePageDoesNotEndTheListing() =
        runTest {
            val gateway =
                gateway { request ->
                    when (request.url.parameters["cursor"]) {
                        null -> json("""{"items":[${room(ID_A, "101")}],"nextCursor":"c2"}""")
                        "c2" -> json("""{"items":[],"nextCursor":"c3"}""")
                        else -> json("""{"items":[${room(ID_C, "103")}]}""")
                    }
                }
            assertEquals(setOf("101", "103"), gateway.rooms().keys)
        }

    @Test
    fun blankNextCursorEndsTheListing() =
        runTest {
            val gateway = gateway { json("""{"items":[${room(ID_A, "101")}],"nextCursor":""}""") }
            assertEquals(setOf("101"), gateway.rooms().keys)
            assertEquals(1, requests.size)
        }

    @Test
    fun serverThatEchoesTheCursorDoesNotLoopForever() =
        runTest {
            val gateway = gateway { json("""{"items":[${room(ID_A, "101")}],"nextCursor":"mesmo"}""") }
            assertEquals(setOf("101"), gateway.rooms().keys)
            assertEquals(2, requests.size)
        }

    @Test
    fun guaranteePolicyOnLastPageIsFound() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        json("""{"items":[${guarantee(ID_A, "Outra")}],"nextCursor":"p2"}""")
                    } else {
                        json("""{"items":[${guarantee(ID_B, "Sem Garantia")}]}""")
                    }
                }
            assertEquals(ID_B, gateway.guaranteePolicyId("sem garantia"))
            assertEquals(2, requests.size)
        }

    @Test
    fun cancellationPolicyOnLastPageIsFound() =
        runTest {
            fun cancellation(
                id: String,
                name: String,
            ) = """{"active":true,"createdAt":"$STAMP","deadlineHours":48,"id":"$id","name":"$name",
                |"penaltyNights":1,"penaltyType":"NIGHTS","updatedAt":"$STAMP"}
                """.trimMargin()
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        json("""{"items":[${cancellation(ID_A, "A")}],"nextCursor":"p2"}""")
                    } else {
                        json("""{"items":[${cancellation(ID_B, "Flexível")}]}""")
                    }
                }
            assertEquals(ID_B, gateway.cancellationPolicyId("FLEXÍVEL"))
        }

    @Test
    fun roomTypesAndAreasReadAllPages() =
        runTest {
            val types =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        json("""{"items":[${roomType(ID_A, "std")}],"nextCursor":"p2"}""")
                    } else {
                        json("""{"items":[${roomType(ID_B, "lux")}]}""")
                    }
                }
            assertEquals(mapOf("STD" to ID_A, "LUX" to ID_B), types.roomTypeIds())
        }

    @Test
    fun listingsDoNotFilterByActiveSoInactiveNamesStayTaken() =
        runTest {
            val gateway = gateway { json("""{"items":[${roomType(ID_A, "std")}]}""") }
            gateway.roomTypeIds()
            assertNull(requests.single().url.parameters["active"])
        }

    // ---------- chaves naturais ----------

    @Test
    fun roomTypeCodesWithMixedCaseAreUppercased() =
        runTest {
            val gateway = gateway { json("""{"items":[${roomType(ID_A, "SuPeRiOr")}]}""") }
            assertEquals(mapOf("SUPERIOR" to ID_A), gateway.roomTypeIds())
        }

    @Test
    fun areaNamesKeepTheServerSpellingWithAccents() =
        runTest {
            val gateway = gateway { json("""{"items":[${area(ID_A, "Recepção")},${area(ID_B, "ÁREA Gourmet")}]}""") }
            assertEquals(setOf("Recepção", "ÁREA Gourmet"), gateway.areaNames())
        }

    @Test
    fun policyNameMatchesAcrossAccentedUppercase() =
        runTest {
            val gateway = gateway { json("""{"items":[${guarantee(ID_A, "Sem Garantia Ânima")}]}""") }
            assertEquals(ID_A, gateway.guaranteePolicyId("SEM GARANTIA ÂNIMA"))
        }

    @Test
    fun policyNameDifferingByAccentIsADifferentName() =
        runTest {
            val gateway = gateway { json("""{"items":[${guarantee(ID_A, "Flexível")}]}""") }
            assertNull(gateway.guaranteePolicyId("Flexivel"))
        }

    @Test
    fun roomsAreKeyedByNumberAsTheServerSendsIt() =
        runTest {
            val gateway = gateway { json("""{"items":[${room(ID_A, "0101")},${room(ID_B, "101")}]}""") }
            assertEquals(setOf("0101", "101"), gateway.rooms().keys)
        }

    // ---------- hotel ----------

    @Test
    fun hotelNotConfiguredIsNullEvenWithTradeNamePresent() =
        runTest {
            val gateway = gateway { json(hotel(configured = false, tradeName = "Resto de rascunho")) }
            assertNull(gateway.configuredHotelName())
        }

    @Test
    fun hotelConfiguredWithoutTradeNameIsNotTakenAsUnconfigured() =
        runTest {
            // O semeador trata nulo como "hotel vazio" e reconfigura: um hotel configurado sem nome fantasia
            // nunca pode virar nulo, senão o PUT sobrescreve dados reais.
            val gateway = gateway { json(hotel(configured = true)) }
            assertNotNull(gateway.configuredHotelName())
        }

    @Test
    fun hotelConfiguredWithBlankTradeNameIsNotTakenAsUnconfigured() =
        runTest {
            val gateway = gateway { json(hotel(configured = true, tradeName = "")) }
            assertNotNull(gateway.configuredHotelName())
        }

    @Test
    fun hotelPutRepeatsTheWeakEtagExactly() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) json(hotel(false), etag = "W/\"7\"") else json(hotel(true))
                }
            gateway.configureHotel(HOTEL_SPEC)
            assertEquals("W/\"7\"", requests[1].headers[HttpHeaders.IfMatch])
        }

    @Test
    fun hotelPut412BecomesPreconditionFailedAndIsNotRetried() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) {
                        json(hotel(false), etag = "\"v1\"")
                    } else {
                        json(problem(412, "PRECONDITION_FAILED"), HttpStatusCode.PreconditionFailed)
                    }
                }
            assertFailsWith<ApiError.PreconditionFailed> { gateway.configureHotel(HOTEL_SPEC) }
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Put), requests.map { it.method })
        }

    @Test
    fun hotelPutNeverGoesWithoutIfMatch() =
        runTest {
            // O contrato exige If-Match no PUT. Sem ETag no GET, o certo é falhar, não sobrescrever às cegas.
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) json(hotel(false)) else json(hotel(true))
                }
            runCatching { gateway.configureHotel(HOTEL_SPEC) }
            val put = requests.firstOrNull { it.method == HttpMethod.Put }
            assertTrue(put == null || put.headers[HttpHeaders.IfMatch] != null, "PUT enviado sem If-Match")
        }

    @Test
    fun hotelGetFailureMeansNoPut() =
        runTest {
            val gateway = gateway { json(problem(500, "INTERNAL"), HttpStatusCode.InternalServerError) }
            assertFailsWith<ApiError> { gateway.configureHotel(HOTEL_SPEC) }
            assertEquals(listOf(HttpMethod.Get), requests.map { it.method })
        }

    @Test
    fun hotelBodyKeepsTextualFieldsAsStrings() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) json(hotel(false), etag = "\"v1\"") else json(hotel(true))
                }
            gateway.configureHotel(HOTEL_SPEC)
            val body = sent(1)
            val address = body.getValue("address").jsonObject
            assertEquals(JsonPrimitive("11222333000181"), body["cnpj"])
            assertEquals(JsonPrimitive("SIMPLES_NACIONAL"), body["taxRegime"])
            assertEquals(JsonPrimitive("3550308"), address["ibgeCityCode"])
            assertEquals(JsonPrimitive("01000-000"), address["postalCode"])
            assertEquals(JsonPrimitive("100"), address["number"])
        }

    // ---------- UHs ----------

    @Test
    fun roomsIgnoreInactiveEvenWhenTheyShareANumberWithAnActiveOne() =
        runTest {
            val items = "${room(ID_A, "101", active = false)},${room(ID_B, "101")}"
            val gateway = gateway { json("""{"items":[$items]}""") }
            assertEquals(ID_B, gateway.rooms().getValue("101").id)
        }

    @Test
    fun outOfServiceOverlayIsNotOutOfOrder() =
        runTest {
            val gateway =
                gateway { json("""{"items":[${room(ID_A, "101", overlay = overlay("OUT_OF_SERVICE"))}]}""") }
            assertFalse(gateway.rooms().getValue("101").outOfOrder)
        }

    @Test
    fun outOfOrderOverlayIsOutOfOrder() =
        runTest {
            val gateway =
                gateway { json("""{"items":[${room(ID_A, "101", overlay = overlay("OUT_OF_ORDER"))}]}""") }
            assertTrue(gateway.rooms().getValue("101").outOfOrder)
        }

    @Test
    fun roomWithoutOverlayIsNotOutOfOrder() =
        runTest {
            val gateway = gateway { json("""{"items":[${room(ID_A, "101")}]}""") }
            assertFalse(gateway.rooms().getValue("101").outOfOrder)
        }

    @Test
    fun occupancyMapsVacantAndOccupied() =
        runTest {
            val items = "${room(ID_A, "101", occupancy = "VACANT")},${room(ID_B, "102", occupancy = "OCCUPIED")}"
            val rooms = gateway { json("""{"items":[$items]}""") }.rooms()
            assertFalse(rooms.getValue("101").occupied)
            assertTrue(rooms.getValue("102").occupied)
        }

    @Test
    fun housekeepingStatusIsPassedRawForEachKnownValue() =
        runTest {
            val items =
                listOf("DIRTY", "CLEAN", "INSPECTED").mapIndexed { i, status ->
                    room("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c1$i", "20$i", housekeeping = status)
                }
            val rooms = gateway { json("""{"items":[${items.joinToString(",")}]}""") }.rooms()
            assertEquals(
                listOf("DIRTY", "CLEAN", "INSPECTED"),
                listOf("200", "201", "202").map { rooms.getValue(it).housekeeping },
            )
        }

    @Test
    fun unknownHousekeepingStatusIsAnIncompatibleResponse() =
        runTest {
            // O enum do contrato é fechado: valor fora dele é quebra do contrato pelo servidor.
            val gateway =
                gateway { json("""{"items":[${room(ID_A, "101", housekeeping = "QUARANTINE")}]}""") }
            val error = assertFailsWith<ApiError.IncompatibleResponse> { gateway.rooms() }
            assertTrue(allMessages(error).none { "QUARANTINE" in it }, "corpo da resposta vazou na mensagem")
        }

    // ---------- corpos enviados ----------

    @Test
    fun createRoomSendsFloorAsStringAndEmptyFeatureIds() =
        runTest {
            val gateway = gateway { json(createdRoom(ID_A, "101"), HttpStatusCode.Created) }
            val spec = RoomSpec("101", 3, "STD", HousekeepingTarget.CLEAN, false)
            gateway.createRoom(spec, ID_C)
            val body = sent(0)
            assertEquals(JsonPrimitive("3"), body["floor"])
            assertEquals(JsonArray(emptyList()), body["featureIds"])
            assertEquals(JsonPrimitive("101"), body["number"])
            assertEquals(JsonPrimitive(ID_C), body["roomTypeId"])
        }

    @Test
    fun createRoomReturnsTheIdFromTheResponse() =
        runTest {
            val gateway = gateway { json(createdRoom(ID_D, "101"), HttpStatusCode.Created) }
            val id = gateway.createRoom(RoomSpec("101", 0, "STD", HousekeepingTarget.CLEAN, false), ID_C)
            assertEquals(ID_D, id)
            assertEquals(JsonPrimitive("0"), sent(0)["floor"])
        }

    @Test
    fun createRoomWithMalformedTypeIdFailsBeforeAnyRequest() =
        runTest {
            val gateway = gateway { json(createdRoom(ID_A, "101"), HttpStatusCode.Created) }
            assertFailsWith<IllegalArgumentException> {
                gateway.createRoom(RoomSpec("101", 1, "STD", HousekeepingTarget.CLEAN, false), "não-é-uuid")
            }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun createRoomTypeSendsNumbersAsNumbersAndCodeAsGiven() =
        runTest {
            val gateway = gateway { json(roomType(ID_A, "STD"), HttpStatusCode.Created) }
            gateway.createRoomType(RoomTypeSpec("STD", "Standard", 4, 2, 1, 3, 2))
            val body = sent(0)
            assertEquals(JsonPrimitive(4), body["displayOrder"])
            assertEquals(JsonPrimitive(2), body["standardAdults"])
            assertEquals(JsonPrimitive(1), body["standardChildren"])
            assertEquals(JsonPrimitive(3), body["maxAdults"])
            assertEquals(JsonPrimitive(2), body["maxChildren"])
            assertEquals(JsonPrimitive("Standard"), body["name"])
        }

    @Test
    fun createPoliciesCarryNamesAndNoMoneyOrExtraFields() =
        runTest {
            val gateway =
                gateway { request ->
                    if ("guarantee" in request.url.encodedPath) {
                        json(guarantee(ID_A, "G"), HttpStatusCode.Created)
                    } else {
                        json(
                            """{"active":true,"createdAt":"$STAMP","deadlineHours":0,"id":"$ID_B","name":"C",
                            |"penaltyNights":0,"penaltyType":"NIGHTS","updatedAt":"$STAMP"}
                            """.trimMargin(),
                            HttpStatusCode.Created,
                        )
                    }
                }
            gateway.createGuaranteePolicy(GuaranteePolicySpec("Sem Garantia"))
            gateway.createCancellationPolicy(CancellationPolicySpec("Grátis", 0, 0))
            assertEquals(JsonPrimitive("Sem Garantia"), sent(0)["name"])
            assertEquals(setOf("name", "type"), sent(0).keys.filterNot { it == "active" }.toSet())
            assertEquals(JsonPrimitive("Grátis"), sent(1)["name"])
            assertEquals(JsonPrimitive(0), sent(1)["deadlineHours"])
            assertEquals(JsonPrimitive(0), sent(1)["penaltyNights"])
        }

    @Test
    fun createAreaSendsKindAndName() =
        runTest {
            val gateway = gateway { json(area(ID_A, "Piscina"), HttpStatusCode.Created) }
            gateway.createArea(AreaSpec("Piscina", "POOL"))
            assertEquals(JsonPrimitive("POOL"), sent(0)["kind"])
            assertEquals(JsonPrimitive("Piscina"), sent(0)["name"])
        }

    @Test
    fun createAreaWithUnknownKindFailsBeforeAnyRequest() =
        runTest {
            val gateway = gateway { json(area(ID_A, "X"), HttpStatusCode.Created) }
            assertFailsWith<IllegalArgumentException> { gateway.createArea(AreaSpec("X", "NAO_EXISTE")) }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun noRegisterOperationSendsIdempotencyKey() =
        runTest {
            val gateway =
                gateway { request ->
                    when {
                        "areas" in request.url.encodedPath -> json(area(ID_A, "P"), HttpStatusCode.Created)
                        "room-types" in request.url.encodedPath -> json(roomType(ID_A, "STD"), HttpStatusCode.Created)
                        else -> json(createdRoom(ID_A, "101"), HttpStatusCode.Created)
                    }
                }
            gateway.createArea(AreaSpec("P", "POOL"))
            gateway.createRoomType(RoomTypeSpec("STD", "S", 1, 2, 0, 2, 1))
            gateway.createRoom(RoomSpec("101", 1, "STD", HousekeepingTarget.CLEAN, false), ID_C)
            assertTrue(requests.all { it.headers["Idempotency-Key"] == null })
        }

    // ---------- erros ----------

    @Test
    fun problemKeepsStatusCodeAndDetail() =
        runTest {
            val gateway =
                gateway {
                    json(problem(409, "ROOM_NUMBER_ALREADY_IN_USE", "UH 101 repetida"), HttpStatusCode.Conflict)
                }
            val error =
                assertFailsWith<ApiError.Problem> {
                    gateway.createRoom(RoomSpec("101", 1, "STD", HousekeepingTarget.CLEAN, false), ID_C)
                }
            assertEquals(409, error.status)
            assertEquals("ROOM_NUMBER_ALREADY_IN_USE", error.code)
        }

    @Test
    fun validationProblemKeepsFieldErrors() =
        runTest {
            val body =
                """{"code":"VALIDATION_FAILED","title":"T","status":422,"detail":"x",
                |"errors":[{"field":"cnpj","code":"CNPJ_INVALID","message":"CNPJ inválido"}]}
                """.trimMargin()
            val gateway =
                gateway { request ->
                    if (request.method == HttpMethod.Get) {
                        json(hotel(false), etag = "\"v1\"")
                    } else {
                        json(body, HttpStatusCode.UnprocessableEntity)
                    }
                }
            val error = assertFailsWith<ApiError.Problem> { gateway.configureHotel(HOTEL_SPEC) }
            assertEquals(422, error.status)
            assertEquals("cnpj", error.fieldErrors.single().field)
        }

    @Test
    fun forbiddenIsAProblemWith403() =
        runTest {
            val gateway = gateway { json(problem(403, "ACCESS_DENIED"), HttpStatusCode.Forbidden) }
            val error = assertFailsWith<ApiError.Problem> { gateway.rooms() }
            assertEquals(403, error.status)
            assertEquals("ACCESS_DENIED", error.code)
        }

    @Test
    fun unauthorizedBecomesSessionEnded() =
        runTest {
            val gateway = gateway { json(problem(401, "TOKEN_EXPIRED"), HttpStatusCode.Unauthorized) }
            assertFailsWith<ApiError.SessionEnded> { gateway.areaNames() }
        }

    @Test
    fun serverErrorWithHtmlBodyBecomesTypedErrorWithoutEchoingTheBody() =
        runTest {
            val gateway =
                gateway {
                    respond(
                        "<html>stacktrace $SECRET</html>",
                        HttpStatusCode.InternalServerError,
                        headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                }
            val error = assertFailsWith<ApiError> { gateway.roomTypeIds() }
            assertTrue(allMessages(error).none { SECRET in it }, "corpo da resposta vazou na mensagem")
        }

    @Test
    fun problemDetailNeverLeaksIntoUserMessageForServerErrors() =
        runTest {
            val gateway =
                gateway { json(problem(500, "INTERNAL_ERROR", "falha em $SECRET"), HttpStatusCode.InternalServerError) }
            val error = assertFailsWith<ApiError> { gateway.guaranteePolicyId("x") }
            assertFalse(SECRET in error.userMessage)
        }

    @Test
    fun malformedSuccessBodyIsIncompatibleResponseWithoutTheBody() =
        runTest {
            val gateway = gateway { json("""{"items":"$SECRET" """) }
            val error = assertFailsWith<ApiError.IncompatibleResponse> { gateway.rooms() }
            assertTrue(allMessages(error).none { SECRET in it })
        }

    @Test
    fun networkFailureBecomesNetworkError() =
        runTest {
            val gateway = gateway { throw IOException("conexão caiu $SECRET") }
            val error = assertFailsWith<ApiError.Network> { gateway.cancellationPolicyId("x") }
            assertIs<ApiError>(error)
            assertEquals(1, requests.size)
        }

    @Test
    fun errorOnSecondPageFailsTheWholeListingInsteadOfReturningPartialData() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        json("""{"items":[${room(ID_A, "101")}],"nextCursor":"p2"}""")
                    } else {
                        json(problem(500, "INTERNAL"), HttpStatusCode.InternalServerError)
                    }
                }
            assertFailsWith<ApiError> { gateway.rooms() }
        }
}
