package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.design.table.DataTable

private fun emptyMessage(list: PendingList) =
    when (list) {
        PendingList.GUARANTEES -> "Nenhuma garantia pendente."
        PendingList.DEPOSITS -> "Nenhum depósito em falta."
        PendingList.NO_SHOWS -> "Nenhum no-show previsto."
        PendingList.REALLOCATION -> "Nenhuma realocação necessária."
    }

/**
 * As pendências de reservas: um seletor das quatro listas, o botão "Atualizar" (recarrega só a selecionada) e a tabela
 * da lista selecionada, que ocupa o resto da altura (a tabela é preguiçosa: sem rolagem vertical em volta). O erro de
 * uma lista, com "Tentar de novo", é o da própria tabela e só aparece nela. O clique numa linha chama [onOpen] com o
 * id da reserva. Quem compõe a tela inicia o [state].
 */
@Composable
fun PendingPanel(
    state: PendingListsState,
    onOpen: (reservationId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            PendingList.entries.forEach { list ->
                FilterChip(
                    selected = state.selected == list,
                    onClick = { state.select(list) },
                    label = { Text(list.label) },
                    modifier = Modifier.testTag("pending-list-${list.name}"),
                )
            }
            OutlinedButton(onClick = state::refresh, modifier = Modifier.testTag("pending-refresh")) {
                Text("Atualizar")
            }
        }
        // Uma tabela por lista: a rolagem e o filtro de uma não passam para a outra.
        key(state.selected) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                DataTable(
                    state = state.table,
                    columns = state.columns,
                    onRowClick = { onOpen(it.reservationId) },
                    modifier = Modifier.fillMaxSize(),
                    emptyMessage = emptyMessage(state.selected),
                )
            }
        }
    }
}
