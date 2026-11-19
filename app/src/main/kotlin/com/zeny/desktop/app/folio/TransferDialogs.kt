package com.zeny.desktop.app.folio

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.design.input.MoneyField

/** Os diálogos abertos de transferir e dividir, um por vez. */
@Composable
internal fun TransferDialogs(state: FolioCorrectionsState) {
    state.transfer?.let { TransferDialog(it, onLeave = state::finishTransfer) }
    state.split?.let { SplitDialog(it, onLeave = state::finishSplit) }
}

/**
 * "Transferir": os lançamentos transferíveis da conta com caixas (a taxa de serviço segue o item), a conta de destino
 * entre as abertas e o motivo opcional.
 */
@Composable
private fun TransferDialog(
    form: TransferState,
    onLeave: () -> Unit,
) {
    WriteDialog(form, WriteDialogTexts("transfer", "Transferir lançamentos", "Transferir"), onLeave) {
        Text("Lançamentos (a taxa de serviço vai junto do item)", style = MaterialTheme.typography.titleSmall)
        form.candidates.forEach { row ->
            Row(
                Modifier.fillMaxWidth().testTag("transfer-posting-${row.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = row.id in form.checked,
                    onCheckedChange = { form.toggle(row.id) },
                    enabled = !form.locked,
                )
                Text("${row.date} · ${row.description} · ${row.amount}")
            }
        }
        Text("Conta de destino", style = MaterialTheme.typography.titleSmall)
        Targets(
            form.targets,
            "transfer",
            selected = form.target?.id,
            enabled = !form.locked,
            onPick = form::chooseTarget,
        )
        ReasonField("transfer", form.reason, enabled = !form.locked) { form.reason = it }
    }
}

/**
 * "Dividir": por percentual ou por valor, a própria conta como primeira parte, as contas escolhidas entre as abertas,
 * cada parte com o digitado, o restante do servidor e o motivo opcional. O front não soma as partes.
 */
@Composable
private fun SplitDialog(
    form: SplitState,
    onLeave: () -> Unit,
) {
    val row = form.row
    WriteDialog(form, WriteDialogTexts("split", "Dividir lançamento", "Dividir"), onLeave) {
        Text("${row.date} · ${row.description} · ${row.amount}")
        form.remaining?.let { Text(it, modifier = Modifier.testTag("split-remaining")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(SplitBy.PERCENT to "Por percentual", SplitBy.AMOUNT to "Por valor").forEach { (by, label) ->
                FilterChip(
                    selected = form.by == by,
                    onClick = { form.by = by },
                    label = { Text(label) },
                    enabled = !form.locked,
                    modifier = Modifier.testTag("split-by-${by.name.lowercase()}"),
                )
            }
        }
        form.parts.forEachIndexed { index, part -> SplitPartLine(form, index, part) }
        Text("Acrescentar conta", style = MaterialTheme.typography.titleSmall)
        Targets(form.targets, "split", selected = null, enabled = !form.locked, onPick = form::addPart)
        ReasonField("split", form.reason, enabled = !form.locked) { form.reason = it }
    }
}

@Composable
private fun SplitPartLine(
    form: SplitState,
    index: Int,
    part: SplitPartInput,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val modifier = Modifier.weight(1f).testTag("split-part-$index")
        if (form.by == SplitBy.AMOUNT) {
            MoneyField(
                value = part.value,
                onValueChange = { form.setPart(index, it) },
                label = part.label,
                modifier = modifier,
                enabled = !form.locked,
            )
        } else {
            OutlinedTextField(
                value = part.value,
                onValueChange = { form.setPart(index, it) },
                label = { Text("${part.label} (%)") },
                singleLine = true,
                enabled = !form.locked,
                modifier = modifier,
            )
        }
        if (index > 0) {
            TextButton(
                onClick = { form.removePart(index) },
                enabled = !form.locked,
                modifier = Modifier.testTag("split-remove-$index"),
            ) { Text("Tirar") }
        }
    }
}

/** As contas abertas que podem receber, com número, dono e saldo do servidor, e "Carregar mais". */
@Composable
private fun Targets(
    targets: FolioTargets,
    tag: String,
    selected: String?,
    enabled: Boolean,
    onPick: (FolioRow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        targets.rows.forEach { folio ->
            val border =
                if (folio.id ==
                    selected
                ) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                }
            Text(
                "${folio.number} · ${folio.owner} · ${folio.balance}",
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, border, MaterialTheme.shapes.small)
                        .clickable(enabled = enabled) { onPick(folio) }
                        .padding(8.dp)
                        .testTag("$tag-target-${folio.id}"),
            )
        }
        if (!targets.loading && targets.error == null && targets.rows.isEmpty()) Text(FolioText.NO_OPEN_FOLIOS)
        targets.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (targets.loading) Text("Carregando…")
        if (targets.hasMore || targets.error != null) {
            OutlinedButton(
                onClick = targets::loadMore,
                enabled = !targets.loading,
                modifier = Modifier.testTag("$tag-targets-more"),
            ) { Text(if (targets.error != null) "Tentar de novo" else "Carregar mais") }
        }
    }
}

@Composable
private fun ReasonField(
    tag: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Motivo (opcional)") },
        supportingText = { Text("${value.length}/$CORRECTION_REASON_MAX") },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("$tag-reason"),
    )
}
