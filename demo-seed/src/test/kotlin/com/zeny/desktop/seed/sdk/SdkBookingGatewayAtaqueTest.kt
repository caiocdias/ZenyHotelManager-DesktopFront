package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.data.CompanySpec
import com.zeny.desktop.seed.data.GuestSpec
import com.zeny.desktop.seed.data.ReservationSpec
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val SERVER_A = checkNotNull(ServerUrl.parse("http://servidor.teste"))
internal const val U1 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c01"
internal const val U2 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c02"
internal const val U3 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c03"
internal const val U4 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c04"
internal const val U5 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c05"
internal const val U6 = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c06"
internal const val NOW = "2026-11-12T10:00:00Z"
internal const val THE_CPF = "52998224725"
internal const val THE_CNPJ = "11222333000181"
internal const val THE_NAME = "Maria Aparecida Sigilosa"
internal val DAY = LocalDate.of(2026, 11, 12)
internal val RES_SPEC = ReservationSpec("Z9", "G1", "STD", DAY, DAY.plusDays(2), 2, "101", true)
internal val GUEST_SPEC = GuestSpec("G1", THE_NAME, LocalDate.of(1985, 3, 9), THE_CPF, THE_CNPJ)

internal fun MockRequestHandleScope.reply(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

internal fun problemBody(
    status: Int,
    code: String,
    detail: String = "x",
) = """{"code":"$code","title":"T","status":$status,"detail":"$detail"}"""

internal fun everyText(failure: Throwable): List<String> =
    generateSequence(failure) { it.cause }.flatMap { listOf(it.message.orEmpty(), it.toString()) }.toList()

internal fun guestOf(
    id: String,
    status: String = "ACTIVE",
) = """{"createdAt":"$NOW","documents":[],"fullName":"F","hasSensitiveData":false,"id":"$id",
    |"preferences":{},"status":"$status","updatedAt":"$NOW","vip":false}
    """.trimMargin()

internal fun itemOf(
    id: String,
    externalId: String?,
    adults: Int = 2,
    status: String = "CONFIRMED",
): String {
    val ext = if (externalId == null) "" else ""","externalId":"$externalId""""
    return """{"adults":$adults,"arrival":"2026-11-12","arrivalOverdue":false,"bookingCode":"B","bookingId":"$U1",
        |"channel":"FRONT_DESK","children":0,"code":"R","dayUse":false,"departure":"2026-11-14",
        |"departureOverdue":false,"folioId":"$U1","holderGuestId":"$U1","id":"$id","origin":"FRONT_DESK",
        |"ratePlanCategory":"NORMAL","ratePlanId":"$U5","roomTypeId":"$U4","segment":"LEISURE","status":"$status",
        |"total":"1.00","version":1$ext}
        """.trimMargin()
}

internal fun reservationOf(status: String = "CONFIRMED") =
    """{"additionalPackagesTotal":"0.00","adults":2,"arrival":"2026-11-12","bookingId":"$U1",
    |"cancellationTerms":{"deadlineHours":24,"name":"x","penaltyType":"NIGHTS","policyId":"$U1","version":1},
    |"childAges":[],"code":"R","createdAt":"$NOW","dayUse":false,"departure":"2026-11-14","folioId":"$U1",
    |"guaranteeTerms":{"name":"x","policyId":"$U1","type":"NONE","version":1},"guests":[],"id":"$U3",
    |"nightlyRates":[],"penaltiesCharged":"0.00","ratePlanCategory":"NORMAL","ratePlanId":"$U5","roomTotal":"0.00",
    |"roomTypeId":"$U4","segment":"LEISURE","status":"$status","total":"0.00","version":1}
    """.trimMargin()

internal fun bookingOf() =
    """{"channel":"FRONT_DESK","code":"B","createdAt":"$NOW","holderGuestId":"$U1","id":"$U1","origin":"FRONT_DESK",
    |"reservations":[${reservationOf()}],"restrictionAlerts":[],"version":1}
    """.trimMargin()

internal const val CHECK_IN_OK =
    """{"reservation":{"adults":2,"arrival":"2026-11-12","bookingId":"$U1","children":0,"code":"R","dayUse":false,
    "departure":"2026-11-14","folioId":"$U1","guests":[],"id":"$U3","roomTypeId":"$U4",
    "status":"IN_HOUSE","version":2},"restrictionAlerts":[],
    "room":{"housekeepingStatus":"INSPECTED","inspectionPending":false,"number":"101","occupancy":"OCCUPIED",
    "roomId":"$U6","version":2}}"""

open class BookingAtaqueBase {
    internal val requests = CopyOnWriteArrayList<HttpRequestData>()
    internal val bodies = CopyOnWriteArrayList<String>()

    internal fun gateway(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SdkBookingGateway = SdkBookingGateway(seedCore(handler))

    internal fun seedCore(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SeedCore(
            SERVER_A,
            MockEngine { request ->
                requests += request
                bodies += request.body.toByteArray().decodeToString()
                handler(request)
            },
        )

    internal fun sent(index: Int): JsonObject = Json.parseToJsonElement(bodies[index]).jsonObject

    internal fun happy(request: HttpRequestData): suspend MockRequestHandleScope.() -> HttpResponseData =
        {
            val path = request.url.encodedPath
            when {
                path.endsWith("/check-in") -> {
                    reply(CHECK_IN_OK)
                }

                path.endsWith("/guests/search") || path.contains("/guests/search") -> {
                    reply("""{"items":[${guestOf(U1)}]}""")
                }

                path.endsWith("/bookings") -> {
                    reply(bookingOf(), HttpStatusCode.Created)
                }

                path.endsWith("/room-assignment") || path.contains("assign") -> {
                    reply(
                        """{"bookingCode":"B","holderGuestId":"$U1","reservation":${reservationOf()},
"restrictionAlerts":[]}
                        """.trimMargin(),
                    )
                }

                else -> {
                    reply("""{"items":[]}""")
                }
            }
        }
}

class SdkBookingGatewayAtaqueTest : BookingAtaqueBase() {
    // ---------- LGPD ----------

    @Test
    fun cpfAndNameNeverAppearInUrlOrHeadersOfAnyCall() =
        runTest {
            val gateway =
                gateway { request ->
                    val path = request.url.encodedPath
                    when {
                        path.endsWith("/check-in") -> {
                            reply(CHECK_IN_OK)
                        }

                        path.endsWith("/bookings") -> {
                            reply(bookingOf(), HttpStatusCode.Created)
                        }

                        path.endsWith("/guests") -> {
                            reply(guestOf(U1), HttpStatusCode.Created)
                        }

                        path.endsWith("/companies") && request.method.value == "POST" -> {
                            reply(
                                """{"active":true,"cnpj":"$THE_CNPJ","createdAt":"$NOW","id":"$U2","legalName":"X",
"updatedAt":"$NOW"}
                                """.trimMargin(),
                                HttpStatusCode.Created,
                            )
                        }

                        else -> {
                            reply("""{"items":[]}""")
                        }
                    }
                }
            gateway.guestId(THE_CPF)
            gateway.createGuest(GUEST_SPEC, U2)
            gateway.createCompany(CompanySpec("Razao", "Fantasia", THE_CNPJ))
            gateway.findReservation("DEMO-SEED-Z9")
            gateway.createReservation(RES_SPEC, U1, U4, U5)
            gateway.checkIn(U3, U6, listOf(U1))
            gateway.inventory(U4, DAY, DAY.plusDays(1))
            assertTrue(requests.size >= 7)
            requests.forEach { request ->
                val visible =
                    request.url.toString() + request.headers.entries().joinToString { "${it.key}=${it.value}" }
                assertFalse(THE_CPF in visible, "CPF em URL ou header de ${request.url.encodedPath}")
                assertFalse(THE_NAME in visible, "nome em URL ou header de ${request.url.encodedPath}")
                assertFalse("Maria" in visible)
            }
        }

    @Test
    fun serverEchoingCpfInKnownCode400DoesNotLeakIntoErrorChain() =
        runTest {
            val gateway =
                gateway {
                    reply(
                        problemBody(400, "DOCUMENT_NUMBER_INVALID", "CPF $THE_CPF inválido"),
                        HttpStatusCode.BadRequest,
                    )
                }
            val error = assertFailsWith<ApiError> { gateway.guestId(THE_CPF) }
            assertTrue(everyText(error).none { THE_CPF in it }, "CPF vazou na mensagem: ${everyText(error)}")
            assertEquals(1, requests.size)
        }

    @Test
    fun serverEchoingCpfAndNameInUnknownCode422DoesNotLeakIntoUserMessage() =
        runTest {
            val gateway =
                gateway {
                    reply(
                        problemBody(422, "GUEST_RULE_NOVA", "O hóspede $THE_NAME, CPF $THE_CPF, não pode ser criado"),
                        HttpStatusCode.UnprocessableEntity,
                    )
                }
            val error = assertFailsWith<ApiError> { gateway.createGuest(GUEST_SPEC, null) }
            val texts = everyText(error)
            assertTrue(texts.none { THE_CPF in it }, "CPF vazou na mensagem ao operador: $texts")
            assertTrue(texts.none { THE_NAME in it }, "nome vazou na mensagem ao operador: $texts")
        }

    @Test
    fun serverEchoingCpfInCodelessError400DoesNotLeakIntoUserMessage() =
        runTest {
            val gateway =
                gateway {
                    respond(
                        """{"title":"Bad","status":400,"detail":"documento $THE_CPF duplicado"}""",
                        HttpStatusCode.BadRequest,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                }
            val error = assertFailsWith<ApiError> { gateway.createGuest(GUEST_SPEC, null) }
            assertTrue(everyText(error).none { THE_CPF in it }, "CPF vazou: ${everyText(error)}")
        }

    @Test
    fun cnpjEchoedInCompanyConflictDoesNotLeakIntoUserMessage() =
        runTest {
            val gateway =
                gateway {
                    reply(problemBody(409, "CNPJ_ALREADY_IN_USE", "CNPJ $THE_CNPJ em uso"), HttpStatusCode.Conflict)
                }
            val error = assertFailsWith<ApiError> { gateway.createCompany(CompanySpec("R", "F", THE_CNPJ)) }
            assertTrue(everyText(error).none { THE_CNPJ in it })
        }

    @Test
    fun htmlErrorBodyWithCpfDoesNotLeak() =
        runTest {
            val gateway =
                gateway {
                    respond(
                        "<html>erro com $THE_CPF e $THE_NAME</html>",
                        HttpStatusCode.BadGateway,
                        headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                }
            val error = assertFailsWith<ApiError> { gateway.guestId(THE_CPF) }
            assertTrue(everyText(error).none { THE_CPF in it || THE_NAME in it })
        }

    @Test
    fun malformedSuccessBodyWithCpfDoesNotLeakIntoCauseChain() =
        runTest {
            val gateway = gateway { reply("""{"items":[{"fullName":"$THE_NAME","cpf":"$THE_CPF"""") }
            val error = assertFailsWith<ApiError.IncompatibleResponse> { gateway.guestId(THE_CPF) }
            assertTrue(everyText(error).none { THE_CPF in it || THE_NAME in it })
        }

    @Test
    fun networkFailureIsTypedAndNotRepeatedByTheAdapter() =
        runTest {
            val gateway = gateway { throw IOException("conexão caiu") }
            assertFailsWith<ApiError.Network> { gateway.createReservation(RES_SPEC, U1, U4, U5) }
            assertEquals(1, requests.size)
            assertFailsWith<ApiError.Network> { gateway.checkIn(U3, U6, listOf(U1)) }
            assertEquals(2, requests.size)
        }

    // ---------- Idempotency-Key ----------

    @Test
    fun operationsThatDoNotDeclareIdempotencyKeyDoNotSendIt() =
        runTest {
            val gateway =
                gateway { request ->
                    val path = request.url.encodedPath
                    when {
                        path.endsWith("/companies") && request.method.value == "POST" -> {
                            reply(
                                """{"active":true,"cnpj":"$THE_CNPJ","createdAt":"$NOW","id":"$U2","legalName":"X",
"updatedAt":"$NOW"}
                                """.trimMargin(),
                                HttpStatusCode.Created,
                            )
                        }

                        path.endsWith("/guests") -> {
                            reply(guestOf(U1), HttpStatusCode.Created)
                        }

                        (path.contains("/room") && request.method.value == "PUT") ||
                            path.contains("assign") -> {
                            reply(
                                """{"bookingCode":"B","holderGuestId":"$U1","reservation":${reservationOf()},
"restrictionAlerts":[]}
                                """.trimMargin(),
                            )
                        }

                        else -> {
                            reply("""{"items":[]}""")
                        }
                    }
                }
            gateway.companyId(THE_CNPJ)
            gateway.createCompany(CompanySpec("R", "F", THE_CNPJ))
            gateway.guestId(THE_CPF)
            gateway.createGuest(GUEST_SPEC, null)
            gateway.inventory(U4, DAY, DAY)
            gateway.findReservation("DEMO-SEED-Z9")
            gateway.assignRoom(U3, U6)
            assertEquals(7, requests.size)
            requests.forEach {
                assertNull(it.headers["Idempotency-Key"], "chave indevida em ${it.method} ${it.url.encodedPath}")
                assertNull(it.headers[HttpHeaders.IfMatch])
                assertNull(it.headers["X-Approval-Token"])
            }
        }

    @Test
    fun idempotencyKeysAreNewForEveryCallAcrossMixedSequence() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.encodedPath.endsWith("/check-in")) {
                        reply(CHECK_IN_OK)
                    } else {
                        reply(bookingOf(), HttpStatusCode.Created)
                    }
                }
            repeat(3) {
                gateway.createReservation(RES_SPEC, U1, U4, U5)
                gateway.checkIn(U3, U6, listOf(U1))
            }
            val keys = requests.map { it.headers["Idempotency-Key"] }
            assertEquals(6, keys.size)
            assertTrue(keys.all { !it.isNullOrBlank() })
            assertEquals(6, keys.toSet().size, "chave reaproveitada: $keys")
        }

    @Test
    fun idempotencyKeyIsNotSharedBetweenGatewaysNorReusedAfterFailure() =
        runTest {
            var first = true
            val gateway =
                gateway {
                    if (first) {
                        first = false
                        reply(problemBody(409, "ROOM_TYPE_UNAVAILABLE"), HttpStatusCode.Conflict)
                    } else {
                        reply(bookingOf(), HttpStatusCode.Created)
                    }
                }
            assertFailsWith<ApiError.Problem> { gateway.createReservation(RES_SPEC, U1, U4, U5) }
            gateway.createReservation(RES_SPEC, U1, U4, U5)
            val keys = requests.map { it.headers["Idempotency-Key"] }
            assertEquals(2, keys.toSet().size, "a repetição depois do erro reusou a chave")
        }

    // ---------- erros do servidor ----------

    private suspend fun assertProblem(
        status: HttpStatusCode,
        code: String,
        block: suspend () -> Unit,
    ) {
        val error = assertFailsWith<ApiError.Problem> { block() }
        assertEquals(status.value, error.status)
        assertEquals(code, error.code)
        assertEquals(1, requests.size, "o adaptador repetiu a chamada")
        assertTrue(error.userMessage.isNotBlank())
        assertFalse(error.userMessage.contains(code), "código cru na mensagem ao operador")
    }

    @Test
    fun roomTypeUnavailable409BecomesProblemWithCodeAndNoRepeat() =
        runTest {
            val gateway = gateway { reply(problemBody(409, "ROOM_TYPE_UNAVAILABLE"), HttpStatusCode.Conflict) }
            assertProblem(HttpStatusCode.Conflict, "ROOM_TYPE_UNAVAILABLE") {
                gateway.createReservation(RES_SPEC, U1, U4, U5)
            }
        }

    @Test
    fun inventoryDateOutOfHorizon422BecomesProblemWithCodeAndNoRepeat() =
        runTest {
            val gateway =
                gateway { reply(problemBody(422, "INVENTORY_DATE_OUT_OF_HORIZON"), HttpStatusCode.UnprocessableEntity) }
            assertProblem(HttpStatusCode.UnprocessableEntity, "INVENTORY_DATE_OUT_OF_HORIZON") {
                gateway.createReservation(RES_SPEC, U1, U4, U5)
            }
        }

    @Test
    fun approvalRequired403OnCreateBookingRisesOnceWithoutApprovalToken() =
        runTest {
            val body =
                """{"code":"APPROVAL_REQUIRED","title":"T","status":403,"detail":"d","action":"rates.discounts.apply",
                |"permission":"rates.discounts.apply","resourceRef":"$U1"}
                """.trimMargin()
            val gateway = gateway { reply(body, HttpStatusCode.Forbidden) }
            val error = assertFailsWith<ApiError.ApprovalRequired> { gateway.createReservation(RES_SPEC, U1, U4, U5) }
            assertEquals("rates.discounts.apply", error.permission)
            assertEquals(1, requests.size)
            assertNull(requests.single().headers["X-Approval-Token"])
        }

    @Test
    fun unauthorized401OnSecuredCallIsSessionEndedWithoutRepeating() =
        runTest {
            val gateway = gateway { reply(problemBody(401, "UNAUTHENTICATED"), HttpStatusCode.Unauthorized) }
            assertFailsWith<ApiError.SessionEnded> { gateway.checkIn(U3, U6, listOf(U1)) }
            assertEquals(1, requests.size)
        }

    @Test
    fun serverError500And503OnWritesBecomeProblemWithCodeAndNoRepeat() =
        runTest {
            val g500 = gateway { reply(problemBody(500, "INTERNAL_ERROR"), HttpStatusCode.InternalServerError) }
            assertProblem(HttpStatusCode.InternalServerError, "INTERNAL_ERROR") {
                g500.createReservation(RES_SPEC, U1, U4, U5)
            }
            requests.clear()
            val g503 = gateway { reply(problemBody(503, "SERVICE_UNAVAILABLE"), HttpStatusCode.ServiceUnavailable) }
            assertProblem(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE") {
                g503.checkIn(U3, U6, listOf(U1))
            }
        }

    @Test
    fun idempotencyKeyInUse409OnCheckInIsProblemNotRepeated() =
        runTest {
            val gateway = gateway { reply(problemBody(409, "IDEMPOTENCY_KEY_IN_USE"), HttpStatusCode.Conflict) }
            assertProblem(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_IN_USE") { gateway.checkIn(U3, U6, listOf(U1)) }
        }

    @Test
    fun errorOnSecondReservationPageFailsInsteadOfReportingNotFound() =
        runTest {
            val gateway =
                gateway { request ->
                    if (request.url.parameters["cursor"] == null) {
                        reply("""{"items":[${itemOf(U1, "OUTRO")}],"nextCursor":"p2"}""")
                    } else {
                        reply(problemBody(500, "INTERNAL_ERROR"), HttpStatusCode.InternalServerError)
                    }
                }
            assertFailsWith<ApiError> { gateway.findReservation("DEMO-SEED-Z9") }
        }
}
