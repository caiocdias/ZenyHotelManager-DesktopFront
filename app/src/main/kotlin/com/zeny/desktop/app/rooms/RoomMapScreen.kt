package com.zeny.desktop.app.rooms

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.feedback.EmptyState
import com.zeny.desktop.design.feedback.ErrorState
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.feedback.StatusChip
import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.design.feedback.errorMessage
import com.zeny.desktop.design.theme.ZenyColors
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import de.quati.kotlin.util.Option
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private const val NO_ROOMS = "Nenhuma UH cadastrada"
private const val NO_ROOMS_FILTERED = "Nenhuma UH com os filtros escolhidos"
private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 * Mapa de UHs ao vivo: barra do tempo real, filtros por tipo, legenda com contadores (que é também o filtro por
 * status), o quadro por andar e o painel da UH escolhida. O erro com ladrilhos na tela vira uma faixa sobre o mapa,
 * que continua à vista. Nenhum dado de hóspede aparece: da ocupação, só o número de hóspedes.
 */
@Composable
fun RoomMapScreen(state: RoomMapState) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Mapa de UHs", style = MaterialTheme.typography.titleLarge)
            RealtimeBar(state.live, state.disconnected, state::retry)
        }
        val error = state.error
        if (error != null && state.floors.isNotEmpty()) ErrorBand(error, state::retry)
        Filters(state)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) { MapBody(state) }
            state.selected?.let { RoomDetailPanel(it, state.roomTypeNames, state::clearSelection) }
        }
    }
}

/** O estado do tempo real; o mapa que perdeu a assinatura sem erro mostra "Desconectado" e o "Tentar de novo". */
@Composable
private fun RealtimeBar(
    live: RealtimeStatus,
    disconnected: Boolean,
    onRetry: () -> Unit,
) {
    val model =
        when {
            disconnected -> ChipModel("Desconectado", StatusTone.DANGER)
            live == RealtimeStatus.Live -> ChipModel("Ao vivo", StatusTone.SUCCESS)
            live is RealtimeStatus.Reconnecting -> ChipModel("Reconectando…", StatusTone.WARNING)
            live is RealtimeStatus.Failed -> ChipModel(errorMessage(live.error), StatusTone.DANGER)
            else -> ChipModel("Conectando…", StatusTone.NEUTRAL)
        }
    Row(
        Modifier.testTag("realtime-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusChip(model)
        if (disconnected) OutlinedButton(onClick = onRetry) { Text("Tentar de novo") }
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
            .testTag("map-error"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(errorMessage(error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Tentar de novo") }
    }
}

/** Os tipos pelo nome e a legenda, que filtra pelo status e mostra quantas UHs do tipo escolhido há em cada um. */
@Composable
private fun Filters(state: RoomMapState) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.roomTypeNames.entries.sortedBy { it.value }.forEach { (id, name) ->
            FilterChip(
                selected = id in state.typeFilter,
                onClick = { state.toggleType(id) },
                label = { Text(name) },
                modifier = Modifier.testTag("type-filter-$id"),
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoomDisplayStatus.entries.forEach { status ->
            FilterChip(
                selected = status in state.statusFilter,
                onClick = { state.toggleStatus(status) },
                label = { StatusChip(ChipModel("${status.label}: ${state.counters[status] ?: 0}", status.tone)) },
                modifier = Modifier.testTag("status-filter-${status.name}"),
            )
        }
    }
}

@Composable
private fun MapBody(state: RoomMapState) {
    val error = state.error
    val openId = state.selected?.let { it.status.roomId.toString() }
    when {
        state.floors.isNotEmpty() -> Floors(state.floors, openId, state::select)
        state.loading -> LoadingState()
        error != null -> ErrorState(error, onRetry = state::retry)
        state.hasRooms -> EmptyState(NO_ROOMS_FILTERED)
        else -> EmptyState(NO_ROOMS)
    }
}

@Composable
private fun Floors(
    floors: List<Pair<String, List<RoomTile>>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), Arrangement.spacedBy(16.dp)) {
        floors.forEach { (floor, tiles) ->
            Text(floor, style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tiles.forEach { Tile(it, it.roomId == selectedId, onSelect) }
            }
        }
    }
}

@Composable
private fun Tile(
    tile: RoomTile,
    isOpen: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        Modifier
            .testTag("room-tile-${tile.roomId}")
            .width(140.dp)
            .border(1.dp, ZenyColors.Outline, MaterialTheme.shapes.small)
            .clickable(role = Role.Button) { onSelect(tile.roomId) }
            .semantics { selected = isOpen }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(tile.number, style = MaterialTheme.typography.titleMedium)
        StatusChip(ChipModel(tile.label, tile.status.tone))
    }
}

/** Só os campos da UH; da ocupação, o número de hóspedes (LGPD). */
@Composable
private fun RoomDetailPanel(
    detail: RoomDetail,
    typeNames: Map<String, String>,
    onClose: () -> Unit,
) {
    val status = detail.status
    val typeId = status.roomTypeId.toString()
    Column(
        Modifier
            .width(300.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .testTag("room-detail"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("UH ${status.number}", style = MaterialTheme.typography.titleMedium)
        Text("Andar: ${status.floor.text() ?: "Sem andar"}")
        // O id nunca aparece: o rótulo vem do mapa, que tem um para todo tipo à vista.
        typeNames[typeId]?.let { Text("Tipo: $it") }
        detail.room?.let { room -> room.notes.text()?.let { Text("Observações: $it") } }
        Text("Ocupação: ${occupancyLabel(status.occupancy)}")
        val guests = (status.guestCount as? Option.Some)?.value
        if (status.occupancy == Occupancy.OCCUPIED && guests != null) Text("Hóspedes: $guests")
        Text("Governança: ${housekeepingStatus(status).label}")
        Text("Sinalização: ${serviceFlagLabel(status.serviceFlag)}")
        outOfOrderPeriod(status)?.let { Text(it) }
        OutlinedButton(onClick = onClose) { Text("Fechar") }
    }
}

private fun outOfOrderPeriod(status: RoomStatusResponse): String? {
    val overlay = (status.outOfOrder as? Option.Some)?.value ?: return null
    return "${outOfOrderKindLabel(overlay.kind)}: de ${date(overlay.startsOn)} a ${date(overlay.endsOn)}"
}

private fun date(iso: String): String =
    try {
        LocalDate.parse(iso).format(DATE_FORMAT)
    } catch (
        @Suppress("SwallowedException") e: DateTimeParseException,
    ) {
        iso
    }

/** Texto presente e não em branco, ou `null`. */
private fun Option<String?>.text(): String? = (this as? Option.Some)?.value?.takeIf { it.isNotBlank() }
