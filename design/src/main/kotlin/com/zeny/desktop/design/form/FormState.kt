package com.zeny.desktop.design.form

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError

/**
 * Um campo de formulário. O erro do servidor aparece na hora; o local só depois de `touched`.
 * `serverPaths` são nomes extras que o servidor usa para este campo, comparados por igualdade exata.
 */
class FormField(
    val name: String,
    val label: String,
    initial: String = "",
    val serverPaths: Set<String> = emptySet(),
    val validate: (String) -> String? = { null },
) {
    var text: String by mutableStateOf(initial)
        private set
    var touched: Boolean by mutableStateOf(false)
        private set
    internal var serverError: String? by mutableStateOf(null)

    val error: String?
        get() = serverError ?: if (touched) validate(text) else null

    fun onChange(text: String) {
        this.text = text
        serverError = null
    }

    fun onBlur() {
        touched = true
    }

    internal fun touch() = onBlur()
}

class FormState(
    val fields: List<FormField>,
) {
    var formError: String? by mutableStateOf(null)
        private set

    /** Marca todos os campos como tocados; `true` se nenhum tem erro local. */
    fun validate(): Boolean {
        fields.forEach { it.touch() }
        return fields.all { it.validate(it.text) == null }
    }

    /**
     * Substitui os erros do servidor anteriores pelos de [error]. Regras:
     * - só um `Problem` com `fieldErrors` é distribuído; qualquer outro erro, ou um `Problem` sem
     *   `fieldErrors`, vira `formError` com o `userMessage`;
     * - o campo é achado por igualdade exata (com maiúsculas) do `field`: o `name` tem precedência
     *   sobre o `serverPaths` de qualquer campo, e só então vale o primeiro `serverPaths` que casa;
     * - várias mensagens do mesmo campo aparecem todas, separadas por quebra de linha;
     * - o erro sem campo casado vai para `formError` (só a `message`, sem `field` nem `code`);
     * - mensagem em branco nunca é exibida: vira o `userMessage` do problema em `formError`, uma vez.
     */
    fun applyServerErrors(error: ApiError) {
        clearServerErrors()
        if (error !is ApiError.Problem || error.fieldErrors.isEmpty()) {
            formError = error.userMessage
            return
        }
        val byField = linkedMapOf<FormField, MutableList<String>>()
        val general = mutableListOf<String>()
        var blankSeen = false
        for (fieldError in error.fieldErrors) {
            if (fieldError.message.isBlank()) {
                blankSeen = true
                continue
            }
            val target = findField(fieldError.field)
            if (target ==
                null
            ) {
                general += fieldError.message
            } else {
                byField.getOrPut(target) { mutableListOf() } +=
                    fieldError.message
            }
        }
        if (blankSeen && general.isEmpty() && byField.isEmpty()) general += error.userMessage
        byField.forEach { (field, messages) -> field.serverError = messages.joinToString("\n") }
        formError = general.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun findField(path: String): FormField? =
        fields.firstOrNull { it.name == path } ?: fields.firstOrNull { path in it.serverPaths }

    fun clearServerErrors() {
        fields.forEach { it.serverError = null }
        formError = null
    }
}
