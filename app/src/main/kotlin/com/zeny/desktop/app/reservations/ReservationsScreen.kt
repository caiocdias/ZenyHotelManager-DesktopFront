package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.app.reception.COUNTRY_REQUIRED
import com.zeny.desktop.app.reception.DetailActions
import com.zeny.desktop.app.reception.GUEST_DOCUMENT_TYPES
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.app.reception.RegistrationCardState
import com.zeny.desktop.app.reception.ReservationDetailPanel
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.core.facade.RESERVATION_STATUSES
import com.zeny.desktop.design.feedback.ErrorState
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.input.DateField
import com.zeny.desktop.design.table.DataTable

private const val EMPTY_RESULT = "Nenhuma reserva encontrada."

/** As ações da área Reservas numa sessão; cada fábrica é nula sem a porta. */
class ReservationsActions(
    val permissions: Set<String>,
    val change: ((reservationId: String) -> ReservationChangeState)?,
    val confirm: ((reservationId: String) -> ReservationConfirmState)?,
    val confirmation: ((reservationId: String, code: String) -> RegistrationCardState)?,
)

/**
 * A busca de reservas: os filtros, a busca por documento, a lista paginada e, ao lado, o detalhe da reserva escolhida
 * (se [detail] houver). Quem compõe a tela inicia o [search]; a tabela só entra com a data operacional. O documento
 * buscado só aparece mascarado. Com [actions], o detalhe oferece "Alterar", "Confirmar reserva" e "Confirmação (PDF)",
 * conforme a reserva e as permissões; sem elas, o detalhe é somente leitura.
 */
@Composable
fun ReservationsScreen(
    search: ReservationSearchState,
    detail: ReservationDetailState?,
    actions: ReservationsActions? = null,
) {
    // A fábrica é uma lambda nova a cada recomposição da área: o holder dos diálogos abertos não pode depender dela.
    val currentActions by rememberUpdatedState(actions)
    val dialogs = detail?.let { remember(it) { ReservationDialogs(it) { currentActions } } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Reservas", style = MaterialTheme.typography.titleLarge)
            search.day?.let { Text("Data operacional: ${ReceptionText.date(it.date.toApi())}") }
        }
        val error = search.error
        when {
            search.day != null -> Content(search, detail, dialogs)
            error != null -> ErrorState(error, onRetry = search::start)
            else -> LoadingState()
        }
    }
    dialogs?.Show()
}

@Composable
private fun Content(
    search: ReservationSearchState,
    detail: ReservationDetailState?,
    dialogs: ReservationDialogs?,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Filters(search)
        DocumentRow(search)
        search.fieldError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("field-error"))
        }
        search.searchedDocument?.let {
            Text("Reservas do documento $it", modifier = Modifier.testTag("searched-document"))
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                DataTable(
                    state = search.table,
                    columns = search.columns,
                    onRowClick = { detail?.open(it.id) },
                    emptyMessage = EMPTY_RESULT,
                )
            }
            if (detail?.openId != null) {
                ReservationDetailPanel(detail, dialogs?.detailActions() ?: DetailActions())
            }
        }
    }
}

@Composable
private fun Filters(search: ReservationSearchState) {
    val submit = onEnter(search::search)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = search.number,
            onValueChange = { search.number = it },
            label = { Text("Número") },
            singleLine = true,
            modifier = submit.testTag("search-number"),
        )
        OutlinedTextField(
            value = search.name,
            onValueChange = { search.name = it },
            label = { Text("Nome do titular ou hóspede") },
            singleLine = true,
            modifier = submit.testTag("search-name"),
        )
        DateField(search.fromText, { search.fromText = it }, "De", modifier = submit.testTag("search-from"))
        DateField(search.toText, { search.toText = it }, "Até", modifier = submit.testTag("search-to"))
        RESERVATION_STATUSES.forEach { status ->
            FilterChip(
                selected = status in search.statuses,
                onClick = { search.statuses = search.statuses.toggled(status) },
                label = { Text(ReceptionText.status(status).label) },
                modifier = Modifier.testTag("status-$status"),
            )
        }
        Button(onClick = search::search, modifier = Modifier.testTag("search-submit")) { Text("Buscar") }
    }
}

@Composable
private fun DocumentRow(search: ReservationSearchState) {
    val submit = onEnter(search::searchByDocument)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        GUEST_DOCUMENT_TYPES.forEach { type ->
            FilterChip(
                selected = search.documentType == type,
                onClick = {
                    search.documentType = type
                    // O campo do país some para estes tipos: o que sobrou de uma escolha anterior não vai à busca.
                    if (type !in COUNTRY_REQUIRED) search.issuingCountry = ""
                },
                label = { Text(ReceptionText.document(type, "").trim()) },
                modifier = Modifier.testTag("doc-type-$type"),
            )
        }
        OutlinedTextField(
            value = search.documentNumber,
            onValueChange = { search.documentNumber = it },
            label = { Text("Número do documento") },
            singleLine = true,
            modifier = submit.testTag("document-number"),
        )
        if (search.documentType in COUNTRY_REQUIRED) {
            OutlinedTextField(
                value = search.issuingCountry,
                onValueChange = { search.issuingCountry = it },
                label = { Text("País emissor") },
                singleLine = true,
                modifier = submit.testTag("issuing-country"),
            )
        }
        Button(onClick = search::searchByDocument, modifier = Modifier.testTag("document-submit")) {
            Text("Buscar por documento")
        }
    }
}

/** O Enter num campo dispara a busca da linha dele. */
private fun onEnter(action: () -> Unit): Modifier =
    Modifier.onPreviewKeyEvent {
        val enter = it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)
        if (enter) action()
        enter
    }

private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value
