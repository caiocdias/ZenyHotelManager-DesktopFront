package com.zeny.desktop.app.folio

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.zeny.desktop.design.input.MoneyField

/** Os diálogos abertos das correções da conta, um por vez. */
@Composable
internal fun CorrectionDialogs(state: FolioCorrectionsState) {
    state.adjustment?.let { AdjustmentDialog(it, onLeave = state::finishAdjustment) }
    state.removal?.let { RemovalDialog(it, onLeave = state::finishRemoval) }
}

/**
 * "Ajustar" o lançamento do [form]: o restante que o servidor devolveu, o valor (sem sugestão) e o motivo, obrigatório.
 * O ajuste entra como um lançamento novo e o original fica; o servidor pode pedir aprovação de superior, que abre o
 * diálogo da casca.
 */
@Composable
private fun AdjustmentDialog(
    form: AdjustmentState,
    onLeave: () -> Unit,
) {
    val row = form.row
    WriteDialog(form, WriteDialogTexts("adjustment", "Ajustar lançamento", "Ajustar"), onLeave) {
        Text("${row.date} · ${row.description} · ${row.amount}")
        form.remaining?.let { Text(it, modifier = Modifier.testTag("adjustment-remaining")) }
        Text("O ajuste entra como um lançamento novo; o original continua na conta.")
        MoneyField(
            value = form.amount,
            onValueChange = { form.amount = it },
            label = "Valor",
            modifier = Modifier.fillMaxWidth().testTag("adjustment-amount"),
            enabled = !form.locked,
        )
        OutlinedTextField(
            value = form.reason,
            onValueChange = { form.reason = it },
            label = { Text("Motivo") },
            supportingText = { Text("${form.reason.length}/$CORRECTION_REASON_MAX") },
            enabled = !form.locked,
            modifier = Modifier.fillMaxWidth().testTag("adjustment-reason"),
        )
    }
}

/** "Remover a taxa" do [form]: a taxa e o motivo que o servidor registra; confirma sem campo. */
@Composable
private fun RemovalDialog(
    form: ServiceChargeRemovalState,
    onLeave: () -> Unit,
) {
    val row = form.row
    WriteDialog(form, WriteDialogTexts("removal", "Remover a taxa de serviço", "Remover"), onLeave) {
        Text("${row.date} · ${row.description} · ${row.amount}")
        Text(form.text, modifier = Modifier.testTag("removal-text"))
    }
}
