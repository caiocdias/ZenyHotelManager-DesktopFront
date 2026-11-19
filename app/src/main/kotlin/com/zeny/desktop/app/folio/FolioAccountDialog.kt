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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.design.input.MoneyField

private val ACCOUNT_DIALOG_WIDTH = 560.dp

/**
 * "Pagador e limite" da conta: o pagador vigente, a escolha ("Sem pagador" ou uma empresa ativa da busca, com
 * "Carregar mais"; sem `guests.companies.read`, só o texto que diz por quê), o limite pelo `MoneyField` (vazio é "Sem
 * limite") e "Salvar". As empresas aparecem só pelo nome; o CNPJ nunca chega aqui.
 */
@Composable
internal fun FolioAccountDialog(
    form: FolioAccountFormState,
    onLeave: () -> Unit,
) {
    val editable = !form.busy && !form.loading && !form.saved
    AlertDialog(
        onDismissRequest = { if (!form.busy) onLeave() },
        modifier = Modifier.widthIn(max = ACCOUNT_DIALOG_WIDTH).testTag("account-dialog"),
        title = { Text("Pagador e limite") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (form.loading) Text("Carregando…")
                Text("Pagador atual: ${currentPayer(form)}", modifier = Modifier.testTag("account-current"))
                val companies = form.companies
                if (companies == null) {
                    form.payerHint?.let { Text(it, modifier = Modifier.testTag("account-payer-hint")) }
                } else {
                    PayerChoice(form, companies, editable)
                }
                MoneyField(
                    value = form.limitText,
                    onValueChange = { form.limitText = it },
                    label = "Limite de crédito",
                    modifier = Modifier.fillMaxWidth().testTag("account-limit"),
                    enabled = editable,
                )
                Text("Vazio: ${FolioText.NO_LIMIT.lowercase()}.", style = MaterialTheme.typography.bodySmall)
                if (form.busy) Text("Enviando…")
                form.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("account-message"))
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onLeave,
                    enabled = !form.busy,
                    modifier = Modifier.testTag("account-back"),
                ) { Text("Voltar") }
                Button(
                    onClick = form::save,
                    enabled = form.canSave,
                    modifier = Modifier.testTag("account-submit"),
                ) { Text("Salvar") }
            }
        },
    )
}

/** O pagador vigente; sem a leitura da conta (em voo ou com falha), não afirma qual é. */
private fun currentPayer(form: FolioAccountFormState): String =
    when {
        form.payerKnown -> form.payerName ?: FolioText.NO_PAYER
        form.loading -> FolioText.PAYER_READING
        else -> FolioText.PAYER_UNREAD
    }

/** O pagador escolhido, "Sem pagador", a busca por nome e as empresas ativas, com "Carregar mais". */
@Composable
private fun PayerChoice(
    form: FolioAccountFormState,
    companies: CompanyList,
    editable: Boolean,
) {
    if (form.payerKnown) {
        Text(
            "Pagador escolhido: ${form.payer?.name ?: FolioText.NO_PAYER}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("account-payer"),
        )
    }
    FilterChip(
        selected = form.payer == null,
        onClick = { form.choose(null) },
        label = { Text(FolioText.NO_PAYER) },
        enabled = editable,
        modifier = Modifier.testTag("account-no-payer"),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = companies.query,
            onValueChange = { companies.query = it },
            label = { Text("Buscar empresa pelo nome") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("account-search"),
        )
        OutlinedButton(
            onClick = companies::search,
            modifier = Modifier.testTag("account-search-submit"),
        ) { Text("Buscar") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        companies.rows.forEachIndexed { index, company ->
            FilterChip(
                selected = form.payer?.id == company.id,
                onClick = { form.choose(company) },
                label = { Text(company.name) },
                enabled = editable,
                modifier = Modifier.testTag("account-company-$index"),
            )
        }
    }
    if (companies.loaded && companies.rows.isEmpty()) Text(FolioText.NO_COMPANIES)
    if (companies.loading) Text("Buscando as empresas…")
    companies.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("account-companies-error"))
    }
    if (companies.hasMore || companies.error != null) {
        OutlinedButton(
            onClick = companies::loadMore,
            enabled = !companies.loading,
            modifier = Modifier.testTag("account-companies-more"),
        ) { Text(if (companies.error != null) "Tentar de novo" else "Carregar mais") }
    }
}
