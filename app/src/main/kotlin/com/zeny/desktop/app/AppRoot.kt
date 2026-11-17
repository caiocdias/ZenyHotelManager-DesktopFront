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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.approval.LocalApprovalCoordinator
import com.zeny.desktop.app.approval.LocalApprovalCoordinatorOrNull
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
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.approval.ApprovalDialog
import com.zeny.desktop.design.feedback.EmptyState
import com.zeny.desktop.design.pdf.FileDialogSaveChooser
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.pdf.PdfDocumentState
import com.zeny.desktop.design.pdf.SystemPrintDialog
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import kotlinx.coroutines.CancellationException
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
                    ShellScreen(state, onLogout = { scope.launch { controller.logout() } }) { area ->
                        AreaContent(area, connection)
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
 * A tela da área aberta na casca: a Recepção é o painel do dia; as Reservas, a busca de reservas; a Governança, o mapa
 * de UHs; as demais ainda mostram só o nome.
 */
@Composable
internal fun AreaContent(
    area: Area,
    connection: ServerConnection,
) {
    when (area) {
        Area.RECEPTION -> ReceptionArea(connection)
        Area.RESERVATIONS -> ReservationsArea(connection)
        Area.HOUSEKEEPING -> HousekeepingMap(connection)
        else -> EmptyState(area.label)
    }
}

/**
 * O painel do dia e o detalhe da reserva vivem enquanto a Recepção está aberta na mesma sessão, como o mapa de UHs:
 * nascem com a chave do `sessionId` vigente (o cache de titulares é da sessão) e fecham ao sair da área, da casca ou
 * da sessão, o que cancela a assinatura de tempo real. Sem sessão `Active` o quadro fica vazio de propósito. O ganho
 * de foco da janela atualiza o painel e relê a reserva aberta; a perda só o registra no painel.
 */
@Composable
private fun ReceptionArea(connection: ServerConnection) {
    val source = remember(connection) { connection.receptionSource() }
    val session by connection.state.collectAsState()
    val sessionId = (session as? SessionState.Active)?.sessionId
    val scope = rememberCoroutineScope()
    when {
        source == null -> {
            Unavailable("Recepção indisponível nesta conexão.")
        }

        sessionId != null -> {
            val panel = remember(source, sessionId) { DayPanelState(source, scope) }
            val detail = remember(source, sessionId) { ReservationDetailState(source, scope) }
            DisposableEffect(panel, detail) {
                onDispose {
                    panel.close()
                    detail.close()
                }
            }
            val traces = receptionTraces(connection.tracesSource(), sessionId, scope)
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
            )
        }
    }
}

/**
 * A busca de reservas e o detalhe somente leitura vivem enquanto a área está aberta na mesma sessão: nascem com a
 * chave do `sessionId` vigente (o cache de titulares é da sessão), a busca é iniciada ao entrar e os dois fecham ao
 * sair da área, da casca ou da sessão (o fechamento descarta a consulta, o cache e o documento mascarado). O detalhe
 * usa a porta da Recepção; sem ela a tela funciona sem painel. O ganho de foco da janela relê a reserva aberta, sem
 * refazer a busca.
 */
@Composable
private fun ReservationsArea(connection: ServerConnection) {
    val source = remember(connection) { connection.reservationSearchSource() }
    val receptionSource = remember(connection) { connection.receptionSource() }
    val session by connection.state.collectAsState()
    val sessionId = (session as? SessionState.Active)?.sessionId
    val scope = rememberCoroutineScope()
    when {
        source == null -> {
            Unavailable("Reservas indisponíveis nesta conexão.")
        }

        sessionId != null -> {
            val search = remember(source, sessionId) { ReservationSearchState(source, scope) }
            val detail =
                remember(receptionSource, sessionId) { receptionSource?.let { ReservationDetailState(it, scope) } }
            DisposableEffect(search, detail) {
                search.start()
                onDispose {
                    search.close()
                    detail?.close()
                }
            }
            val window = LocalWindowInfo.current
            LaunchedEffect(detail, window) {
                snapshotFlow { window.isWindowFocused }.collect { focused ->
                    if (focused) detail?.reload()
                }
            }
            ReservationsContent(connection, search, detail, sessionId, scope)
        }
    }
}

/**
 * A busca e, com as portas e as permissões, as ações do detalhe, a aba de disponibilidade e cotação com a nova reserva
 * e a aba das pendências. Sem a porta da Recepção ou sem a leitura das permissões não há ação alguma; a aba das
 * pendências só pede a porta delas. Sem nenhuma aba, a área é só a busca.
 */
@Composable
private fun ReservationsContent(
    connection: ServerConnection,
    search: ReservationSearchState,
    detail: ReservationDetailState?,
    sessionId: String,
    scope: CoroutineScope,
) {
    val receptionSource = remember(connection) { connection.receptionSource() }
    val availability = reservationsAvailability(connection.availabilitySource(), sessionId, scope)
    val pending = reservationsPending(remember(connection) { connection.pendingSource() }, sessionId, scope)
    val permissions = receptionPermissions(receptionSource, sessionId)
    val approval = LocalApprovalCoordinatorOrNull.current
    val frame = LocalWindowFrame.current
    val confirmation = remember(connection, scope, frame) { confirmationCardFactory(connection, scope, frame) }
    val refreshDetailAndTable: () -> Unit = {
        detail?.reload()
        scope.launch { search.table.refresh() }
    }
    // Depois de confirmar: o detalhe, a busca e a lista de pendências aberta (a disponibilidade não muda).
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
        permissions?.let {
            ReservationsActions(
                it,
                change = changeFactory(connection, scope, approval, it, refreshAfterChange),
                confirm = confirmFactory(connection, scope, refreshAfterConfirm),
                confirmation = confirmation,
                cancel = cancelFactory(connection, scope, approval, refreshAfterChange),
                reactivate = reactivateFactory(connection, scope, approval, refreshAfterChange),
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

/** O calendário e a cotação de uma sessão; só existem com a permissão de leitura da disponibilidade. */
private class ReservationsAvailability(
    val calendar: AvailabilityState,
    val quote: QuoteState,
)

/**
 * A disponibilidade e a cotação da área Reservas, ou nulo enquanto as permissões não foram lidas, se a leitura falhou,
 * se falta `reservations.availability.read` ou se a conexão não tem a porta. As permissões são lidas uma vez por
 * sessão; os dois estados nascem com a chave `(source, sessionId)` e fecham ao sair da área.
 */
@Composable
private fun reservationsAvailability(
    source: AvailabilitySource?,
    sessionId: String,
    scope: CoroutineScope,
): ReservationsAvailability? {
    // A leitura pertence à (source, sessionId) que a produziu: com a chave nova, o valor da sessão anterior não vale.
    val granted by produceState<Pair<AvailabilitySource, String>?>(null, source, sessionId) {
        val allowed = source != null && readOrNull { AVAILABILITY_READ in source.permissions() } == true
        value = if (allowed) source to sessionId else null
    }
    if (source == null || granted != (source to sessionId)) return null
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
 * As pendências da sessão: o [state] e se a aba já o iniciou. [refresh] só recarrega depois do [start]: sem a aba
 * aberta, nada é lido (a entrada na aba carrega as quatro listas de qualquer modo). A marca é só deste guarda; a tela
 * não a lê.
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
        if (started) state.refresh()
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

/** A leitura de permissões da [sessionId] na [source] que a produziu. */
private class GrantedPermissions(
    val source: ReceptionSource,
    val sessionId: String,
    val permissions: Set<String>,
)

/**
 * As permissões efetivas da sessão, lidas na porta da Recepção (a busca não as tem, e a disponibilidade pode faltar),
 * ou nulas enquanto não foram lidas, se a leitura falhou ou sem a porta. Lidas uma vez por `(source, sessionId)`: a
 * leitura da sessão anterior não vale na nova.
 */
@Composable
private fun receptionPermissions(
    source: ReceptionSource?,
    sessionId: String,
): Set<String>? {
    val read by produceState<GrantedPermissions?>(null, source, sessionId) {
        value = source?.let { s -> readOrNull { s.permissions() }?.let { GrantedPermissions(s, sessionId, it) } }
    }
    val granted = read
    return granted?.takeIf { it.source === source && it.sessionId == sessionId }?.permissions
}

/** O resultado de uma leitura, ou nulo se ela falhou; o cancelamento da coroutine nunca é engolido. */
private suspend fun <T : Any> readOrNull(block: suspend () -> T): T? =
    runCatching { block() }.onFailure { if (it is CancellationException) throw it }.getOrNull()

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
    permissions: Set<String>?,
    onChanged: () -> Unit,
): ((QuoteOfferResponse, QuotedStay) -> NewReservationState)? {
    val source = connection.newReservationSource()
    val granted = permissions?.takeIf { RESERVATIONS_MANAGE in it }
    if (source == null || guests == null || granted == null) return null
    return { offer, stay ->
        NewReservationState(
            source,
            guests,
            scope,
            offer,
            stay,
            granted,
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
 * Os recados da recepção, ou nulo sem porta de recados nesta conexão. A lista da aba nasce com a chave do `sessionId`,
 * é iniciada ao entrar e fecha no `onDispose`, como o painel; a seção de cada reserva e o formulário são criados pela
 * tela com estas fábricas.
 */
@Composable
private fun receptionTraces(
    source: TracesSource?,
    sessionId: String,
    scope: CoroutineScope,
): ReceptionTraces? {
    source ?: return null
    val list = remember(source, sessionId) { TracesListState(source, scope) }
    DisposableEffect(list) {
        list.start()
        onDispose { list.close() }
    }
    return remember(list) {
        ReceptionTraces(
            list = list,
            forReservation = { id -> TracesListState(source, scope, id) },
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
