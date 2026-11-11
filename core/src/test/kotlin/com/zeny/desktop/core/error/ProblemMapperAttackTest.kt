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
import kotlin.test.assertTrue

class ProblemMapperAttackTest {
    private suspend fun respondBytes(
        status: Int,
        body: ByteArray,
        contentType: String,
    ): ApiError {
        val engine =
            MockEngine {
                respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, contentType))
            }
        val response: HttpResponse = HttpClient(engine) { expectSuccess = false }.use { it.get("http://x/y") }
        return ProblemMapper.fromResponse(response)
    }

    private suspend fun respondText(
        status: Int,
        body: String,
        contentType: String = "application/problem+json",
    ) = respondBytes(status, body.toByteArray(), contentType)

    private fun approvalBody(
        action: String = "a",
        permission: String = "p",
        resourceRef: String = "r",
    ) = """{"code":"APPROVAL_REQUIRED","action":"$action","permission":"$permission","resourceRef":"$resourceRef"}"""

    @Test
    fun codeThatIsNotAStringIsIgnored() {
        val bodies =
            listOf(
                """{"code":123}""",
                """{"code":{"a":1}}""",
                """{"code":null}""",
                """{"code":["X"]}""",
                """{"code":true}""",
            )
        for (body in bodies) {
            assertIs<ApiError.Unexpected>(ProblemMapper.fromBody(422, body), body)
            assertNull(ProblemMapper.problemCode(body), body)
        }
    }

    @Test
    fun codeThatIsNotAStringKeepsDetailAsProblemWithoutCode() {
        val error =
            assertIs<ApiError.Problem>(ProblemMapper.fromBody(400, """{"code":123,"detail":"Falhou ao salvar."}"""))
        assertNull(error.code)
        assertEquals("Falhou ao salvar.", error.userMessage)
    }

    @Test
    fun errorsThatIsNotAnArrayYieldsNoFieldErrors() {
        for (errors in listOf("""{"a":1}""", "\"x\"", "7", "null", "true")) {
            val error =
                assertIs<ApiError.Problem>(
                    ProblemMapper.fromBody(422, """{"code":"VALIDATION_FAILED","errors":$errors}"""),
                )
            assertTrue(error.fieldErrors.isEmpty(), errors)
            assertEquals("VALIDATION_FAILED", error.code)
        }
    }

    @Test
    fun errorsItemsOfWrongShapeAreSkippedAndValidOnesKept() {
        val body =
            """{"code":"VALIDATION_FAILED","errors":[null,1,"x",[],{},{"field":"a"},{"field":1,"code":"C","message":"m"},
            {"field":"ok","code":"NOT_BLANK","message":"msg"},{"field":"b","code":null,"message":"m"}]}"""
        val error = assertIs<ApiError.Problem>(ProblemMapper.fromBody(422, body))
        assertEquals(listOf(FieldError("ok", "NOT_BLANK", "msg")), error.fieldErrors)
    }

    @Test
    fun limitWithNumericAmountIsDroppedButApprovalKept() {
        val body =
            """{"code":"APPROVAL_REQUIRED","action":"a","permission":"p","resourceRef":"r","limit":{"key":"maxAmount","amount":150.00}}"""
        val error = assertIs<ApiError.ApprovalRequired>(ProblemMapper.fromBody(403, body))
        assertNull(error.limit)
    }

    @Test
    fun limitThatIsNotAnObjectIsDropped() {
        for (limit in listOf("\"x\"", "[]", "null", "5")) {
            val body = """{"code":"APPROVAL_REQUIRED","action":"a","permission":"p","resourceRef":"r","limit":$limit}"""
            assertNull(assertIs<ApiError.ApprovalRequired>(ProblemMapper.fromBody(403, body)).limit, limit)
        }
    }

    @Test
    fun limitAmountIsCopiedVerbatim() {
        val body =
            """{"code":"APPROVAL_REQUIRED","action":"a","permission":"p","resourceRef":"r","limit":{"key":"k","amount":"0150.500"}}"""
        assertEquals("0150.500", assertIs<ApiError.ApprovalRequired>(ProblemMapper.fromBody(403, body)).limit?.amount)
    }

    @Test
    fun approvalRequiredWithNonStringFieldsIsProblem() {
        val body = """{"code":"APPROVAL_REQUIRED","action":1,"permission":null,"resourceRef":{}}"""
        assertIs<ApiError.Problem>(ProblemMapper.fromBody(403, body))
    }

    @Test
    fun approvalRequiredWithEmptyActionIsProblem() {
        assertIs<ApiError.Problem>(ProblemMapper.fromBody(403, approvalBody(action = "")))
    }

    @Test
    fun approvalRequiredWithEmptyPermissionIsProblem() {
        assertIs<ApiError.Problem>(ProblemMapper.fromBody(403, approvalBody(permission = "")))
    }

    @Test
    fun approvalRequiredWithEmptyResourceRefIsProblem() {
        assertIs<ApiError.Problem>(ProblemMapper.fromBody(403, approvalBody(resourceRef = "")))
    }

    @Test
    fun approvalRequiredOnOtherStatusIsPlainProblem() {
        for (status in listOf(401, 409, 422, 500)) {
            val error = assertIs<ApiError.Problem>(ProblemMapper.fromBody(status, approvalBody()), "$status")
            assertEquals("APPROVAL_REQUIRED", error.code)
        }
    }

    @Test
    fun passwordChangeRequiredOnOtherStatusIsPlainProblem() {
        val error = assertIs<ApiError.Problem>(ProblemMapper.fromBody(401, """{"code":"PASSWORD_CHANGE_REQUIRED"}"""))
        assertEquals(401, error.status)
    }

    @Test
    fun lowercaseCodesAreNotSpecial() {
        assertIs<ApiError.Problem>(ProblemMapper.fromBody(403, """{"code":"password_change_required"}"""))
        assertIs<ApiError.Problem>(
            ProblemMapper.fromBody(403, approvalBody().replace("APPROVAL_REQUIRED", "approval_required")),
        )
    }

    @Test
    fun preconditionFailedWithHtmlBodyIsStillTyped() {
        val error =
            assertIs<ApiError.PreconditionFailed>(ProblemMapper.fromBody(412, "<html>Precondition Failed</html>"))
        assertNull(error.detail)
    }

    @Test
    fun preconditionFailedWinsOverOtherCodes() {
        assertIs<ApiError.PreconditionFailed>(ProblemMapper.fromBody(412, approvalBody()))
        assertIs<ApiError.PreconditionFailed>(ProblemMapper.fromBody(412, ""))
    }

    @Test
    fun nonObjectTopLevelJsonIsUnexpected() {
        for (body in listOf("null", "\"x\"", "123", "true", "[{\"code\":\"X\"}]", "{}", "{", "   ", "\u0000")) {
            assertIs<ApiError.Unexpected>(ProblemMapper.fromBody(500, body), body)
            assertNull(ProblemMapper.problemCode(body), body)
        }
    }

    @Test
    fun detailOnlyBodyIsProblemWithoutCode() {
        val error = assertIs<ApiError.Problem>(ProblemMapper.fromBody(500, """{"detail":"Banco indisponível."}"""))
        assertNull(error.code)
        assertEquals("Banco indisponível.", error.detail)
    }

    @Test
    fun bomPrefixedBodyNeverThrows() {
        // O BOM vai por escape: um U+FEFF literal no fonte some sem aviso em editores e no ktlint.
        val body = "﻿{\"code\":\"UNAUTHENTICATED\",\"detail\":\"Token vencido.\"}"
        assertEquals('﻿', body.first())

        // Resultado atual, fixado: o parser recusa o BOM, o corpo conta como ilegível e o 401 vira `Unexpected`
        // (sem `code`), em vez de `Problem(401, UNAUTHENTICATED)`.
        val error = assertIs<ApiError.Unexpected>(ProblemMapper.fromBody(401, body))
        assertEquals(401, error.status)
        assertNull(ProblemMapper.problemCode(body))
    }

    @Test
    fun surroundingSpacesInCodeDoNotMatchCatalog() {
        val error = assertIs<ApiError.Problem>(ProblemMapper.fromBody(401, """{"code":" INVALID_CREDENTIALS "}"""))
        assertEquals(" INVALID_CREDENTIALS ", error.code)
        assertFalse(error.userMessage.contains("INVALID_CREDENTIALS"))
    }

    @Test
    fun problemCodeReadsOnlyStringCode() {
        assertEquals("", ProblemMapper.problemCode("""{"code":""}"""))
        assertNull(ProblemMapper.problemCode("""{"Code":"X"}"""))
        assertEquals("X", ProblemMapper.problemCode("""{"code":"X","errors":7,"extra":[1,2]}"""))
    }

    @Test
    fun hugeBodyIsParsedWithoutFailing() {
        val padding = "a".repeat(5_000_000)
        val error =
            assertIs<ApiError.Problem>(ProblemMapper.fromBody(422, """{"code":"VALIDATION_FAILED","pad":"$padding"}"""))
        assertEquals("VALIDATION_FAILED", error.code)
    }

    @Test
    fun hugeBodyOfNonJsonIsUnexpected() {
        assertIs<ApiError.Unexpected>(ProblemMapper.fromBody(502, "x".repeat(5_000_000)))
    }

    @Test
    fun deeplyNestedJsonNeverThrows() {
        val depth = 100_000
        val open = "[".repeat(depth)
        val nested = open + "]".repeat(depth)
        assertIs<ApiError>(ProblemMapper.fromBody(500, """{"code":"X","deep":$nested}"""))
        assertIs<ApiError>(ProblemMapper.fromBody(500, """{"code":"X","deep":$open"""))
        assertIs<ApiError>(ProblemMapper.fromBody(500, open))
        ProblemMapper.problemCode("""{"code":"X","deep":${"{\"a\":".repeat(depth)}""")
    }

    @Test
    fun contentTypeIsIgnoredWhenBodyIsValidProblemJson() =
        runTest {
            val error = respondText(422, """{"code":"VALIDATION_FAILED"}""", "text/html")
            assertEquals("VALIDATION_FAILED", assertIs<ApiError.Problem>(error).code)
        }

    @Test
    fun jsonContentTypeWithHtmlBodyIsUnexpected() =
        runTest {
            val error = respondText(502, "<html><body>Bad Gateway</body></html>", "application/json")
            assertEquals(502, assertIs<ApiError.Unexpected>(error).status)
        }

    @Test
    fun declaredLatin1CharsetIsHonoredForDetail() =
        runTest {
            val bytes = """{"code":"X","detail":"Não foi possível salvar."}""".toByteArray(Charsets.ISO_8859_1)
            val error = respondBytes(400, bytes, "application/problem+json; charset=ISO-8859-1")
            assertEquals("Não foi possível salvar.", assertIs<ApiError.Problem>(error).detail)
        }

    @Test
    fun utf8BodyWithoutCharsetKeepsAccents() =
        runTest {
            val error = respondText(400, """{"code":"X","detail":"Não foi possível salvar."}""", "application/json")
            assertEquals("Não foi possível salvar.", assertIs<ApiError.Problem>(error).detail)
        }

    @Test
    fun userMessageNeverEchoesHostileBodies() {
        val hostile =
            listOf(
                "<html><script>alert('segredo-xyz')</script></html>",
                """{"detail":"{\"token\":\"segredo-xyz\"}"}""",
                """{"detail":"[segredo-xyz]"}""",
                """{"code":"CODIGO_INEXISTENTE_SEGREDO","detail":"SEGREDO_XYZ"}""",
                """{"code":"VALIDATION_FAILED","errors":[{"field":"f","code":"C","message":"segredo-xyz"}]}""",
            )
        for (body in hostile) {
            for (status in listOf(400, 401, 403, 404, 500, 502)) {
                val message = ProblemMapper.fromBody(status, body).userMessage
                assertFalse(message.contains("segredo-xyz", ignoreCase = true), "$status $body -> $message")
                assertFalse(message.contains("<"), "$status $body -> $message")
                assertFalse(message.contains("{"), "$status $body -> $message")
            }
        }
    }
}
