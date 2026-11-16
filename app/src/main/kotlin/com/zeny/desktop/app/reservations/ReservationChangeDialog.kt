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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.design.input.DateField
import com.zeny.desktop.sdk.model.QuoteOfferResponse

private val DIALOG_WIDTH = 600.dp
private val DIALOG_MAX_BODY = 520.dp
private val childAgeField = Regex("""childAges\[\d+]""")

// Os campos que a tela mostra junto do próprio campo; o resto dos `fieldErrors` sai em linhas, só com a mensagem.
private val placedFields = setOf("arrival", "departure", "adults", "specialRequests", "segment")

private fun isPlaced(name: String) = name in placedFields || childAgeField.matches(name)

/**
 * Diálogo da alteração de reserva: chegada, saída, adultos, idades, segmento e pedidos especiais, com "Ver opções"
 * para trocar o tipo e o plano pela cotação da estadia digitada. Abre lendo a reserva ([state] `start()`); fica
 * aberto até "Fechar" (desabilitado enquanto escreve), que também encerra o [state]. Depois da alteração só resta o
 * resumo e "Fechar". Hóspede em casa: chegada, adultos e idades ficam travados e não há "Ver opções". Os erros do
 * servidor aparecem no campo deles; nenhum id nem código cru.
 */
@Composable
internal fun ReservationChangeDialog(
    state: ReservationChangeState,
    onClose: () -> Unit,
) {
    LaunchedEffect(state) { state.start() }
    val done = state.done
    // `canConfirm` lê `loaded`, que não é estado observável: sem ler `loading` antes, o botão nunca recompõe.
    val canSubmit = !state.loading && state.canConfirm
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("change-dialog"),
        title = { Text(state.code?.let { "Alterar reserva $it" } ?: "Alterar reserva") },
        text = {
            Column(
                Modifier.heightIn(max = DIALOG_MAX_BODY).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (done != null) {
                    Text(done, modifier = Modifier.testTag("change-done"))
                } else {
                    ChangeForm(state)
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
                    modifier = Modifier.testTag("change-close"),
                ) { Text("Fechar") }
                if (done == null) {
                    Button(
                        onClick = state::confirm,
                        enabled = canSubmit,
                        modifier = Modifier.testTag("change-submit"),
                    ) { Text("Salvar alteração") }
                }
            }
        },
    )
}

@Composable
private fun ChangeForm(state: ReservationChangeState) {
    if (state.loading) Text("Buscando a reserva...")
    state.notice?.let {
        Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("change-notice"))
    }
    if (state.code != null) {
        if (state.inHouse) Text("Hóspede em casa: só a saída, o segmento e os pedidos podem mudar.")
        StayFields(state)
        if (state.canQuote && !state.inHouse) Options(state)
        SegmentChips(state)
        RequestsField(state)
    }
    Messages(state)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StayFields(state: ReservationChangeState) {
    val errors = state.fieldErrors
    val ageErrors = errors.filterKeys(childAgeField::matches).values.toList()
    val editable = !state.loading && !state.busy
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.Top,
    ) {
        if (state.inHouse) {
            // O `DateField` não desabilita: na hospedada a chegada é só leitura.
            OutlinedTextField(
                value = state.arrivalText,
                onValueChange = {},
                label = { Text("Chegada") },
                singleLine = true,
                enabled = false,
                modifier = Modifier.testTag("change-arrival"),
            )
        } else {
            DateField(
                state.arrivalText,
                { state.arrivalText = it },
                "Chegada",
                error = errors["arrival"],
                modifier = Modifier.testTag("change-arrival"),
            )
        }
        DateField(
            state.departureText,
            { state.departureText = it },
            "Saída",
            error = errors["departure"],
            modifier = Modifier.testTag("change-departure"),
        )
        OutlinedTextField(
            value = state.adultsText,
            onValueChange = { state.adultsText = it },
            label = { Text("Adultos") },
            singleLine = true,
            enabled = editable && !state.inHouse,
            isError = errors["adults"] != null,
            supportingText = errors["adults"]?.let { { Text(it) } },
            modifier = Modifier.testTag("change-adults"),
        )
        OutlinedTextField(
            value = state.childAgesText,
            onValueChange = { state.childAgesText = it },
            label = { Text("Idades das crianças") },
            singleLine = true,
            enabled = editable && !state.inHouse,
            isError = ageErrors.isNotEmpty(),
            supportingText = ageErrors.takeIf { it.isNotEmpty() }?.let { { Text(it.joinToString(" ")) } },
            modifier = Modifier.testTag("change-children"),
        )
    }
}

@Composable
private fun Options(state: ReservationChangeState) {
    OutlinedButton(
        onClick = state::loadOptions,
        enabled = !state.loading && !state.busy && !state.loadingOptions,
        modifier = Modifier.testTag("change-options-load"),
    ) { Text("Ver opções") }
    if (state.loadingOptions) Text("Buscando opções...")
    if (state.options.isEmpty()) return
    Column(Modifier.testTag("change-options"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Cotação de hoje para a estadia digitada. O total final é o que o servidor devolver ao confirmar.",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.options.forEachIndexed { index, offer ->
            OptionRow(
                text = offerText(offer),
                selected = state.option == offer,
                tag = "change-option-$index",
            ) { state.choose(offer) }
        }
        OptionRow(
            text = "Manter o tipo e o plano atuais",
            selected = state.option == null,
            tag = "change-option-keep",
        ) { state.choose(null) }
    }
}

// Os valores são os decimais do servidor, formatados; nada é somado aqui.
private fun offerText(offer: QuoteOfferResponse): String =
    "${offer.roomTypeName} · ${offer.ratePlanName} · ${AvailabilityText.mealPlan(offer.mealPlan)} · " +
        "Total ${ReceptionText.money(offer.total)}"

@Composable
private fun OptionRow(
    text: String,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentChips(state: ReservationChangeState) {
    Text("Segmento", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BookingText.SEGMENTS.forEach { value ->
            FilterChip(
                selected = state.segment == value,
                onClick = { state.segment = value },
                label = { Text(BookingText.segment(value)) },
                enabled = !state.loading && !state.busy,
                modifier = Modifier.testTag("change-segment-$value"),
            )
        }
    }
    state.fieldErrors["segment"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun RequestsField(state: ReservationChangeState) {
    val error = state.fieldErrors["specialRequests"]
    OutlinedTextField(
        value = state.specialRequests,
        onValueChange = { state.specialRequests = it },
        label = { Text("Pedidos especiais") },
        minLines = 2,
        enabled = !state.loading && !state.busy,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth().testTag("change-requests"),
    )
}

@Composable
private fun Messages(state: ReservationChangeState) {
    state.validation?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("change-validation"))
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("change-error"))
    }
    state.fieldErrors
        .filterKeys { !isPlaced(it) }
        .values
        .forEach { Text(it, color = MaterialTheme.colorScheme.error) }
}
