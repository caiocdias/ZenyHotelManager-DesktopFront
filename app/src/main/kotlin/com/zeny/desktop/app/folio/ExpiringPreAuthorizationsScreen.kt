package com.zeny.desktop.app.folio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.design.input.DateField

private val UNTIL_WIDTH = 180.dp
private val COLUMN_WIDTH = 150.dp
private const val NO_EXPIRING = "Nenhuma caução a vencer até a data."

/**
 * A aba "Cauções a vencer": o "Até" com "Buscar", a tabela (validade, situação, valor e capturável, na ordem do
 * servidor) e "Abrir conta" em cada linha, que entrega a conta ao [onOpenFolio]. Cada entrada na aba relê a primeira
 * página com o "Até" vigente. A linha não mostra a conta (o contrato só traz o identificador), o cartão nem a
 * autorização.
 */
@Composable
fun ExpiringPreAuthorizationsScreen(
    state: ExpiringPreAuthorizationsState,
    onOpenFolio: (String) -> Unit,
) {
    LaunchedEffect(state) { state.enter() }
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .testTag("expiring-screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            DateField(
                value = state.untilText,
                onValueChange = { state.untilText = it },
                label = "Até",
                error = state.untilError,
                modifier = Modifier.width(UNTIL_WIDTH).testTag("expiring-until"),
            )
            OutlinedButton(onClick = state::search, modifier = Modifier.testTag("expiring-search")) { Text("Buscar") }
        }
        state.rows.forEachIndexed { index, row -> ExpiringRowItem(row, index, onOpenFolio) }
        if (state.loaded && !state.loading && state.rows.isEmpty()) Text(NO_EXPIRING)
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("expiring-error"))
        }
        if (state.loading) Text("Carregando…")
        if (state.hasMore || state.error != null) {
            OutlinedButton(
                onClick = state::loadMore,
                enabled = !state.loading,
                modifier = Modifier.testTag("expiring-more"),
            ) { Text(if (state.error != null) "Tentar de novo" else "Carregar mais") }
        }
    }
}

@Composable
private fun ExpiringRowItem(
    row: ExpiringRow,
    index: Int,
    onOpenFolio: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag("expiring-row-$index"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.expires, Modifier.width(COLUMN_WIDTH))
        Text(row.status, Modifier.width(COLUMN_WIDTH))
        Text(row.amount, Modifier.width(COLUMN_WIDTH))
        Text(row.capturable, Modifier.width(COLUMN_WIDTH))
        OutlinedButton(
            onClick = { onOpenFolio(row.folioId) },
            modifier = Modifier.testTag("expiring-open-$index"),
        ) { Text("Abrir conta") }
    }
}
