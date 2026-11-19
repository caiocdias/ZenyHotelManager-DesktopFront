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

private const val REASON_MAX = 200

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
        afterMessage = { OpenShiftButton(form.offersOpenShift, form::openShiftAndClose) },
    ) { PaymentFields(form) }
}

/**
 * "Reembolsar" o recebimento do [form]: o valor (sugerido com o reembolsável do servidor), o motivo e, fora do
 * dinheiro, o NSU e a autorização da devolução. O servidor pode pedir aprovação de superior, que abre o diálogo da
 * casca; com o turno fechado e o atalho, "Abrir turno" fecha o diálogo e leva ao caixa.
 */
@Composable
internal fun RefundDialog(
    form: RefundState,
    onLeave: () -> Unit,
) {
    val row = form.payment
    WriteDialog(
        form,
        WriteDialogTexts("refund", "Reembolsar pagamento", "Reembolsar"),
        onLeave,
        afterMessage = { OpenShiftButton(form.offersOpenShift, form::openShiftAndClose) },
    ) {
        Text(listOf(row.date, row.method, row.amount).joinToString(" · "))
        form.refundable?.let { Text(it, modifier = Modifier.testTag("refund-refundable")) }
        MoneyField(
            value = form.amount,
            onValueChange = { form.amount = it },
            label = "Valor",
            error = form.errors[PaymentProblem.AMOUNT],
            modifier = Modifier.fillMaxWidth().testTag("refund-amount"),
            enabled = !form.locked,
        )
        val reasonError = form.errors[PaymentProblem.REASON]
        OutlinedTextField(
            value = form.reason,
            onValueChange = { form.reason = it },
            label = { Text("Motivo") },
            isError = reasonError != null,
            supportingText = { Text(reasonError ?: "${form.reason.length}/$REASON_MAX") },
            enabled = !form.locked,
            modifier = Modifier.fillMaxWidth().testTag("refund-reason"),
        )
        if (form.showsCodes) {
            ShortTextField(
                form.nsu,
                { form.nsu = it },
                "NSU (opcional)",
                form.errors[PaymentProblem.NSU],
                form.locked,
                "refund-nsu",
            )
            ShortTextField(
                form.authorizationCode,
                { form.authorizationCode = it },
                "Autorização (opcional)",
                form.errors[PaymentProblem.AUTHORIZATION_CODE],
                form.locked,
                "refund-authorization_code",
            )
        }
    }
}

/** "Abrir turno", logo abaixo da mensagem do turno fechado, só quando o diálogo oferece o atalho. */
@Composable
internal fun OpenShiftButton(
    offered: Boolean,
    onClick: () -> Unit,
) {
    if (offered) {
        OutlinedButton(onClick = onClick, modifier = Modifier.testTag("open-shift")) { Text("Abrir turno") }
    }
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

/** Um campo curto do recebimento, com a tag `payment-<problema>`. */
@Composable
private fun PaymentFormState.CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    problem: PaymentProblem,
) = ShortTextField(value, onValueChange, label, errors[problem], locked, "payment-${problem.name.lowercase()}")

/** Um campo curto de texto, travado com o formulário, com o texto do [error] embaixo. */
@Suppress("LongParameterList") // o valor, a edição, o rótulo, o aviso, a trava e a tag de cada campo
@Composable
private fun ShortTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    locked: Boolean,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        enabled = !locked,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
