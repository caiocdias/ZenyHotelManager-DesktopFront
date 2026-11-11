package com.zeny.desktop.sdk

import com.zeny.desktop.sdk.client.BillingApi
import com.zeny.desktop.sdk.client.FNRHApi
import com.zeny.desktop.sdk.client.HotelApi
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.client.RoomsApi
import com.zeny.desktop.sdk.model.AddressDto
import com.zeny.desktop.sdk.model.HotelRequest
import com.zeny.desktop.sdk.model.RegisterPreAuthorizationRequest
import com.zeny.desktop.sdk.model.ReservationCancelRequest
import de.quati.kotlin.util.Option
import de.quati.ogen.client.ktor.HttpClientOgen
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Ataques ao `sdk` gerado (ADR 0007), além do SdkCriteriaTest: bordas dos headers, do ETag, do dinheiro,
 * dos enums, do erro RFC 9457 e da cobertura do contrato. Engine HTTP falso, sem rede.
 */
class SdkAttackTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<HttpRequestData>()

    private val id = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
    private val someId = Uuid.parse(id)

    private fun client(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        contentType: String = "application/json",
        vararg headers: Pair<String, String>,
    ): HttpClientOgen {
        val engine =
            MockEngine { request ->
                requests += request
                respond(
                    body,
                    status,
                    headersOf(
                        HttpHeaders.ContentType to listOf(contentType),
                        *headers.map { it.first to listOf(it.second) }.toTypedArray(),
                    ),
                )
            }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return HttpClientOgen.Base(http, "http://servidor.teste", {}, json)
    }

    private val hotelJson =
        """{"configured":true,"id":"$id","timezone":"America/Sao_Paulo","updatedAt":"2026-11-10T13:00:00Z"}"""

    private fun paymentJson(amount: String) =
        """{"amount":"$amount","businessDate":"2026-11-10","cashierShiftId":"$id","deposit":false,""" +
            """"folioId":"$id","id":"$id","method":"CASH","receivedAt":"2026-11-10T13:00:00Z",""" +
            """"receivedBy":"$id","type":"PAYMENT"}"""

    private fun sentBody(request: HttpRequestData) = String((request.body as OutgoingContent.ByteArrayContent).bytes())

    private val cancelRequest =
        ReservationCancelRequest(reason = ReservationCancelRequest.Reason.OTHER, waivePenalty = false)

    // ---- ETag ----

    @Test
    fun keepsWeakEtagVerbatim() =
        runBlocking {
            val response = HotelApi(client(hotelJson, headers = arrayOf("ETag" to "W/\"7\""))).getHotel()

            assertEquals("W/\"7\"", response.raw.headers[HttpHeaders.ETag])
        }

    @Test
    fun etagIsNullWhenServerSendsNone() =
        runBlocking {
            val response = HotelApi(client(hotelJson)).getHotel()

            assertNull(response.raw.headers[HttpHeaders.ETag])
        }

    @Test
    fun readsEtagRegardlessOfHeaderNameCase() =
        runBlocking {
            val response = HotelApi(client(hotelJson, headers = arrayOf("etag" to "\"9\""))).getHotel()

            assertEquals("\"9\"", response.raw.headers[HttpHeaders.ETag])
        }

    // ---- Headers por chamada ----

    // cancelReservation declara Idempotency-Key e X-Approval-Token.
    @Test
    fun injectedHeaderReplacesDeclaredOneAndGoesOutOnce() =
        runBlocking {
            ReservationsApi(client("{}")).cancelReservation(
                id = someId,
                idempotencyKey = "chave-1",
                xApprovalToken = "gerado",
                reservationCancelRequest = cancelRequest,
            ) {
                headers["X-Approval-Token"] = "injetado"
                headers["Idempotency-Key"] = "chave-2"
            }

            val headers = requests.single().headers
            assertEquals(listOf("injetado"), headers.getAll("X-Approval-Token"))
            assertEquals(listOf("chave-2"), headers.getAll("Idempotency-Key"))
        }

    // Fato da ADR 0007: header(...) acrescenta. O core não pode usá-lo onde a operação já declara o header.
    @Test
    fun headerFunctionInBlockDuplicatesDeclaredHeader() =
        runBlocking {
            ReservationsApi(client("{}")).cancelReservation(
                id = someId,
                idempotencyKey = "chave-1",
                xApprovalToken = "gerado",
                reservationCancelRequest = cancelRequest,
            ) {
                header("X-Approval-Token", "injetado")
            }

            assertEquals(listOf("gerado", "injetado"), requests.single().headers.getAll("X-Approval-Token"))
        }

    @Test
    fun optionalDeclaredHeadersAreAbsentWhenNull() =
        runBlocking {
            ReservationsApi(client("{}")).cancelReservation(
                id = someId,
                idempotencyKey = "chave-1",
                reservationCancelRequest = cancelRequest,
            )

            val headers = requests.single().headers
            assertNull(headers["X-Approval-Token"])
            assertEquals(listOf("chave-1"), headers.getAll("Idempotency-Key"))
        }

    @Test
    fun ifMatchIsAbsentWhenNotGiven() =
        runBlocking {
            HotelApi(client(hotelJson)).updateHotel(
                hotelRequest =
                    HotelRequest(
                        address = AddressDto("c", Option.Undefined, "d", "1", "1", "1", AddressDto.State.AC, "s"),
                        cnpj = "1",
                        legalName = "l",
                        taxRegime = HotelRequest.TaxRegime.LUCRO_REAL,
                        timezone = "America/Sao_Paulo",
                        tradeName = "t",
                    ),
            )

            assertNull(requests.single().headers["If-Match"])
        }

    @Test
    fun writeSendsJsonContentTypeAndAcceptsJson() =
        runBlocking {
            BillingApi(client("{}")).registerCardPreAuthorization(
                someId,
                "chave-1",
                RegisterPreAuthorizationRequest(amount = "1.00", expiresAt = "2026-11-10T13:00:00Z"),
            )

            val request = requests.single()
            assertEquals(
                "application/json",
                request.body.contentType
                    ?.withoutParameters()
                    ?.toString(),
            )
            assertTrue(
                request.headers
                    .getAll("Accept")
                    .orEmpty()
                    .any { it.contains("application/json") },
            )
        }

    // ---- Dinheiro ----

    @Test
    fun readsMoneyWithManyDecimalsAndSignWithoutRounding() =
        runBlocking {
            for (amount in listOf("-10.50", "0.0000001", "123456789012345678901234567890.1234567890", "-0.00", "1e3")) {
                val payment = BillingApi(client(paymentJson(amount))).getPayment(someId, someId).body()

                assertEquals(amount, payment.amount)
            }
        }

    @Test
    fun writesMoneyWithManyDecimalsAndSignWithoutRounding() =
        runBlocking {
            BillingApi(client("{}")).registerCardPreAuthorization(
                someId,
                "chave-1",
                RegisterPreAuthorizationRequest(
                    amount = "-123456789012345678901234567890.1234567890",
                    expiresAt = null,
                ),
            )

            assertEquals(
                """{"amount":"-123456789012345678901234567890.1234567890","expiresAt":null}""",
                sentBody(requests.single()),
            )
        }

    // ---- Opcionais: ausente x nulo ----

    @Test
    fun optionalFieldUndefinedIsOmittedAndNullIsSentAsNull() =
        runBlocking {
            BillingApi(client("{}")).registerCardPreAuthorization(
                someId,
                "chave-1",
                RegisterPreAuthorizationRequest(
                    amount = "1.00",
                    expiresAt = "2026-11-10T13:00:00Z",
                    nsu = Option.Some(null),
                ),
            )

            assertEquals(
                """{"amount":"1.00","expiresAt":"2026-11-10T13:00:00Z","nsu":null}""",
                sentBody(requests.single()),
            )
        }

    // ---- x-extensible-enum ----

    private fun fnrhJson(
        outcome: String?,
        environment: String = "HOMOLOGATION",
    ) = """{"configured":true,"enabled":true,"environment":"$environment"""" +
        outcome?.let { ""","lastFailureOutcome":$it""" }.orEmpty() + "}"

    @Test
    fun nullableExtensibleEnumDistinguishesAbsentNullAndUnknownValue() =
        runBlocking {
            val absent = FNRHApi(client(fnrhJson(null))).getFnrhStatus().body()
            val explicitNull = FNRHApi(client(fnrhJson("null"))).getFnrhStatus().body()
            val unknown = FNRHApi(client(fnrhJson("\"VALOR_NOVO\""))).getFnrhStatus().body()

            assertEquals(Option.Undefined, absent.lastFailureOutcome)
            assertEquals(Option.Some(null), explicitNull.lastFailureOutcome)
            assertEquals(Option.Some("VALOR_NOVO"), unknown.lastFailureOutcome)
        }

    @Test
    fun requiredExtensibleEnumToleratesUnknownValue() =
        runBlocking {
            val status = FNRHApi(client(fnrhJson(null, environment = "STAGING"))).getFnrhStatus().body()

            assertEquals("STAGING", status.environment)
        }

    @Test
    fun requiredExtensibleEnumAbsentFailsTheResponse() {
        val failure =
            assertFailsWith<JsonConvertException> {
                runBlocking { FNRHApi(client("""{"configured":true,"enabled":true}""")).getFnrhStatus().body() }
            }

        assertTrue(failure.cause is SerializationException)
        assertTrue(
            failure.cause
                ?.message
                .orEmpty()
                .contains("environment"),
            failure.cause?.message,
        )
    }

    @Test
    fun requiredExtensibleEnumNullFailsTheResponse() {
        assertFailsWith<JsonConvertException> {
            val fnrh = fnrhJson(null).replace("\"HOMOLOGATION\"", "null")
            runBlocking { FNRHApi(client(fnrh)).getFnrhStatus().body() }
        }
    }

    // Comportamento aceito e documentado na ADR 0007 (enum fechado com valor novo derruba a resposta).
    @Test
    fun closedEnumWithNewValueFailsTheResponse() {
        val area =
            """{"active":true,"createdAt":"2026-11-10T13:00:00Z","id":"$id","kind":"ROOFTOP",""" +
                """"name":"x","updatedAt":"2026-11-10T13:00:00Z"}"""

        // O body() lança io.ktor.serialization.JsonConvertException (Ktor), com a SerializationException na causa,
        // como a ADR 0007 documenta.
        val failure =
            assertFailsWith<JsonConvertException> {
                runBlocking { RoomsApi(client(area)).getArea(someId).body() }
            }

        assertTrue(failure.cause is SerializationException)
        assertTrue(
            failure.cause
                ?.message
                .orEmpty()
                .contains("ROOFTOP"),
            failure.cause?.message,
        )
    }

    @Test
    fun closedEnumWithKnownValueDecodes() =
        runBlocking {
            val area =
                """{"active":true,"createdAt":"2026-11-10T13:00:00Z","id":"$id","kind":"POOL",""" +
                    """"name":"x","updatedAt":"2026-11-10T13:00:00Z"}"""

            assertEquals(
                com.zeny.desktop.sdk.model.AreaResponse.Kind.POOL,
                RoomsApi(client(area)).getArea(someId).body().kind,
            )
        }

    // ---- Propriedade desconhecida ----

    @Test
    fun ignoresUnknownPropertyInsideNestedObject() =
        runBlocking {
            val body =
                """{"active":true,"createdAt":"2026-11-10T13:00:00Z","description":"d","id":"$id",""" +
                    """"kind":"POOL","name":"x",""" +
                    """"updatedAt":"2026-11-10T13:00:00Z","novo":{"a":[1,2,{"b":null}]}}"""

            assertEquals("x", RoomsApi(client(body)).getArea(someId).body().name)
        }

    @Test
    fun unknownPropertyFailsWithDefaultJson() {
        val strict = Json
        val http =
            HttpClient(
                MockEngine {
                    respond(
                        hotelJson.dropLast(1) + ""","novo":1}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ) {
                install(ContentNegotiation) { json(strict) }
            }

        assertFailsWith<JsonConvertException> {
            runBlocking { HotelApi(HttpClientOgen.Base(http, "http://servidor.teste", {}, strict)).getHotel().body() }
        }
    }

    // ---- Erro RFC 9457 (fato para a E02) ----

    private val problem =
        """{"type":"about:blank","title":"Forbidden","status":403,"code":"APPROVAL_REQUIRED","detail":"precisa de aprovação"}"""

    @Test
    fun problemJson4xxDoesNotThrowOnCallAndExposesStatusAndRawBody() =
        runBlocking {
            val response = HotelApi(client(problem, HttpStatusCode.Forbidden, "application/problem+json")).getHotel()

            assertEquals(HttpStatusCode.Forbidden, response.raw.status)
            assertEquals(problem, response.raw.bodyAsText())
        }

    @Test
    fun problemJson4xxMakesTypedBodyFailInsteadOfReturningTheProblem() {
        val response =
            runBlocking {
                HotelApi(client(problem, HttpStatusCode.Forbidden, "application/problem+json")).getHotel()
            }

        val failure = assertFailsWith<JsonConvertException> { runBlocking { response.body() } }

        assertTrue(failure.cause is SerializationException, "causa: ${failure.cause}")
    }

    // ---- Resposta sem corpo ----

    @Test
    fun noContentResponseReturnsUnitAndStatus() =
        runBlocking {
            val response = FNRHApi(client("", HttpStatusCode.NoContent)).deactivateFnrhRequester(someId)

            assertEquals(HttpStatusCode.NoContent, response.raw.status)
        }

    // ---- Cobertura do contrato ----

    @Test
    fun everyOperationInTheContractHasAGeneratedMethod() {
        val contract = File("../api/openapi.yaml").readLines().count { it.trim().startsWith("operationId:") }
        val apis =
            listOf(
                "Audit",
                "Billing",
                "Checklists",
                "FNRH",
                "Files",
                "Frontdesk",
                "Guests",
                "Hotel",
                "Iam",
                "Meta",
                "Notifications",
                "Rates",
                "Reservations",
                "Rooms",
                "Staff",
                "Stream",
                "Sync",
            ).map { Class.forName("com.zeny.desktop.sdk.client.${it}Api") }
        val methods =
            apis
                .flatMap { it.declaredMethods.toList() }
                .filter { it.name != "getClient" && it.name.startsWith("prepare").not() && it.name.contains('$').not() }
        val prepares =
            apis
                .flatMap { it.declaredMethods.toList() }
                .filter { it.name.startsWith("prepare") && it.name.contains('$').not() }

        assertEquals(303, contract)
        assertEquals(contract, methods.size)
        assertEquals(contract, prepares.size)
    }
}
