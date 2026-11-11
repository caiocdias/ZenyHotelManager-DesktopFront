package com.zeny.desktop.design.form

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError

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

    internal fun matches(path: String): Boolean = path == name || path in serverPaths
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

    /** Substitui os erros do servidor anteriores pelos de [error]. */
    fun applyServerErrors(error: ApiError) {
        clearServerErrors()
        if (error is ApiError.Problem && error.fieldErrors.isNotEmpty()) {
            val unmatched = mutableListOf<FieldError>()
            for (fieldError in error.fieldErrors) {
                val target = fields.firstOrNull { it.matches(fieldError.field) }
                if (target == null) unmatched += fieldError else target.serverError = fieldError.message
            }
            formError = unmatched.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.message }
        } else {
            formError = error.userMessage
        }
    }

    fun clearServerErrors() {
        fields.forEach { it.serverError = null }
        formError = null
    }
}
