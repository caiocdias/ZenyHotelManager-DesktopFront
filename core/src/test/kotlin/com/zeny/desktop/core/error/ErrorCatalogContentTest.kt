package com.zeny.desktop.core.error

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Fixa o conteúdo (não só a forma) das mensagens do catálogo, conforme o sentido do contrato. */
class ErrorCatalogContentTest {
    private fun msg(code: String): String = assertNotNull(ErrorCatalog.message(code), "sem mensagem para $code")

    @Test
    fun sensitiveCodesKeepTheContractMeaning() {
        val expected =
            mapOf(
                "ROOM_NOT_READY" to "A UH ainda não está pronta.",
                "UNDO_CHECK_IN_EXPIRED" to "O check-in só pode ser desfeito no mesmo dia em que foi feito.",
                "INVALID_CREDENTIALS" to "Login ou senha incorretos.",
                "ACCOUNT_LOCKED" to "Conta bloqueada por tentativas seguidas. Aguarde e tente de novo.",
                "ACCOUNT_DISABLED" to "Esta conta está desativada. Fale com um administrador.",
                "APPROVAL_REQUIRED" to "Esta ação precisa da aprovação de um superior.",
                "PRECONDITION_FAILED" to "Alguém alterou este registro. Recarregue e tente de novo.",
                "PRECONDITION_REQUIRED" to "Recarregue o registro antes de alterá-lo.",
                "IDEMPOTENCY_KEY_IN_USE" to "Este pedido já está sendo processado. Aguarde um instante.",
                "IDEMPOTENCY_KEY_REUSED" to "Este pedido já foi usado para outra operação. Tente de novo.",
                "REFRESH_TOKEN_REUSED" to "Sua sessão foi encerrada por segurança. Entre novamente.",
            )
        for ((code, text) in expected) assertEquals(text, msg(code), code)
    }

    @Test
    fun lockedAndDisabledAccountsAreNotPresentedAsWrongPassword() {
        assertFalse(msg("ACCOUNT_LOCKED").contains("incorret"), "bloqueio não é senha errada")
        assertTrue(msg("ACCOUNT_LOCKED").contains("bloqueada"))
        assertTrue(msg("ACCOUNT_DISABLED").contains("desativada"))
        assertTrue(msg("INVALID_CREDENTIALS").contains("incorretos"))
    }

    @Test
    fun codesOfDifferentMeaningHaveDifferentMessages() {
        val pairs =
            listOf(
                "INVALID_CREDENTIALS" to "ACCOUNT_LOCKED",
                "ACCOUNT_LOCKED" to "ACCOUNT_DISABLED",
                "IDEMPOTENCY_KEY_IN_USE" to "IDEMPOTENCY_KEY_REUSED",
                "PRECONDITION_FAILED" to "PRECONDITION_REQUIRED",
                "APPROVAL_REQUIRED" to "APPROVAL_NOT_APPLICABLE",
                "APPROVAL_TOKEN_EXPIRED" to "APPROVAL_TOKEN_USED",
                "APPROVAL_TOKEN_USED" to "APPROVAL_TOKEN_MISMATCH",
                "REFRESH_TOKEN_INVALID" to "REFRESH_TOKEN_REUSED",
                "UNAUTHENTICATED" to "REFRESH_TOKEN_REUSED",
                "ACCESS_DENIED" to "RESOURCE_OUT_OF_SCOPE",
            )
        for ((a, b) in pairs) assertNotEquals(msg(a), msg(b), "$a e $b não podem ter a mesma mensagem")
    }

    @Test
    fun onlyCodesOfTheSameMeaningShareAMessage() {
        val allowedShared =
            setOf(
                setOf("BOOKING_NOT_FOUND", "RESERVATION_NOT_FOUND"),
                setOf("IDEMPOTENCY_KEY_INVALID", "IDEMPOTENCY_KEY_REQUIRED"),
                setOf("ACCESS_DENIED", "PERMISSION_DENIED"),
            )
        val groups =
            ContractErrorCodes
                .all()
                .groupBy { msg(it) }
                .values
                .filter { it.size > 1 }
                .map { it.toSet() }
        val unexpected = groups.filter { it !in allowedShared }
        assertTrue(unexpected.isEmpty(), "códigos distintos com a mesma mensagem: $unexpected")
    }

    @Test
    fun messagesCarryNoInternalJargon() {
        val camel = Regex("[a-z][A-Z]")
        val words =
            Regex(
                "\\b(token|cursor|etag|header|payload|json|http|idempot\\w*|api|sdk|endpoint|null|uuid|id)\\b",
                RegexOption.IGNORE_CASE,
            )
        for (code in ContractErrorCodes.all()) {
            val m = msg(code)
            assertFalse(camel.containsMatchIn(m), "$code tem nome de campo em camelCase: $m")
            assertFalse(m.contains('`'), "$code tem crase: $m")
            assertFalse(words.containsMatchIn(m), "$code tem jargão técnico: $m")
        }
    }

    @Test
    fun messagesAreWellFormedSentences() {
        for (code in ContractErrorCodes.all()) {
            val m = msg(code)
            assertEquals(m.trim(), m, "$code tem espaço nas pontas")
            assertTrue(m.first().isUpperCase(), "$code não começa com maiúscula: $m")
            assertFalse(m.contains("  "), "$code tem espaço duplo")
            assertTrue(m.length in MIN_LENGTH..MAX_LENGTH, "$code com tamanho estranho (${m.length})")
        }
    }

    @Test
    fun catalogSourceHasNoDuplicateKeys() {
        val candidates =
            listOf(
                "src/main/kotlin/com/zeny/desktop/core/error/ErrorCatalog.kt",
                "core/src/main/kotlin/com/zeny/desktop/core/error/ErrorCatalog.kt",
            )
        val source = assertNotNull(candidates.map(::File).firstOrNull { it.isFile }, "fonte do catálogo não encontrada")
        val entry = Regex("^\\s*\"([A-Z0-9_]+)\" to ", RegexOption.MULTILINE)
        val keys =
            entry
                .findAll(source.readText())
                .map { it.groupValues[1] }
                .toList()
        assertTrue(keys.size >= ContractErrorCodes.all().size, "leitura da fonte achou só ${keys.size} chaves")
        val duplicated =
            keys
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        assertTrue(duplicated.isEmpty(), "chaves repetidas no mapa (a última vence em silêncio): $duplicated")
    }

    private companion object {
        const val MIN_LENGTH = 10
        const val MAX_LENGTH = 160
    }
}
