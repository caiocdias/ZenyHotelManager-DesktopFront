@file:Suppress("TooManyFunctions") // detekt: a casca reúne a raiz e uma composição por área

package com.zeny.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.approval.LocalApprovalCoordinator
import com.zeny.desktop.app.approval.LocalApprovalCoordinatorOrNull
import com.zeny.desktop.app.cashier.CashierScreen
import com.zeny.desktop.app.cashier.CashierState
import com.zeny.desktop.app.folio.FolioAccounts
import com.zeny.desktop.app.folio.FolioListScreen
import com.zeny.desktop.app.folio.FolioSource
import com.zeny.desktop.app.folio.FolioWiring
import com.zeny.desktop.app.folio.pdfKind
import com.zeny.desktop.app.folio.statementCards
import com.zeny.desktop.app.login.LoginScreen
import com.zeny.desktop.app.login.LoginState
import com.zeny.desktop.app.login.PasswordChangeScreen
import com.zeny.desktop.app.login.PasswordChangeState
import com.zeny.desktop.app.reception.CheckInSeed
import com.zeny.desktop.app.reception.CheckInState
import com.zeny.desktop.app.reception.DayPanelState
import com.zeny.desktop.app.reception.PdfKind
import com.zeny.desktop.app.reception.ReceptionScreen
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reception.ReceptionTraces
import com.zeny.desktop.app.reception.RegistrationCardState
import com.zeny.desktop.app.reception.RegistrationCards
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.app.reception.RoomMoveState
import com.zeny.desktop.app.reception.UndoCheckInState
import com.zeny.desktop.app.reception.WalkInState
import com.zeny.desktop.app.reservations.AvailabilityScreen
import com.zeny.desktop.app.reservations.AvailabilitySource
import com.zeny.desktop.app.reservations.AvailabilityState
import com.zeny.desktop.app.reservations.NewReservationState
import com.zeny.desktop.app.reservations.PendingListsState
import com.zeny.desktop.app.reservations.PendingSource
import com.zeny.desktop.app.reservations.PendingTab
import com.zeny.desktop.app.reservations.QuoteState
import com.zeny.desktop.app.reservations.QuotedStay
import com.zeny.desktop.app.reservations.ReservationCancelState
import com.zeny.desktop.app.reservations.ReservationChangeState
import com.zeny.desktop.app.reservations.ReservationConfirmState
import com.zeny.desktop.app.reservations.ReservationReactivateState
import com.zeny.desktop.app.reservations.ReservationSearchState
import com.zeny.desktop.app.reservations.ReservationsActions
import com.zeny.desktop.app.reservations.ReservationsScreen
import com.zeny.desktop.app.reservations.asCards
import com.zeny.desktop.app.reservations.rememberNewReservationHost
import com.zeny.desktop.app.rooms.RoomMapScreen
import com.zeny.desktop.app.rooms.RoomMapState
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.app.shell.ShellScreen
import com.zeny.desktop.app.shell.ShellState
import com.zeny.desktop.app.traces.TraceFormState
import com.zeny.desktop.app.traces.TracesListState
import com.zeny.desktop.app.traces.TracesSource
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.approval.ApprovalDialog
import com.zeny.desktop.design.feedback.EmptyState
import com.zeny.desktop.design.pdf.FileDialogSaveChooser
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.SystemPrintDialog
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.awt.EventQueue
import java.awt.Frame
import java.time.Clock
import java.util.concurrent.Executor

/** A janela do app, pai do diálogo nativo "Salvar como…" da ficha; nula fora da janela real (testes). */
val LocalWindowFrame = staticCompositionLocalOf<Frame?> { null }

/** Casca de desenvolvimento (áreas "(em breve)"): só com `ZENY_DEV_SHELL=1` ou `-Dzeny.devShell=true`. */
fun isShellDevMode(
    env: (String) -> String? = System::getenv,
    prop: (String) -> String? = System::getProperty,
): Boolean = env("ZENY_DEV_SHELL") == "1" || prop("zeny.devShell") == "true"

/**
 * Desenha a tela do `AppController`. Os estados das telas nascem a cada visita, com o escopo da raiz: a troca
 * de tela que o próprio login provoca não cancela o login em voo. Na casca, `LocalApprovalCoordinator` dá às
 * features o coordenador de aprovação da sessão.
 */
@Composable
fun AppRoot(
    controller: AppController,
    devMode: Boolean,
) = AppRoot(controller, devMode, insideShell = {})

/** `insideShell` é desenhado dentro da casca, com o `LocalApprovalCoordinator` da sessão (os testes o usam). */
@Composable
internal fun AppRoot(
    controller: AppController,
    devMode: Boolean,
    insideShell: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val screen by controller.screen.collectAsState()
    Surface(Modifier.fillMaxSize()) {
        when (screen) {
            Screen.Login -> {
                val state =
                    remember { LoginState(controller, controller.initialServer, controller.initialLogin, scope) }
                LoginScreen(state, controller.notice)
            }

            Screen.PasswordChange -> {
                val state =
                    remember {
                        PasswordChangeState(
                            change = { current, new -> controller.requireConnection().changePassword(current, new) },
                            retry = { controller.requireConnection().retryPasswordChangeRefresh() },
                            awaitingRefresh = {
                                val session = controller.connection?.state?.value
                                session is SessionState.Active && session.passwordChangeAwaitingRefresh
                            },
                            scope = scope,
                        )
                    }
                PasswordChangeScreen(state, onLogout = { scope.launch { controller.logout() } })
            }

            Screen.Shell -> {
                val connection = remember { controller.requireConnection() }
                // A casca e a aprovação são da sessão vigente: outra sessão, mesmo sem passar pelo login, as recria
                // e nunca mostra o nome nem o menu do usuário anterior. A área aberta segue só se o menu novo a tem.
                val sessionId by remember(connection) { connection.activeSessionIds() }
                    .collectAsState(connection.state.value.activeSessionId())
                val previous = remember { PreviousShell() }
                val state =
                    remember(sessionId) {
                        ShellState(connection, devMode, scope, restoreArea = previous.shell?.selected)
                            .also { previous.shell = it }
                    }
                val approvals = remember(sessionId) { sessionApprovals(controller, scope) }
                // Sair da casca (logout, sessão encerrada) cancela a carga em voo e a aprovação pendente.
                DisposableEffect(state, approvals) {
                    onDispose {
                        state.close()
                        approvals.close()
                    }
                }
                CompositionLocalProvider(
                    LocalApprovalCoordinator provides approvals,
                    LocalApprovalCoordinatorOrNull provides approvals,
                ) {
                    // A área recebe o conjunto lido por esta casca e o `sessionId` dela (ADR 0013); a de caixa relê o
                    // turno do topo depois de abrir ou fechar um turno.
                    ShellScreen(state, onLogout = { scope.launch { controller.logout() } }) { area, permissions ->
                        AreaContent(area, connection, sessionId, permissions, state::refreshShift) {
                            state.select(Area.ACCOUNTS)
                        }
                    }
                    insideShell()
                    ApprovalHost(approvals)
                }
            }
        }
    }
}

/** A casca da sessão anterior, lida só ao criar a da seguinte (não é estado da composição). */
private class PreviousShell {
    var shell: ShellState? = null
}

/** O `sessionId` de cada sessão ativa, sem repetir; a saída da sessão não emite (a tela já deixa a casca). */
private fun ServerConnection.activeSessionIds(): Flow<String?> =
    state.mapNotNull { it.activeSessionId() }.distinctUntilChanged()

private fun SessionState.activeSessionId(): String? = (this as? SessionState.Active)?.sessionId

/**
 * A tela da área aberta na casca: a Recepção é o painel do dia; as Reservas, a busca de reservas; Contas e caixa, o
 * turno de caixa e, com `billing.folios.read`, a aba das contas abertas; a Governança, o mapa de UHs; as demais ainda
 * mostram só o nome. [sessionId] e [permissions] são os da casca (ADR 0013): a Recepção, as Reservas e o caixa, que os
 * recebem, só desenham quando a sessão ativa da conexão é a mesma [sessionId]. [onShiftChanged] é o que o caixa chama
 * quando o turno muda (a casca relê o topo). [onOpenShift] é o atalho "Abrir turno" da conta na Recepção e nas Reservas
 * (a casca abre "Contas e caixa"); dentro de "Contas e caixa", o atalho troca para a aba "Caixa".
 */
@Composable
@Suppress("LongParameterList") // a área, o que a casca entrega e os dois avisos dela
internal fun AreaContent(
    area: Area,
    connection: ServerConnection,
    sessionId: String?,
    permissions: Set<String>,
    onShiftChanged: () -> Unit = {},
    onOpenShift: () -> Unit = {},
) {
    when (area) {
        Area.RECEPTION -> ReceptionArea(connection, sessionId, permissions, onOpenShift)
        Area.RESERVATIONS -> ReservationsArea(connection, sessionId, permissions, onOpenShift)
        Area.ACCOUNTS -> CashierArea(connection, sessionId, permissions, onShiftChanged)
        Area.HOUSEKEEPING -> HousekeepingMap(connection)
        else -> EmptyState(area.label)
    }
}

/**
 * O turno de caixa e, com o portador da conta, a aba "Contas" vivem enquanto a área está aberta na mesma sessão: nascem
 * com a chave do [sessionId] e das [permissions] da casca e fecham ao sair da área, da casca ou da sessão (o fechamento
 * descarta a leitura ou a abertura em voo). A aba "Caixa" continua a primeira e a padrão; a escolhida é lembrada aqui,
 * com o [sessionId], para o atalho "Abrir turno" da conta voltar a ela.
 * Enquanto a sessão ativa da conexão não é a [sessionId] da casca, o quadro fica vazio: as [permissions] de uma sessão
 * nunca valem na outra.
 */
@Composable
private fun CashierArea(
    connection: ServerConnection,
    sessionId: String?,
    permissions: Set<String>,
    onShiftChanged: () -> Unit,
) {
    val source = remember(connection) { connection.cashierSource() }
    val session by connection.state.collectAsState()
    val activeSessionId = (session as? SessionState.Active)?.sessionId
    val scope = rememberCoroutineScope()
    val notify by rememberUpdatedState(onShiftChanged)
    when {
        source == null -> {
            Unavailable("Caixa indisponível nesta conexão.")
        }

        sessionId != null && sessionId == activeSessionId -> {
            val cashier =
                remember(source, sessionId, permissions) { CashierState(source, scope, permissions) { notify() } }
            // Um efeito por holder, com a chave dele, como na Recepção.
            DisposableEffect(cashier) {
                cashier.start()
                onDispose { cashier.close() }
            }
            var tab by remember(sessionId) { mutableStateOf(AccountsTab.CASHIER) }
            val wiring = rememberFolioWiring(connection, sessionId, permissions, scope) { tab = AccountsTab.CASHIER }
            val folios = wiring?.let { remember(it.source, sessionId, permissions) { FolioAccounts(it, scope) } }
            DisposableEffect(folios) { onDispose { folios?.close() } }
            if (folios == null) CashierScreen(cashier) else AccountsTabs(cashier, folios, tab) { tab = it }
        }
    }
}

private enum class AccountsTab(
    val label: String,
) {
    CASHIER("Caixa"),
    FOLIOS("Contas"),
}

/** As abas da área: "Caixa" (a primeira e a padrão) e "Contas". Os estados e a aba escolhida vivem na área. */
@Composable
private fun AccountsTabs(
    cashier: CashierState,
    folios: FolioAccounts,
    selected: AccountsTab,
    onSelect: (AccountsTab) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selected.ordinal) {
            AccountsTab.entries.forEach {
                Tab(selected = selected == it, onClick = { onSelect(it) }, text = { Text(it.label) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (selected) {
                AccountsTab.CASHIER -> CashierScreen(cashier)
                AccountsTab.FOLIOS -> FolioListScreen(folios)
            }
        }
    }
}

/**
 * O portador da conta de uma área, lembrado com a chave da porta, do [sessionId] e das [permissions] da casca (E06):
 * recompor a área não o recria. Nulo sem a porta ou sem `billing.folios.read`. O [openShift] mais recente é lido na
 * hora do atalho, sem entrar na chave.
 */
@Composable
private fun rememberFolioWiring(
    connection: ServerConnection,
    sessionId: String,
    permissions: Set<String>,
    scope: CoroutineScope,
    openShift: () -> Unit,
): FolioWiring? {
    val source = remember(connection) { connection.folioSource() }
    val approval = LocalApprovalCoordinatorOrNull.current
    val window = LocalWindowFrame.current
    val latestOpenShift by rememberUpdatedState(openShift)
    return remember(source, sessionId, permissions) {
        folioWiring(connection, permissions, scope, approval, window) { latestOpenShift() }
    }
}

/**
 * O painel do dia e o detalhe da reserva vivem enquanto a Recepção está aberta na mesma sessão, como o mapa de UHs:
 * o painel nasce com a chave do [sessionId] da casca e das [permissions] dela (o cache de titulares é da sessão), o
 * detalhe só com a do [sessionId], e cada um fecha ao sair da área, da casca ou da sessão (o painel também com outro
 * conjunto), o que cancela a assinatura de tempo real. Enquanto a sessão ativa da conexão
 * não é a [sessionId] da casca, o quadro fica vazio de propósito: as [permissions] de uma sessão nunca valem na outra,
 * e uma assinatura aberta sem sessão esperaria pela próxima. O painel, o check-in, o walk-in e os recados usam o
 * conjunto da casca (ADR 0013). O ganho de foco da janela atualiza o painel e relê a reserva aberta; a perda só o
 * registra no painel.
 */
@Composable
private fun ReceptionArea(
    connection: ServerConnection,
    sessionId: String?,
    permissions: Set<String>,
    onOpenShift: () -> Unit,
) {
    val source = remember(connection) { connection.receptionSource() }
    val session by connection.state.collectAsState()
    val activeSessionId = (session as? SessionState.Active)?.sessionId
    val scope = rememberCoroutineScope()
    when {
        source == null -> {
            Unavailable("Recepção indisponível nesta conexão.")
        }

        sessionId != null && sessionId == activeSessionId -> {
            val panel = remember(source, sessionId, permissions) { DayPanelState(source, scope, permissions) }
            val detail = remember(source, sessionId) { ReservationDetailState(source, scope) }
            // Um efeito por holder, com a chave dele: o conjunto novo fecha só o painel, e o detalhe da sessão segue.
            DisposableEffect(panel) { onDispose { panel.close() } }
            DisposableEffect(detail) { onDispose { detail.close() } }
            val traces = receptionTraces(connection.tracesSource(), sessionId, permissions, scope)
            val window = LocalWindowInfo.current
            LaunchedEffect(panel, detail, window) {
                snapshotFlow { window.isWindowFocused }.collect { focused ->
                    panel.onWindowFocus(focused)
                    if (focused) detail.reload()
                }
            }
            // Fora da casca (sem coordenador) o check-in segue sem aprovação: o servidor decide se pede.
            val approval = LocalApprovalCoordinatorOrNull.current
            ReceptionScreen(
                panel,
                detail,
                checkIn = checkInFactory(source, scope, approval, panel, detail),
                walkIn = walkInFactory(source, scope, approval, panel),
                roomMove = roomMoveFactory(source, scope, approval, panel, detail),
                undoCheckIn = undoCheckInFactory(source, scope, approval, panel, detail),
                traces = traces,
                registrationCard = registrationCardFactory(connection, scope, LocalWindowFrame.current),
                folio = rememberFolioWiring(connection, sessionId, permissions, scope, onOpenShift),
                checkOut = remember(connection) { connection.checkOutSource() },
            )
        }
    }
}

/**
 * A busca de reservas e o detalhe somente leitura vivem enquanto a área está aberta na mesma sessão: nascem com a
 * chave do [sessionId] da casca (o cache de titulares é da sessão), a busca é iniciada ao entrar e os dois fecham ao
 * sair da área, da casca ou da sessão (o fechamento descarta a consulta, o cache e o documento mascarado). Enquanto a
 * sessão ativa da conexão não é a [sessionId] da casca, o quadro fica vazio: as [permissions] de uma sessão nunca
 * valem na outra. O detalhe usa a porta da Recepção; sem ela a tela funciona sem painel. O ganho de foco da janela
 * relê a reserva aberta, sem refazer a busca.
 */
@Composable
private fun ReservationsArea(
    connection: ServerConnection,
    sessionId: String?,
    permissions: Set<String>,
    onOpenShift: () -> Unit,
) {
    val source = remember(connection) { connection.reservationSearchSource() }
    val receptionSource = remember(connection) { connection.receptionSource() }
    val session by connection.state.collectAsState()
    val activeSessionId = (session as? SessionState.Active)?.sessionId
    val scope = rememberCoroutineScope()
    when {
        source == null -> {
            Unavailable("Reservas indisponíveis nesta conexão.")
        }

        sessionId != null && sessionId == activeSessionId -> {
            val search = remember(source, sessionId) { ReservationSearchState(source, scope) }
            val detail =
                remember(receptionSource, sessionId) { receptionSource?.let { ReservationDetailState(it, scope) } }
            // Um efeito por holder, com a chave dele, como na Recepção.
            DisposableEffect(search) {
                search.start()
                onDispose { search.close() }
            }
            DisposableEffect(detail) { onDispose { detail?.close() } }
            val window = LocalWindowInfo.current
            LaunchedEffect(detail, window) {
                snapshotFlow { window.isWindowFocused }.collect { focused ->
                    if (focused) detail?.reload()
                }
            }
            ReservationsContent(connection, search, detail, sessionId, permissions, scope, onOpenShift)
        }
    }
}

/**
 * A busca e, com as portas e as [permissions] da casca, as ações do detalhe, a aba de disponibilidade e cotação com a
 * nova reserva e a aba das pendências: as ações pedem a porta da Recepção e a aba de disponibilidade, a porta dela e
 * `reservations.availability.read`; a aba das pendências só pede a porta delas. Sem nenhuma aba, a área é só a busca.
 */
@Composable
@Suppress("LongParameterList") // os estados da área e o que a casca entrega
private fun ReservationsContent(
    connection: ServerConnection,
    search: ReservationSearchState,
    detail: ReservationDetailState?,
    sessionId: String,
    permissions: Set<String>,
    scope: CoroutineScope,
    onOpenShift: () -> Unit,
) {
    val receptionSource = remember(connection) { connection.receptionSource() }
    val availabilitySource = remember(connection) { connection.availabilitySource() }
    val availability = reservationsAvailability(availabilitySource, permissions, sessionId, scope)
    val pending = reservationsPending(remember(connection) { connection.pendingSource() }, sessionId, scope)
    val approval = LocalApprovalCoordinatorOrNull.current
    val frame = LocalWindowFrame.current
    val confirmation = remember(connection, scope, frame) { confirmationCardFactory(connection, scope, frame) }
    val folio = rememberFolioWiring(connection, sessionId, permissions, scope, onOpenShift)
    val refreshDetailAndTable: () -> Unit = {
        detail?.reload()
        scope.launch { search.table.refresh() }
    }
    // Depois de confirmar: o detalhe, a busca e as quatro listas de pendências (a disponibilidade não muda).
    val refreshAfterConfirm: () -> Unit = {
        refreshDetailAndTable()
        pending?.refresh()
    }
    // Depois de alterar, cancelar ou reativar: o mesmo e o calendário (a disponibilidade muda).
    val refreshAfterChange: () -> Unit = {
        refreshAfterConfirm()
        availability?.calendar?.reload()
    }
    val actions =
        receptionSource?.let {
            ReservationsActions(
                permissions,
                change = changeFactory(connection, scope, approval, permissions, refreshAfterChange),
                confirm = confirmFactory(connection, scope, refreshAfterConfirm),
                confirmation = confirmation,
                cancel = cancelFactory(connection, scope, approval, refreshAfterChange),
                reactivate = reactivateFactory(connection, scope, approval, refreshAfterChange),
                folio = folio,
            )
        }
    if (availability == null && pending == null) {
        ReservationsScreen(search, detail, actions)
    } else {
        val newReservation =
            availability?.let {
                newReservationFactory(connection, receptionSource, scope, approval, permissions) {
                    it.calendar.reload()
                    refreshDetailAndTable()
                }
            }
        ReservationsTabs(availability, pending, newReservation, confirmation, detail, actions) {
            ReservationsScreen(search, detail, actions)
        }
    }
}

private const val AVAILABILITY_READ = "reservations.availability.read"
private const val RESERVATIONS_MANAGE = "reservations.reservations.manage"
private const val FOLIOS_READ = "billing.folios.read"
private const val CASHIER_OPERATE = "billing.cashier.operate"

/** O calendário e a cotação de uma sessão; só existem com a permissão de leitura da disponibilidade. */
private class ReservationsAvailability(
    val calendar: AvailabilityState,
    val quote: QuoteState,
)

/**
 * A disponibilidade e a cotação da área Reservas, ou nulo se falta `reservations.availability.read` nas [permissions]
 * da casca ou se a conexão não tem a porta. Os dois estados nascem com a chave `(source, sessionId)` e fecham ao sair
 * da área.
 */
@Composable
private fun reservationsAvailability(
    source: AvailabilitySource?,
    permissions: Set<String>,
    sessionId: String,
    scope: CoroutineScope,
): ReservationsAvailability? {
    if (source == null || AVAILABILITY_READ !in permissions) return null
    val availability =
        remember(source, sessionId) {
            ReservationsAvailability(AvailabilityState(source, scope), QuoteState(source, scope))
        }
    DisposableEffect(availability) {
        onDispose {
            availability.calendar.close()
            availability.quote.close()
        }
    }
    return availability
}

/**
 * As pendências da sessão: o [state] e se a aba já o iniciou. [refresh] recarrega as quatro listas, porque a reserva
 * de uma ação pode estar em mais de uma, e só depois do [start]: sem a aba aberta, nada é lido (a entrada na aba
 * carrega as quatro listas de qualquer modo). A marca é só deste guarda; a tela não a lê.
 */
private class ReservationsPending(
    val state: PendingListsState,
) {
    private var started = false

    fun start() {
        started = true
        state.start()
    }

    fun refresh() {
        if (started) state.refreshAll()
    }
}

/**
 * As pendências da área Reservas, ou nulas sem a porta da conexão. O estado nasce com a chave `(source, sessionId)` e
 * fecha ao sair da área; quem o inicia é a aba, ao ser aberta. Não depende da porta da Recepção nem de permissão lida
 * aqui: o menu da área já exige `reservations.reservations.read`.
 */
@Composable
private fun reservationsPending(
    source: PendingSource?,
    sessionId: String,
    scope: CoroutineScope,
): ReservationsPending? {
    if (source == null) return null
    val pending =
        remember(source, sessionId) {
            ReservationsPending(PendingListsState(source, scope, Clock.systemUTC()::instant))
        }
    DisposableEffect(pending) { onDispose { pending.state.close() } }
    return pending
}

/** A alteração de reserva do detalhe, ou nula sem a porta; a aprovação é a do coordenador da sessão. */
private fun changeFactory(
    connection: ServerConnection,
    scope: CoroutineScope,
    approval: ApprovalCoordinator?,
    permissions: Set<String>,
    onChanged: () -> Unit,
): ((String) -> ReservationChangeState)? {
    val source = connection.reservationChangeSource() ?: return null
    val canQuote = AVAILABILITY_READ in permissions
    return { id -> ReservationChangeState(source, scope, id, canQuote, { approveAction(approval, it) }, onChanged) }
}

/** A confirmação da pré-reserva do detalhe, ou nula sem a porta (a confirmação não pede aprovação). */
private fun confirmFactory(
    connection: ServerConnection,
    scope: CoroutineScope,
    onChanged: () -> Unit,
): ((String) -> ReservationConfirmState)? {
    val source = connection.reservationConfirmSource() ?: return null
    return { id -> ReservationConfirmState(source, scope, id, onChanged) }
}

/** O cancelamento da reserva do detalhe, ou nulo sem a porta; a aprovação (da isenção da multa) é a da sessão. */
private fun cancelFactory(
    connection: ServerConnection,
    scope: CoroutineScope,
    approval: ApprovalCoordinator?,
    onChanged: () -> Unit,
): ((String) -> ReservationCancelState)? {
    val source = connection.reservationCancelSource() ?: return null
    return { id -> ReservationCancelState(source, scope, id, { approveAction(approval, it) }, onChanged) }
}

/** A reativação da reserva do detalhe, ou nula sem a porta; a aprovação é a do coordenador da sessão. */
private fun reactivateFactory(
    connection: ServerConnection,
    scope: CoroutineScope,
    approval: ApprovalCoordinator?,
    onChanged: () -> Unit,
): ((String, String) -> ReservationReactivateState)? {
    val source = connection.reservationReactivateSource() ?: return null
    return { id, code ->
        ReservationReactivateState(source, scope, id, code, { approveAction(approval, it) }, onChanged)
    }
}

/**
 * A nova reserva a partir de uma oferta da cotação. Só existe com `reservations.reservations.manage`, com a porta da
 * criação e com a da Recepção (a busca de titular); senão, nula.
 */
@Suppress("LongParameterList") // as dependências da área, como nas outras fábricas
private fun newReservationFactory(
    connection: ServerConnection,
    guests: ReceptionSource?,
    scope: CoroutineScope,
    approval: ApprovalCoordinator?,
    permissions: Set<String>,
    onChanged: () -> Unit,
): ((QuoteOfferResponse, QuotedStay) -> NewReservationState)? {
    val source = connection.newReservationSource()
    if (source == null || guests == null || RESERVATIONS_MANAGE !in permissions) return null
    return { offer, stay ->
        NewReservationState(
            source,
            guests,
            scope,
            offer,
            stay,
            permissions,
            { approveAction(approval, it) },
            onChanged,
        )
    }
}

private enum class ReservationsTab(
    val label: String,
) {
    SEARCH("Busca"),
    AVAILABILITY("Disponibilidade e cotação"),
    PENDING("Pendências"),
}

/**
 * As abas da área: "Busca", "Disponibilidade e cotação" (com a disponibilidade) e "Pendências" (com a porta delas).
 * A busca fica composta fora das abas só no estado: o conteúdo é recriado, mas os estados vivem acima. A aba das
 * pendências inicia o estado ao ser aberta e usa o mesmo detalhe e as mesmas ações da busca.
 */
@Composable
@Suppress("LongParameterList") // os estados e as fábricas da área, já resolvidos por quem a compõe
private fun ReservationsTabs(
    availability: ReservationsAvailability?,
    pending: ReservationsPending?,
    newReservation: ((QuoteOfferResponse, QuotedStay) -> NewReservationState)?,
    confirmation: ((reservationId: String, code: String) -> RegistrationCardState)?,
    detail: ReservationDetailState?,
    actions: ReservationsActions?,
    search: @Composable () -> Unit,
) {
    var selected by remember { mutableStateOf(ReservationsTab.SEARCH) }
    val tabs =
        ReservationsTab.entries.filter {
            when (it) {
                ReservationsTab.SEARCH -> true
                ReservationsTab.AVAILABILITY -> availability != null
                ReservationsTab.PENDING -> pending != null
            }
        }
    val tab = selected.takeIf { it in tabs } ?: ReservationsTab.SEARCH
    val reserve = rememberNewReservationHost(newReservation, confirmation)
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
            tabs.forEach {
                Tab(selected = tab == it, onClick = { selected = it }, text = { Text(it.label) })
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                tab == ReservationsTab.AVAILABILITY && availability != null -> {
                    LaunchedEffect(availability) { availability.calendar.start() }
                    AvailabilityScreen(
                        availability.calendar,
                        availability.quote,
                        Modifier.fillMaxSize().padding(16.dp),
                        onReserve = if (newReservation != null) reserve::open else null,
                    )
                }

                tab == ReservationsTab.PENDING && pending != null -> {
                    LaunchedEffect(pending) { pending.start() }
                    PendingTab(pending.state, detail, actions, Modifier.padding(16.dp))
                }

                else -> {
                    search()
                }
            }
        }
    }
    reserve.Show()
}

/**
 * A fábrica da ficha de registro da recepção, ou nula sem porta de fichas nesta conexão. As portas reais (o diálogo
 * "Salvar como…" com a janela por pai e o de impressão do sistema) nascem aqui, a cada abertura; o diálogo de salvar
 * roda na thread de UI do AWT e a escrita e a impressão, fora dela.
 */
private fun registrationCardFactory(
    connection: ServerConnection,
    scope: CoroutineScope,
    window: Frame?,
): ((String, String) -> RegistrationCardState)? =
    connection.registrationCards()?.let { pdfFactory(it, PdfKind.REGISTRATION_CARD, scope, window) }

/** A fábrica da confirmação da reserva em PDF (o visualizador da ficha), ou nula sem a porta nesta conexão. */
private fun confirmationCardFactory(
    connection: ServerConnection,
    scope: CoroutineScope,
    window: Frame?,
): ((String, String) -> RegistrationCardState)? =
    connection.reservationConfirmations()?.let { pdfFactory(it.asCards(), PdfKind.CONFIRMATION, scope, window) }

/**
 * O portador da conta que as três entradas recebem (a aba "Contas", "Conta" na Recepção e nas Reservas), ou nulo sem a
 * porta nesta conexão ou sem `billing.folios.read` nas [permissions] da casca. A aprovação (do estorno e do reembolso)
 * é a do coordenador da sessão e os dois PDFs do extrato abrem no visualizador da ficha, cada um com a audiência e o
 * `PdfKind` dele. Os pagamentos usam a porta deles nesta conexão; o atalho [openShift] só vai com
 * `billing.cashier.operate` nas [permissions] (a decisão pelo conjunto é daqui, não da tela).
 */
@Suppress("LongParameterList") // o que a área entrega e o atalho do turno
internal fun folioWiring(
    connection: ServerConnection,
    permissions: Set<String>,
    scope: CoroutineScope,
    approval: ApprovalCoordinator?,
    window: Frame?,
    openShift: (() -> Unit)? = null,
): FolioWiring? {
    val source = connection.folioSource()
    if (source == null || FOLIOS_READ !in permissions) return null
    return FolioWiring(
        source = source,
        permissions = permissions,
        approve = { approveAction(approval, it) },
        guestPdf = statementPdfFactory(source, StatementAudience.GUEST, scope, window),
        internalPdf = statementPdfFactory(source, StatementAudience.INTERNAL, scope, window),
        payments = connection.paymentSource(),
        approveRefund = { approveAction(approval, it) },
        openShift = openShift?.takeIf { CASHIER_OPERATE in permissions },
        corrections = connection.folioCorrectionSource(),
    )
}

private fun statementPdfFactory(
    source: FolioSource,
    audience: StatementAudience,
    scope: CoroutineScope,
    window: Frame?,
): (String, String) -> RegistrationCardState =
    pdfFactory(source.statementCards(audience), audience.pdfKind, scope, window)

private fun pdfFactory(
    cards: RegistrationCards,
    kind: PdfKind,
    scope: CoroutineScope,
    window: Frame?,
): (String, String) -> RegistrationCardState {
    @Suppress("InjectDispatcher") // a escrita e a impressão vão para o pool de E/S, sem injeção nesta fábrica
    val io = Dispatchers.IO
    val awt = Executor { EventQueue.invokeLater(it) }.asCoroutineDispatcher()
    return { id, code ->
        RegistrationCardState(
            source = cards,
            scope = scope,
            reservationId = id,
            reservationCode = code,
            newDocument = { bytes, documentScope -> PdfDocumentState(bytes, documentScope) },
            newActions = { bytes, name ->
                PdfActions(bytes, name, FileDialogSaveChooser(window), SystemPrintDialog(), awt, io)
            },
            kind = kind,
        )
    }
}

/**
 * Os recados da recepção, ou nulo sem porta de recados nesta conexão. A lista da aba nasce com a chave do `sessionId`
 * e das [permissions] da casca, é iniciada ao entrar e fecha no `onDispose`, como o painel; a seção de cada reserva e
 * o formulário são criados pela tela com estas fábricas, com o mesmo conjunto.
 */
@Composable
private fun receptionTraces(
    source: TracesSource?,
    sessionId: String,
    permissions: Set<String>,
    scope: CoroutineScope,
): ReceptionTraces? {
    source ?: return null
    val list = remember(source, sessionId, permissions) { TracesListState(source, scope, permissions) }
    DisposableEffect(list) {
        list.start()
        onDispose { list.close() }
    }
    return remember(list) {
        ReceptionTraces(
            list = list,
            forReservation = { id -> TracesListState(source, scope, permissions, id) },
            form = { target, onSaved ->
                TraceFormState(source, scope, target, list.departments, list.departmentsDenied, onSaved)
            },
        )
    }
}

/** O check-in do detalhe: a aprovação é a do coordenador; depois dele a lista e o detalhe se refazem. */
private fun checkInFactory(
    source: ReceptionSource,
    scope: CoroutineScope,
    approval: ApprovalCoordinator?,
    panel: DayPanelState,
    detail: ReservationDetailState,
): (CheckInSeed) -> CheckInState =
    { seed ->
        CheckInState(
            source,
            scope,
            seed,
            panel.permissions,
            { approveAction(approval, it) },
            Clock.systemUTC()::instant,
        ) {
            panel.refresh()
            detail.reload()
        }
    }

/** O walk-in do cabeçalho, com a mesma aprovação; depois dele só a lista se refaz. */
private fun walkInFactory(
    source: ReceptionSource,
    scope: CoroutineScope,
    approval: ApprovalCoordinator?,
    panel: DayPanelState,
): () -> WalkInState =
    { WalkInState(source, scope, panel.permissions, { approveAction(approval, it) }, onChanged = { panel.refresh() }) }

/** A troca de UH do detalhe: a aprovação é a do coordenador; depois dela a lista e o detalhe se refazem. */
private fun roomMoveFactory(
    source: ReceptionSource,
    scope: CoroutineScope,
    approval: ApprovalCoordinator?,
    panel: DayPanelState,
    detail: ReservationDetailState,
): (String) -> RoomMoveState =
    { id ->
        RoomMoveState(source, scope, id, { approveAction(approval, it) }) {
            panel.refresh()
            detail.reload()
        }
    }

/** O desfazer check-in do detalhe, com a mesma aprovação e o mesmo refresh da troca. */
private fun undoCheckInFactory(
    source: ReceptionSource,
    scope: CoroutineScope,
    approval: ApprovalCoordinator?,
    panel: DayPanelState,
    detail: ReservationDetailState,
): (String) -> UndoCheckInState =
    { id ->
        UndoCheckInState(source, scope, id, { approveAction(approval, it) }) {
            panel.refresh()
            detail.reload()
        }
    }

/**
 * A aprovação pelo coordenador da sessão. Só sem coordenador (fora da casca) a ação corre sem aprovação; o
 * cancelamento da aprovação devolve `null` e nunca refaz a ação.
 */
private suspend fun <T : Any> approveAction(
    approval: ApprovalCoordinator?,
    action: ApprovableAction<T>,
): ApiResponse<T>? = if (approval == null) action.run(null) else approval.run(action)

/**
 * O mapa de UHs vive enquanto a Governança está aberta na mesma sessão: nasce com a chave do `sessionId` vigente e
 * fecha ao sair da área, da casca ou da sessão (o `retry` de um mapa que sobrevivesse reassinaria na sessão nova).
 * Sem sessão `Active` o quadro fica vazio de propósito: uma assinatura aberta sem sessão esperaria pela próxima e
 * mostraria a ela um mapa nascido na anterior. Isso só dura o instante entre a sessão sair (logout, `Ended`) e a
 * tela deixar a casca. Corre no escopo da composição, numa thread só.
 */
@Composable
private fun HousekeepingMap(connection: ServerConnection) {
    val source = remember(connection) { connection.roomsSource() }
    val session by connection.state.collectAsState()
    val sessionId = (session as? SessionState.Active)?.sessionId
    val scope = rememberCoroutineScope()
    when {
        source == null -> {
            Unavailable("Mapa indisponível nesta conexão.")
        }

        sessionId != null -> {
            val map = remember(source, sessionId) { RoomMapState(source, scope) }
            DisposableEffect(map) { onDispose { map.close() } }
            RoomMapScreen(map)
        }
    }
}

@Composable
private fun Unavailable(message: String) {
    Text(
        message,
        Modifier.padding(24.dp),
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * Um coordenador de aprovação por sessão, com a conexão dela. A sessão acaba para ele quando a tela sai da casca:
 * `SessionState.Ended`, logout ou troca de servidor (que só acontece a partir do login).
 */
internal fun sessionApprovals(
    controller: AppController,
    scope: CoroutineScope,
): ApprovalCoordinator {
    val connection = controller.requireConnection()
    return ApprovalCoordinator(
        grant = connection::grant,
        sessionEnded = controller.screen.filter { it != Screen.Shell }.map { },
        scope = scope,
    )
}

/** Desenha o diálogo de aprovação enquanto o coordenador tem uma pendência. */
@Composable
internal fun ApprovalHost(coordinator: ApprovalCoordinator) {
    val model = coordinator.dialog ?: return
    ApprovalDialog(
        model = model,
        onLogin = { coordinator.login = it },
        onPin = { coordinator.pin = it },
        onReason = { coordinator.reason = it },
        onConfirm = coordinator::confirm,
        onDismiss = coordinator::cancel,
    )
}

private fun AppController.requireConnection(): ServerConnection = checkNotNull(connection) { "sem conexão" }
