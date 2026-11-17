package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val DIALOG_WIDTH = 560.dp

/**
 * Diálogo que reativa uma reserva cancelada ou em no-show. Pergunta uma vez, com o código da reserva; a aprovação de
 * superior, se o servidor pedir, vem do `ApprovalCoordinator` por cima. Depois da reativação, só "Fechar". "Fechar"
 * (desabilitado enquanto escreve) encerra o [state] e chama [onClose]; o Esc só fecha fora de [busy] e de
 * [unconfirmed]. Nenhum id nem código cru.
 */
@Composable
internal fun ReservationReactivateDialog(
    state: ReservationReactivateState,
    onClose: () -> Unit,
) {
    val done = state.done
    val close = {
        state.close()
        onClose()
    }
    AlertDialog(
        onDismissRequest = { if (!state.busy && !state.unconfirmed) close() },
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("reactivate-dialog"),
        title = { Text("Reativar reserva") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done != null) {
                    Text(done, modifier = Modifier.testTag("reactivate-done"))
                } else {
                    Text(
                        "Reativar a reserva ${state.code}? Ela volta a Confirmada, com o preço que tinha. " +
                            "A ação pode pedir a aprovação de um superior.",
                        modifier = Modifier.testTag("reactivate-question"),
                    )
                    state.error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("reactivate-error"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = close,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("reactivate-close"),
                ) { Text("Fechar") }
                if (done == null) {
                    Button(
                        onClick = state::confirm,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("reactivate-submit"),
                    ) { Text("Reativar") }
                }
            }
        },
    )
}
