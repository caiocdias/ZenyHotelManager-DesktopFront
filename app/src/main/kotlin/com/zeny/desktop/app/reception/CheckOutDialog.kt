package com.zeny.desktop.app.reception

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.app.folio.PaymentDialog

private val DIALOG_WIDTH = 720.dp
private val DIALOG_MAX_BODY = 560.dp

/**
 * O assistente de check-out: o que falta (saldos e cauções capturáveis), o late oferecido com a caixa, o saldo devido
 * por conta com "Receber" e o total, e "Concluir check-out". Um diálogo por vez: com o recebimento aberto, só ele
 * aparece. [onOpenFolio] é o lugar do "Abrir conta" em cada conta (nulo, sem o botão). Nenhum id nem código cru.
 */
@Composable
fun CheckOutDialog(
    state: CheckOutState,
    onOpenFolio: (() -> Unit)? = null,
) {
    LaunchedEffect(state) { state.reload() }
    val payment = state.payment
    if (payment != null) {
        PaymentDialog(payment, state::finishPayment)
        return
    }
    AlertDialog(
        onDismissRequest = { if (state.closesOnEscape) state.close() },
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("check-out-dialog"),
        title = { Text("Check-out") },
        text = {
            Column(
                Modifier
                    .heightIn(max = DIALOG_MAX_BODY)
                    .verticalScroll(rememberScrollState())
                    .testTag("check-out-body"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PendingSection(state, onOpenFolio)
                LateSection(state)
                DueSection(state, onOpenFolio)
                Outcome(state)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = state::close,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("check-out-close"),
                ) { Text("Fechar") }
                if (!state.closeOnly) {
                    Button(
                        onClick = state::conclude,
                        enabled = state.canConclude,
                        modifier = Modifier.testTag("check-out-conclude"),
                    ) { Text(if (state.unconfirmed) "Tentar de novo" else "Concluir check-out") }
                }
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun PendingSection(
    state: CheckOutState,
    onOpenFolio: (() -> Unit)?,
) {
    SectionTitle("Pendências")
    val unreadable = state.unreadable
    val error = state.error
    when {
        unreadable != null -> {
            Text(unreadable, modifier = Modifier.testTag("check-out-unreadable"))
        }

        error != null -> {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("check-out-error"))
            OutlinedButton(onClick = state::reload, modifier = Modifier.testTag("check-out-retry-read")) {
                Text("Tentar de novo")
            }
        }

        !state.loaded -> {
            Text("Conferindo as contas e as cauções…")
        }

        state.pending.isEmpty() -> {
            Text(CheckOutText.NOTHING_PENDING)
        }

        else -> {
            state.pending.forEachIndexed { index, item ->
                FolioLine("check-out-pending-$index", item.number, item.text, onOpenFolio)
            }
        }
    }
    state.blocked?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("check-out-blocked"))
    }
}

@Composable
private fun LateSection(state: CheckOutState) {
    val offer = state.lateOffer ?: return
    SectionTitle("Late check-out")
    Text(offer, modifier = Modifier.testTag("check-out-late-offer"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.chargeLate,
            onCheckedChange = { state.chargeLate = it },
            enabled = !state.lateLocked,
            modifier = Modifier.testTag("check-out-charge-late"),
        )
        Text(CheckOutText.CHARGE_LATE)
    }
}

/** O devido por conta como o servidor mandou e, embaixo, a linha do late (com a caixa ligada) acima do total. */
@Composable
private fun DueSection(
    state: CheckOutState,
    onOpenFolio: (() -> Unit)?,
) {
    val lines = state.dueLines
    if (lines.isNotEmpty()) {
        SectionTitle("Saldo devido")
        lines.forEachIndexed { index, line ->
            FolioLine("check-out-due-$index", line.number, line.text, onOpenFolio) {
                line.receive?.let { label ->
                    Button(
                        onClick = { state.receiveDue(line.folioId) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("check-out-receive-$index"),
                    ) { Text(label) }
                }
            }
        }
    }
    state.lateLine?.let { Text(it, modifier = Modifier.testTag("check-out-late-line")) }
    state.dueTotal?.let {
        Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("check-out-total"))
    }
}

@Composable
private fun FolioLine(
    tag: String,
    number: String,
    text: String,
    onOpenFolio: (() -> Unit)?,
    action: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Conta $number: $text", modifier = Modifier.weight(1f))
        action()
        if (onOpenFolio != null) {
            OutlinedButton(onClick = onOpenFolio, modifier = Modifier.testTag("$tag-open")) { Text("Abrir conta") }
        }
    }
}

@Composable
private fun Outcome(state: CheckOutState) {
    state.notice?.let { Text(it, modifier = Modifier.testTag("check-out-notice")) }
    if (state.busy) Text("Enviando…")
    state.message?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("check-out-message"))
    }
}
