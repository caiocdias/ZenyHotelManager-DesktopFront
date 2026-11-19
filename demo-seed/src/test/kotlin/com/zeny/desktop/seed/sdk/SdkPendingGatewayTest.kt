package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.pending.PendingPlanSpec
import com.zeny.desktop.seed.pending.PendingPriceSpec
import com.zeny.desktop.seed.pending.PendingReservationRef
import com.zeny.desktop.seed.pending.PendingReservationSpec
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PENDING_SERVER = checkNotNull(ServerUrl.parse("http://servidor.teste"))
private const val P_POLICY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d01"
private const val P_CANCELLATION = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d02"
private const val P_TYPE = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d03"
private const val P_PLAN = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d04"
private const val P_GUEST = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d05"
private const val P_RESERVATION = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d06"
private const val P_ROOM = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d07"
private const val P_ASSIGNMENT = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d08"
private const val P_STAMP = "2026-11-12T10:00:00Z"
private const val P_CPF = "52998224725"
private val P_TODAY = LocalDate.of(2026, 11, 12)

private fun MockRequestHandleScope.pendingReply(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

private fun pendingProblem(
    status: Int,
    code: String,
    detail: String = "d",
) = """{"code":"$code","title":"T","status":$status,"detail":"$detail"}"""

private fun pendingPolicyJson(name: String) =
    """{"active":true,"createdAt":"$P_STAMP","id":"$P_POLICY","name":"$name","type":"CARD","updatedAt":"$P_STAMP"}"""

private fun pendingCancellationPolicyJson(name: String) =
    """{"active":true,"createdAt":"$P_STAMP","deadlineHours":24,"id":"$P_CANCELLATION","name":"$name",
    |"penaltyNights":1,"penaltyType":"NIGHTS","updatedAt":"$P_STAMP"}
    """.trimMargin()

private fun pendingPlanJson(code: String) =
    """{"active":true,"cancellationPolicyId":"$P_CANCELLATION","category":"NORMAL","channels":["WEBSITE"],
    |"code":"$code","createdAt":"$P_STAMP","guaranteePolicyId":"$P_POLICY","id":"$P_PLAN","mealPlan":"ROOM_ONLY",
    |"name":"Tarifa","packageIds":[],"roomTypeIds":["$P_TYPE"],"updatedAt":"$P_STAMP"}
    """.trimMargin()

private fun pendingListItem(
    externalId: String?,
    status: String = "CONFIRMED",
    roomId: String? = null,
): String {
    val ext = if (externalId == null) "" else ""","externalId":"$externalId""""
    val room =
        if (roomId == null) {
            ""
        } else {
            ""","roomAssignment":{"doNotMove":false,"id":"$P_ASSIGNMENT","reallocationRequired":false,"roomId":"$roomId"}"""
        }
    return """{"adults":2,"arrival":"2026-11-17","arrivalOverdue":false,"bookingCode":"B-1","bookingId":"$P_GUEST",
        |"channel":"FRONT_DESK","children":0,"code":"R-1","dayUse":false,"departure":"2026-11-19",
        |"departureOverdue":false,"folioId":"$P_GUEST","holderGuestId":"$P_GUEST","id":"$P_RESERVATION",
        |"origin":"FRONT_DESK","ratePlanCategory":"NORMAL","ratePlanId":"$P_PLAN","roomTypeId":"$P_TYPE",
        |"segment":"LEISURE","status":"$status","total":"600.00","version":1$ext$room}
        """.trimMargin()
}

private fun pendingReservationJson(status: String) =
    """{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-17","bookingId":"$P_GUEST",
    |"cancellationTerms":{"deadlineHours":24,"name":"x","penaltyType":"NIGHTS","policyId":"$P_GUEST","version":1},
    |"childAges":[],"code":"R-1","createdAt":"$P_STAMP","dayUse":false,"departure":"2026-11-19","folioId":"$P_GUEST",
    |"guaranteeTerms":{"name":"x","policyId":"$P_GUEST","type":"CARD","version":1},"guests":[],
    |"id":"$P_RESERVATION","nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL",
    |"ratePlanId":"$P_PLAN","roomTotal":"0.00","roomTypeId":"$P_TYPE","segment":"LEISURE","status":"$status",
    |"total":"0.00","version":1}
    """.trimMargin()

private fun pendingBookingJson(status: String) =
    """{"channel":"WEBSITE","code":"B-1","createdAt":"$P_STAMP","holderGuestId":"$P_GUEST","id":"$P_GUEST",
    |"origin":"WEBSITE","reservations":[${pendingReservationJson(status)}],"restrictionAlerts":[],"version":1}
    """.trimMargin()

private fun pendingDetailJson(status: String) =
    """{"bookingCode":"B-1","holderGuestId":"$P_GUEST","reservation":${pendingReservationJson(status)},
    |"restrictionAlerts":[]}
    """.trimMargin()

private const val OUT_OF_ORDER_JSON =
    """{"endsOn":"2026-11-19","id":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d09","kind":"OUT_OF_ORDER","reason":"x",
    "responsible":"y","returnStatus":"DIRTY","roomId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d07",
    "startsOn":"2026-11-17","openedAt":"2026-11-12T10:00:00Z","updatedAt":"2026-11-12T10:00:00Z","version":1}"""

private const val PRICE_CHANGE_JSON =
    """{"changed":0,"created":120,"derivedRatePlanIds":[],"matchedDates":60,
    "ratePlanId":"0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9d04","removed":0}"""

private fun pendingReservationSpec(origin: String = "WEBSITE") =
    PendingReservationSpec(
        externalId = "DEMO-PEND-P1",
        holderGuestId = P_GUEST,
        roomTypeId = P_TYPE,
        ratePlanId = P_PLAN,
        origin = origin,
        arrival = P_TODAY.plusDays(5),
        departure = P_TODAY.plusDays(7),
        adults = 2,
    )

class SdkPendingGatewayTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val bodies = CopyOnWriteArrayList<String>()

    private fun gateway(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SdkPendingGateway(
            SeedCore(
                PENDING_SERVER,
                MockEngine { request ->
                    requests += request
                    bodies += request.body.toByteArray().decodeToString()
                    handler(request)
                },
            ),
        )

    private fun body(index: Int): JsonObject = Json.parseToJsonElement(bodies[index]).jsonObject

    @Test
    fun createsCardPolicy() =
        runTest {
            val created = pendingPolicyJson("Garantia por cartão (demo)")
            val gateway = gateway { pendingReply(created, HttpStatusCode.Created) }
            assertEquals(P_POLICY, gateway.createCardGuaranteePolicy("Garantia por cartão (demo)"))
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.url.encodedPath.endsWith("/guarantee-policies"))
            assertEquals("CARD", body(0).getValue("type").jsonPrimitive.content)
            assertEquals("Garantia por cartão (demo)", body(0).getValue("name").jsonPrimitive.content)
        }

    @Test
    fun findsPoliciesAndPlansByNameWithoutCase() =
        runTest {
            val gateway =
                gateway { request ->
                    val path = request.url.encodedPath
                    when {
                        path.endsWith("/guarantee-policies") -> {
                            pendingReply("""{"items":[${pendingPolicyJson("Garantia X")}]}""")
                        }

                        path.endsWith("/cancellation-policies") -> {
                            pendingReply("""{"items":[${pendingCancellationPolicyJson("Flexível")}]}""")
                        }

                        else -> {
                            pendingReply("""{"items":[${pendingPlanJson("GARANTIDA")}]}""")
                        }
                    }
                }
            assertEquals(P_POLICY, gateway.guaranteePolicyId("garantia x"))
            assertEquals(P_CANCELLATION, gateway.cancellationPolicyId("FLEXÍVEL"))
            assertEquals(P_PLAN, gateway.ratePlanId("garantida"))
            assertNull(gateway.ratePlanId("OUTRO"))
        }

    @Test
    fun createsWebsiteOnlyPlan() =
        runTest {
            val gateway = gateway { pendingReply(pendingPlanJson("GARANTIDA"), HttpStatusCode.Created) }
            val spec = PendingPlanSpec("GARANTIDA", "Tarifa garantida", P_POLICY, P_CANCELLATION, P_TYPE)
            assertEquals(P_PLAN, gateway.createWebsiteRatePlan(spec))
            val sent = body(0)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/rate-plans"),
            )
            assertEquals(listOf("WEBSITE"), sent.getValue("channels").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf(P_TYPE), sent.getValue("roomTypeIds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(P_POLICY, sent.getValue("guaranteePolicyId").jsonPrimitive.content)
            assertEquals(P_CANCELLATION, sent.getValue("cancellationPolicyId").jsonPrimitive.content)
            assertEquals("GARANTIDA", sent.getValue("code").jsonPrimitive.content)
            assertEquals("Tarifa garantida", sent.getValue("name").jsonPrimitive.content)
            assertEquals("NORMAL", sent.getValue("category").jsonPrimitive.content)
        }

    @Test
    fun writesOneAndTwoAdultPricesForPeriod() =
        runTest {
            val gateway = gateway { pendingReply(PRICE_CHANGE_JSON) }
            gateway.writePrices(
                P_PLAN,
                PendingPriceSpec(P_TYPE, BigDecimal("300"), BigDecimal("320.5"), P_TODAY, P_TODAY.plusDays(59)),
            )
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.url.encodedPath.endsWith("/rate-plans/$P_PLAN/prices/bulk-update"))
            val sent = body(0)
            assertEquals("2026-11-12", sent.getValue("from").jsonPrimitive.content)
            assertEquals("2027-01-10", sent.getValue("to").jsonPrimitive.content)
            assertFalse("seasonId" in sent, "período e temporada juntos são recusados pelo servidor")
            assertEquals(listOf(P_TYPE), sent.getValue("roomTypeIds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(0, sent.getValue("weekdays").jsonArray.size)
            val prices = sent.getValue("prices").jsonObject
            assertEquals("300.00", prices.getValue("oneAdult").jsonPrimitive.content)
            assertEquals("320.50", prices.getValue("twoAdults").jsonPrimitive.content)
            assertEquals(0, prices.getValue("children").jsonArray.size)
        }

    @Test
    fun createsReservationWithOriginAndExternalId() =
        runTest {
            val gateway = gateway { pendingReply(pendingBookingJson("PROVISIONAL"), HttpStatusCode.Created) }
            val ref = gateway.createReservation(pendingReservationSpec())
            assertEquals(PendingReservationRef(P_RESERVATION, "PROVISIONAL", null), ref)
            val request = requests.single()
            assertTrue(request.headers["Idempotency-Key"].orEmpty().isNotBlank())
            val sent = body(0)
            assertEquals("WEBSITE", sent.getValue("origin").jsonPrimitive.content)
            assertEquals("DEMO-PEND-P1", sent.getValue("externalId").jsonPrimitive.content)
            assertEquals(P_GUEST, sent.getValue("holderGuestId").jsonPrimitive.content)
            val stay =
                sent
                    .getValue("reservations")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(P_TYPE, stay.getValue("roomTypeId").jsonPrimitive.content)
            assertEquals(P_PLAN, stay.getValue("ratePlanId").jsonPrimitive.content)
            assertEquals("2026-11-17", stay.getValue("arrival").jsonPrimitive.content)
            assertEquals("2026-11-19", stay.getValue("departure").jsonPrimitive.content)
            assertEquals("2", stay.getValue("adults").jsonPrimitive.content)
            val guest =
                stay
                    .getValue("guests")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(P_GUEST, guest.getValue("guestId").jsonPrimitive.content)
        }

    @Test
    fun everyContractOriginIsAccepted() =
        runTest {
            val gateway = gateway { pendingReply(pendingBookingJson("CONFIRMED"), HttpStatusCode.Created) }
            val origins = listOf("FRONT_DESK", "PHONE", "EMAIL", "WHATSAPP", "WEBSITE", "OTA", "OTHER")
            origins.forEach { gateway.createReservation(pendingReservationSpec(it)) }
            assertEquals(origins, bodies.indices.map { body(it).getValue("origin").jsonPrimitive.content })
            assertEquals(origins.size, requests.map { it.headers["Idempotency-Key"] }.toSet().size)
        }

    @Test
    fun unknownOriginFailsBeforeTheNetwork() =
        runTest {
            val gateway = gateway { error("não devia ir à rede") }
            assertFailsWith<IllegalArgumentException> { gateway.createReservation(pendingReservationSpec("BALCAO")) }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun findsReservationWithStatusAndRoom() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        pendingReply("""{"items":[${pendingListItem("OUTRA")}],"nextCursor":"p2"}""")
                    } else {
                        pendingReply("""{"items":[${pendingListItem("DEMO-PEND-R1", "CANCELLED", P_ROOM)}]}""")
                    }
                }
            val ref = gateway.findReservation("DEMO-PEND-R1")
            assertEquals(PendingReservationRef(P_RESERVATION, "CANCELLED", P_ROOM), ref)
            assertEquals("DEMO-PEND-R1", requests.first().url.parameters["externalId"])
            assertEquals(2, requests.size)
        }

    @Test
    fun missingReservationIsNull() =
        runTest {
            val gateway = gateway { pendingReply("""{"items":[${pendingListItem("OUTRA")}]}""") }
            assertNull(gateway.findReservation("DEMO-PEND-R1"))
        }

    @Test
    fun assignsRoomWithInitialReason() =
        runTest {
            val gateway = gateway { pendingReply(pendingDetailJson("CONFIRMED")) }
            gateway.assignRoom(P_RESERVATION, P_ROOM)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains(P_RESERVATION),
            )
            assertEquals(P_ROOM, body(0).getValue("roomId").jsonPrimitive.content)
            assertEquals("INITIAL", body(0).getValue("reason").jsonPrimitive.content)
        }

    @Test
    fun opensOutOfOrderForThePeriod() =
        runTest {
            val gateway = gateway { pendingReply(OUT_OF_ORDER_JSON, HttpStatusCode.Created) }
            val opened =
                gateway.openOutOfOrder(P_ROOM, P_TODAY.plusDays(5), P_TODAY.plusDays(7), "Manutenção (demo)")
            assertTrue(opened)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/rooms/$P_ROOM/out-of-order"),
            )
            assertEquals("OUT_OF_ORDER", body(0).getValue("kind").jsonPrimitive.content)
            assertEquals("2026-11-17", body(0).getValue("startsOn").jsonPrimitive.content)
            assertEquals("2026-11-19", body(0).getValue("endsOn").jsonPrimitive.content)
            assertEquals("Manutenção (demo)", body(0).getValue("reason").jsonPrimitive.content)
        }

    @Test
    fun overlapIsFalse() =
        runTest {
            val gateway = gateway { pendingReply(pendingProblem(409, "OUT_OF_ORDER_OVERLAP"), HttpStatusCode.Conflict) }
            assertFalse(gateway.openOutOfOrder(P_ROOM, P_TODAY, P_TODAY.plusDays(2), "x"))
            assertEquals(1, requests.size)
        }

    @Test
    fun otherRefusalPropagates() =
        runTest {
            val conflict = gateway { pendingReply(pendingProblem(409, "ROOM_NOT_AVAILABLE"), HttpStatusCode.Conflict) }
            val other = assertFailsWith<ApiError.Problem> { conflict.openOutOfOrder(P_ROOM, P_TODAY, P_TODAY, "x") }
            assertEquals("ROOM_NOT_AVAILABLE", other.code)
            val past =
                gateway {
                    pendingReply(pendingProblem(422, "OUT_OF_ORDER_PERIOD_IN_PAST"), HttpStatusCode.UnprocessableEntity)
                }
            val error = assertFailsWith<ApiError.Problem> { past.openOutOfOrder(P_ROOM, P_TODAY, P_TODAY, "x") }
            assertEquals(422, error.status)
        }

    @Test
    fun errorsHideGuestData() =
        runTest {
            val echoed = pendingProblem(400, "GUEST_INVALID", "documento $P_CPF")
            val gateway = gateway { pendingReply(echoed, HttpStatusCode.BadRequest) }
            val error = assertFailsWith<ApiError.Problem> { gateway.guestId(P_CPF) }
            assertFalse(P_CPF in error.detail.orEmpty(), error.detail)
            assertFalse(P_CPF in error.userMessage, error.userMessage)
            assertFalse(P_CPF in error.toString(), error.toString())
            assertFalse(P_CPF in gateway.toString())
            assertNull(error.detail)
        }

    @Test
    fun guestIdSendsCpfInBodyOnly() =
        runTest {
            val guest =
                """{"createdAt":"$P_STAMP","documents":[],"fullName":"Fulano","hasSensitiveData":false,"id":"$P_GUEST",
                |"preferences":{},"status":"ACTIVE","updatedAt":"$P_STAMP","vip":false}
                """.trimMargin()
            val gateway = gateway { pendingReply("""{"items":[$guest]}""") }
            assertEquals(P_GUEST, gateway.guestId(P_CPF))
            assertFalse(P_CPF in requests.single().url.toString())
            assertEquals(P_CPF, body(0).getValue("number").jsonPrimitive.content)
        }

    @Test
    fun createsReservationWithPhoneOrigin() =
        runTest {
            val gateway = gateway { pendingReply(pendingBookingJson("CONFIRMED"), HttpStatusCode.Created) }
            gateway.createReservation(pendingReservationSpec("PHONE"))
            assertEquals("PHONE", body(0).getValue("origin").jsonPrimitive.content)
        }

    @Test
    fun cancelSendsReasonNoWaiverAndOwnIdempotencyKey() =
        runTest {
            val gateway = gateway { pendingReply(pendingDetailJson("CANCELLED")) }
            gateway.cancelReservation(P_RESERVATION, "GUEST_REQUEST")
            gateway.cancelReservation(P_RESERVATION, "GUEST_REQUEST")
            val request = requests.first()
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.url.encodedPath.endsWith("/reservations/$P_RESERVATION/cancel"), request.url.toString())
            val sent = body(0)
            assertEquals("GUEST_REQUEST", sent.getValue("reason").jsonPrimitive.content)
            assertEquals("false", sent.getValue("waivePenalty").jsonPrimitive.content)
            val keys = requests.map { it.headers["Idempotency-Key"].orEmpty() }
            assertTrue(keys.all { it.isNotBlank() }, keys.toString())
            assertEquals(2, keys.toSet().size, "cada cancelamento leva a sua chave")
        }

    @Test
    fun unknownCancelReasonFailsBeforeTheNetwork() =
        runTest {
            val gateway = gateway { error("não devia ir à rede") }
            assertFailsWith<IllegalArgumentException> { gateway.cancelReservation(P_RESERVATION, "NADA") }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun cancelRefusalPropagatesWithoutGuestData() =
        runTest {
            val echoed = pendingProblem(422, "RESERVATION_NOT_CANCELLABLE", "hóspede Fulano CPF $P_CPF")
            val gateway = gateway { pendingReply(echoed, HttpStatusCode.UnprocessableEntity) }
            val error = assertFailsWith<ApiError.Problem> { gateway.cancelReservation(P_RESERVATION, "GUEST_REQUEST") }
            assertEquals("RESERVATION_NOT_CANCELLABLE", error.code)
            assertEquals(422, error.status)
            assertNull(error.detail)
            listOf(error.detail.orEmpty(), error.userMessage, error.toString(), error.message.orEmpty()).forEach {
                assertFalse(P_CPF in it, it)
                assertFalse("Fulano" in it, it)
            }
        }

    @Test
    fun markedDetailNeverLeavesTheAdapter() =
        runTest {
            val marked = "DETALHE-MARCADO-DO-SERVIDOR"
            val operations: Map<String, suspend SdkPendingGateway.() -> Unit> =
                mapOf(
                    "guaranteePolicyId" to { guaranteePolicyId("x") },
                    "createCardGuaranteePolicy" to { createCardGuaranteePolicy("x") },
                    "cancellationPolicyId" to { cancellationPolicyId("x") },
                    "ratePlanId" to { ratePlanId("x") },
                    "createWebsiteRatePlan" to {
                        createWebsiteRatePlan(PendingPlanSpec("C", "N", P_POLICY, P_CANCELLATION, P_TYPE))
                    },
                    "writePrices" to {
                        writePrices(
                            P_PLAN,
                            PendingPriceSpec(P_TYPE, BigDecimal("300"), BigDecimal("320"), P_TODAY, P_TODAY),
                        )
                    },
                    "guestId" to { guestId(P_CPF) },
                    "findReservation" to { findReservation("DEMO-PEND-R1") },
                    "createReservation" to { createReservation(pendingReservationSpec()) },
                    "assignRoom" to { assignRoom(P_RESERVATION, P_ROOM) },
                    "cancelReservation" to { cancelReservation(P_RESERVATION, "GUEST_REQUEST") },
                    "openOutOfOrder" to { openOutOfOrder(P_ROOM, P_TODAY, P_TODAY, "x") },
                )
            for ((name, operation) in operations) {
                val failures =
                    listOf(
                        HttpStatusCode.UnprocessableEntity to pendingProblem(422, "ALGUM_CODIGO", marked),
                        HttpStatusCode.Forbidden to
                            """{"code":"APPROVAL_REQUIRED","title":"T","status":403,"detail":"$marked",
                            |"action":"x","permission":"p","resourceRef":"r"}
                            """.trimMargin(),
                    )
                for ((status, reply) in failures) {
                    val gateway = gateway { pendingReply(reply, status) }
                    val failure = assertFailsWith<ApiError>(name) { gateway.operation() }
                    generateSequence<Throwable>(failure) { it.cause }.forEach {
                        assertFalse(marked in it.message.orEmpty(), "$name: $it")
                        assertFalse(marked in it.toString(), "$name: $it")
                    }
                    when (failure) {
                        is ApiError.Problem -> assertNull(failure.detail, name)
                        is ApiError.ApprovalRequired -> assertNull(failure.detail, name)
                        else -> Unit
                    }
                }
            }
        }
}
