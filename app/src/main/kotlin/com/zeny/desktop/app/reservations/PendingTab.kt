package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeny.desktop.app.reception.DetailActions
import com.zeny.desktop.app.reception.ReservationDetailPanel
import com.zeny.desktop.app.reception.ReservationDetailState

/**
 * A aba "Pendências": o painel das listas e, ao lado, o detalhe da reserva aberta, com as mesmas ações da busca.
 * Sem [detail] (conexão sem a porta da recepção), o clique na linha não abre nada. Quem compõe a aba inicia o
 * [pending]; a aba não o fecha.
 */
@Composable
internal fun PendingTab(
    pending: PendingListsState,
    detail: ReservationDetailState?,
    actions: ReservationsActions?,
    modifier: Modifier = Modifier,
) {
    // A fábrica é uma lambda nova a cada recomposição da área: o holder dos diálogos abertos não pode depender dela.
    val currentActions by rememberUpdatedState(actions)
    val dialogs = detail?.let { remember(it) { ReservationDialogs(it) { currentActions } } }
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PendingPanel(pending, onOpen = { detail?.open(it) }, modifier = Modifier.weight(1f).fillMaxHeight())
        if (detail?.openId != null) {
            ReservationDetailPanel(detail, dialogs?.detailActions() ?: DetailActions())
        }
    }
    dialogs?.Show()
}
