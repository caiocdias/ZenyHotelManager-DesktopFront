package com.zeny.desktop.core.error

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Ataques ao contrato público de `ApiError` e `ErrorCatalog` (Task 3). */
class ApiErrorAttackTest {
    private val generic = "Não foi possível concluir a operação."
    private val hostileCause = "token=abc123 {\"code\":\"REFRESH_TOKEN_REUSED\"}"

    private val everyKind: List<ApiError> =
        listOf(
            ApiError.Problem(422, "SOMETHING_NEW", null, emptyList()),
            ApiError.Problem(400, "VALIDATION_FAILED", "{\"x\":1}", listOf(FieldError("f", "INVALID_FORMAT", "ruim"))),
            ApiError.ApprovalRequired("DISCOUNT", "folio.discount", "folio/1", ApprovalLimit("k", "10.5"), "{\"d\":1}"),
            ApiError.PreconditionFailed("{\"d\":1}"),
            ApiError.PasswordChangeRequired,
            ApiError.SessionEnded("REFRESH_TOKEN_REUSED"),
            ApiError.Network(IOException(hostileCause)),
            ApiError.IncompatibleResponse(IllegalStateException(hostileCause)),
            ApiError.Unexpected(502),
        )

    @Test
    fun noUserMessageEchoesCauseOrCodeOrJson() {
        for (error in everyKind) {
            val shown = listOf(error.userMessage, error.message.orEmpty(), error.toString())
            for (text in shown) {
                assertFalse(text.contains("abc123"), "vaza a causa em: $text")
                assertFalse(text.contains("REFRESH_TOKEN_REUSED"), "vaza o code em: $text")
                assertFalse(text.contains("SOMETHING_NEW"), "vaza o code em: $text")
                assertFalse(text.contains("{") || text.contains("}"), "vaza JSON em: $text")
            }
        }
    }

    @Test
    fun networkAndIncompatibleKeepCauseByIdentityButNeverShowIt() {
        val io = IOException(hostileCause)
        val network = ApiError.Network(io)
        assertSame(io, network.cause)
        assertFalse(network.userMessage.contains("abc123"))
        val bad = IllegalStateException(hostileCause)
        assertSame(bad, ApiError.IncompatibleResponse(bad).cause)
    }

    @Test
    fun messageEqualsUserMessageForEveryKind() {
        for (error in everyKind) assertEquals(error.userMessage, error.message)
    }

    @Test
    fun problemAndApprovalAndPreconditionHaveNoCause() {
        assertNull(ApiError.Problem(500, null, null, emptyList()).cause)
        assertNull(ApiError.ApprovalRequired("a", "p", "r", null, null).cause)
        assertNull(ApiError.PreconditionFailed(null).cause)
        assertNull(ApiError.Unexpected(500).cause)
    }

    @Test
    fun approvalAndPreconditionMessagesIgnoreTheirDetail() {
        val approval = ApiError.ApprovalRequired("a", "p", "r", ApprovalLimit("k", "1.5"), "{\"detalhe\":\"cru\"}")
        assertEquals("Esta ação precisa da aprovação de um superior.", approval.userMessage)
        val precondition = ApiError.PreconditionFailed("{\"detalhe\":\"cru\"}")
        assertEquals("Alguém alterou este registro. Recarregue e tente de novo.", precondition.userMessage)
    }

    @Test
    fun approvalLimitAmountIsKeptVerbatim() {
        val approval = ApiError.ApprovalRequired("a", "p", "r", ApprovalLimit("k", "1000.50"), null)
        assertEquals("1000.50", approval.limit?.amount)
    }

    @Test
    fun problemMessagesForSharedCodesMatchTheTypedErrors() {
        assertEquals(
            ApiError.PasswordChangeRequired.userMessage,
            ApiError.Problem(403, "PASSWORD_CHANGE_REQUIRED", null, emptyList()).userMessage,
        )
        assertEquals(
            ApiError.ApprovalRequired("a", "p", "r", null, null).userMessage,
            ApiError.Problem(403, "APPROVAL_REQUIRED", null, emptyList()).userMessage,
        )
        assertEquals(
            ApiError.PreconditionFailed(null).userMessage,
            ApiError.Problem(412, "PRECONDITION_FAILED", null, emptyList()).userMessage,
        )
    }

    @Test
    fun unexpectedMessageCarriesTheExactStatus() {
        for (status in listOf(0, 418, 500, 503, 599)) {
            assertEquals(
                "O servidor respondeu com um erro inesperado (HTTP $status).",
                ApiError.Unexpected(status).userMessage,
            )
        }
    }

    @Test
    fun validationProblemShowsCatalogTextNotFieldErrors() {
        val problem =
            ApiError.Problem(
                400,
                "VALIDATION_FAILED",
                "cnpj inválido",
                listOf(FieldError("cnpj", "INVALID_FORMAT", "formato")),
            )
        assertEquals("Alguns campos estão inválidos. Corrija e tente de novo.", problem.userMessage)
    }

    @Test
    fun knownCodeWinsOverJsonDetailRegardlessOfStatus() {
        for (status in listOf(0, 400, 404, 500)) {
            assertEquals(
                "O registro não foi encontrado.",
                ErrorCatalog.userMessage(status, "NOT_FOUND", "{\"cru\":true}"),
            )
        }
    }

    @Test
    fun unknownCodeNeverEchoesItselfWhenDetailIsMissing() {
        val text = ErrorCatalog.userMessage(409, "SOMETHING_NEW", null)
        assertFalse(text.contains("SOMETHING_NEW"))
        assertEquals(generic, text)
    }

    @Test
    fun everyKindOfBlankDetailIsGeneric() {
        for (blank in listOf("", " ", "\t", "\n", " \r\n\t ", " ", " ")) {
            assertEquals(generic, ErrorCatalog.userMessage(500, null, blank), "detail=${blank.toList()}")
            assertEquals(generic, ErrorCatalog.userMessage(500, "SOMETHING_NEW", blank))
        }
    }

    @Test
    fun codeLookupIsExactAndCaseSensitive() {
        for (code in listOf("", " ", "not_found", " NOT_FOUND", "NOT_FOUND ", "Not_Found", "NOT-FOUND")) {
            assertNull(ErrorCatalog.message(code), "code='$code'")
        }
        assertEquals("Detalhe.", ErrorCatalog.userMessage(404, "not_found", "Detalhe."))
    }

    @Test
    fun catalogMessagesAreCleanSingleSentences() {
        val codes = ContractErrorCodes.all()
        val mapped = codes.mapNotNull { c -> ErrorCatalog.message(c)?.let { c to it } }
        assertTrue(mapped.size >= 36, "poucos códigos do contrato mapeados: ${mapped.size}")
        for ((code, text) in mapped) {
            assertEquals(text.trim(), text, "$code com espaço sobrando")
            assertFalse(text.contains("  "), "$code com espaço duplo")
            assertFalse(text.contains('\n'), "$code com quebra de linha")
            assertTrue(text.length <= 120, "$code com mensagem longa (${text.length})")
            assertTrue(text.first().isUpperCase(), "$code não começa em maiúscula")
            assertTrue(text.none { it == '`' || it == '"' || it == '<' || it == '>' }, "$code com marcação")
        }
    }

    @Test
    fun everyCatalogCodeUsedByTheTaskIsAContractCode() {
        val contract = ContractErrorCodes.all()
        val used =
            listOf(
                "VALIDATION_FAILED",
                "UNAUTHENTICATED",
                "ACCESS_DENIED",
                "PASSWORD_CHANGE_REQUIRED",
                "APPROVAL_REQUIRED",
                "PRECONDITION_FAILED",
                "INVALID_CREDENTIALS",
                "ACCOUNT_LOCKED",
                "ACCOUNT_DISABLED",
                "REFRESH_TOKEN_INVALID",
                "REFRESH_TOKEN_REUSED",
                "PASSWORD_UNCHANGED",
                // APPROVAL_TOKEN_* ficam de fora: o extrator os perde (relatório do testador, caso duvidoso 1).
                "APPROVAL_CREDENTIALS_INVALID",
                "APPROVAL_NOT_APPLICABLE",
                "APPROVER_IS_REQUESTER",
                "APPROVER_LOCKED",
                "APPROVER_NOT_ALLOWED",
                "PERMISSION_DENIED",
                "RESOURCE_OUT_OF_SCOPE",
            )
        assertEquals(emptyList(), used - contract, "códigos mapeados que o contrato não cita")
    }

    @Test
    fun extractedCodesHaveCodeShapeAndExcludeEnumValues() {
        val shape = Regex("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*")
        val codes = ContractErrorCodes.all()
        for (code in codes) assertTrue(shape.matches(code), "formato estranho: '$code'")
        for (enumValue in listOf("ROOM_MOVED", "CHECKED_OUT", "RESERVATION_CANCELLED", "UNASSIGNED")) {
            assertFalse(codes.contains(enumValue), "valor de enum $enumValue virou código")
        }
        assertTrue(codes.contains("UNAUTHENTICATED") && codes.contains("CONFLICT"))
    }

    @Test
    fun extractionIsDeterministic() {
        assertEquals(ContractErrorCodes.all(), ContractErrorCodes.all())
    }

    @Test
    fun problemWithJsonDetailAndUnknownCodeNeverShowsRawJson() {
        for (detail in listOf("{\"error\":\"boom\"}", "  {\"error\":\"boom\"}", "[{\"a\":1}]", "{}")) {
            val text = ErrorCatalog.userMessage(500, "SOMETHING_NEW", detail)
            assertFalse(text.contains("{") || text.contains("["), "JSON cru ao usuário: $text")
        }
    }

    @Test
    fun problemWithoutCodeAndJsonDetailNeverShowsRawJson() {
        val text = ApiError.Problem(502, null, "{\"upstream\":\"timeout\"}", emptyList()).userMessage
        assertFalse(text.contains("{") || text.contains("}"), "JSON cru ao usuário: $text")
    }

    @Test
    fun problemDetailThatIsJustTheCodeNeverShowsTheRawCode() {
        val text = ErrorCatalog.userMessage(409, "SOMETHING_NEW", "SOMETHING_NEW")
        assertFalse(text.contains("SOMETHING_NEW"), "code cru ao usuário: $text")
    }

    @Test
    fun everyKindIsHandledExhaustively() {
        for (error in everyKind) {
            val name =
                when (error) {
                    is ApiError.Problem -> "Problem"
                    is ApiError.ApprovalRequired -> "ApprovalRequired"
                    is ApiError.PreconditionFailed -> "PreconditionFailed"
                    ApiError.PasswordChangeRequired -> "PasswordChangeRequired"
                    is ApiError.SessionEnded -> "SessionEnded"
                    is ApiError.Network -> "Network"
                    is ApiError.IncompatibleResponse -> "IncompatibleResponse"
                    is ApiError.Unexpected -> "Unexpected"
                }
            assertTrue(name.isNotEmpty())
        }
    }
}
