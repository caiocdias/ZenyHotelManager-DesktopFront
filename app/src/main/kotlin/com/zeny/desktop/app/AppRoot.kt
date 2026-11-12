package com.zeny.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeny.desktop.app.login.LoginScreen
import com.zeny.desktop.app.login.LoginState
import com.zeny.desktop.app.login.PasswordChangeScreen
import com.zeny.desktop.app.login.PasswordChangeState
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.session.SessionState
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
    @Suppress("UnusedParameter") devMode: Boolean, // a casca (Task 7) mostra as áreas "(em breve)" com ele
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
                SessionStarted(onLogout = { scope.launch { controller.logout() } })
            }
        }
    }
}

private fun AppController.requireConnection(): ServerConnection = checkNotNull(connection) { "sem conexão" }

/** Lugar da casca até a Task 7: só confirma a sessão e permite sair. */
@Composable
private fun SessionStarted(onLogout: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sessão iniciada.")
        OutlinedButton(onClick = onLogout) { Text("Sair") }
    }
}
