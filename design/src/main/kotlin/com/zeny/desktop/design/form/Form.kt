package com.zeny.desktop.design.form

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag

@Composable
fun FormTextField(
    field: FormField,
    modifier: Modifier = Modifier,
) {
    var hadFocus by remember { mutableStateOf(false) }
    val error = field.error
    OutlinedTextField(
        value = field.text,
        onValueChange = field::onChange,
        label = { Text(field.label) },
        isError = error != null,
        supportingText =
            error?.let { message ->
                { Text(message, modifier = Modifier.testTag("error-${field.name}")) }
            },
        modifier =
            modifier
                .testTag("field-${field.name}")
                .onFocusChanged {
                    if (hadFocus && !it.isFocused) field.onBlur()
                    hadFocus = it.isFocused
                },
    )
}

@Composable
fun FormErrorBanner(state: FormState) {
    val message = state.formError ?: return
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("form-error"),
    )
}
