package com.zeny.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.approval.LocalApprovalCoordinator
import com.zeny.desktop.app.login.LoginScreen
import com.zeny.desktop.app.login.LoginState
import com.zeny.desktop.app.login.PasswordChangeScreen
import com.zeny.desktop.app.login.PasswordChangeState
import com.zeny.desktop.app.rooms.RoomMapScreen
import com.zeny.desktop.app.rooms.RoomMapState
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.app.shell.ShellScreen
import com.zeny.desktop.app.shell.ShellState
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.approval.ApprovalDialog
import com.zeny.desktop.design.feedback.EmptyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
                val state = remember { ShellState(connection, devMode, scope) }
                val approvals = remember { sessionApprovals(controller, scope) }
                // Sair da casca (logout, sessão encerrada) cancela a carga em voo e a aprovação pendente.
                DisposableEffect(state, approvals) {
                    onDispose {
                        state.close()
                        approvals.close()
                    }
                }
                CompositionLocalProvider(LocalApprovalCoordinator provides approvals) {
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

/** A tela da área aberta na casca: a Governança é o mapa de UHs; as demais ainda mostram só o nome. */
@Composable
internal fun AreaContent(
    area: Area,
    connection: ServerConnection,
) {
    when (area) {
        Area.HOUSEKEEPING -> HousekeepingMap(connection)
        else -> EmptyState(area.label)
    }
}

/**
 * O mapa de UHs vive enquanto a Governança está aberta na mesma sessão: nasce com a chave do `sessionId` vigente e
 * fecha ao sair da área, da casca ou da sessão (o `retry` de um mapa que sobrevivesse reassinaria na sessão nova).
 * Sem sessão ativa não abre: a assinatura ficaria para a próxima. Corre no escopo da composição, numa thread só.
 */
@Composable
private fun HousekeepingMap(connection: ServerConnection) {
    val source = remember(connection) { connection.roomsSource() }
    val session by connection.state.collectAsState()
    val sessionId = (session as? SessionState.Active)?.sessionId
    val scope = rememberCoroutineScope()
    when {
        source == null -> {
            MapUnavailable()
        }

        sessionId != null -> {
            val map = remember(source, sessionId) { RoomMapState(source, scope) }
            DisposableEffect(map) { onDispose { map.close() } }
            RoomMapScreen(map)
        }
    }
}

@Composable
private fun MapUnavailable() {
    Text(
        "Mapa indisponível nesta conexão.",
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
