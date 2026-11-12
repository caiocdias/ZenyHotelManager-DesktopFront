package com.zeny.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.login.LoginScreen
import com.zeny.desktop.app.login.LoginState
import com.zeny.desktop.app.login.PasswordChangeScreen
import com.zeny.desktop.app.login.PasswordChangeState
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.ShellScreen
import com.zeny.desktop.app.shell.ShellState
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.approval.ApprovalDialog
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
 * de tela que o próprio login provoca não cancela o login em voo.
 */
@Composable
fun AppRoot(
    controller: AppController,
    devMode: Boolean,
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
                PasswordChangeScreen(state)
            }

            Screen.Shell -> {
                val state = remember { ShellState(controller.requireConnection(), devMode, scope) }
                val approvals = remember { sessionApprovals(controller, scope) }
                // Sair da casca (logout, sessão encerrada) cancela a carga em voo e a aprovação pendente.
                DisposableEffect(state, approvals) {
                    onDispose {
                        state.close()
                        approvals.close()
                    }
                }
                ShellScreen(state, onLogout = { scope.launch { controller.logout() } })
                ApprovalHost(approvals)
            }
        }
    }
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
