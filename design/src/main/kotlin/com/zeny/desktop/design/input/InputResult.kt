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
    var touched by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val localError = if (touched) (parse(value) as? InputResult.Invalid)?.message else null
    val shown = error ?: localError
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = shown != null,
        supportingText = shown?.let { { Text(it) } },
        modifier =
            modifier.onFocusChanged {
                if (hadFocus && !it.isFocused) touched = true
                if (it.isFocused) hadFocus = true
            },
    )
}
