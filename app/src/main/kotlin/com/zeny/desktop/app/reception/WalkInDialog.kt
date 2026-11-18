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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val DIALOG_WIDTH = 560.dp
private val DIALOG_MAX_BODY = 480.dp
private val SEGMENT_LABELS =
    listOf("LEISURE" to "Lazer", "BUSINESS" to "Negócios", "EVENT" to "Evento", "OTHER" to "Outro")

/**
 * Diálogo do walk-in, em passos na mesma janela: estadia, ofertas, UH, hóspede principal e a conclusão.
 * Os passos seguintes só aparecem com a escolha do anterior. Fica aberto até "Fechar" (o Esc não fecha). Com a
 * falha guardada no servidor ([WalkInState.closeOnly]), só resta "Fechar". Nenhum id nem código cru.
 */
@Composable
fun WalkInDialog(
    state: WalkInState,
    onClose: () -> Unit,
) {
    LaunchedEffect(state) { state.start() }
    val done = state.done
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("walk-in-dialog"),
        title = { Text("Walk-in") },
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
                // Só as escritas travam o fechar: leituras em voo o `close()` cancela.
                OutlinedButton(
                    onClick = onClose,
                    enabled = !state.busy && !state.picker.creating,
                    modifier = Modifier.testTag("walk-in-close"),
                ) { Text("Fechar") }
                if (done == null && !state.closeOnly) {
                    Button(
                        onClick = state::confirm,
                        enabled = state.canConfirm,
                        modifier = Modifier.testTag("walk-in-confirm"),
                    ) { Text("Concluir walk-in") }
                }
            }
        },
    )
}

@Composable
private fun Done(done: WalkInDone) {
    Text(
        "Walk-in concluído: reserva ${done.reservationCode}, UH ${done.roomNumber}, ${done.period}, " +
            "total ${done.total}.",
        modifier = Modifier.testTag("walk-in-done"),
    )
    done.alerts.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Form(state: WalkInState) {
    StaySection(state)
    if (state.noOffers) Text(ActionText.noOffers(), modifier = Modifier.testTag("walk-in-no-offers"))
    if (state.offers.isNotEmpty()) OffersSection(state)
    if (state.offer != null) RoomSection(state)
    if (state.stepGuestEnabled) GuestSection(state)
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("walk-in-error"))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

private val WalkInState.inputsLocked: Boolean get() = busy || unconfirmed || closeOnly

@Composable
private fun StaySection(state: WalkInState) {
    val enabled = !state.inputsLocked
    SectionTitle("Estadia")
    Text(state.arrival?.let { "Chegada: ${ReceptionText.date(it.toApi())}" } ?: "Chegada: carregando")
    OutlinedTextField(
        value = state.departureText,
        onValueChange = { state.departureText = it },
        label = { Text("Saída (dd/mm/aaaa)") },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("walk-in-departure"),
    )
    // O texto digitado fica aqui; o estado só recebe o valor lido (inválido vira fora da faixa e o estado avisa).
    var adultsText by remember { mutableStateOf(state.adults.toString()) }
    OutlinedTextField(
        value = adultsText,
        onValueChange = {
            adultsText = it
            state.adults = it.trim().toIntOrNull() ?: 0
        },
        label = { Text("Adultos") },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("walk-in-adults"),
    )
    var agesText by remember { mutableStateOf(state.childAges.joinToString(", ")) }
    OutlinedTextField(
        value = agesText,
        onValueChange = {
            agesText = it
            state.childAges = parseAges(it)
        },
        label = { Text("Crianças (idades separadas por vírgula)") },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("walk-in-children"),
    )
    Text("Segmento")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SEGMENT_LABELS.forEach { (value, label) ->
            FilterChip(
                selected = state.segment == value,
                onClick = { state.segment = value },
                label = { Text(label) },
                enabled = enabled,
                modifier = Modifier.testTag("walk-in-segment-$value"),
            )
        }
    }
    Button(
        onClick = state::quote,
        enabled = enabled && state.canQuote && !state.quoting,
        modifier = Modifier.testTag("walk-in-quote"),
    ) { Text("Cotar") }
}

/** "5, 7" vira [5, 7]; o que não é número vira -1, fora da faixa, para o estado recusar com o texto da idade. */
private fun parseAges(text: String): List<Int> =
    text
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.toIntOrNull() ?: -1 }

@Composable
private fun OffersSection(state: WalkInState) {
    SectionTitle("Ofertas")
    state.offers.forEachIndexed { index, offer ->
        ChoiceRow(ActionText.offer(offer), "offer-choose-$index", !state.inputsLocked && offer != state.offer) {
            state.chooseOffer(offer)
        }
    }
}

@Composable
private fun RoomSection(state: WalkInState) {
    SectionTitle("UH")
    if (state.loadingRooms) Text("Buscando UHs...")
    if (state.noRooms) Text(ActionText.noRooms(), modifier = Modifier.testTag("walk-in-no-rooms"))
    state.rooms.forEachIndexed { index, room ->
        ChoiceRow(room.number, "room-choose-$index", !state.inputsLocked && room != state.room) {
            state.chooseRoom(room)
        }
    }
}

@Composable
private fun ChoiceRow(
    text: String,
    tag: String,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f))
        OutlinedButton(onClick = onChoose, enabled = enabled, modifier = Modifier.testTag(tag)) { Text("Escolher") }
    }
}

@Composable
private fun GuestSection(state: WalkInState) {
    SectionTitle("Hóspede principal")
    var picking by remember { mutableStateOf(false) }
    val primary = state.primary
    // A escolha do recepcionista fecha o painel.
    LaunchedEffect(primary) { if (primary != null) picking = false }
    if (primary == null) {
        Text("Escolha o hóspede")
    } else {
        Text(primary.summary.name)
        primary.summary.document?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    val frozen = state.inputsLocked || state.done != null
    OutlinedButton(
        onClick = { picking = !picking },
        enabled = !frozen,
        modifier = Modifier.testTag("swap-guest"),
    ) { Text(if (primary == null) "Escolher hóspede" else "Trocar hóspede") }
    // Fora do ar enquanto escreve ou depois do resultado desconhecido: um cadastro ainda criaria hóspede no servidor.
    if (picking && !frozen) GuestPickerPanel(state.picker)
}
