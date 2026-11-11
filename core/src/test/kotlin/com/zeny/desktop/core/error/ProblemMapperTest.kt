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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

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
}
