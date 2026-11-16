package com.zeny.desktop.app.reception

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.feedback.ErrorState
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.feedback.StatusChip
import com.zeny.desktop.design.feedback.errorMessage
import com.zeny.desktop.design.theme.ZenyColors

private val DETAIL_WIDTH = 380.dp

/** Os botões do topo do detalhe; cada um só aparece se a ação existe. */
data class DetailActions(
    val onCheckIn: (() -> Unit)? = null,
    val onMove: (() -> Unit)? = null,
    val onUndo: (() -> Unit)? = null,
    val onCard: (() -> Unit)? = null,
    val onChange: (() -> Unit)? = null,
    val onConfirmReservation: (() -> Unit)? = null,
    val onConfirmationPdf: (() -> Unit)? = null,
)

/** A falha de uma releitura com o conteúdo antigo à vista: o conteúdo fica, e a falha aparece por cima dele. */
@Composable
internal fun ErrorBand(
    error: ApiError,
    onRetry: () -> Unit,
    tag: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, ZenyColors.Danger, MaterialTheme.shapes.small)
            .padding(8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(errorMessage(error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Tentar de novo") }
    }
}

/**
 * O painel de detalhe de uma reserva aberta no [detail]: o desenho é o mesmo na Recepção e na busca. As [actions] são
 * os botões que o dono da tela oferece (nenhum, na busca) e [traces] é a seção de recados, se houver.
 */
@Composable
internal fun ReservationDetailPanel(
    detail: ReservationDetailState,
    actions: DetailActions = DetailActions(),
    traces: (@Composable () -> Unit)? = null,
) {
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
        if (actions.onCheckIn != null) {
            Button(onClick = actions.onCheckIn, modifier = Modifier.testTag("open-check-in")) { Text("Fazer check-in") }
        }
        if (actions.onMove != null) {
            Button(onClick = actions.onMove, modifier = Modifier.testTag("open-room-move")) { Text("Trocar UH") }
        }
        if (actions.onUndo != null) {
            OutlinedButton(onClick = actions.onUndo, modifier = Modifier.testTag("open-undo-check-in")) {
                Text("Desfazer check-in")
            }
        }
        if (actions.onCard != null) {
            OutlinedButton(onClick = actions.onCard, modifier = Modifier.testTag("open-registration-card")) {
                Text("Ficha de registro")
            }
        }
        if (actions.onChange != null) {
            Button(onClick = actions.onChange, modifier = Modifier.testTag("open-reservation-change")) {
                Text("Alterar")
            }
        }
        if (actions.onConfirmReservation != null) {
            Button(onClick = actions.onConfirmReservation, modifier = Modifier.testTag("open-reservation-confirm")) {
                Text("Confirmar reserva")
            }
        }
        if (actions.onConfirmationPdf != null) {
            OutlinedButton(
                onClick = actions.onConfirmationPdf,
                modifier = Modifier.testTag("open-reservation-confirmation"),
            ) { Text("Confirmação (PDF)") }
        }
        if (view != null && error != null) ErrorBand(error, detail::reload, "detail-error")
        Box(Modifier.weight(1f)) {
            when {
                view != null -> DetailBody(view, detail::reload) { traces?.invoke() }
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
    traces: @Composable () -> Unit,
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
        if (view.stayGuests.isNotEmpty() || view.stayGuestsError != null) {
            Section("Hóspedes da estadia", view.stayGuestsError, onRetry) {
                view.stayGuests.forEach {
                    val primary = if (it.primary) " (principal)" else ""
                    Text(listOfNotNull(it.name, it.document).joinToString(" · ") + primary)
                }
            }
        }
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
        traces()
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
