package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val DIALOG_WIDTH = 560.dp

/**
 * Diálogo que cancela uma reserva. Abre lendo a reserva ([state] `start()`) e mostra os termos da política copiada
 * nela, sem valor calculado: a multa só aparece depois, como o servidor a decidiu. Motivo em chips, observação e a
 * opção de isentar a multa (ação sensível: a aprovação de superior, se o servidor pedir, vem do `ApprovalCoordinator`
 * por cima). Com resultado desconhecido o botão vira "Tentar de novo" (a mesma intenção);
 * se a leitura da reserva falhar, "Tentar de novo" ao lado do erro lê de novo. Depois do cancelamento, só
 * o texto e "Fechar". "Voltar"/"Fechar" (desabilitados enquanto escreve) encerram o [state] e chamam [onClose]; o Esc
 * só fecha fora de [busy][ReservationCancelState.busy] e de [unconfirmed][ReservationCancelState.unconfirmed]. Nenhum
 * id nem código cru.
 */
@Composable
internal fun ReservationCancelDialog(
    state: ReservationCancelState,
    onClose: () -> Unit,
) {
    LaunchedEffect(state) { state.start() }
    val done = state.done
    val close = {
        state.close()
        onClose()
    }
    AlertDialog(
        onDismissRequest = { if (!state.busy && !state.unconfirmed) close() },
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("cancel-dialog"),
        title = { Text(state.code?.let { "Cancelar a reserva $it" } ?: "Cancelar a reserva") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done != null) Text(done, modifier = Modifier.testTag("cancel-done")) else CancelForm(state)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = close,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("cancel-close"),
                ) { Text(if (done != null) "Fechar" else "Voltar") }
                if (done == null) {
                    Button(
                        onClick = state::confirm,
                        enabled = state.canConfirm,
                        modifier = Modifier.testTag("cancel-submit"),
                    ) { Text(if (state.unconfirmed) "Tentar de novo" else "Cancelar reserva") }
                }
            }
        },
    )
}

@Composable
private fun CancelForm(state: ReservationCancelState) {
    val editable = !state.busy && !state.unconfirmed
    if (state.loading) Text("Buscando a reserva...")
    state.terms?.let {
        Text(it, modifier = Modifier.testTag("cancel-terms"))
        Text("A multa é lançada na conta da reserva conforme o prazo da política.")
    }
    Text("Motivo", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CANCEL_REASONS.forEach { (value, label) ->
            FilterChip(
                selected = state.reason == value,
                onClick = { state.reason = value },
                label = { Text(label) },
                enabled = editable,
                modifier = Modifier.testTag("cancel-reason-$value"),
            )
        }
    }
    OutlinedTextField(
        value = state.notes,
        onValueChange = { state.notes = it },
        label = { Text("Observação") },
        enabled = editable,
        modifier = Modifier.fillMaxWidth().testTag("cancel-notes"),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.waivePenalty,
            onCheckedChange = { state.waivePenalty = it },
            enabled = editable,
            modifier = Modifier.testTag("cancel-waive"),
        )
        Text("Isentar a multa (pode pedir a aprovação de um superior)")
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cancel-error"))
        if (!state.loading && state.terms == null && !state.unconfirmed) {
            OutlinedButton(onClick = state::start, modifier = Modifier.testTag("cancel-reload")) {
                Text("Tentar de novo")
            }
        }
    }
}
