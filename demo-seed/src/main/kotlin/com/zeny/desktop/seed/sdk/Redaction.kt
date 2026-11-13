package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError

private const val MASK = "***"

/**
 * O servidor pode ecoar o que recebeu (senha, PIN, CPF, nome) em `detail` e nas mensagens de campo; o `userMessage`
 * do `Problem` nasce do `detail`. Aqui o erro é refeito, com o mesmo status, código e campos, sem os valores
 * enviados (trocados por `***`).
 */
@Suppress("SwallowedException") // o erro original traz o eco do valor enviado: é descartado de propósito
internal suspend fun <T> redactingValues(
    sent: List<String>,
    block: suspend () -> T,
): T {
    val values = sent.filter(String::isNotEmpty)

    fun String.clean() = values.fold(this) { text, value -> text.replace(value, MASK) }
    try {
        return block()
    } catch (e: ApiError.Problem) {
        throw ApiError.Problem(
            e.status,
            e.code?.clean(),
            e.detail?.clean(),
            e.fieldErrors.map { FieldError(it.field.clean(), it.code.clean(), it.message.clean()) },
        )
    } catch (e: ApiError.ApprovalRequired) {
        throw ApiError.ApprovalRequired(
            e.action.clean(),
            e.permission.clean(),
            e.resourceRef.clean(),
            e.limit,
            e.detail?.clean(),
        )
    }
}
