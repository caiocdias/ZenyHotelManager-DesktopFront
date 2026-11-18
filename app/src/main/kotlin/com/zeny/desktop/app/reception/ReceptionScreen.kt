package com.zeny.desktop.app.reception

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
import com.zeny.desktop.app.folio.FolioWiring
import com.zeny.desktop.app.folio.ReservationFolioDialog
import com.zeny.desktop.app.traces.TraceFormDialog
import com.zeny.desktop.app.traces.TraceFormState
import com.zeny.desktop.app.traces.TraceRow
import com.zeny.desktop.app.traces.TraceTarget
import com.zeny.desktop.app.traces.TracesListState
import com.zeny.desktop.app.traces.TracesPanel
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.design.feedback.ErrorState
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.table.DataTable
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

private const val EMPTY_LIST = "Nenhuma reserva nesta lista."
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
    val form: (TraceTarget, () -> Unit) -> TraceFormState,
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
    registrationCard: ((reservationId: String, code: String) -> RegistrationCardState)? = null,
    folio: FolioWiring? = null,
) {
    val currentCard by rememberUpdatedState(registrationCard)
    val card = rememberRegistrationCard(detail) { currentCard }
    val checkInUi = remember { CheckInUi() }
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
    val folioUi = remember(detail) { FolioDialogUi(detail) }
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
                ReservationDetailPanel(
                    detail,
                    DetailActions(
                        onCheckIn = open?.let { { checkInUi.open(it, checkIn) } },
                        onMove = stay.moveAction(detail, panel.permissions),
                        onUndo = stay.undoAction(detail, panel.permissions),
                        onCard = card.action(panel.permissions),
                        onFolio = folioUi.action(folio),
                    ),
                    traces = { tracesUi.Section(detail) },
                )
            }
        }
    }
    checkInUi.Show(detail, card)
    card.Show()
    stay.Show(detail)
    tracesUi.ShowForm()
    folioUi.Show(folio)
    walkInState?.let { state ->
        WalkInDialog(state) {
            state.close()
            walkInState = null
            panel.refresh()
        }
    }
}

/** O check-in aberto e a reserva (id e código) em que abriu: o atalho da ficha usa esta, mesmo que o detalhe mude. */
@Stable
private class CheckInUi {
    private var state by mutableStateOf<CheckInState?>(null)
    private var target by mutableStateOf<CardTarget?>(null)

    fun open(
        reservation: ReservationDetailResponse,
        make: ((CheckInSeed) -> CheckInState)?,
    ) {
        target = CardTarget(reservation.reservation.id.toString(), reservation.reservation.code)
        state = make?.invoke(reservation.toCheckInSeed())
    }

    @Composable
    fun Show(
        detail: ReservationDetailState,
        card: RegistrationCardUi,
    ) {
        val open = state ?: return
        val cardTarget = target
        val close = {
            open.close()
            state = null
            target = null
            detail.reload()
        }
        CheckInDialog(
            open,
            onClose = close,
            onOpenCard =
                cardTarget?.takeIf { card.available }?.let { t ->
                    {
                        close()
                        card.open(t)
                    }
                },
        )
    }
}

/** A reserva (id e código) de que a conta foi pedida e a reserva do detalhe em que o diálogo abriu. */
private class OpenFolio(
    val ownerId: String?,
    val reservationId: String,
    val code: String,
)

/**
 * O diálogo "Conta" aberto. Ele é da reserva que estava aberta no detalhe ao pedir: trocar de reserva, fechar o detalhe
 * ou sair da composição (área, sessão) o fecha, e o diálogo encerra os estados dele. Sem o portador, nada.
 */
@Stable
private class FolioDialogUi(
    private val detail: ReservationDetailState,
) {
    private var current by mutableStateOf<OpenFolio?>(null)

    fun action(folio: FolioWiring?): (() -> Unit)? {
        val reservation = detail.reservation?.reservation?.takeIf { folio != null }
        return reservation?.let { { current = OpenFolio(detail.openId, it.id.toString(), it.code) } }
    }

    @Composable
    fun Show(folio: FolioWiring?) {
        val shown = current?.takeIf { it.ownerId == detail.openId && folio != null }
        DisposableEffect(shown) {
            onDispose { if (current === shown) current = null }
        }
        if (shown != null && folio != null) {
            ReservationFolioDialog(folio, shown.reservationId, shown.code) { current = null }
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
                    onClick = {
                        if (!tracesUi.tabOpen) {
                            tracesUi.tabOpen = true
                            tracesUi.refresh()
                        }
                    },
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
            onNew = if (list.canCreate) ({ form = traces?.form?.invoke(TraceTarget.ForRoom, ::refresh) }) else null,
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
                        form = traces?.form?.invoke(target, ::refresh)
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
                state.close()
                form = null
            }
        }
    }

    private fun edit(row: TraceRow) {
        form = traces?.form?.invoke(TraceTarget.Existing(row.id), ::refresh)
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
