package com.zeny.desktop.app.reception

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.design.feedback.StatusChip

private val DIALOG_WIDTH = 560.dp
private val DIALOG_MAX_BODY = 480.dp

/**
 * Diálogo do check-in: UH (com sugestões, se a permissão deixa), early check-in, hóspede principal e a confirmação.
 * Fica aberto até "Fechar", mesmo que o detalhe da reserva mude de situação por baixo. Nenhum id nem código cru.
 */
@Composable
fun CheckInDialog(
    state: CheckInState,
    onClose: () -> Unit,
) {
    LaunchedEffect(state) { state.start() }
    val done = state.done
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("check-in-dialog"),
        title = { Text("Check-in") },
        text = {
            Column(
                Modifier.heightIn(max = DIALOG_MAX_BODY).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (done != null) Done(done) else Form(state)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClose,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("check-in-close"),
                ) { Text("Fechar") }
                if (done == null) {
                    Button(
                        onClick = state::confirm,
                        enabled = state.canConfirm,
                        modifier = Modifier.testTag("check-in-confirm"),
                    ) { Text("Fazer check-in") }
                }
            }
        },
    )
}

@Composable
private fun Done(done: CheckInDone) {
    Text("Check-in feito na UH ${done.roomNumber}.", modifier = Modifier.testTag("check-in-done"))
    done.alerts.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Form(state: CheckInState) {
    RoomSection(state.allocation)
    EarlySection(state)
    GuestSection(state)
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("check-in-error"))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun RoomSection(allocation: RoomAllocationState) {
    SectionTitle("UH")
    Text(allocation.currentRoomNumber?.let { "UH atual: $it" } ?: "Sem UH")
    if (!allocation.canManage) return
    allocation.options.forEachIndexed { index, option ->
        Row(
            Modifier.fillMaxWidth().testTag("room-option-$index"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(option.number)
                option.floor?.let { Text("Andar $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                StatusChip(option.readiness)
                if (option.outOfService) Text("Fora de serviço", color = MaterialTheme.colorScheme.error)
                if (option.matchesFloorPreference) Text("Andar preferido")
            }
            OutlinedButton(
                onClick = { allocation.assign(option.roomId) },
                enabled = !allocation.busy && !option.current,
                modifier = Modifier.testTag("assign-$index"),
            ) { Text("Alocar") }
        }
    }
    allocation.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("allocation-error"))
    }
    val hasRoom = allocation.currentRoomId != null
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = allocation::unassign,
            enabled = hasRoom && !allocation.busy,
            modifier = Modifier.testTag("unassign"),
        ) { Text("Soltar UH") }
        LabeledCheckbox("Não mover", allocation.doNotMove, hasRoom && !allocation.busy, "do-not-move") {
            allocation.toggleDoNotMove()
        }
    }
}

@Composable
private fun EarlySection(state: CheckInState) {
    SectionTitle("Early check-in")
    val text = state.earlyText
    if (text == null) {
        Text("Sem early check-in agora.")
    } else {
        Text(text)
        LabeledCheckbox("Cobrar early check-in", state.chargeEarly, !state.busy, "charge-early") {
            state.chargeEarly = it
        }
    }
}

@Composable
private fun GuestSection(state: CheckInState) {
    SectionTitle("Hóspede principal")
    var picking by remember { mutableStateOf(false) }
    val primary = state.primary
    // O hóspede escolhido (ou cadastrado) fecha o painel: o diálogo mostra o novo principal.
    LaunchedEffect(primary) { picking = false }
    if (primary == null) {
        Text("Escolha o hóspede")
    } else {
        Text(primary.summary.name)
        primary.summary.document?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    OutlinedButton(
        onClick = { picking = !picking },
        enabled = !state.busy,
        modifier = Modifier.testTag("swap-guest"),
    ) { Text("Trocar hóspede") }
    if (picking) GuestPickerPanel(state.picker)
}

@Composable
private fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
        Text(label)
    }
}
