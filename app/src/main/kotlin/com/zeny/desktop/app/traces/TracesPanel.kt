package com.zeny.desktop.app.traces

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.zeny.desktop.core.facade.TraceStatusFilter
import com.zeny.desktop.design.feedback.errorMessage
import com.zeny.desktop.design.table.DataTable
import com.zeny.desktop.design.theme.ZenyColors
import com.zeny.desktop.design.table.Column as TableColumn

private const val NO_ASSIGNEE = "—"
private const val EMPTY_TRACES = "Nenhum recado encontrado."
private val STATUS_FILTERS =
    listOf<Pair<String, TraceStatusFilter?>>(
        "Abertos" to TraceStatusFilter.OPEN,
        "Resolvidos" to TraceStatusFilter.RESOLVED,
        "Todos" to null,
    )
private val DUE_FILTERS = listOf("Todos" to DueFilter.ANY, "Hoje" to DueFilter.TODAY)

/**
 * A lista de recados: filtros, tabela e as ações de cada linha. Quem a mostra cria e inicia o [list] (`start`) e o
 * fecha (`close`). [onNew] nulo esconde "Novo recado"; [onOpenReservation] nulo deixa a linha sem clique. Com [compact]
 * (seção do detalhe da reserva), sem filtros e sem a coluna de vínculo. Nenhum id nem código cru aparece.
 */
@Composable
fun TracesPanel(
    list: TracesListState,
    onNew: (() -> Unit)?,
    onEdit: (TraceRow) -> Unit,
    onOpenReservation: ((String) -> Unit)?,
    compact: Boolean = false,
) {
    Column(Modifier.fillMaxSize().testTag("traces-panel"), Arrangement.spacedBy(8.dp)) {
        Header(list, onNew, compact)
        val error = list.error
        if (error != null && list.table.error == null) ErrorBand(error, list::refresh)
        if (!compact) Filters(list)
        DataTable(
            state = list.table,
            columns = columnsOf(compact),
            onRowClick = { row -> row.reservationId?.let { id -> onOpenReservation?.invoke(id) } },
            modifier = Modifier.weight(1f),
            emptyMessage = EMPTY_TRACES,
            rowActions = { index, row -> RowActions(list, row, index, onEdit) },
        )
    }
}

@Composable
private fun Header(
    list: TracesListState,
    onNew: (() -> Unit)?,
    compact: Boolean,
) {
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        if (list.departmentsDenied && onNew == null && !compact) {
            Text(TraceText.DEPARTMENTS_DENIED, Modifier.weight(1f).testTag("traces-departments-denied"))
        } else {
            Row(Modifier.weight(1f)) {}
        }
        if (onNew != null) Button(onClick = onNew, modifier = Modifier.testTag("trace-new")) { Text("Novo recado") }
    }
}

@Composable
private fun Filters(list: TracesListState) {
    if (!list.departmentsDenied) DepartmentFilter(list)
    ChipRow("Situação", "traces-status-filter") {
        STATUS_FILTERS.forEach { (label, status) ->
            FilterChip(
                selected = list.statusFilter == status,
                onClick = { list.setStatus(status) },
                label = { Text(label) },
            )
        }
    }
    ChipRow("Vencimento", "traces-due-filter") {
        DUE_FILTERS.forEach { (label, due) ->
            FilterChip(
                selected = list.dueFilter == due,
                onClick = { list.setDue(due) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun ChipRow(
    title: String,
    tag: String,
    chips: @Composable () -> Unit,
) {
    Row(Modifier.testTag(tag), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        chips()
    }
}

@Composable
private fun DepartmentFilter(list: TracesListState) {
    val error = list.departmentsError
    if (error != null) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Text(errorMessage(error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = list::retryDepartments) { Text("Tentar de novo") }
        }
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.testTag("traces-department-filter"),
    ) {
        FilterChip(
            selected = list.departmentFilter == null,
            onClick = { list.setDepartment(null) },
            label = { Text("Todos os setores") },
        )
        list.departments.forEach { department ->
            FilterChip(
                selected = list.departmentFilter == department.id,
                onClick = { list.setDepartment(department.id) },
                label = { Text(department.name) },
            )
        }
    }
}

private fun columnsOf(compact: Boolean): List<TableColumn<TraceRow>> =
    listOfNotNull(
        TableColumn("due", "Vencimento") { it.due },
        TableColumn("department", "Setor") { it.department },
        if (compact) null else TableColumn("link", "Vínculo") { it.link },
        TableColumn("text", "Recado", weight = 3f) { it.text },
        TableColumn("assignee", "Responsável") { it.assignee ?: NO_ASSIGNEE },
        TableColumn("status", "Situação") { it.status.label },
    )

@Composable
private fun RowActions(
    list: TracesListState,
    row: TraceRow,
    index: Int,
    onEdit: (TraceRow) -> Unit,
) {
    if (list.canResolve(row)) {
        OutlinedButton(
            onClick = { list.resolve(row.id) },
            enabled = !list.busy,
            modifier = Modifier.testTag("trace-resolve-$index"),
        ) { Text("Resolver") }
    }
    if (list.canEdit(row)) {
        OutlinedButton(onClick = { onEdit(row) }, modifier = Modifier.testTag("trace-edit-$index")) { Text("Editar") }
    }
}

@Composable
internal fun ErrorBand(
    error: ApiError,
    onRetry: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, ZenyColors.Danger, MaterialTheme.shapes.small)
            .padding(8.dp)
            .testTag("traces-error"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(TraceText.message(error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Tentar de novo") }
    }
}
