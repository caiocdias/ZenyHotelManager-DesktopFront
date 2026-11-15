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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.app.traces.TraceFormDialog
import com.zeny.desktop.app.traces.TraceFormState
import com.zeny.desktop.app.traces.TraceRow
import com.zeny.desktop.app.traces.TraceTarget
import com.zeny.desktop.app.traces.TracesListState
import com.zeny.desktop.app.traces.TracesPanel
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.design.feedback.ErrorState
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.feedback.StatusChip
import com.zeny.desktop.design.feedback.errorMessage
import com.zeny.desktop.design.table.DataTable
import com.zeny.desktop.design.theme.ZenyColors
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

private const val EMPTY_LIST = "Nenhuma reserva nesta lista."
private val DETAIL_WIDTH = 380.dp
private const val TRACES_READ = "frontdesk.traces.read"
private const val TRACES_TAB = "TRACES"
private val WALK_IN_PERMISSIONS =
    listOf(
        "frontdesk.stays.checkin",
        "reservations.reservations.manage",
        "reservations.assignments.manage",
        "reservations.availability.read",
        "reservations.reservations.read",
        "rooms.status.read",
        "guests.profiles.read",
    )

/**
 * Os recados da recepção: a lista da aba "Recados" (já iniciada por quem a criou), a fábrica da lista de uma reserva
 * (a tela a inicia ao abrir a reserva e a fecha ao trocar ou fechar) e a fábrica do formulário.
 */
class ReceptionTraces(
    val list: TracesListState,
    val forReservation: (String) -> TracesListState,
    val form: (TraceTarget) -> TraceFormState,
)

/**
 * Painel do dia da recepção: a data operacional, as três listas em abas e o detalhe da reserva escolhida ao lado.
 * Dado pessoal (titular, documento mascarado, alertas) só aparece no detalhe, como o estado o entrega: nenhum id.
 */
@Composable
@Suppress("LongParameterList") // as fábricas de diálogo são opcionais, com padrão nulo
fun ReceptionScreen(
    panel: DayPanelState,
    detail: ReservationDetailState,
    checkIn: ((CheckInSeed) -> CheckInState)? = null,
    walkIn: (() -> WalkInState)? = null,
    roomMove: ((String) -> RoomMoveState)? = null,
    undoCheckIn: ((String) -> UndoCheckInState)? = null,
    traces: ReceptionTraces? = null,
) {
    var checkInState by remember { mutableStateOf<CheckInState?>(null) }
    var walkInState by remember { mutableStateOf<WalkInState?>(null) }
    val openWalkIn: (() -> Unit)? =
        walkIn
            ?.takeIf {
                WALK_IN_PERMISSIONS.all { p ->
                    p in panel.permissions
                }
            }?.let { make -> { walkInState = make() } }
    // As fábricas são lambdas novas a cada recomposição da área: o holder dos diálogos abertos não pode depender delas.
    val currentMove by rememberUpdatedState(roomMove)
    val currentUndo by rememberUpdatedState(undoCheckIn)
    val stay = remember { StayDialogs({ currentMove }, { currentUndo }) }
    val tracesUi = rememberReceptionTracesUi(traces, detail)
    val canCheckIn =
        checkIn != null &&
            detail.reservation?.reservation?.status == "CONFIRMED" &&
            "frontdesk.stays.checkin" in panel.permissions
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(panel, detail, onWalkIn = openWalkIn, onRefreshTraces = tracesUi::refresh)
        val error = panel.error
        if (error != null && panel.day != null) ErrorBand(error, panel::refresh, "panel-error")
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) { Body(panel, detail, tracesUi) }
            if (detail.openId != null) {
                val open = detail.reservation?.takeIf { canCheckIn }
                DetailPanel(
                    detail,
                    open?.let { { checkInState = checkIn?.invoke(it.toCheckInSeed()) } },
                    stay.moveAction(detail, panel.permissions),
                    stay.undoAction(detail, panel.permissions),
                    tracesUi,
                )
            }
        }
    }
    checkInState?.let { state ->
        CheckInDialog(state) {
            state.close()
            checkInState = null
            detail.reload()
        }
    }
    stay.Show(detail)
    tracesUi.ShowForm()
    walkInState?.let { state ->
        WalkInDialog(state) {
            state.close()
            walkInState = null
            panel.refresh()
        }
    }
}

@Composable
private fun Header(
    panel: DayPanelState,
    detail: ReservationDetailState,
    onWalkIn: (() -> Unit)?,
    onRefreshTraces: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Recepção", style = MaterialTheme.typography.titleLarge)
        panel.day?.let { Text("Data operacional: ${ReceptionText.date(it.date.toApi())}") }
        OutlinedButton(
            onClick = {
                panel.refresh()
                detail.reload()
                onRefreshTraces()
            },
        ) { Text("Atualizar") }
        if (onWalkIn != null) {
            Button(onClick = onWalkIn, modifier = Modifier.testTag("open-walk-in")) { Text("Walk-in") }
        }
    }
}

/** A falha de uma releitura com o conteúdo antigo à vista: o conteúdo fica, e a falha aparece por cima dele. */
@Composable
private fun ErrorBand(
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

/** A tabela só entra com a data operacional: antes dela, o carregando ou o erro da data. */
@Composable
private fun Body(
    panel: DayPanelState,
    detail: ReservationDetailState,
    tracesUi: ReceptionTracesUi,
) {
    val error = panel.error
    when {
        panel.day != null -> Lists(panel, detail, tracesUi)
        error != null -> ErrorState(error, onRetry = panel::refresh)
        else -> LoadingState()
    }
}

@Composable
private fun Lists(
    panel: DayPanelState,
    detail: ReservationDetailState,
    tracesUi: ReceptionTracesUi,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayList.entries.forEach { list ->
                FilterChip(
                    selected = !tracesUi.tabOpen && panel.selectedTab == list,
                    onClick = {
                        tracesUi.tabOpen = false
                        panel.select(list)
                    },
                    label = { Text(panel.tabLabel(list)) },
                    modifier = Modifier.testTag("tab-${list.name}"),
                )
            }
            if (tracesUi.canRead) {
                FilterChip(
                    selected = tracesUi.tabOpen,
                    onClick = { tracesUi.tabOpen = true },
                    label = { Text("Recados") },
                    modifier = Modifier.testTag("tab-$TRACES_TAB"),
                )
            }
        }
        if (tracesUi.tabOpen) {
            Box(Modifier.weight(1f)) { tracesUi.Tab(detail) }
        } else {
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
}

@Composable
private fun DetailPanel(
    detail: ReservationDetailState,
    onCheckIn: (() -> Unit)?,
    onMove: (() -> Unit)?,
    onUndo: (() -> Unit)?,
    tracesUi: ReceptionTracesUi,
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
        if (onCheckIn != null) {
            Button(onClick = onCheckIn, modifier = Modifier.testTag("open-check-in")) { Text("Fazer check-in") }
        }
        if (onMove != null) {
            Button(onClick = onMove, modifier = Modifier.testTag("open-room-move")) { Text("Trocar UH") }
        }
        if (onUndo != null) {
            OutlinedButton(onClick = onUndo, modifier = Modifier.testTag("open-undo-check-in")) {
                Text("Desfazer check-in")
            }
        }
        if (view != null && error != null) ErrorBand(error, detail::reload, "detail-error")
        Box(Modifier.weight(1f)) {
            when {
                view != null -> DetailBody(view, detail::reload) { tracesUi.Section(detail) }
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

/**
 * O que a recepção guarda dos recados: se a aba está aberta, a lista da seção da reserva aberta e o formulário. A
 * seção nasce com a reserva aberta e fecha quando ela troca ou o detalhe fecha; o formulário é o diálogo aberto.
 */
@Stable
internal class ReceptionTracesUi(
    private val tracesOf: () -> ReceptionTraces?,
) {
    /** A lista da reserva aberta, posta pela composição antes de desenhar os filhos. */
    var section: TracesListState? = null

    var tabOpen by mutableStateOf(false)
    private val traces: ReceptionTraces? get() = tracesOf()
    private var form by mutableStateOf<TraceFormState?>(null)

    val canRead: Boolean get() = traces?.list?.permissions?.contains(TRACES_READ) == true

    fun refresh() {
        traces?.list?.refresh()
        section?.refresh()
    }

    @Composable
    fun Tab(detail: ReservationDetailState) {
        val list = traces?.list ?: return
        TracesPanel(
            list = list,
            onNew = if (list.canCreate) ({ form = traces?.form?.invoke(TraceTarget.ForRoom) }) else null,
            onEdit = ::edit,
            onOpenReservation = detail::open,
        )
    }

    @Composable
    fun Section(detail: ReservationDetailState) {
        val list = section ?: return
        if (!canRead) return
        val reservation = detail.reservation?.reservation
        Text("Recados", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        TracesPanel(
            list = list,
            onNew =
                if (reservation != null && list.canCreate) {
                    {
                        val target = TraceTarget.ForReservation(reservation.id.toString(), reservation.code)
                        form = traces?.form?.invoke(target)
                    }
                } else {
                    null
                },
            onEdit = ::edit,
            onOpenReservation = null,
            compact = true,
        )
    }

    @Composable
    fun ShowForm() {
        form?.let { state ->
            TraceFormDialog(state) {
                if (state.saved) refresh()
                state.close()
                form = null
            }
        }
    }

    private fun edit(row: TraceRow) {
        form = traces?.form?.invoke(TraceTarget.Existing(row.id))
    }
}

/**
 * Cria o [ReceptionTracesUi]. A lista da seção nasce (e inicia) quando uma reserva abre e há leitura de recados,
 * e fecha quando a reserva troca, o detalhe fecha ou a tela sai. As fábricas são lambdas novas a cada recomposição
 * da área: só a última é lida.
 */
@Composable
internal fun rememberReceptionTracesUi(
    traces: ReceptionTraces?,
    detail: ReservationDetailState,
): ReceptionTracesUi {
    val current by rememberUpdatedState(traces)
    val readable = traces != null && TRACES_READ in traces.list.permissions
    val openId = detail.openId.takeIf { readable }
    val section = remember(openId) { openId?.let { id -> current?.forReservation?.invoke(id) } }
    DisposableEffect(section) {
        section?.start()
        onDispose { section?.close() }
    }
    val ui = remember(traces?.list) { ReceptionTracesUi { current } }
    ui.section = section
    // Resolver numa lista atualiza a outra: a aba e a seção mostram os mesmos recados.
    traces?.list?.onResolved = { section?.refresh() }
    section?.onResolved = { traces?.list?.refresh() }
    val window = LocalWindowInfo.current
    LaunchedEffect(ui, window) {
        // O ganho de foco relê a aba e a seção; o estado inicial (já carregado agora) não conta.
        snapshotFlow { window.isWindowFocused }.drop(1).filter { it }.collect { ui.refresh() }
    }
    return ui
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
