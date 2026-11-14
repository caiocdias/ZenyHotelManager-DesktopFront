package com.zeny.desktop.app.reception

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val DIALOG_WIDTH = 560.dp
private val DIALOG_MAX_BODY = 480.dp
private val MOVE_REASON_ORDER = listOf("GUEST_REQUEST", "OUT_OF_ORDER", "ROOM_ISSUE", "OPERATIONAL")
private val MOVE_TARIFF_ORDER = listOf("KEEP", "RECALCULATE")

/**
 * Diálogo da troca de UH: a UH atual, as UHs candidatas, o motivo e a tarifa das noites restantes. Fica aberto até
 * "Fechar" (desabilitado enquanto escreve). Nenhum id nem código cru.
 */
@Composable
fun RoomMoveDialog(
    state: RoomMoveState,
    onClose: () -> Unit,
) {
    LaunchedEffect(state) { state.start() }
    val done = state.done
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("room-move-dialog"),
        title = { Text("Trocar UH") },
        text = {
            Column(
                Modifier.heightIn(max = DIALOG_MAX_BODY).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (done != null) {
                    Text(done, modifier = Modifier.testTag("room-move-done"))
                } else {
                    MoveForm(state)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClose,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("room-move-close"),
                ) { Text("Fechar") }
                if (done == null) {
                    Button(
                        onClick = state::confirm,
                        enabled = state.canConfirm,
                        modifier = Modifier.testTag("room-move-confirm"),
                    ) { Text("Trocar UH") }
                }
            }
        },
    )
}

@Composable
private fun MoveForm(state: RoomMoveState) {
    val enabled = !state.busy && !state.loading
    state.currentRoomNumber?.let { Text("UH atual: $it") }
    Text("UH", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    if (state.loading) Text("Buscando UHs...")
    state.rooms.forEachIndexed { index, room ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(room.number, Modifier.weight(1f))
            OutlinedButton(
                onClick = { state.room = room },
                enabled = enabled && room != state.room,
                modifier = Modifier.testTag("room-move-choose-$index"),
            ) { Text("Escolher") }
        }
    }
    Text("Motivo", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MOVE_REASON_ORDER.forEach { value ->
            FilterChip(
                selected = state.reason == value,
                onClick = { state.reason = value },
                label = { Text(ReceptionText.assignmentReason(value)) },
                enabled = enabled,
                modifier = Modifier.testTag("room-move-reason-$value"),
            )
        }
    }
    Text(
        "Tarifa das noites restantes",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MOVE_TARIFF_ORDER.forEach { value ->
            FilterChip(
                selected = state.tariff == value,
                onClick = { state.tariff = value },
                label = { Text(ActionText.tariff(value)) },
                enabled = enabled,
                modifier = Modifier.testTag("room-move-tariff-$value"),
            )
        }
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("room-move-error"))
    }
}

/** Diálogo do desfazer check-in; [reservationCode] é o código que o detalhe mostra, nunca o id. */
@Composable
fun UndoCheckInDialog(
    state: UndoCheckInState,
    reservationCode: String,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.widthIn(max = DIALOG_WIDTH).testTag("undo-dialog"),
        title = { Text("Desfazer check-in") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.done) {
                    Text("Check-in desfeito.", modifier = Modifier.testTag("undo-done"))
                } else {
                    Text(
                        "Desfazer o check-in da reserva $reservationCode? " +
                            "A reserva volta a confirmada e a UH fica vaga.",
                    )
                    Text("A caução e o early lançados ficam na conta: libere e estorne pela conta da reserva.")
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("undo-error"))
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClose,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("undo-close"),
                ) { Text("Fechar") }
                if (!state.done) {
                    Button(
                        onClick = state::confirm,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("undo-confirm"),
                    ) { Text("Desfazer") }
                }
            }
        },
    )
}

private val MOVE_PERMISSIONS = setOf("frontdesk.stays.move", "rooms.status.read", "reservations.reservations.read")
private const val IN_HOUSE_STATUS = "IN_HOUSE"

/**
 * Os botões "Trocar UH" e "Desfazer check-in" do detalhe e os diálogos que eles abrem. Os botões só existem com a
 * reserva em casa, a fábrica ligada e as permissões da ação; o servidor continua decidindo.
 */
internal class StayDialogs(
    private val roomMove: ((String) -> RoomMoveState)?,
    private val undoCheckIn: ((String) -> UndoCheckInState)?,
) {
    private var move by mutableStateOf<RoomMoveState?>(null)
    private var undo by mutableStateOf<Pair<UndoCheckInState, String>?>(null)

    fun moveAction(
        detail: ReservationDetailState,
        permissions: Set<String>,
    ): (() -> Unit)? {
        val stay = detail.reservation?.reservation?.takeIf { it.status == IN_HOUSE_STATUS }
        return if (stay != null && roomMove != null && permissions.containsAll(MOVE_PERMISSIONS)) {
            { move = roomMove.invoke(stay.id.toString()) }
        } else {
            null
        }
    }

    fun undoAction(
        detail: ReservationDetailState,
        permissions: Set<String>,
    ): (() -> Unit)? {
        val stay = detail.reservation?.reservation?.takeIf { it.status == IN_HOUSE_STATUS }
        return if (stay != null && undoCheckIn != null && "frontdesk.stays.checkin" in permissions) {
            { undo = undoCheckIn.invoke(stay.id.toString()) to stay.code }
        } else {
            null
        }
    }

    @Composable
    fun Show(detail: ReservationDetailState) {
        move?.let { state ->
            RoomMoveDialog(state) {
                state.close()
                move = null
                detail.reload()
            }
        }
        undo?.let { (state, code) ->
            UndoCheckInDialog(state, code) {
                state.close()
                undo = null
                detail.reload()
            }
        }
    }
}
