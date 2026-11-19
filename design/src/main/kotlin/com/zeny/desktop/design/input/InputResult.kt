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

/** Sinal do campo sem máscara quando uma edição passaria do `maxLength` e é recusada. */
internal const val OVER_LIMIT_SIGNAL = "Texto colado com mais de 1000 caracteres. Ele não foi aceito."

/**
 * Rótulo, erro do formulário, interpretação do texto e habilitação de um [ParsedTextField]. `maxLength` (só sem
 * máscara) recusa a edição que deixaria o texto maior e mostra [OVER_LIMIT_SIGNAL] até a próxima edição aceita.
 */
internal data class FieldSpec(
    val label: String,
    val error: String?,
    val parse: (String) -> InputResult<*>,
    val mask: ((String) -> String)? = null,
    val significant: (Char) -> Boolean = { true },
    val enabled: Boolean = true,
    val maxLength: Int? = null,
) {
    init {
        require(mask == null || maxLength == null) { "maxLength não vale em campo com máscara" }
    }
}

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
    var overLimit by remember { mutableStateOf(false) }
    val localError = if (touched) (parse(value) as? InputResult.Invalid)?.message else null
    val shown = if (overLimit) OVER_LIMIT_SIGNAL else error ?: localError
    val focusModifier =
        modifier.onFocusChanged {
            if (hadFocus && !it.isFocused) touched = true
            if (it.isFocused) hadFocus = true
        }
    if (mask == null) {
        OutlinedTextField(
            value = value,
            onValueChange = limited(spec.maxLength, onValueChange) { overLimit = it },
            label = { Text(label) },
            singleLine = true,
            enabled = spec.enabled,
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
                val textChanged = typed.text != field.text
                if (masked == typed.text) {
                    // Só a seleção ou a composição mudou (ou o texto já estava formatado): preserva tudo.
                    field = typed
                    if (textChanged) onValueChange(masked)
                } else {
                    val before = typed.text.take(typed.selection.end).count(significant)
                    field = TextFieldValue(masked, TextRange(cursorAfter(masked, before, significant)))
                    onValueChange(masked)
                }
            },
            label = { Text(label) },
            singleLine = true,
            enabled = spec.enabled,
            isError = shown != null,
            supportingText = shown?.let { { Text(it) } },
            modifier = focusModifier,
        )
    }
}

/** Recusa a edição maior que [maxLength] (avisando por [signal]); a aceita avisa que o sinal saiu. */
private fun limited(
    maxLength: Int?,
    accept: (String) -> Unit,
    signal: (Boolean) -> Unit,
): (String) -> Unit =
    { typed ->
        val over = maxLength != null && typed.length > maxLength
        signal(over)
        if (!over) accept(typed)
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
