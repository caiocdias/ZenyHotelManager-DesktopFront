package com.zeny.desktop.app.folio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val REOPEN_DIALOG_WIDTH = 560.dp

/**
 * "Reabrir conta": o motivo, obrigatório, e "Reabrir". O servidor pode pedir aprovação de superior, que abre o diálogo
 * da casca. "Voltar" sai (com o envio em curso, nada).
 */
@Composable
internal fun ReopenDialog(
    form: ReopenState,
    onLeave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!form.busy) onLeave() },
        modifier = Modifier.widthIn(max = REOPEN_DIALOG_WIDTH).testTag("reopen-dialog"),
        title = { Text("Reabrir conta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A conta volta a ficar aberta. O servidor pode pedir a aprovação de um superior.")
                OutlinedTextField(
                    value = form.reason,
                    onValueChange = { form.reason = it },
                    label = { Text("Motivo") },
                    supportingText = { Text("${form.reason.length}/$CORRECTION_REASON_MAX") },
                    enabled = !form.locked,
                    modifier = Modifier.fillMaxWidth().testTag("reopen-reason"),
                )
                if (form.busy) Text("Enviando…")
                form.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("reopen-message"))
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onLeave,
                    enabled = !form.busy,
                    modifier = Modifier.testTag("reopen-back"),
                ) { Text("Voltar") }
                Button(
                    onClick = form::confirm,
                    enabled = form.canConfirm,
                    modifier = Modifier.testTag("reopen-submit"),
                ) { Text("Reabrir") }
            }
        },
    )
}
