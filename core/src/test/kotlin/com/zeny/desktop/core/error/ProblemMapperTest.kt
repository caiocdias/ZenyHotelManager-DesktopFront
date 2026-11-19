package com.zeny.desktop.core.error

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProblemMapperTest {
    private suspend fun respondWith(
        status: Int,
        body: String,
    ): ApiError {
        val engine =
            MockEngine {
                respond(
                    body,
                    HttpStatusCode.fromValue(status),
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            }
        val response: HttpResponse = HttpClient(engine) { expectSuccess = false }.use { it.get("http://x/y") }
        return ProblemMapper.fromResponse(response)
    }

    @Test
    fun approvalRequiredHasFourFields() =
        runTest {
            val body =
                """{"code":"APPROVAL_REQUIRED","action":"folio.reverse","permission":"billing.postings.reverse",
                "resourceRef":"folio:123","limit":{"key":"maxAmount","amount":"150.00"},"detail":"x"}"""
            val error = assertIs<ApiError.ApprovalRequired>(respondWith(403, body))
            assertEquals("folio.reverse", error.action)
            assertEquals("billing.postings.reverse", error.permission)
            assertEquals("folio:123", error.resourceRef)
            assertEquals("150.00", error.limit?.amount)
            assertEquals("maxAmount", error.limit?.key)

            val semLimite =
                ProblemMapper.fromBody(
                    403,
                    """{"code":"APPROVAL_REQUIRED","action":"a","permission":"p","resourceRef":"r"}""",
                )
            assertNull(assertIs<ApiError.ApprovalRequired>(semLimite).limit)
        }

    @Test
    fun approvalRequiredWithoutActionIsProblem() =
        runTest {
            val error =
                respondWith(403, """{"code":"APPROVAL_REQUIRED","permission":"p","resourceRef":"r"}""")
            assertEquals("APPROVAL_REQUIRED", assertIs<ApiError.Problem>(error).code)
        }

    @Test
    fun preconditionFailedIsTyped() =
        runTest {
            val error = respondWith(412, """{"code":"PRECONDITION_FAILED","detail":"d"}""")
            assertEquals("d", assertIs<ApiError.PreconditionFailed>(error).detail)
        }

    @Test
    fun passwordChangeRequiredIsTyped() =
        runTest {
            val error = respondWith(403, """{"code":"PASSWORD_CHANGE_REQUIRED"}""")
            assertIs<ApiError.PasswordChangeRequired>(error)
        }

    @Test
    fun validationErrorsGoToFields() =
        runTest {
            val body =
                """{"code":"VALIDATION_FAILED","errors":[
                {"field":"price","code":"NOT_BLANK","message":"não deve estar em branco"},
                {"field":"x"},{"code":1}]}"""
            val error = assertIs<ApiError.Problem>(respondWith(422, body))
            assertEquals(listOf(FieldError("price", "NOT_BLANK", "não deve estar em branco")), error.fieldErrors)
        }

    @Test
    fun partialProblemKeepsCode() =
        runTest {
            val error = assertIs<ApiError.Problem>(respondWith(401, """{"code":"INVALID_CREDENTIALS"}"""))
            assertEquals("INVALID_CREDENTIALS", error.code)
            assertEquals("Login ou senha incorretos.", error.userMessage)
        }

    @Test
    fun wrongTypesBecomeNull() =
        runTest {
            val error = assertIs<ApiError.Problem>(respondWith(400, """{"code":"MALFORMED_REQUEST","detail":5}"""))
            assertNull(error.detail)
        }

    @Test
    fun nonProblemBodyBecomesUnexpected() =
        runTest {
            val error = assertIs<ApiError.Unexpected>(respondWith(502, "<html>Bad Gateway</html>"))
            assertEquals(502, error.status)
            assertFalse(error.userMessage.contains("html"))
        }

    @Test
    fun emptyBodyBecomesUnexpected() =
        runTest {
            assertEquals(500, assertIs<ApiError.Unexpected>(respondWith(500, "")).status)
            assertIs<ApiError.Unexpected>(ProblemMapper.fromBody(500, """{"title":"t"}"""))
            assertIs<ApiError.Unexpected>(ProblemMapper.fromBody(500, "[]"))
        }

    @Test
    fun problemCodeReadsOnlyCode() {
        assertEquals("UNAUTHENTICATED", ProblemMapper.problemCode("{\"code\":\"UNAUTHENTICATED\"}"))
        assertNull(ProblemMapper.problemCode("<html>"))
        assertNull(ProblemMapper.problemCode("[]"))
        assertNull(ProblemMapper.problemCode("{\"code\":3}"))
    }

    @Test
    fun extensionsKeepTheExtraFields() {
        val body =
            """{"type":"about:blank","title":"t","status":409,"detail":"d","instance":"/x","code":"FOLIO_BALANCE_NOT_ZERO",
            "errors":[{"field":"f","code":"c","message":"m"}],
            "folios":[{"folioId":"a","number":"F-1","status":"OPEN","balance":"10.00"}],"total":"10.00"}"""
        val error = assertIs<ApiError.Problem>(ProblemMapper.fromBody(409, body))
        assertEquals(setOf("folios", "total"), error.extensions.keys)
        assertEquals("10.00", error.extensions["total"]?.jsonPrimitive?.content)
        assertEquals(1, error.extensions["folios"]?.jsonArray?.size)
        assertEquals("FOLIO_BALANCE_NOT_ZERO", error.code)
        assertEquals("d", error.detail)
        assertEquals(1, error.fieldErrors.size)
    }

    @Test
    fun problemWithoutExtrasHasEmptyExtensions() {
        val error =
            assertIs<ApiError.Problem>(
                ProblemMapper.fromBody(409, """{"type":"about:blank","title":"t","code":"RESERVATION_CHANGED"}"""),
            )
        assertTrue(error.extensions.isEmpty())
        assertTrue(ApiError.Problem(409, "X", null, emptyList()).extensions.isEmpty())
    }

    @Test
    fun extensionsStayOutOfToString() {
        val body = """{"code":"FOLIO_BALANCE_NOT_ZERO","detail":"d","segredo":"valor-extra-987","total":"4321.09"}"""
        val error = assertIs<ApiError.Problem>(ProblemMapper.fromBody(409, body))
        assertEquals(setOf("segredo", "total"), error.extensions.keys)
        val text = error.toString()
        assertFalse(text.contains("valor-extra-987"), text)
        assertFalse(text.contains("4321.09"), text)
        assertFalse(text.contains("segredo"), text)
    }
}
