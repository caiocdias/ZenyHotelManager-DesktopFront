package com.zeny.desktop.app.traces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.design.input.DateField

private val DIALOG_WIDTH = 560.dp
private val DIALOG_MAX_BODY = 480.dp
private const val MAX_TEXT = 500
private const val PRIVACY_HINT = "Não escreva dados pessoais do hóspede no recado."

/**
 * Formulário do recado (criação e edição). Inicia o [state] ao abrir e fecha sozinho quando ele salva. Nenhum id nem
 * código cru: o vínculo é "Reserva R-…" ou a UH escolhida pelo número.
 */
@Composable
fun TraceFormDialog(
    state: TraceFormState,
    onClose: () -> Unit,
) {
    LaunchedEffect(state) { state.start() }
    LaunchedEffect(state.saved) { if (state.saved) onClose() }
    val cancel = {
        state.close()
        onClose()
    }
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("trace-form"),
        title = { Text(if (state.target is TraceTarget.Existing) "Editar recado" else "Novo recado") },
        text = {
            Column(
                Modifier.heightIn(max = DIALOG_MAX_BODY).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { Body(state) }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = cancel, modifier = Modifier.testTag("trace-cancel")) { Text("Cancelar") }
                Button(
                    onClick = state::save,
                    enabled = state.canSave,
                    modifier = Modifier.testTag("trace-save"),
                ) { Text("Salvar") }
            }
        },
    )
}

@Composable
private fun Body(state: TraceFormState) {
    Link(state)
    DepartmentPicker(state)
    DateField(
        value = state.dueText,
        onValueChange = { state.dueText = it },
        label = "Vencimento",
        error = state.fieldErrors["dueOn"],
        modifier = Modifier.fillMaxWidth().testTag("trace-due"),
    )
    TextInput(state)
    Text(PRIVACY_HINT, style = MaterialTheme.typography.bodySmall)
    state.assigneeName?.let { Text("Responsável: $it", Modifier.testTag("trace-assignee")) }
    state.notice?.let { Text(it, Modifier.testTag("trace-notice")) }
    state.error?.let { Text(it, Modifier.testTag("trace-error"), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Link(state: TraceFormState) {
    when (val target = state.target) {
        is TraceTarget.ForReservation -> Text("Reserva ${target.code}")
        is TraceTarget.ForRoom -> RoomPicker(state)
        is TraceTarget.Existing -> Unit
    }
    state.fieldErrors["reservationId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun RoomPicker(state: TraceFormState) {
    Text("UH", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.rooms.forEachIndexed { index, room ->
            FilterChip(
                selected = state.roomId == room.id,
                onClick = { state.roomId = room.id },
                label = { Text(room.number) },
                modifier = Modifier.testTag("trace-room-$index"),
            )
        }
    }
    state.fieldErrors["roomId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun DepartmentPicker(state: TraceFormState) {
    Text("Setor", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.departmentOptions.forEachIndexed { index, department ->
            FilterChip(
                selected = state.departmentId == department.id,
                onClick = { state.departmentId = department.id },
                label = { Text(department.name) },
                modifier = Modifier.testTag("department-$index"),
            )
        }
    }
    state.fieldErrors["departmentId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun TextInput(state: TraceFormState) {
    val error = state.fieldErrors["text"]
    OutlinedTextField(
        value = state.text,
        onValueChange = { state.text = it },
        label = { Text("Recado") },
        isError = error != null,
        supportingText = { Text(error ?: "${state.text.length}/$MAX_TEXT") },
        modifier = Modifier.fillMaxWidth().testTag("trace-text"),
    )
}
