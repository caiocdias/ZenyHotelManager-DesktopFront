package com.zeny.desktop.core.error

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ErrorCatalogTest {
    private val generic = "Não foi possível concluir a operação."

    private val common =
        listOf(
            "VALIDATION_FAILED",
            "MALFORMED_REQUEST",
            "INVALID_CURSOR",
            "UNAUTHENTICATED",
            "ACCESS_DENIED",
            "PASSWORD_CHANGE_REQUIRED",
            "APPROVAL_REQUIRED",
            "NOT_FOUND",
            "METHOD_NOT_ALLOWED",
            "NOT_ACCEPTABLE",
            "CONFLICT",
            "PRECONDITION_FAILED",
            "PAYLOAD_TOO_LARGE",
            "UNSUPPORTED_MEDIA_TYPE",
            "BUSINESS_RULE_VIOLATED",
            "PRECONDITION_REQUIRED",
            "IDEMPOTENCY_KEY_REQUIRED",
            "IDEMPOTENCY_KEY_INVALID",
            "IDEMPOTENCY_KEY_IN_USE",
            "IDEMPOTENCY_KEY_REUSED",
            "REQUEST_REJECTED",
            "INTERNAL_ERROR",
            "SERVICE_UNAVAILABLE",
        )

    private val auth =
        listOf(
            "INVALID_CREDENTIALS",
            "ACCOUNT_LOCKED",
            "ACCOUNT_DISABLED",
            "REFRESH_TOKEN_INVALID",
            "REFRESH_TOKEN_REUSED",
            "PASSWORD_UNCHANGED",
            "APPROVAL_CREDENTIALS_INVALID",
            "APPROVAL_NOT_APPLICABLE",
            "APPROVAL_TOKEN_EXPIRED",
            "APPROVAL_TOKEN_INVALID",
            "APPROVAL_TOKEN_MISMATCH",
            "APPROVAL_TOKEN_USED",
            "APPROVER_IS_REQUESTER",
            "APPROVER_LOCKED",
            "APPROVER_NOT_ALLOWED",
            "PERMISSION_DENIED",
            "RESOURCE_OUT_OF_SCOPE",
        )

    @Test
    fun contractCodesAreExtracted() {
        val codes = ContractErrorCodes.all()
        println("CONTRACT_CODES_BEGIN")
        codes.sorted().forEach { println(it) }
        println("CONTRACT_CODES_END")
        assertEquals(353, codes.size)
        assertTrue(codes.containsAll(common), "faltam comuns: ${common - codes.toSet()}")
    }

    @Test
    fun commonAndAuthCodesAreCovered() {
        assertEquals(23, common.size)
        assertEquals(17, auth.size)
        for (code in common + auth) assertNotNull(ErrorCatalog.message(code), "sem mensagem para $code")
    }

    @Test
    fun messagesArePortugueseAndNeverRaw() {
        val raw = Regex("[A-Z]+_[A-Z]+")
        for (code in common + auth) {
            val message = assertNotNull(ErrorCatalog.message(code))
            assertFalse(message.contains(code), "$code vaza o código")
            assertFalse(message.contains("{") || message.contains("}"), "$code tem chave")
            assertFalse(raw.containsMatchIn(message), "$code tem identificador cru")
            assertTrue(message.endsWith("."), "$code não termina com ponto")
        }
    }

    @Test
    fun unknownCodeFallsBackToDetail() {
        assertEquals("Detalhe do servidor.", ErrorCatalog.userMessage(422, "SOMETHING_NEW", "Detalhe do servidor."))
    }

    @Test
    fun unknownCodeWithoutDetailIsGeneric() {
        assertEquals(generic, ErrorCatalog.userMessage(500, "SOMETHING_NEW", null))
        assertEquals(generic, ErrorCatalog.userMessage(500, null, "  "))
    }

    @Test
    fun knownCodeWinsOverDetail() {
        assertEquals("Login ou senha incorretos.", ErrorCatalog.userMessage(401, "INVALID_CREDENTIALS", "x"))
    }

    @Test
    fun typedErrorsHaveFixedMessages() {
        assertEquals(
            "Esta ação precisa da aprovação de um superior.",
            ApiError.ApprovalRequired("a", "p", "r", null, null).userMessage,
        )
        assertEquals(
            "Alguém alterou este registro. Recarregue e tente de novo.",
            ApiError.PreconditionFailed(null).userMessage,
        )
        assertEquals("Troque a senha para continuar.", ApiError.PasswordChangeRequired.userMessage)
        assertEquals("Sua sessão foi encerrada. Entre novamente.", ApiError.SessionEnded(null).userMessage)
        val network = ApiError.Network(IOException("x"))
        assertEquals("Não foi possível falar com o servidor. Confira o endereço e a rede.", network.userMessage)
        assertFalse(network.userMessage.contains("x"))
        assertEquals(
            "O servidor respondeu num formato que esta versão do app não reconhece. Atualize o app.",
            ApiError.IncompatibleResponse(IOException("y")).userMessage,
        )
        assertEquals("O servidor respondeu com um erro inesperado (HTTP 502).", ApiError.Unexpected(502).userMessage)
        assertEquals("Detalhe.", ApiError.Problem(422, null, "Detalhe.", emptyList()).userMessage)
        assertEquals(
            "Login ou senha incorretos.",
            ApiError.Problem(401, "INVALID_CREDENTIALS", null, emptyList()).userMessage,
        )
    }
}
