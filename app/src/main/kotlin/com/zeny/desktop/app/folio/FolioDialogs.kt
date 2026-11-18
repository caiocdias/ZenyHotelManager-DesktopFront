package com.zeny.desktop.app.folio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.design.input.MoneyField

private val DIALOG_WIDTH = 560.dp

/** O que muda entre os dois diálogos de escrita da conta: o prefixo das tags, o título e o botão de envio. */
private class WriteDialogTexts(
    val tag: String,
    val title: String,
    val submitLabel: String,
)

/**
 * "Lançar": o código (os oferecidos, "<code> — <name>"), a quantidade, o valor unitário e a descrição opcional. Sem
 * permissão para listar os códigos, só o texto. Com resultado desconhecido o botão vira "Tentar de novo" (a mesma
 * intenção); com [closeOnly][PostingFormState.closeOnly], as entradas travam e só resta "Fechar", que confere a conta.
 */
@Composable
internal fun PostingDialog(
    form: PostingFormState,
    onLeave: () -> Unit,
) {
    WriteDialog(form, WriteDialogTexts("posting", "Lançar na conta", "Lançar"), onLeave) {
        form.codesError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("posting-codes-error"))
            if (form.codesRetryable) {
                OutlinedButton(onClick = form::start, modifier = Modifier.testTag("posting-codes-retry")) {
                    Text("Tentar de novo")
                }
            }
        }
        if (form.codesLoading) Text("Buscando os códigos de lançamento…")
        if (form.codes.isNotEmpty()) PostingFields(form)
    }
}

/** O código, a quantidade, o valor unitário e a descrição, travados enquanto o envio não tem resultado. */
@Composable
private fun PostingFields(form: PostingFormState) {
    Text("Código de lançamento", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        form.codes.forEachIndexed { index, code ->
            FilterChip(
                selected = form.codeId == code.id,
                onClick = { form.codeId = code.id },
                label = { Text(code.label) },
                enabled = !form.locked,
                modifier = Modifier.testTag("posting-code-$index"),
            )
        }
    }
    OutlinedTextField(
        value = form.quantity,
        onValueChange = { form.quantity = it },
        label = { Text("Quantidade") },
        singleLine = true,
        enabled = !form.locked,
        modifier = Modifier.fillMaxWidth().testTag("posting-quantity"),
    )
    MoneyField(
        value = form.unitPrice,
        onValueChange = { form.unitPrice = it },
        label = "Valor unitário",
        error = form.unitPriceError,
        modifier = Modifier.fillMaxWidth().testTag("posting-price"),
        enabled = !form.locked,
    )
    OutlinedTextField(
        value = form.description,
        onValueChange = { form.description = it },
        label = { Text("Descrição (opcional)") },
        supportingText = { Text("${form.description.length}/200") },
        enabled = !form.locked,
        modifier = Modifier.fillMaxWidth().testTag("posting-description"),
    )
}

/**
 * "Estornar" o lançamento [row]: o motivo, obrigatório. O estorno vira um lançamento novo e o original fica; o servidor
 * pode pedir aprovação de superior, que abre o diálogo da casca.
 */
@Composable
internal fun ReversalDialog(
    form: ReversalState,
    row: PostingRow,
    onLeave: () -> Unit,
) {
    WriteDialog(form, WriteDialogTexts("reversal", "Estornar lançamento", "Estornar"), onLeave) {
        Text("${row.date} · ${row.description} · ${row.amount}")
        Text("O estorno entra como um lançamento novo; o original continua na conta.")
        OutlinedTextField(
            value = form.reason,
            onValueChange = { form.reason = it },
            label = { Text("Motivo") },
            supportingText = { Text("${form.reason.length}/500") },
            enabled = !form.locked,
            modifier = Modifier.fillMaxWidth().testTag("reversal-reason"),
        )
    }
}

@Composable
private fun WriteDialog(
    form: FolioWriteForm,
    spec: WriteDialogTexts,
    onLeave: () -> Unit,
    fields: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (form.closesOnEscape) onLeave() },
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("${spec.tag}-dialog"),
        title = { Text(spec.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields()
                if (form.busy) Text("Enviando…")
                form.message?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("${spec.tag}-message"),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onLeave,
                    enabled = !form.busy,
                    modifier = Modifier.testTag("${spec.tag}-back"),
                ) { Text(if (form.closeOnly) "Fechar" else "Voltar") }
                if (!form.closeOnly) {
                    Button(
                        onClick = form::confirm,
                        enabled = form.canConfirm,
                        modifier = Modifier.testTag("${spec.tag}-submit"),
                    ) { Text(if (form.unconfirmed) "Tentar de novo" else spec.submitLabel) }
                }
            }
        },
    )
}
