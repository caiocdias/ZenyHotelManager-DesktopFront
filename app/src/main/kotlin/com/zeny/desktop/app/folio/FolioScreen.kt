package com.zeny.desktop.app.folio

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zeny.desktop.app.reception.RegistrationCardDialog
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.design.feedback.ErrorState
import com.zeny.desktop.design.feedback.LoadingState

private val LIST_WIDTH = 320.dp
private val CHARGE_INDENT = 24.dp
private const val DIALOG_FRACTION = 0.92f

/**
 * A aba "Contas": as contas abertas (número, dono, saldo e "Limite excedido"), com "Carregar mais", e, ao lado, a conta
 * escolhida. A lista é lida ao entrar na aba pela primeira vez.
 */
@Composable
fun FolioListScreen(accounts: FolioAccounts) {
    val list = accounts.list
    LaunchedEffect(list) { list.start() }
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.width(LIST_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Contas abertas", style = MaterialTheme.typography.titleLarge)
            list.rows.forEach { row ->
                FolioRowItem(row, selected = accounts.open?.folioId == row.id) { accounts.select(row.id) }
            }
            if (!list.loading && list.error == null && list.rows.isEmpty()) Text(FolioText.NO_OPEN_FOLIOS)
            list.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("folio-list-error"))
            }
            if (list.loading) Text("Carregando…")
            if (list.hasMore || list.error != null) {
                OutlinedButton(
                    onClick = list::loadMore,
                    enabled = !list.loading,
                    modifier = Modifier.testTag("folio-list-more"),
                ) { Text(if (list.error != null) "Tentar de novo" else "Carregar mais") }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            accounts.open?.let { FolioPanel(it) }
        }
    }
}

@Composable
private fun FolioRowItem(
    row: FolioRow,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, border, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(8.dp)
            .testTag("folio-row-${row.id}"),
    ) {
        Text("${row.number} · ${row.owner} · ${row.balance}", style = MaterialTheme.typography.titleSmall)
        if (row.limitExceeded) Text(FolioText.LIMIT_EXCEEDED, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * As contas da reserva num diálogo: nenhuma, "Esta reserva ainda não tem conta."; uma, o painel dela; mais de uma, um
 * seletor por número. Os estados nascem e fecham com o diálogo; quem o abre o tira da composição para fechá-lo.
 */
@Composable
fun ReservationFolioDialog(
    wiring: FolioWiring,
    reservationId: String,
    reservationCode: String,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val folios = remember(wiring.source, reservationId) { ReservationFoliosState(wiring.source, scope, reservationId) }
    DisposableEffect(folios) {
        folios.start()
        onDispose { folios.close() }
    }
    val chosen = folios.chosen
    val folio = remember(folios, chosen, wiring.permissions) { chosen?.let { FolioState(wiring, scope, it) } }
    DisposableEffect(folio) {
        folio?.start()
        onDispose { folio?.close() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(DIALOG_FRACTION).testTag("folio-dialog"), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Conta da reserva $reservationCode", style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("folio-dialog-close"),
                    ) { Text("Fechar") }
                }
                ReservationFolios(folios, folio)
            }
        }
    }
}

@Composable
private fun ReservationFolios(
    folios: ReservationFoliosState,
    folio: FolioState?,
) {
    val error = folios.error
    val empty = folios.empty
    when {
        error != null -> {
            ErrorState(error, onRetry = folios::start)
        }

        empty != null -> {
            Text(empty, modifier = Modifier.testTag("folio-none"))
        }

        folios.loading -> {
            LoadingState()
        }

        else -> {
            if (folios.folios.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    folios.folios.forEachIndexed { index, row ->
                        FilterChip(
                            selected = folios.chosen == row.id,
                            onClick = { folios.choose(row.id) },
                            label = { Text(row.number) },
                            modifier = Modifier.testTag("folio-choice-$index"),
                        )
                    }
                }
            }
            folio?.let { FolioPanel(it) }
        }
    }
}

/**
 * O painel da conta: cabeçalho, lançamentos (a taxa de serviço recuada sob o item dela), o extrato da audiência
 * escolhida e os dois botões do extrato em PDF, independentes dela. Tudo como o servidor devolve.
 */
@Composable
fun FolioPanel(
    state: FolioState,
    modifier: Modifier = Modifier,
) {
    val view = state.view
    val error = state.error
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("folio-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            view == null && error != null -> {
                ErrorState(error, onRetry = state::reload)
            }

            view == null -> {
                LoadingState()
            }

            else -> {
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("folio-error"),
                    )
                }
                Header(view)
                Postings(state.postings)
                Statement(state)
            }
        }
    }
    state.pdf?.let { RegistrationCardDialog(it, onClose = state::closePdf) }
}

@Composable
private fun Header(view: FolioView) {
    Text("Conta ${view.number}", style = MaterialTheme.typography.titleLarge)
    Text("Dono: ${view.owner}")
    Text("Situação: ${view.status}", modifier = Modifier.testTag("folio-status"))
    Text("Saldo: ${view.balance}", modifier = Modifier.testTag("folio-balance"))
    Text("Limite: ${view.creditLimit}")
    if (view.limitExceeded) Text(FolioText.LIMIT_EXCEEDED, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Postings(rows: List<PostingRow>) {
    Text("Lançamentos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    if (rows.isEmpty()) Text(FolioText.NO_POSTINGS)
    rows.forEachIndexed { index, row ->
        val parts = listOf(row.date, row.description, row.type, row.quantity, row.amount)
        val reversed = if (row.reversed) " · ${FolioText.REVERSED}" else ""
        Text(
            parts.joinToString(" · ") + reversed,
            Modifier.padding(start = if (row.serviceCharge) CHARGE_INDENT else 0.dp).testTag("posting-$index"),
        )
    }
}

@Composable
private fun Statement(state: FolioState) {
    Text("Extrato", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        StatementAudience.entries.reversed().forEach { audience ->
            FilterChip(
                selected = state.audience == audience,
                onClick = { state.audience = audience },
                label = { Text(FolioText.audience(audience)) },
                modifier = Modifier.testTag("folio-audience-$audience"),
            )
        }
        Button(onClick = state::showStatement, modifier = Modifier.testTag("folio-statement-show")) {
            Text("Ver extrato")
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.canOpenGuestPdf) {
            OutlinedButton(
                onClick = { state.openPdf(StatementAudience.GUEST) },
                modifier = Modifier.testTag("folio-pdf-guest"),
            ) { Text("Extrato em PDF (hóspede)") }
        }
        if (state.canOpenInternalPdf) {
            OutlinedButton(
                onClick = { state.openPdf(StatementAudience.INTERNAL) },
                modifier = Modifier.testTag("folio-pdf-internal"),
            ) { Text("Extrato em PDF (interno)") }
        }
    }
    state.statementError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("folio-statement-error"))
    }
    if (state.statementLoading) Text("Buscando o extrato…")
    state.statement?.let { StatementBody(it) }
}

@Composable
private fun StatementBody(statement: StatementView) {
    Column(Modifier.testTag("folio-statement"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Extrato ${statement.audience.lowercase()}", style = MaterialTheme.typography.titleSmall)
        statement.days.forEach { day ->
            Text(day.date, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            day.lines.forEach { line ->
                Text(listOf(line.date, line.description, line.kind, line.amount).joinToString(" · "))
                line.reason?.let { Text("Motivo: $it", Modifier.padding(start = CHARGE_INDENT)) }
            }
            Text("Débitos: ${day.charges} · Créditos: ${day.payments} · Saldo do dia: ${day.balanceAtEnd}")
        }
        Text("Total de débitos: ${statement.charges}")
        Text("Total de créditos: ${statement.payments}")
        Text("Saldo da conta: ${statement.balance}", style = MaterialTheme.typography.titleSmall)
    }
}
