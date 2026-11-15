package com.zeny.desktop.design.table

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Quantas linhas antes do fim da lista já contam como "perto do fim". */
private const val NEAR_END_ROWS = 5

/** Largura reservada ao cabeçalho da coluna de ações da linha, quando há `rowActions`. */
private val ACTIONS_WIDTH = 190.dp

/**
 * Tabela de uma listagem por cursor: cabeçalho que ordena, filtro, linhas e carga da próxima página.
 * Sem filtro, a próxima página vem sozinha perto do fim da rolagem; com filtro, pelo botão "Carregar mais".
 * `rowActions`, se dado, desenha uma coluna de ações (botões) no fim de cada linha, com o índice da linha visível;
 * `actionsWidth` é a largura dela.
 */
@Composable
@Suppress("LongParameterList") // `rowActions` é opcional e fica no fim, sem quebrar quem já chama
fun <T> DataTable(
    state: TableState<T>,
    columns: List<Column<T>>,
    onRowClick: (T) -> Unit = {},
    modifier: Modifier = Modifier,
    emptyMessage: String = "Nenhum registro encontrado.",
    rowActions: (@Composable (index: Int, row: T) -> Unit)? = null,
    actionsWidth: Dp = ACTIONS_WIDTH,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val filtering = state.filter.isNotBlank()
    val needsFirstPage = state.rows.isEmpty() && state.hasMore && state.error == null && !state.loading

    // A chave do efeito não pode ser `needsFirstPage`: `loadMore` o zera e o Compose cancelaria a própria carga.
    val firstPageNeeded by rememberUpdatedState(needsFirstPage)
    LaunchedEffect(state) {
        snapshotFlow { firstPageNeeded }
            .filter { it }
            .collect { state.loadMore() }
    }
    LaunchedEffect(state, listState, filtering) {
        if (filtering) return@LaunchedEffect
        // Uma emissão por tamanho da lista: a mesma página não dispara duas vezes.
        snapshotFlow {
            val last =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: -1
            val count = listState.layoutInfo.totalItemsCount
            // Com erro, só "Tentar de novo" pede a página de novo; o rodapé de erro não conta como fim da lista.
            (state.error == null && count > 0 && last >= count - 1 - NEAR_END_ROWS) to state.rows.size
        }.distinctUntilChanged()
            .filter { (nearEnd, _) -> nearEnd }
            .collect { state.loadMore() }
    }

    val visibleRows = state.visibleRows(columns)
    Column(modifier) {
        OutlinedTextField(
            value = state.filter,
            onValueChange = { state.filter = it },
            label = { Text("Filtrar") },
            modifier = Modifier.fillMaxWidth().testTag("table-filter"),
        )
        HeaderRow(state, columns, rowActions != null, actionsWidth)
        LazyColumn(Modifier.weight(1f).testTag("table-list"), listState) {
            itemsIndexed(visibleRows, key = { _, row -> state.key(row) }) { index, row ->
                DataRow(
                    row,
                    columns,
                    onRowClick,
                    Modifier.testTag("row-${state.key(row)}"),
                    rowActions?.let { actions -> { actions(index, row) } },
                    actionsWidth,
                )
            }
            item {
                Footer(
                    state,
                    filtering,
                    visibleRows.isEmpty(),
                    emptyMessage,
                ) { scope.launch { loadOrRetry(state) } }
            }
        }
    }
}

private suspend fun <T> loadOrRetry(state: TableState<T>) {
    if (state.rows.isEmpty()) state.reload() else state.loadMore()
}

@Composable
private fun <T> HeaderRow(
    state: TableState<T>,
    columns: List<Column<T>>,
    withActions: Boolean,
    actionsWidth: Dp,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        columns.forEach { column ->
            val current = state.sort?.takeIf { it.first == column.id }
            val arrow =
                when (current?.second) {
                    true -> " ▲"
                    false -> " ▼"
                    null -> ""
                }
            val sortable = column.comparator != null
            Text(
                text = column.header + arrow,
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier
                        .weight(column.weight)
                        .testTag("header-${column.id}")
                        .then(
                            if (sortable) {
                                Modifier.clickable { state.sort = column.id to (current?.second != true) }
                            } else {
                                Modifier
                            },
                        ),
            )
        }
        if (withActions) Spacer(Modifier.width(actionsWidth))
    }
}

@Composable
@Suppress("LongParameterList") // o desenho de uma linha recebe tudo o que a tabela decide
private fun <T> DataRow(
    row: T,
    columns: List<Column<T>>,
    onRowClick: (T) -> Unit,
    modifier: Modifier,
    actions: (@Composable () -> Unit)?,
    actionsWidth: Dp,
) {
    Row(
        modifier.fillMaxWidth().clickable { onRowClick(row) }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            Text(column.cell(row), Modifier.weight(column.weight))
        }
        if (actions != null) {
            Row(
                Modifier.width(actionsWidth),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { actions() }
        }
    }
}

@Composable
private fun <T> Footer(
    state: TableState<T>,
    filtering: Boolean,
    noVisibleRows: Boolean,
    emptyMessage: String,
    onLoad: () -> Unit,
) {
    val error = state.error
    // Com mais páginas por vir (inclusive antes da primeira carga) a lista ainda não terminou.
    val finishedEmpty = noVisibleRows && !state.loading && error == null && !state.hasMore
    if (finishedEmpty) Text(emptyMessage, Modifier.padding(vertical = 8.dp).testTag("table-empty"))
    when {
        state.loading -> {
            CircularProgressIndicator(Modifier.testTag("table-loading"))
        }

        error != null -> {
            Column(Modifier.testTag("table-error")) {
                Text(error.userMessage, color = MaterialTheme.colorScheme.error)
                Button(onClick = onLoad, modifier = Modifier.testTag("table-retry")) { Text("Tentar de novo") }
            }
        }

        filtering && state.hasMore -> {
            Button(onClick = onLoad, modifier = Modifier.testTag("load-more")) { Text("Carregar mais") }
        }
    }
}
