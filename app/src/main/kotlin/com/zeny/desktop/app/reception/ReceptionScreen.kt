package com.zeny.desktop.app.reception

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.design.feedback.ErrorState
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.feedback.StatusChip
import com.zeny.desktop.design.feedback.errorMessage
import com.zeny.desktop.design.table.DataTable
import com.zeny.desktop.design.theme.ZenyColors

private const val EMPTY_LIST = "Nenhuma reserva nesta lista."
private val DETAIL_WIDTH = 380.dp

/**
 * Painel do dia da recepção: a data operacional, as três listas em abas e o detalhe da reserva escolhida ao lado.
 * Dado pessoal (titular, documento mascarado, alertas) só aparece no detalhe, como o estado o entrega: nenhum id.
 */
@Composable
fun ReceptionScreen(
    panel: DayPanelState,
    detail: ReservationDetailState,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(panel, detail)
        val error = panel.error
        if (error != null && panel.day != null) ErrorBand(error, panel::refresh)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) { Body(panel, detail) }
            if (detail.openId != null) DetailPanel(detail)
        }
    }
}

@Composable
private fun Header(
    panel: DayPanelState,
    detail: ReservationDetailState,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Recepção", style = MaterialTheme.typography.titleLarge)
        panel.day?.let { Text("Data operacional: ${ReceptionText.date(it.date.toApi())}") }
        OutlinedButton(
            onClick = {
                panel.refresh()
                detail.reload()
            },
        ) { Text("Atualizar") }
    }
}

@Composable
private fun ErrorBand(
    error: ApiError,
    onRetry: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, ZenyColors.Danger, MaterialTheme.shapes.small)
            .padding(8.dp)
            .testTag("panel-error"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(errorMessage(error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Tentar de novo") }
    }
}

/** A tabela só entra com a data operacional: antes dela, o carregando ou o erro da data. */
@Composable
private fun Body(
    panel: DayPanelState,
    detail: ReservationDetailState,
) {
    val error = panel.error
    when {
        panel.day != null -> Lists(panel, detail)
        error != null -> ErrorState(error, onRetry = panel::refresh)
        else -> LoadingState()
    }
}

@Composable
private fun Lists(
    panel: DayPanelState,
    detail: ReservationDetailState,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayList.entries.forEach { list ->
                FilterChip(
                    selected = panel.selectedTab == list,
                    onClick = { panel.select(list) },
                    label = { Text(panel.tabLabel(list)) },
                    modifier = Modifier.testTag("tab-${list.name}"),
                )
            }
        }
        val table = panel.tabs.getValue(panel.selectedTab)
        DataTable(
            state = table,
            columns = panel.columns,
            onRowClick = { detail.open(it.id) },
            modifier = Modifier.weight(1f),
            emptyMessage = EMPTY_LIST,
        )
    }
}

@Composable
private fun DetailPanel(detail: ReservationDetailState) {
    val error = detail.error
    val view = detail.view
    Column(
        Modifier
            .width(DETAIL_WIDTH)
            .fillMaxHeight()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(12.dp)
            .testTag("reservation-detail"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = detail::close) { Text("Fechar") }
        Box(Modifier.weight(1f)) {
            when {
                view != null -> DetailBody(view, detail::reload)
                error != null -> ErrorState(error, onRetry = detail::reload)
                else -> LoadingState()
            }
        }
    }
}

@Composable
private fun DetailBody(
    view: ReservationDetailView,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), Arrangement.spacedBy(6.dp)) {
        Text(view.code, style = MaterialTheme.typography.titleMedium)
        StatusChip(view.status)
        Field("Titular", view.holder)
        view.document?.let { Field("Documento", it) }
        Field("Período", view.period)
        Field("Tipo", view.roomType)
        Field("UH", view.room)
        Field("Hóspedes", view.guests)
        Field("Plano", view.plan)
        Field("Total", view.total)
        view.alerts.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Section("Alocações", view.assignmentsError, onRetry) {
            view.assignments.forEach {
                val moved = if (it.doNotMove) " · Não mover" else ""
                Text("${it.room} · ${it.period} · ${it.reason} · ${it.state}$moved")
            }
        }
        Section("Histórico", view.historyError, onRetry) {
            view.history.forEach {
                Text("${it.at} · ${it.title}", style = MaterialTheme.typography.labelLarge)
                if (it.detail.isNotBlank()) Text(it.detail)
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun Section(
    title: String,
    error: ApiError?,
    onRetry: () -> Unit,
    content: @Composable () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    if (error != null) {
        Text(errorMessage(error), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text("Tentar de novo") }
    } else {
        content()
    }
}
