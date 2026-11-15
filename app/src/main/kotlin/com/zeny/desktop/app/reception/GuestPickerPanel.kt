package com.zeny.desktop.app.reception

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.design.form.FormErrorBanner
import com.zeny.desktop.design.form.FormTextField

private val COUNTRY_TYPES = setOf("PASSPORT", "FOREIGN_ID")
private const val FIELD_NAME = "fullName"
private const val FIELD_TYPE = "documentType"
private const val FIELD_NUMBER = "documentNumber"
private const val FIELD_COUNTRY = "issuingCountry"
private const val FIELD_NATIONALITY = "nationality"
private const val FIELD_BIRTH = "birthDate"

/**
 * Painel de busca (por nome e por documento) e cadastro mínimo de hóspede, sobre o [GuestPickerState].
 * Dado pessoal de risco alto: só o nome e o documento mascarado do hóspede achado aparecem; nenhum id.
 */
@Composable
fun GuestPickerPanel(state: GuestPickerState) {
    var byDocument by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.canSearch) {
            Search(state, byDocument, onMode = { byDocument = it })
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("guest-error"))
            }
            Results(state)
        }
        if (state.canCreate) CreateForm(state)
    }
}

@Composable
private fun Search(
    state: GuestPickerState,
    byDocument: Boolean,
    onMode: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(byDocument.not(), { onMode(false) }, { Text("Por nome") }, Modifier.testTag("tab-guest-name"))
        FilterChip(byDocument, { onMode(true) }, { Text("Por documento") }, Modifier.testTag("tab-guest-document"))
    }
    if (byDocument) {
        Text("Documento")
        TypeChips(state.documentType, "doc-type-") { state.documentType = it }
        OutlinedTextField(
            value = state.documentNumber,
            onValueChange = { state.documentNumber = it },
            label = { Text("Número") },
            singleLine = true,
            modifier = Modifier.testTag("guest-document-number"),
        )
        if (state.documentType in COUNTRY_TYPES) {
            OutlinedTextField(
                value = state.issuingCountry,
                onValueChange = { state.issuingCountry = it },
                label = { Text("País emissor") },
                singleLine = true,
                modifier = Modifier.testTag("guest-issuing-country"),
            )
        }
    } else {
        OutlinedTextField(
            value = state.query,
            onValueChange = { state.query = it },
            label = { Text("Nome") },
            singleLine = true,
            modifier = Modifier.testTag("guest-query"),
        )
    }
    Button(
        onClick = { if (byDocument) state.searchByDocument() else state.searchByName() },
        enabled = !state.searching,
        modifier = Modifier.testTag("guest-search"),
    ) { Text("Buscar") }
}

@Composable
private fun TypeChips(
    selected: String,
    tagPrefix: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GUEST_DOCUMENT_TYPES.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(ReceptionText.document(type, "").trim()) },
                modifier = Modifier.testTag("$tagPrefix$type"),
            )
        }
    }
}

@Composable
private fun Results(state: GuestPickerState) {
    state.results.forEachIndexed { index, pick ->
        Row(
            Modifier.fillMaxWidth().testTag("guest-result-$index"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(pick.summary.name)
                pick.summary.document?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButton(onClick = { state.pick(pick) }, modifier = Modifier.testTag("guest-pick-$index")) {
                Text("Escolher")
            }
        }
    }
}

@Composable
private fun CreateForm(state: GuestPickerState) {
    val form = state.form
    val fields = form.fields.associateBy { it.name }
    val type = fields.getValue(FIELD_TYPE)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FormTextField(fields.getValue(FIELD_NAME))
        Text("Documento")
        TypeChips(type.text, "create-doc-type-") { type.onChange(it) }
        type.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("error-$FIELD_TYPE"),
            )
        }
        FormTextField(fields.getValue(FIELD_NUMBER))
        if (type.text in COUNTRY_TYPES) FormTextField(fields.getValue(FIELD_COUNTRY))
        FormTextField(fields.getValue(FIELD_NATIONALITY))
        FormTextField(fields.getValue(FIELD_BIRTH))
        FormErrorBanner(form)
        state.createError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("create-error"))
        }
        Button(onClick = state::create, enabled = !state.creating, modifier = Modifier.testTag("guest-create")) {
            Text("Cadastrar hóspede")
        }
    }
}
