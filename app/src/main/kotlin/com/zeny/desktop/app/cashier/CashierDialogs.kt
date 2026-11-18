package com.zeny.desktop.app.cashier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.zeny.desktop.design.input.MoneyField

private val DIALOG_WIDTH = 480.dp

/**
 * Sangria ou suprimento: valor e motivo. Com resultado desconhecido o botão vira "Tentar de novo" (a mesma intenção);
 * com [closeOnly][CashierWriteForm.closeOnly], as entradas travam e só resta "Fechar", que confere o turno.
 */
@Composable
internal fun MovementDialog(form: MovementForm) {
    WriteDialog(
        form = form,
        tag = "movement",
        title = "Registrar ${form.title.lowercase()}",
        submitLabel = "Registrar",
    ) {
        MoneyField(
            value = form.amount,
            onValueChange = { form.amount = it },
            label = "Valor",
            error = form.amountError,
            modifier = Modifier.fillMaxWidth().testTag("movement-amount"),
            enabled = !form.locked,
        )
        OutlinedTextField(
            value = form.reason,
            onValueChange = { form.reason = it },
            label = { Text("Motivo") },
            supportingText = { Text("${form.reason.trim().length}/200") },
            enabled = !form.locked,
            modifier = Modifier.fillMaxWidth().testTag("movement-reason"),
        )
    }
}

/**
 * O fechamento cego: o valor contado de cada forma de pagamento, sem nenhum valor do sistema. A forma deixada vazia
 * vale zero e não vai no pedido.
 */
@Composable
internal fun CloseShiftDialog(form: CloseShiftForm) {
    WriteDialog(
        form = form,
        tag = "close",
        title = "Fechar o turno",
        submitLabel = "Fechar turno",
    ) {
        Text("Informe o valor contado de cada forma de pagamento. Deixe vazia a forma que não houve.")
        form.entries.forEach { entry ->
            MoneyField(
                value = entry.text,
                onValueChange = { form.setCount(entry.method, it) },
                label = entry.label,
                modifier = Modifier.fillMaxWidth().testTag("close-count-${entry.method.name}"),
                enabled = !form.locked,
            )
        }
    }
}

@Composable
private fun WriteDialog(
    form: CashierWriteForm<*>,
    tag: String,
    title: String,
    submitLabel: String,
    fields: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (form.closesOnEscape) form.leave() },
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("$tag-dialog"),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields()
                if (form.busy) Text("Enviando…")
                form.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("$tag-message"))
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = form::leave,
                    enabled = !form.busy,
                    modifier = Modifier.testTag("$tag-back"),
                ) { Text(if (form.closeOnly) "Fechar" else "Voltar") }
                if (!form.closeOnly) {
                    Button(
                        onClick = form::confirm,
                        enabled = form.canConfirm,
                        modifier = Modifier.testTag("$tag-submit"),
                    ) { Text(if (form.unconfirmed) "Tentar de novo" else submitLabel) }
                }
            }
        },
    )
}
