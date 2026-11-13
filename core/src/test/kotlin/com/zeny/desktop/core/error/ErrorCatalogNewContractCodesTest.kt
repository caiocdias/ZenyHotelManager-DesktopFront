package com.zeny.desktop.core.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Os 24 códigos que entraram com o contrato de 57eb5fac5b8a (FNRH, fechamento do dia e impressão). */
class ErrorCatalogNewContractCodesTest {
    private val newCodes =
        listOf(
            "CONFIRMATION_UNAVAILABLE",
            "END_OF_DAY_ALREADY_CLOSED",
            "END_OF_DAY_DATE_MISMATCH",
            "END_OF_DAY_NOT_CONFIGURED",
            "END_OF_DAY_RUN_EXISTS",
            "END_OF_DAY_RUN_IN_PROGRESS",
            "END_OF_DAY_RUN_NOT_FOUND",
            "END_OF_DAY_RUN_NOT_RESUMABLE",
            "FNRH_SUBMISSION_NOT_DISMISSABLE",
            "FNRH_SUBMISSION_NOT_FOUND",
            "FNRH_SUBMISSION_NOT_RETRYABLE",
            "INVALID_FAILURE_CODE",
            "PRINTER_INACTIVE",
            "PRINTER_NOT_FOUND",
            "PRINT_AGENT_INACTIVE",
            "PRINT_AGENT_NOT_FOUND",
            "PRINT_CONTENT_EMPTY",
            "PRINT_CONTENT_NOT_PDF",
            "PRINT_CONTENT_TOO_LARGE",
            "PRINT_JOB_NOT_ACKNOWLEDGEABLE",
            "PRINT_JOB_NOT_FOUND",
            "REGISTRATION_CARD_UNAVAILABLE",
            "UNKNOWN_PRINTER",
            "UNKNOWN_PRINT_AGENT",
        )

    private fun msg(code: String) = assertNotNull(ErrorCatalog.message(code), "sem mensagem para $code")

    @Test
    fun everyNewCodeIsInTheContract() {
        val contract = ContractErrorCodes.all().toSet()
        assertEquals(24, newCodes.size)
        assertEquals(emptyList(), newCodes.filter { it !in contract }, "código fora do contrato (erro de digitação?)")
    }

    @Test
    fun everyNewCodeHasADistinctPortugueseSentence() {
        val messages = newCodes.map { msg(it) }
        assertEquals(newCodes.size, messages.toSet().size, "mensagens repetidas entre os códigos novos")
        for ((code, text) in newCodes.zip(messages)) {
            assertTrue(text.length > 15 && text.first().isUpperCase() && text.endsWith("."), "$code: $text")
            assertTrue(
                text.any { it in "áàâãéêíóôõúç" } || text.contains(" não ") || text.contains(" de "),
                "$code: $text",
            )
        }
    }

    @Test
    fun notFoundInactiveAndUnknownKeepDifferentMeanings() {
        assertTrue(msg("PRINTER_NOT_FOUND").contains("não foi encontrad"))
        assertTrue(msg("PRINTER_INACTIVE").contains("desativad"))
        assertTrue(msg("UNKNOWN_PRINTER").contains("informad"))
        assertTrue(msg("PRINT_AGENT_NOT_FOUND").contains("não foi encontrad"))
        assertTrue(msg("PRINT_AGENT_INACTIVE").contains("desativad"))
        assertTrue(msg("UNKNOWN_PRINT_AGENT").contains("informad"))
    }

    @Test
    fun fnrhAndPrintMessagesNameTheirSubject() {
        for (code in newCodes.filter { it.startsWith("FNRH_") }) assertTrue(msg(code).contains("FNRH"), code)
        for (code in newCodes.filter { it.startsWith("END_OF_DAY_") }) {
            assertTrue(msg(code).contains("echamento") || msg(code).contains("data"), code)
        }
        assertTrue(msg("PRINT_CONTENT_NOT_PDF").contains("PDF"))
    }

    @Test
    fun userMessageUsesTheCatalogForNewCodesInsteadOfServerDetail() {
        assertEquals(
            msg("PRINTER_INACTIVE"),
            ErrorCatalog.userMessage(409, "PRINTER_INACTIVE", "Detalhe cru do servidor."),
        )
    }
}
