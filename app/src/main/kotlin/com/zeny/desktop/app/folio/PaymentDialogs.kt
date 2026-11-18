package com.zeny.desktop.app.folio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.design.input.MoneyField

/**
 * "Receber": a forma, o valor, "Depósito (sinal)" e os campos que a forma mostra (o valor entregue no dinheiro; a
 * bandeira e os 4 últimos dígitos no cartão; o NSU e a autorização fora do dinheiro). Nenhum campo de número de cartão
 * nem de CVV. Com o turno fechado e o atalho, "Abrir turno" fecha o diálogo e leva ao caixa.
 */
@Composable
internal fun PaymentDialog(
    form: PaymentFormState,
    onLeave: () -> Unit,
) {
    WriteDialog(
        form,
        WriteDialogTexts("payment", "Receber pagamento", "Receber"),
        onLeave,
        afterMessage = {
            if (form.shiftRequired) {
                OutlinedButton(onClick = form::openShiftAndClose, modifier = Modifier.testTag("open-shift")) {
                    Text("Abrir turno")
                }
            }
        },
    ) { PaymentFields(form) }
}

@Composable
private fun PaymentFields(form: PaymentFormState) {
    Text("Forma de pagamento", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaymentMethod.entries.forEach { method ->
            FilterChip(
                selected = form.method == method,
                onClick = { form.method = method },
                label = { Text(FolioText.method(method)) },
                enabled = !form.locked,
                modifier = Modifier.testTag("payment-method-$method"),
            )
        }
    }
    MoneyField(
        value = form.amount,
        onValueChange = { form.amount = it },
        label = "Valor",
        error = form.errors[PaymentProblem.AMOUNT],
        modifier = Modifier.fillMaxWidth().testTag("payment-amount"),
        enabled = !form.locked,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = form.deposit,
            onCheckedChange = { form.deposit = it },
            enabled = !form.locked,
            modifier = Modifier.testTag("payment-deposit"),
        )
        Text("Depósito (sinal)")
    }
    if (form.showsTendered) {
        MoneyField(
            value = form.tendered,
            onValueChange = { form.tendered = it },
            label = "Valor entregue (opcional)",
            error = form.errors[PaymentProblem.TENDERED],
            modifier = Modifier.fillMaxWidth().testTag("payment-tendered"),
            enabled = !form.locked,
        )
    }
    if (form.showsCard) {
        form.CodeField(form.cardBrand, { form.cardBrand = it }, "Bandeira (opcional)", PaymentProblem.CARD_BRAND)
        form.CodeField(
            form.cardLast4,
            { form.cardLast4 = it },
            "4 últimos dígitos (opcional)",
            PaymentProblem.CARD_LAST4,
        )
    }
    if (form.showsCodes) {
        form.CodeField(form.nsu, { form.nsu = it }, "NSU (opcional)", PaymentProblem.NSU)
        form.CodeField(
            form.authorizationCode,
            { form.authorizationCode = it },
            "Autorização (opcional)",
            PaymentProblem.AUTHORIZATION_CODE,
        )
    }
}

/** Um campo curto de texto, travado com o formulário, com o texto do [problem] dele embaixo. */
@Composable
private fun PaymentFormState.CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    problem: PaymentProblem,
) {
    val error = errors[problem]
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        enabled = !locked,
        modifier = Modifier.fillMaxWidth().testTag("payment-${problem.name.lowercase()}"),
    )
}
