package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val DIALOG_WIDTH = 560.dp

/**
 * Diálogo que confirma a pré-reserva com a garantia (`CARD` ou `DEPOSIT`). Abre lendo a reserva ([state] `start()`);
 * fica aberto até "Fechar" (desabilitado enquanto escreve). Depois da confirmação, só "Ver confirmação" (se
 * [onConfirmation] existe) e "Fechar". "Fechar" também encerra o [state]. Nenhum id nem código cru.
 */
@Composable
internal fun ReservationConfirmDialog(
    state: ReservationConfirmState,
    onConfirmation: (() -> Unit)?,
    onClose: () -> Unit,
) {
    LaunchedEffect(state) { state.start() }
    val done = state.done
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("confirm-dialog"),
        title = { Text(state.code?.let { "Confirmar reserva $it" } ?: "Confirmar reserva") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done != null) {
                    Text(done, modifier = Modifier.testTag("confirm-done"))
                } else {
                    ConfirmForm(state)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        state.close()
                        onClose()
                    },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("confirm-close"),
                ) { Text("Fechar") }
                if (done != null) {
                    if (onConfirmation != null) {
                        OutlinedButton(
                            onClick = onConfirmation,
                            modifier = Modifier.testTag("confirm-confirmation"),
                        ) { Text("Ver confirmação") }
                    }
                } else {
                    Button(
                        onClick = state::confirm,
                        enabled = state.canConfirm,
                        modifier = Modifier.testTag("confirm-submit"),
                    ) { Text("Confirmar reserva") }
                }
            }
        },
    )
}

@Composable
private fun ConfirmForm(state: ReservationConfirmState) {
    if (state.loading) Text("Buscando a reserva...")
    state.terms?.let { Text(it, modifier = Modifier.testTag("confirm-terms")) }
    state.deposit?.let { Text(it, modifier = Modifier.testTag("confirm-deposit")) }
    Text("Garantia", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BookingText.GUARANTEES.forEach { value ->
            FilterChip(
                selected = state.guarantee == value,
                onClick = { state.guarantee = value },
                label = { Text(BookingText.guarantee(value)) },
                enabled = state.canConfirm,
                modifier = Modifier.testTag("confirm-guarantee-$value"),
            )
        }
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("confirm-error"))
    }
}
