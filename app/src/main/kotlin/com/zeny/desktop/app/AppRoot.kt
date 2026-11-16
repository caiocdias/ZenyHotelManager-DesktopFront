@file:Suppress("TooManyFunctions") // detekt: a casca reúne a raiz e uma composição por área

package com.zeny.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.zeny.desktop.app.reception.ReceptionScreen
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reception.ReceptionTraces
import com.zeny.desktop.app.reception.RegistrationCardState
import com.zeny.desktop.app.reception.ReservationDetailState
import com.zeny.desktop.app.reception.RoomMoveState
import com.zeny.desktop.app.reception.UndoCheckInState
import com.zeny.desktop.app.reception.WalkInState
import com.zeny.desktop.app.reservations.ReservationSearchState
import com.zeny.desktop.app.reservations.ReservationsScreen
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
            ReservationsScreen(search, detail)
        }
    }
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
): ((String, String) -> RegistrationCardState)? {
    val cards = connection.registrationCards() ?: return null

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
