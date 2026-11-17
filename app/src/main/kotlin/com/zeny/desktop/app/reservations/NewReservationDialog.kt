package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.zeny.desktop.app.reception.GuestPickerPanel
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.design.feedback.StatusChip
import com.zeny.desktop.design.input.MoneyField

private val DIALOG_WIDTH = 560.dp
private val DIALOG_MAX_BODY = 480.dp

/**
 * Diálogo da nova reserva a partir de uma oferta da cotação: o resumo da oferta, o titular, a origem, o segmento, os
 * pedidos especiais, a diária negociada e, depois de [NewReservationState.done], o resultado.
 *
 * Quem abre é dono do [state] e o fecha em [onClose]. O Esc só fecha fora de uma escrita em curso e sem resultado
 * desconhecido; com resultado desconhecido o botão vira "Tentar de novo" (a mesma intenção). [onConfirmation] (nulo =
 * sem botão) recebe o id e o código da reserva criada para abrir a confirmação em PDF. Nenhum id nem código cru.
 */
@Composable
internal fun NewReservationDialog(
    state: NewReservationState,
    onConfirmation: ((reservationId: String, code: String) -> Unit)?,
    onClose: () -> Unit,
) {
    val done = state.done
    AlertDialog(
        onDismissRequest = { if (!state.busy && !state.unconfirmed && !state.picker.creating) onClose() },
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("new-reservation-dialog"),
        title = { Text("Nova reserva") },
        text = {
            Column(
                Modifier.heightIn(max = DIALOG_MAX_BODY).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Summary(state)
                if (done != null) Done(done) else Form(state)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClose,
                    enabled = !state.busy && !state.picker.creating,
                    modifier = Modifier.testTag("new-reservation-close"),
                ) { Text(if (done != null) "Fechar" else "Cancelar") }
                if (done != null && onConfirmation != null) {
                    Button(
                        onClick = { onConfirmation(done.reservationId, done.code) },
                        modifier = Modifier.testTag("new-reservation-confirmation"),
                    ) { Text("Ver confirmação") }
                }
                if (done == null) {
                    Button(
                        onClick = state::confirm,
                        enabled = state.canConfirm,
                        modifier = Modifier.testTag("new-reservation-submit"),
                    ) { Text(if (state.unconfirmed) "Tentar de novo" else "Criar reserva") }
                }
            }
        },
    )
}

@Composable
private fun Summary(state: NewReservationState) {
    val offer = state.offer
    val stay = state.stay
    Column(
        Modifier.testTag("new-reservation-summary").semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "${offer.roomTypeName} · ${offer.ratePlanName} · ${AvailabilityText.mealPlan(offer.mealPlan)}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(ReceptionText.period(stay.arrival.toApi(), stay.departure.toApi(), dayUse = false))
        Text("Total ${ReceptionText.money(offer.total)}", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Done(done: NewReservationDone) {
    Column(
        Modifier.testTag("new-reservation-done").semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Reserva ${done.code} criada.", style = MaterialTheme.typography.titleSmall)
        StatusChip(ReceptionText.status(done.status))
        Text(done.period)
        Text("Total ${done.total}", style = MaterialTheme.typography.titleMedium)
        done.provisionalUntil?.let { Text("Prazo para confirmar: $it") }
        done.guarantee?.let { Text(it) }
        Text(done.cancellation)
    }
    done.alerts.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Form(state: NewReservationState) {
    val enabled = !state.busy && !state.unconfirmed
    Holder(state, enabled)
    Chips(ORIGIN_CHIPS, state.origin, enabled) { state.origin = it }
    Chips(SEGMENT_CHIPS, state.segment, enabled) { state.segment = it }
    OutlinedTextField(
        value = state.specialRequests,
        onValueChange = { state.specialRequests = it },
        label = { Text("Pedidos especiais") },
        enabled = enabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth().testTag("new-reservation-requests"),
    )
    MoneyField(
        value = state.nightlyRateText,
        onValueChange = { state.nightlyRateText = it },
        label = "Diária negociada (opcional)",
        modifier = Modifier.fillMaxWidth().testTag("new-reservation-rate"),
    )
    state.rateError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("new-reservation-rate-error"))
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("new-reservation-error"))
    }
    if (state.fieldErrors.isNotEmpty()) {
        Column(Modifier.testTag("new-reservation-field-errors")) {
            state.fieldErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Holder(
    state: NewReservationState,
    enabled: Boolean,
) {
    SectionTitle("Titular")
    val holder = state.holder
    var picking by remember { mutableStateOf(holder == null) }
    // A escolha do recepcionista fecha o painel.
    LaunchedEffect(holder) { if (holder != null) picking = false }
    Column(Modifier.testTag("new-reservation-holder").semantics(mergeDescendants = true) {}) {
        if (holder == null) {
            Text("Escolha o titular")
        } else {
            Text(holder.summary.name)
            holder.summary.document?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (holder != null) {
        OutlinedButton(
            onClick = { picking = !picking },
            enabled = enabled,
            modifier = Modifier.testTag("new-reservation-swap-holder"),
        ) { Text("Trocar titular") }
    }
    // Fora do ar enquanto escreve ou depois do resultado desconhecido: um cadastro ainda criaria hóspede no servidor.
    if (picking && enabled) GuestPickerPanel(state.picker)
}

/** Um grupo de chips de escolha única: o título, os valores, o rótulo de cada um e o prefixo da tag. */
private class ChipGroup(
    val title: String,
    val values: List<String>,
    val label: (String) -> String,
    val tagPrefix: String,
)

private val ORIGIN_CHIPS = ChipGroup("Origem", BookingText.ORIGINS, BookingText::origin, "new-reservation-origin-")
private val SEGMENT_CHIPS =
    ChipGroup("Segmento", BookingText.SEGMENTS, BookingText::segment, "new-reservation-segment-")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(
    group: ChipGroup,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    SectionTitle(group.title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        group.values.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(group.label(value)) },
                enabled = enabled,
                modifier = Modifier.testTag("${group.tagPrefix}$value"),
            )
        }
    }
}
