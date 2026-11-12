package com.zeny.desktop.core.facade

import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ApprovalLimit
import com.zeny.desktop.core.http.Intent
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UID = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
private const val SECRET_PIN = "pin-secreto-7731"
private const val SECRET_BODY = "SEGREDO-NO-CORPO-9917"
private const val GRANTED_BODY =
    """{"approvalId":"$UID","approvalToken":"tok-1","approverId":"$UID","expiresAt":"2026-11-11T10:05:00Z"}"""
private const val DENIED_BODY = """{"code":"PERMISSION_DENIED","detail":"$SECRET_BODY"}"""
private const val DATE_BODY = """{"businessDate":"2026-11-11","timezone":"America/Sao_Paulo"}"""
private const val APPROVAL_PROBLEM =
    """{"code":"APPROVAL_REQUIRED","detail":"d","action":"a","permission":"p","resourceRef":"r",""" +
        """"limit":{"key":"discount.percent","amount":"150.00"}}"""
private const val NETWORK_MESSAGE = "Não foi possível falar com o servidor. Confira o endereço e a rede."

/**
 * Ataques às fachadas do `core`. Este arquivo não tem `@OptIn(RawSdkAccess::class)` em lugar nenhum: se uma fachada
 * exigisse o opt-in, ele não compilaria. As chamadas cruas usam `sdkApis`, `internal` ao módulo.
 */
class FacadesAttackTest {
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

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        contentType: String = "application/json",
    ): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, contentType))

    private fun meBody(permissions: String) =
        """{"login":"maria","name":"Maria","permissions":[$permissions],"roles":[],"sessionId":"$UID","userId":"$UID"}"""

    private fun perm(
        code: String,
        scope: String = "ALL",
    ) = """{"code":"$code","limits":[],"scope":"$scope"}"""

    private fun hotelBody(extra: String) =
        """{"id":"$UID","timezone":"America/Sao_Paulo","updatedAt":"2026-11-11T10:00:00Z"$extra}"""

    private fun required(limit: ApprovalLimit? = null) = ApiError.ApprovalRequired("a", "p", "r", limit, null)

    /** Nenhum texto que o usuário veja pode carregar corpo, PIN ou código cru. */
    private fun assertClean(
        error: Throwable,
        vararg forbidden: String,
    ) {
        val seen = generateSequence(error) { it.cause }.flatMap { listOf(it.message.orEmpty(), it.toString()) }
        seen.forEach { text -> forbidden.forEach { assertFalse(it in text, "'$it' vazou em: $text") } }
    }

    // ---- getMe ----

    @Test
    fun meCollapsesRepeatedPermissionCodesIntoOne() =
        runTest {
            val body = meBody(listOf(perm("a.b"), perm("a.b", "OWN"), perm("c.d")).joinToString(","))
            core { json(body) }.use { assertEquals(setOf("a.b", "c.d"), it.account.me().permissions) }
        }

    @Test
    fun meKeepsUnknownPermissionCodesVerbatim() =
        runTest {
            val body = meBody(perm("modulo.futuro.acao"))
            core { json(body) }.use { assertEquals(setOf("modulo.futuro.acao"), it.account.me().permissions) }
        }

    @Test
    fun meWithEmptyPermissionListHasNoPermissions() =
        runTest {
            core { json(meBody("")) }.use {
                val me = it.account.me()
                assertEquals(emptySet(), me.permissions)
                assertEquals("maria", me.login)
            }
        }

    @Test
    fun meWithUnknownScopeIsATypedErrorWithoutEcho() =
        runTest {
            val body = meBody(perm("a.b", "GALAXY"))
            core { json(body) }.use { core ->
                val error = assertFailsWith<ApiError> { core.account.me() }
                assertClean(error, "GALAXY", "a.b")
            }
        }

    @Test
    fun meWithoutPermissionsFieldIsIncompatibleResponse() =
        runTest {
            val body = """{"login":"maria","name":"Maria","roles":[],"sessionId":"$UID","userId":"$UID"}"""
            core { json(body) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.account.me() }
            }
        }

    @Test
    fun meForbiddenIsAProblemWithCatalogMessage() =
        runTest {
            core { json(DENIED_BODY, HttpStatusCode.Forbidden) }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.account.me() }
                assertEquals("Você não tem permissão para esta ação.", error.userMessage)
                assertClean(error, SECRET_BODY, "PERMISSION_DENIED")
            }
        }

    // ---- getHotel ----

    @Test
    fun hotelWithoutTradeNameFieldHasNullName() =
        runTest {
            core { json(hotelBody(""","configured":false""")) }.use {
                assertEquals(HotelInfo(null, false), it.hotel.hotel())
            }
        }

    @Test
    fun hotelWithNullTradeNameIsConfiguredWithNullName() =
        runTest {
            core { json(hotelBody(""","configured":true,"tradeName":null""")) }.use {
                assertEquals(HotelInfo(null, true), it.hotel.hotel())
            }
        }

    @Test
    fun hotelWithEmptyTradeNameDoesNotFail() =
        runTest {
            core { json(hotelBody(""","configured":true,"tradeName":""""")) }.use {
                val info = it.hotel.hotel()
                assertTrue(info.configured)
                assertTrue(info.tradeName.isNullOrEmpty(), "nome vazio deveria ser vazio ou nulo: ${info.tradeName}")
            }
        }

    @Test
    fun hotelNotConfiguredKeepsTheNameTheServerSent() =
        runTest {
            core { json(hotelBody(""","configured":false,"tradeName":"Rascunho"""")) }.use {
                assertEquals(HotelInfo("Rascunho", false), it.hotel.hotel())
            }
        }

    @Test
    fun hotelWithoutConfiguredFieldIsIncompatibleResponse() =
        runTest {
            core { json(hotelBody(""","tradeName":"Sol"""")) }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.hotel.hotel() }
            }
        }

    @Test
    fun hotelForbiddenIsAProblemWithoutEcho() =
        runTest {
            core { json(DENIED_BODY, HttpStatusCode.Forbidden) }.use { core ->
                val error = assertFailsWith<ApiError.Problem> { core.hotel.hotel() }
                assertEquals(403, error.status)
                assertEquals("Você não tem permissão para esta ação.", error.userMessage)
                assertClean(error, SECRET_BODY, "PERMISSION_DENIED")
            }
        }

    @Test
    fun hotelServerErrorIsATypedErrorWithoutEcho() =
        runTest {
            for (body in listOf(
                """{"code":"INTERNAL_ERROR","detail":"$SECRET_BODY"}""",
                "<html>$SECRET_BODY</html>",
                "",
            )) {
                core { json(body, HttpStatusCode.InternalServerError) }.use { core ->
                    val error = assertFailsWith<ApiError> { core.hotel.hotel() }
                    assertClean(error, SECRET_BODY, "INTERNAL_ERROR")
                }
            }
        }

    @Test
    fun hotelWithUnreadableJsonIsIncompatibleResponseWithoutEcho() =
        runTest {
            for (body in listOf("""{"configured":tru$SECRET_BODY""", "[1,2,$SECRET_BODY", "não é json $SECRET_BODY")) {
                core { json(body) }.use { core ->
                    val error = assertFailsWith<ApiError.IncompatibleResponse> { core.hotel.hotel() }
                    assertClean(error, SECRET_BODY)
                }
            }
        }

    @Test
    fun hotelWithNonJsonContentTypeIsATypedError() =
        runTest {
            core { json("<html>$SECRET_BODY</html>", contentType = "text/html") }.use { core ->
                val error = assertFailsWith<ApiError> { core.hotel.hotel() }
                assertClean(error, SECRET_BODY)
            }
        }

    // ---- getBusinessDate ----

    @Test
    fun businessDateIgnoresAStrangeTimezone() =
        runTest {
            for (zone in listOf("Not/AZone", "", "UTC+99", "Pacific/Kiritimati")) {
                core { json("""{"businessDate":"2026-03-01","timezone":"$zone"}""") }.use { core ->
                    assertEquals("2026-03-01", core.hotel.businessDate().toApi(), "zona '$zone'")
                }
            }
        }

    @Test
    fun businessDateAtYearBoundaryAndLeapDayStaysTheSame() =
        runTest {
            for (date in listOf("2026-12-31", "2027-01-01", "2028-02-29")) {
                core { json("""{"businessDate":"$date","timezone":"Pacific/Auckland"}""") }.use { core ->
                    assertEquals(date, core.hotel.businessDate().toApi())
                }
            }
        }

    @Test
    fun businessDateWithInvalidValueIsATypedApiError() =
        runTest {
            val invalid = listOf("2026-02-30", "11/11/2026", "", "2026-11-11T00:00:00Z", "2026-13-01", "ontem")
            for (date in invalid) {
                core { json("""{"businessDate":"$date","timezone":"America/Sao_Paulo"}""") }.use { core ->
                    val error = runCatching { core.hotel.businessDate() }.exceptionOrNull()
                    assertTrue(error is ApiError, "data '$date' deveria dar ApiError, deu: $error")
                }
            }
        }

    @Test
    fun businessDateWithoutDateFieldIsIncompatibleResponse() =
        runTest {
            core { json("""{"timezone":"America/Sao_Paulo"}""") }.use { core ->
                assertFailsWith<ApiError.IncompatibleResponse> { core.hotel.businessDate() }
            }
        }

    // ---- grant ----

    private suspend fun grantAndCapture(limit: ApprovalLimit?): String {
        var sent = ""
        core { request ->
            sent = request.body.toByteArray().decodeToString()
            json(GRANTED_BODY)
        }.use { it.approvals.grant(required(limit), ApprovalInput("gerente", SECRET_PIN, "motivo")) }
        return sent
    }

    @Test
    fun grantCopiesLimitAmountByteForByte() =
        runTest {
            for (amount in listOf("150.00", "15", "0.5", "0.50", "1e3", "007", "1.000,50")) {
                val sent = grantAndCapture(ApprovalLimit("discount.percent", amount))
                val limit =
                    Json
                        .parseToJsonElement(sent)
                        .jsonObject
                        .getValue("limit")
                        .jsonObject
                assertEquals(amount, limit.getValue("amount").jsonPrimitive.content, sent)
                assertTrue(limit.getValue("amount").jsonPrimitive.isString, "amount deve seguir string: $sent")
                assertTrue(""""amount":"$amount"""" in sent, sent)
            }
        }

    @Test
    fun grantWithoutLimitOmitsOrNullsTheLimitField() =
        runTest {
            val limit = Json.parseToJsonElement(grantAndCapture(null)).jsonObject["limit"]
            assertTrue(limit == null || limit == JsonNull)
        }

    @Test
    fun grantSendsPinOnlyInTheApproverPinField() =
        runTest {
            val body = Json.parseToJsonElement(grantAndCapture(null)).jsonObject
            assertEquals(SECRET_PIN, body.getValue("approverPin").jsonPrimitive.content)
            assertEquals(1, body.values.count { it is JsonPrimitive && SECRET_PIN in it.content })
        }

    @Test
    fun grantRefusalsBecomeApiErrorWithPtBrMessageAndNoPin() =
        runTest {
            val expected =
                listOf(
                    Triple(
                        HttpStatusCode.Forbidden,
                        "APPROVAL_CREDENTIALS_INVALID",
                        "Login ou senha do aprovador incorretos.",
                    ),
                    Triple(
                        HttpStatusCode.Forbidden,
                        "APPROVER_LOCKED",
                        "A conta do aprovador está bloqueada. Aguarde e tente de novo.",
                    ),
                    Triple(
                        HttpStatusCode.UnprocessableEntity,
                        "APPROVAL_NOT_APPLICABLE",
                        "Esta ação não precisa de aprovação.",
                    ),
                )
            for ((status, code, message) in expected) {
                val body = """{"code":"$code","detail":"PIN $SECRET_PIN recusado"}"""
                core { json(body, status) }.use { core ->
                    val error =
                        assertFailsWith<ApiError.Problem> {
                            core.approvals.grant(required(), ApprovalInput("gerente", SECRET_PIN, "motivo"))
                        }
                    assertEquals(status.value, error.status)
                    assertEquals(message, error.userMessage)
                    assertClean(error, SECRET_PIN, code)
                }
            }
        }

    @Test
    fun grantWithUnknownRefusalCodeDoesNotEchoThePin() =
        runTest {
            val body = """{"code":"NOVO_CODIGO","detail":"{\"pin\":\"$SECRET_PIN\"}"}"""
            core { json(body, HttpStatusCode.Forbidden) }.use { core ->
                val error =
                    assertFailsWith<ApiError> {
                        core.approvals.grant(required(), ApprovalInput("g", SECRET_PIN, "m"))
                    }
                assertClean(error, SECRET_PIN, "NOVO_CODIGO")
            }
        }

    @Test
    fun grantResponseWithoutTokenIsIncompatibleAndDoesNotEchoThePin() =
        runTest {
            core { json("""{"approvalId":"$UID","pinEco":"$SECRET_PIN"}""") }.use { core ->
                val error =
                    assertFailsWith<ApiError.IncompatibleResponse> {
                        core.approvals.grant(required(), ApprovalInput("g", SECRET_PIN, "m"))
                    }
                assertClean(error, SECRET_PIN)
            }
        }

    @Test
    fun grantNetworkFailureIsNetworkErrorWithoutPin() =
        runTest {
            val core =
                ZenyCore(
                    checkNotNull(ServerUrl.parse("http://servidor.teste")),
                    MemorySettings(),
                    MockEngine { throw IOException("falha com $SECRET_PIN") },
                )
            core.use {
                val error =
                    assertFailsWith<ApiError.Network> {
                        it.approvals.grant(required(), ApprovalInput("g", SECRET_PIN, "m"))
                    }
                assertEquals(NETWORK_MESSAGE, error.userMessage)
                assertFalse(SECRET_PIN in error.userMessage)
            }
        }

    // ---- ApprovalInput ----

    @Test
    fun approvalInputNeverShowsThePinInAnyStringForm() {
        val input = ApprovalInput("gerente", SECRET_PIN, "motivo")
        val texts =
            listOf(input.toString(), "$input", input.copy(reason = "outro").toString(), listOf(input).toString())
        for (text in texts) {
            assertFalse(SECRET_PIN in text, text)
            assertTrue("gerente" in text, text)
        }
        assertEquals(SECRET_PIN, input.approverPin, "o PIN continua acessível a quem monta a requisição")
    }

    @Test
    fun approvalInputToStringDoesNotLeakPinLengthOrEmptiness() {
        assertEquals(ApprovalInput("g", "", "m").toString(), ApprovalInput("g", "123456789", "m").toString())
    }

    // ---- approvable ----

    @Test
    fun approvableRunNullWithApprovalRequiredThrowsApprovalRequiredWithTheProblemFields() =
        runTest {
            core { json(APPROVAL_PROBLEM, HttpStatusCode.Forbidden) }.use { core ->
                val action = core.approvable(Intent()) { core.sdkApis.hotel.getBusinessDate(it.headers) }
                val error = assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                assertEquals(Triple("a", "p", "r"), Triple(error.action, error.permission, error.resourceRef))
                assertEquals(ApprovalLimit("discount.percent", "150.00"), error.limit)
                assertNull(requests.single().headers["X-Approval-Token"])
                assertTrue(requests.single().headers["Idempotency-Key"]!!.isNotBlank())
            }
        }

    @Test
    fun approvableRunTokenSendsTheTokenOnceAndANewKey() =
        runTest {
            core { request ->
                if (request.headers["X-Approval-Token"] == null) {
                    json(APPROVAL_PROBLEM, HttpStatusCode.Forbidden)
                } else {
                    json(DATE_BODY)
                }
            }.use { core ->
                val action = core.approvable(Intent()) { core.sdkApis.hotel.getBusinessDate(it.headers) }
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                action.run("tok-1")
                assertEquals(listOf("tok-1"), requests[1].headers.getAll("X-Approval-Token"))
                assertEquals(1, requests[1].headers.getAll("Idempotency-Key")?.size)
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun approvableNetworkResendAfterApprovalKeepsKeyAndToken() =
        runTest {
            var attempt = 0
            core { request ->
                attempt++
                when {
                    request.headers["X-Approval-Token"] == null -> json(APPROVAL_PROBLEM, HttpStatusCode.Forbidden)
                    attempt == 2 -> throw IOException("rede caiu")
                    else -> json(DATE_BODY)
                }
            }.use { core ->
                val action = core.approvable(Intent()) { core.sdkApis.hotel.getBusinessDate(it.headers) }
                assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
                assertFailsWith<ApiError.Network> { action.run("tok-1") }
                val resent = action.run(null)
                assertEquals(200, resent.status)
                assertEquals(3, requests.size)
                assertEquals("tok-1", requests[2].headers["X-Approval-Token"])
                assertEquals(requests[1].headers["Idempotency-Key"], requests[2].headers["Idempotency-Key"])
            }
        }

    @Test
    fun approvableNetworkResendWithoutApprovalKeepsTheSameKeyAndNoToken() =
        runTest {
            var attempt = 0
            core {
                attempt++
                if (attempt == 1) throw IOException("rede caiu") else json(DATE_BODY)
            }.use { core ->
                val action = core.approvable(Intent()) { core.sdkApis.hotel.getBusinessDate(it.headers) }
                assertFailsWith<ApiError.Network> { action.run(null) }
                action.run(null)
                assertEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
                assertNull(requests[1].headers["X-Approval-Token"])
            }
        }

    @Test
    fun approvableDifferentIntentsDoNotShareKeyOrToken() =
        runTest {
            core { json(DATE_BODY) }.use { core ->
                val first = core.approvable(Intent()) { core.sdkApis.hotel.getBusinessDate(it.headers) }
                val second = core.approvable(Intent()) { core.sdkApis.hotel.getBusinessDate(it.headers) }
                first.run("tok-A")
                second.run(null)
                assertNull(requests[1].headers["X-Approval-Token"])
                assertNotEquals(requests[0].headers["Idempotency-Key"], requests[1].headers["Idempotency-Key"])
            }
        }

    @Test
    fun approvableRejectedTokenSurfacesTheCatalogMessageNotTheToken() =
        runTest {
            val body = """{"code":"APPROVAL_TOKEN_EXPIRED","detail":"token tok-secreto expirou"}"""
            core { json(body, HttpStatusCode.Forbidden) }.use { core ->
                val action = core.approvable(Intent()) { core.sdkApis.hotel.getBusinessDate(it.headers) }
                val error = assertFailsWith<ApiError.Problem> { action.run("tok-secreto") }
                assertEquals("A aprovação expirou. Peça uma nova aprovação.", error.userMessage)
                assertClean(error, "tok-secreto")
            }
        }

    @Test
    fun approvableSuccessKeepsEtagAndStatus() =
        runTest {
            val headers =
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.ETag to listOf("\"v3\""),
                )
            core { respond(DATE_BODY, HttpStatusCode.OK, headers) }.use { core ->
                val response = core.approvable(Intent()) { core.sdkApis.hotel.getBusinessDate(it.headers) }.run(null)
                assertEquals("\"v3\"", response.etag)
                assertEquals(200, response.status)
            }
        }

    // ---- exposição ----

    @Test
    fun publicFacadesAreUsableWithoutAnyOptIn() =
        runTest {
            core { request ->
                when {
                    request.url.encodedPath.endsWith("/me") -> json(meBody(perm("a.b")))
                    request.url.encodedPath.endsWith("/business-date") -> json(DATE_BODY)
                    request.url.encodedPath.endsWith("/approvals") -> json(GRANTED_BODY)
                    else -> json(hotelBody(""","configured":true,"tradeName":"Sol""""))
                }
            }.use { core ->
                assertEquals(setOf("a.b"), core.account.me().permissions)
                assertEquals(HotelInfo("Sol", true), core.hotel.hotel())
                assertEquals("2026-11-11", core.hotel.businessDate().toApi())
                assertEquals("tok-1", core.approvals.grant(required(), ApprovalInput("g", "1", "m")))
            }
        }
}
