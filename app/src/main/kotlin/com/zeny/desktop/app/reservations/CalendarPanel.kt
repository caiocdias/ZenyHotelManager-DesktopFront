package com.zeny.desktop.app.reservations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.feedback.EmptyState
import com.zeny.desktop.design.feedback.ErrorState
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.theme.ZenyColors
import com.zeny.desktop.sdk.model.AvailabilityDayResponse

private const val NO_DAY = "—"
private const val EMPTY_CALENDAR = "Nenhum tipo de UH para mostrar."
private val NAME_WIDTH = 160.dp
private val CELL_WIDTH = 84.dp
private val CELL_HEIGHT = 40.dp

/** Como a célula de um dia se mostra: a cor do tom e o que a semântica diz dela. */
private enum class CellTone(
    val container: Color?,
    val content: Color,
    val description: String?,
) {
    NORMAL(null, ZenyColors.OnSurface, null),
    SOLD_OUT(ZenyColors.Warning, ZenyColors.OnWarning, "Esgotado"),
    OVERBOOKED(ZenyColors.Danger, ZenyColors.OnDanger, "Sobrevendido"),
}

private fun toneOf(day: AvailabilityDayResponse) =
    when {
        day.overbooked -> CellTone.OVERBOOKED
        day.available == 0 -> CellTone.SOLD_OUT
        else -> CellTone.NORMAL
    }

/**
 * O calendário de disponibilidade: uma linha por tipo de UH, uma coluna por data da janela, com a navegação e, ao
 * clicar numa célula, o inventário do dia e o atalho para cotar a partir dele. Quem compõe a tela inicia o
 * [calendar]. A célula é lida pela data do cabeçalho: sem dia para ela (inclusive com linhas velhas sob um
 * cabeçalho novo, depois de uma navegação que falhou), mostra "—", nunca zero.
 */
@Composable
fun CalendarPanel(
    calendar: AvailabilityState,
    onQuoteFrom: (BusinessDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Controls(calendar)
        calendar.error?.let { ErrorState(it, onRetry = calendar::reload) }
        when {
            calendar.rows.isNotEmpty() -> {
                Grid(calendar)
                Detail(calendar, onQuoteFrom)
            }

            calendar.loading -> {
                LoadingState()
            }

            calendar.error == null -> {
                EmptyState(EMPTY_CALENDAR)
            }
        }
    }
}

@Composable
private fun Controls(calendar: AvailabilityState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = calendar::previous, enabled = calendar.canGoBack && !calendar.loading) {
            Text("Anterior")
        }
        OutlinedButton(onClick = calendar::next, enabled = calendar.from != null && !calendar.loading) {
            Text("Próximo")
        }
        OutlinedButton(onClick = calendar::reload, enabled = !calendar.loading) { Text("Recarregar") }
    }
}

@Composable
private fun Grid(calendar: AvailabilityState) {
    val dates = calendar.dates
    Row {
        Column {
            Box(Modifier.width(NAME_WIDTH).height(CELL_HEIGHT))
            calendar.rows.forEach { row ->
                Box(Modifier.width(NAME_WIDTH).height(CELL_HEIGHT), contentAlignment = Alignment.CenterStart) {
                    Text(row.name, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                dates.forEach { date ->
                    Box(Modifier.width(CELL_WIDTH).height(CELL_HEIGHT), contentAlignment = Alignment.Center) {
                        Text(AvailabilityText.dayHeader(date), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            calendar.rows.forEach { row ->
                Row {
                    dates.forEach { date ->
                        val picked = calendar.selected == (row.roomTypeId to date)
                        Cell(row.roomTypeId, date, row.cells[date], picked) { calendar.select(row.roomTypeId, date) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(
    roomTypeId: String,
    date: BusinessDate,
    day: AvailabilityDayResponse?,
    picked: Boolean,
    onClick: () -> Unit,
) {
    val tone = day?.let(::toneOf) ?: CellTone.NORMAL
    val border = if (picked) ZenyColors.Primary else ZenyColors.Outline
    val base =
        Modifier
            .width(CELL_WIDTH)
            .height(CELL_HEIGHT)
            .testTag("availability-cell-$roomTypeId-${date.toApi()}")
            .background(tone.container ?: Color.Transparent)
            .border(BorderStroke(if (picked) 2.dp else 1.dp, border))
            .semantics(mergeDescendants = true) { tone.description?.let { stateDescription = it } }
    val modifier = if (day != null) base.clickable(onClick = onClick) else base
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = day?.available?.toString() ?: NO_DAY,
            style = MaterialTheme.typography.bodyMedium,
            color = tone.content,
        )
    }
}

@Composable
private fun Detail(
    calendar: AvailabilityState,
    onQuoteFrom: (BusinessDate) -> Unit,
) {
    val (typeId, date) = calendar.selected ?: return
    val day = calendar.selectedDay
    val name = calendar.rows.firstOrNull { it.roomTypeId == typeId }?.name
    if (day == null || name == null) return
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(ReceptionText.date(date.toApi()), style = MaterialTheme.typography.bodyMedium)
        Text(AvailabilityText.inventory(day), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { onQuoteFrom(date) }) { Text("Cotar a partir deste dia") }
    }
}
