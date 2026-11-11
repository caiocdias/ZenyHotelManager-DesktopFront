package com.zeny.desktop.design.input

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Resultado de interpretar o texto digitado em um campo. */
sealed interface InputResult<out T> {
    data class Valid<T>(
        val value: T,
    ) : InputResult<T>

    data class Invalid(
        val message: String,
    ) : InputResult<Nothing>

    data object Empty : InputResult<Nothing>
}

/** Rótulo, erro do formulário e interpretação do texto de um [ParsedTextField]. */
internal data class FieldSpec(
    val label: String,
    val error: String?,
    val parse: (String) -> InputResult<*>,
    val mask: ((String) -> String)? = null,
    val significant: (Char) -> Boolean = { true },
)

/**
 * Campo de texto que mostra [error] (o do formulário) ou, depois que o campo perdeu o foco,
 * a mensagem de [InputResult.Invalid] do texto atual.
 */
@Composable
internal fun ParsedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    spec: FieldSpec,
    modifier: Modifier,
) {
    val (label, error, parse) = spec
    val mask = spec.mask
    val significant = spec.significant
    var touched by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val localError = if (touched) (parse(value) as? InputResult.Invalid)?.message else null
    val shown = error ?: localError
    val focusModifier =
        modifier.onFocusChanged {
            if (hadFocus && !it.isFocused) touched = true
            if (it.isFocused) hadFocus = true
        }
    if (mask == null) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = shown != null,
            supportingText = shown?.let { { Text(it) } },
            modifier = focusModifier,
        )
    } else {
        // Com máscara a seleção precisa acompanhar o texto formatado, senão o próximo dígito entra fora de ordem.
        val shownText = mask(value)
        var field by remember { mutableStateOf(TextFieldValue(shownText, TextRange(shownText.length))) }
        if (field.text != shownText) field = TextFieldValue(shownText, TextRange(shownText.length))
        OutlinedTextField(
            value = field,
            onValueChange = { typed ->
                val masked = mask(typed.text)
                val cursor =
                    if (masked == typed.text) {
                        typed.selection.end
                    } else {
                        cursorAfter(masked, typed.text.take(typed.selection.end).count(significant), significant)
                    }
                field = TextFieldValue(masked, TextRange(cursor))
                onValueChange(masked)
            },
            label = { Text(label) },
            singleLine = true,
            isError = shown != null,
            supportingText = shown?.let { { Text(it) } },
            modifier = focusModifier,
        )
    }
}

/** Posição logo depois do [count]-ésimo caractere significativo de [masked]. */
private fun cursorAfter(
    masked: String,
    count: Int,
    significant: (Char) -> Boolean,
): Int {
    var seen = 0
    var position = 0
    while (position < masked.length && seen < count) {
        if (significant(masked[position])) seen++
        position++
    }
    return position
}
