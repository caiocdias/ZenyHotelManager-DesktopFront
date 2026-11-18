package com.zeny.desktop.app.cashier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.facade.CashMovementType
import com.zeny.desktop.design.feedback.ErrorState
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.input.MoneyField

/**
 * A área "Contas e caixa": o turno corrente do usuário (com sangria, suprimento e o fechamento cego) ou, sem turno, a
 * abertura; fechado, o relatório. Sem `billing.cashier.operate`, só o aviso. Os valores são os do servidor, em BRL.
 */
@Composable
fun CashierScreen(
    state: CashierState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Turno de caixa", style = MaterialTheme.typography.titleLarge)
        val notice = state.notice
        val loadError = state.loadError
        val shift = state.shift
        val report = state.report
        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cashier-message"))
        }
        when {
            notice != null -> Text(notice, modifier = Modifier.testTag("cashier-notice"))
            report != null -> ReportPanel(report, onDismiss = state::dismissReport)
            loadError != null -> ErrorState(loadError, onRetry = state::reload)
            shift != null -> ShiftPanel(shift, state)
            state.loading -> LoadingState()
            state.showsOpenForm -> OpenForm(state)
        }
        if (state.reportLoading) Text("Buscando o relatório…")
        if (state.canViewReport && shift == null) {
            OutlinedButton(onClick = state::showReport, modifier = Modifier.testTag("cashier-view-report")) {
                Text("Ver relatório")
            }
        }
    }
    state.movement?.let { MovementDialog(it) }
    state.closing?.let { CloseShiftDialog(it) }
}

@Composable
private fun OpenForm(state: CashierState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Abrir turno", style = MaterialTheme.typography.titleMedium)
        Text("Ponto de caixa", style = MaterialTheme.typography.titleSmall)
        state.cashPointsError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cashier-points-error"))
            if (state.cashPointsRetryable) {
                OutlinedButton(onClick = state::reload, modifier = Modifier.testTag("cashier-points-retry")) {
                    Text("Tentar de novo")
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.cashPoints.forEach { point ->
                FilterChip(
                    selected = state.cashPointId == point.id,
                    onClick = { state.cashPointId = point.id },
                    label = { Text(point.name) },
                    enabled = !state.locked,
                    modifier = Modifier.testTag("cashier-point-${point.id}"),
                )
            }
        }
        MoneyField(
            value = state.openingFloat,
            onValueChange = { state.openingFloat = it },
            label = "Fundo de troco",
            error = state.floatError,
            modifier = Modifier.testTag("cashier-float"),
            enabled = !state.locked,
        )
        if (state.busy) Text("Abrindo o turno…")
        if (state.closeOnly) {
            Button(onClick = state::dismiss, modifier = Modifier.testTag("cashier-dismiss")) { Text("Fechar") }
        } else {
            Button(
                onClick = state::open,
                enabled = state.canOpen,
                modifier = Modifier.testTag("cashier-open"),
            ) { Text("Abrir turno") }
        }
    }
}

@Composable
private fun ShiftPanel(
    shift: ShiftView,
    state: CashierState,
) {
    Column(Modifier.testTag("cashier-shift"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Turno ${shift.number}", style = MaterialTheme.typography.titleMedium)
        Text("Ponto de caixa: ${shift.cashPointName}")
        Text("Aberto em: ${shift.openedOn}")
        Text("Fundo de troco: ${shift.openingFloat}")
        Text("Movimentos", style = MaterialTheme.typography.titleSmall)
        if (shift.movements.isEmpty()) Text("Nenhum movimento no turno.")
        shift.movements.forEach { Text("${it.type} · ${it.amount} · ${it.reason}") }
        if (shift.expected.isNotEmpty() || shift.expectedTotal != null) {
            Text("Esperado", style = MaterialTheme.typography.titleSmall)
            shift.expected.forEach { Text("${it.method}: ${it.amount}") }
            shift.expectedTotal?.let { Text("Total esperado: $it") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { state.startMovement(CashMovementType.WITHDRAWAL) },
                modifier = Modifier.testTag("cashier-withdrawal"),
            ) { Text("Sangria") }
            OutlinedButton(
                onClick = { state.startMovement(CashMovementType.SUPPLY) },
                modifier = Modifier.testTag("cashier-supply"),
            ) { Text("Suprimento") }
            Button(onClick = state::startClose, modifier = Modifier.testTag("cashier-close-shift")) {
                Text("Fechar turno")
            }
        }
    }
}

/** O relatório de fechamento: por forma, esperado, declarado e diferença, e os totais, como o servidor mandou. */
@Composable
private fun ReportPanel(
    report: ReportView,
    onDismiss: () -> Unit,
) {
    Column(Modifier.testTag("cashier-report"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Relatório do turno ${report.number}", style = MaterialTheme.typography.titleMedium)
        Text("Ponto de caixa: ${report.cashPointName}")
        report.closedOn?.let { Text("Fechado em: $it") }
        report.counts.forEach { line ->
            Column {
                Text(line.method, style = MaterialTheme.typography.titleSmall)
                Text("Esperado: ${line.expected}")
                Text("Declarado: ${line.declared}")
                Text("Diferença: ${line.difference}")
            }
        }
        report.expectedTotal?.let { Text("Total esperado: $it") }
        report.declaredTotal?.let { Text("Total declarado: $it") }
        report.differenceTotal?.let { Text("Diferença total: $it") }
        when (report.hasDifference) {
            true -> Text("O fechamento tem diferença.", color = MaterialTheme.colorScheme.error)
            false -> Text("O fechamento não tem diferença.")
            null -> Unit
        }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("cashier-report-dismiss")) {
            Text("Fechar relatório")
        }
    }
}
