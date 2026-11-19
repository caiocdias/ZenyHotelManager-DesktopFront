package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.error.ApiError
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FOLIO = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"
private const val CODE_AB = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
private const val CODE_OTHER = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
private const val CODE_OFF = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c03"
private const val RESERVATION = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01"
private const val ECHO_DETAIL = "conta do hóspede Maria Aparecida Sigilosa, CPF 123.456.789-09"

/** Os lançamentos do seed nas contas dos hospedados, sobre o `sdk`, com o `MockEngine`. */
class SdkFolioGatewayTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val bodies = CopyOnWriteArrayList<String>()

    private fun gateway(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SdkFolioGateway(
            SeedCore(
                SERVER_A,
                MockEngine { request ->
                    requests += request
                    bodies += request.body.toByteArray().decodeToString()
                    handler(request)
                },
            ),
        )

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        contentType: String = "application/json",
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, contentType))

    private fun posting(description: String) =
        """{"amount":"86.50","businessDate":"2026-11-19","description":"$description","folioId":"$FOLIO",
           |"id":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9e01","postedAt":"2026-11-19T10:00:00Z","quantity":1.0,
           |"source":"MANUAL","transactionCodeId":"$CODE_AB","type":"CHARGE","unitPrice":"86.50"}
        """.trimMargin()

    private fun code(
        id: String,
        name: String,
        active: Boolean,
        serviceCharge: Boolean,
        kind: String = "CHARGE",
    ) = """{"active":$active,"code":"X","createdAt":"2026-01-01T00:00:00Z","id":"$id","kind":"$kind","name":"$name",
           |"passThrough":false,"revenueGroup":"FOOD_BEVERAGE","serviceChargeApplies":$serviceCharge,
           |"updatedAt":"2026-01-01T00:00:00Z"}
        """.trimMargin()

    private fun page(
        items: List<String>,
        next: String? = null,
    ) = """{"items":[${items.joinToString(",")}]${next?.let { ""","nextCursor":"$it"""" }.orEmpty()}}"""

    @Test
    fun postSendsTheCodeThePriceAndAKey() =
        runTest {
            val gateway = gateway { json(posting("Seed M4: Frigobar"), HttpStatusCode.Created) }
            gateway.post(FOLIO, CODE_AB, "32.00", "Seed M4: Frigobar")
            gateway.post(FOLIO, CODE_AB, "32.00", "Seed M4: Frigobar")
            val request = requests.first()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/folios/$FOLIO/postings", request.url.encodedPath)
            val body = Json.parseToJsonElement(bodies.first()).jsonObject
            assertEquals(CODE_AB, body.getValue("transactionCodeId").jsonPrimitive.content)
            assertEquals("32.00", body.getValue("unitPrice").jsonPrimitive.content)
            assertEquals("Seed M4: Frigobar", body.getValue("description").jsonPrimitive.content)
            assertEquals(
                1.0,
                body
                    .getValue("quantity")
                    .jsonPrimitive.content
                    .toDouble(),
            )
            val keys = requests.map { it.headers["Idempotency-Key"] }
            assertTrue(keys.all { !it.isNullOrBlank() }, keys.toString())
            assertNotEquals(keys[0], keys[1], "cada lançamento leva a sua chave")
        }

    @Test
    fun postingDescriptionsReadsAllPages() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        json(page(listOf(posting("Seed M4: A")), "c2"))
                    } else {
                        json(page(listOf(posting("Seed M4: B"))))
                    }
                }
            assertEquals(listOf("Seed M4: A", "Seed M4: B"), gateway.postingDescriptions(FOLIO))
            assertEquals(2, requests.size)
            assertEquals("/api/v1/folios/$FOLIO/postings", requests.first().url.encodedPath)
        }

    @Test
    fun serverDetailIsRedacted() =
        runTest {
            val body = """{"code":"FOLIO_CLOSED","title":"T","status":409,"detail":"$ECHO_DETAIL"}"""
            val gateway = gateway { json(body, HttpStatusCode.Conflict, "application/problem+json") }
            val error = assertFailsWith<ApiError.Problem> { gateway.post(FOLIO, CODE_AB, "32.00", "Seed M4: Frigobar") }
            assertEquals(409, error.status)
            assertEquals("FOLIO_CLOSED", error.code)
            assertNull(error.detail)
            val chain = generateSequence<Throwable>(error) { it.cause }
            val texts = chain.flatMap { sequenceOf(it.message, it.toString()) }.filterNotNull().toList()
            assertTrue(texts.none { "Sigilosa" in it || "123.456" in it }, texts.toString())
        }

    @Test
    fun chargeableCodeIsTheActiveServiceChargeOneByName() =
        runTest {
            val gateway =
                gateway {
                    json(
                        page(
                            listOf(
                                code(CODE_OFF, "Alimentos e bebidas", active = false, serviceCharge = true),
                                code(CODE_OTHER, "Lavanderia", active = true, serviceCharge = true),
                                code(CODE_AB, "ALIMENTOS E BEBIDAS", active = true, serviceCharge = true),
                            ),
                        ),
                    )
                }
            assertEquals(CODE_AB, gateway.chargeableCodeId(listOf("Alimentos e bebidas")))
            assertNull(gateway.chargeableCodeId(listOf("Frigobar")))
        }

    @Test
    fun codeWithoutServiceChargeIsNotChargeable() =
        runTest {
            val plain = code(CODE_AB, "Alimentos e bebidas", active = true, serviceCharge = false)
            val gateway = gateway { json(page(listOf(plain))) }
            assertNull(gateway.chargeableCodeId(listOf("Alimentos e bebidas")))
        }

    @Test
    fun reservationFolioIsTheOpenOneOfTheReservation() =
        runTest {
            val folio =
                """{"balance":"0.00","creditLimitExceeded":false,"id":"$FOLIO","number":"C-2026-000001",
                   |"openedAt":"2026-11-19T09:00:00Z","openedOn":"2026-11-19",
                   |"owner":{"id":"$RESERVATION","type":"RESERVATION"},"status":"OPEN",
                   |"updatedAt":"2026-11-19T09:00:00Z"}
                """.trimMargin()
            val gateway = gateway { json(page(listOf(folio))) }
            assertEquals(FOLIO, gateway.reservationFolioId(RESERVATION))
            val query = requests.single().url.parameters
            assertEquals("RESERVATION", query["ownerType"])
            assertEquals(RESERVATION, query["ownerId"])
            assertEquals("OPEN", query["status"])
            assertEquals("/api/v1/folios", requests.single().url.encodedPath)
        }

    @Test
    fun reservationIdMatchesTheExternalIdOfTheItem() =
        runTest {
            fun item(
                id: String,
                externalId: String,
            ) = """{"adults":1,"arrival":"2026-11-19","arrivalOverdue":false,"bookingCode":"B","bookingId":"$FOLIO",
                |"channel":"DIRECT","children":0,"code":"R","dayUse":false,"departure":"2026-11-20",
                |"departureOverdue":false,"externalId":"$externalId","folioId":"$FOLIO","holderGuestId":"$FOLIO",
                |"id":"$id","origin":"DIRECT","ratePlanCategory":"NORMAL","ratePlanId":"$FOLIO","roomTypeId":"$FOLIO",
                |"segment":"LEISURE","status":"IN_HOUSE","total":"0.00","version":1}
                """.trimMargin()
            val other = item(CODE_OTHER, "DEMO-SEED-X")
            val wanted = item(RESERVATION, "DEMO-SEED-H1")
            val gateway = gateway { json("""{"items":[$other,$wanted]}""") }
            assertEquals(RESERVATION, gateway.reservationId("DEMO-SEED-H1"))
            assertNull(gateway.reservationId("DEMO-SEED-ZZ"))
        }

    @Test
    fun reservationWithoutOpenFolioIsNull() =
        runTest {
            assertNull(gateway { json(page(emptyList())) }.reservationFolioId(RESERVATION))
        }
}
