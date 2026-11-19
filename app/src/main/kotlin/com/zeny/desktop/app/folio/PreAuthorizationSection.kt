package com.zeny.desktop.app.folio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.design.input.DateField
import com.zeny.desktop.design.input.MoneyField
import com.zeny.desktop.design.input.MoneyInput

/**
 * A seção "Cauções" do painel da conta, na ordem do servidor: situação, cartão, autorizado, capturado, capturável,
 * liberado (quando há) e validade, tudo como o servidor devolve. "Registrar caução" e "Liberar" aparecem pelas regras
 * do [state]; "Nenhuma caução nesta conta." só depois da primeira leitura.
 */
@Composable
internal fun PreAuthorizationSection(state: PreAuthorizationsState) {
    Text("Cauções", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    if (state.canRegister) {
        Button(onClick = state::startRegister, modifier = Modifier.testTag("register-pre-authorization")) {
            Text("Registrar caução")
        }
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("pre-authorizations-error"))
    }
    if (state.loaded && state.error == null && state.rows.isEmpty()) Text(FolioText.NO_PRE_AUTHORIZATIONS)
    state.rows.forEachIndexed { index, row ->
        // A chave é a caução: "Liberar" segue a linha dela quando a lista relida muda de ordem.
        key(row.id) {
            PreAuthorizationLine(
                index,
                row,
                onRelease = if (state.canRelease(row)) ({ state.startRelease(row) }) else null,
            )
        }
    }
}

/** Os diálogos da seção: "Registrar caução" e a confirmação "Liberar". */
@Composable
internal fun PreAuthorizationDialogs(state: PreAuthorizationsState) {
    state.register?.let { PreAuthorizationDialog(it, onLeave = state::finishRegister) }
    state.release?.let { ReleaseDialog(it, onLeave = state::finishRelease) }
}

/** Uma linha das cauções; com [onRelease], o botão "Liberar". */
@Composable
private fun PreAuthorizationLine(
    index: Int,
    row: PreAuthorizationRow,
    onRelease: (() -> Unit)?,
) {
    FlowRow(
        Modifier.fillMaxWidth().testTag("pre-authorization-row-$index"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(listOfNotNull(row.status, row.card).joinToString(" · "))
        Text("Autorizado: ${row.amount}")
        Text("Capturado: ${row.captured}")
        row.capturable?.let { Text("Capturável: ${MoneyInput.format(it)}") }
        row.released?.let { Text("Liberado: $it") }
        Text("Validade: ${row.expiresAt}")
        if (onRelease != null) {
            TextButton(onClick = onRelease, modifier = Modifier.testTag("release-$index")) { Text("Liberar") }
        }
    }
}

/**
 * "Registrar caução": o valor, a validade ("válida até", do comprovante), a autorização e, opcionais, o NSU, a bandeira
 * e os 4 últimos dígitos, todos travados com o formulário. Nenhum campo de número de cartão nem de CVV.
 */
@Composable
private fun PreAuthorizationDialog(
    form: PreAuthorizationFormState,
    onLeave: () -> Unit,
) {
    WriteDialog(form, WriteDialogTexts("pre-authorization", "Registrar caução", "Registrar"), onLeave) {
        MoneyField(
            value = form.amount,
            onValueChange = { form.amount = it },
            label = "Valor autorizado",
            error = form.errors[PaymentProblem.AMOUNT],
            modifier = Modifier.fillMaxWidth().testTag("pre-authorization-amount"),
            enabled = !form.locked,
        )
        DateField(
            value = form.expiresOn,
            onValueChange = { form.expiresOn = it },
            label = "Válida até",
            error = form.validityError,
            modifier = Modifier.fillMaxWidth().testTag("pre-authorization-expires"),
            enabled = !form.locked,
        )
        form.CodeField(
            form.authorizationCode,
            { form.authorizationCode = it },
            "Autorização",
            PaymentProblem.AUTHORIZATION_CODE,
        )
        form.CodeField(form.nsu, { form.nsu = it }, "NSU (opcional)", PaymentProblem.NSU)
        form.CodeField(form.cardBrand, { form.cardBrand = it }, "Bandeira (opcional)", PaymentProblem.CARD_BRAND)
        form.CodeField(
            form.cardLast4,
            { form.cardLast4 = it },
            "4 últimos dígitos (opcional)",
            PaymentProblem.CARD_LAST4,
        )
    }
}

/** A confirmação "Liberar": só o que a liberação faz, com o capturável que o servidor devolveu. */
@Composable
private fun ReleaseDialog(
    form: ReleaseState,
    onLeave: () -> Unit,
) {
    WriteDialog(form, WriteDialogTexts("release", "Liberar caução", "Liberar"), onLeave) {
        Text(form.text, modifier = Modifier.testTag("release-text"))
    }
}

/** Um campo curto do registro, com a tag `pre-authorization-<problema>`, travado com o formulário. */
@Composable
private fun PreAuthorizationFormState.CodeField(
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
        modifier = Modifier.fillMaxWidth().testTag("pre-authorization-${problem.name.lowercase()}"),
    )
}
