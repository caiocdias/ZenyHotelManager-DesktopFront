package com.zeny.desktop.design.approval

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private const val MAX_PIN = 8
private val PIN = Regex("[0-9]{4,$MAX_PIN}")
private val PIN_TYPING = Regex("[0-9]{0,$MAX_PIN}")
private const val MAX_REASON = 500

/**
 * O que o diálogo de aprovação mostra. `detail` é texto pronto para o usuário (nunca os campos crus do
 * problema). O PIN fica fora do `toString`.
 */
data class ApprovalDialogModel(
    val detail: String,
    val login: String,
    val pin: String,
    val reason: String,
    val message: String?,
    val busy: Boolean,
) {
    /** Login informado, PIN de 4 a 8 dígitos e motivo de 1 a 500 caracteres, sem aprovação em voo. */
    val canConfirm: Boolean
        get() = !busy && login.isNotBlank() && PIN.matches(pin) && reason.trim().length in 1..MAX_REASON

    override fun toString(): String =
        "ApprovalDialogModel(detail=$detail, login=$login, pin=***, reason=$reason, message=$message, busy=$busy)"
}

/** Corta em `MAX_REASON` unidades UTF-16 sem deixar a metade alta de um par substituto no fim. */
private fun cutReason(text: String): String {
    val cut = text.take(MAX_REASON)
    return if (cut.length == MAX_REASON && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/**
 * Login e PIN (mascarado) de quem aprova e o motivo; "Aprovar" só com os campos válidos. Na digitação, o PIN
 * aceita só dígitos de 0 a 9 até 8 (outra edição é ignorada) e o motivo é cortado em 500 unidades UTF-16, sem
 * partir um par substituto.
 */
@Composable
@Suppress("LongParameterList") // assinatura fixada pelo plano
fun ApprovalDialog(
    model: ApprovalDialogModel,
    onLogin: (String) -> Unit,
    onPin: (String) -> Unit,
    onReason: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!model.busy) onDismiss() },
        title = { Text("Aprovação de superior") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.detail)
                model.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("approval-message"))
                }
                OutlinedTextField(
                    value = model.login,
                    onValueChange = onLogin,
                    label = { Text("Login de quem aprova") },
                    singleLine = true,
                    enabled = !model.busy,
                    modifier = Modifier.fillMaxWidth().testTag("approval-login"),
                )
                OutlinedTextField(
                    value = model.pin,
                    onValueChange = { if (PIN_TYPING.matches(it)) onPin(it) },
                    label = { Text("PIN") },
                    supportingText = { Text("De 4 a 8 dígitos.") },
                    singleLine = true,
                    enabled = !model.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().testTag("approval-pin"),
                )
                OutlinedTextField(
                    value = model.reason,
                    onValueChange = { onReason(cutReason(it)) },
                    label = { Text("Motivo") },
                    supportingText = { Text("Obrigatório, até $MAX_REASON caracteres.") },
                    enabled = !model.busy,
                    modifier = Modifier.fillMaxWidth().testTag("approval-reason"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = model.canConfirm,
                modifier = Modifier.testTag("approval-confirm"),
            ) { Text("Aprovar") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !model.busy) { Text("Cancelar") } },
    )
}
