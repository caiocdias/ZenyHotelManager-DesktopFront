package com.zeny.desktop.app.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Servidor (com "Testar"), login e senha mascarada; o aviso é o do fim de sessão. A tela segue o `AppController`. */
@Composable
fun LoginScreen(
    state: LoginState,
    notice: String?,
) = FormPanel(title = "Entrar no Zeny Hotel Manager") {
    notice?.let { Message(it, "login-notice") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlainField(state.server, { state.server = it }, "Servidor", "login-server", Modifier.weight(1f))
        OutlinedButton(onClick = state::testConnection) { Text("Testar") }
    }
    state.probeMessage?.let { Hint(it, "login-probe-message") }
    PlainField(state.login, { state.login = it }, "Login", "login-user", Modifier.fillMaxWidth())
    PasswordField(state.password, { state.password = it }, "Senha", "login-password")
    state.message?.let { Message(it, "login-message") }
    Button(onClick = { state.submit {} }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Entrar")
    }
}

/** Troca de senha pedida pelo servidor: senha atual, nova e confirmação, todas mascaradas. */
@Composable
fun PasswordChangeScreen(state: PasswordChangeState) =
    FormPanel(title = "Troque sua senha") {
        Hint("O servidor pede uma senha nova antes de continuar. Use de 12 a 128 caracteres.", "password-hint")
        PasswordField(state.current, { state.current = it }, "Senha atual", "password-current")
        PasswordField(state.new, { state.new = it }, "Nova senha", "password-new")
        PasswordField(state.confirm, { state.confirm = it }, "Confirme a nova senha", "password-confirm")
        state.message?.let { Message(it, "password-message") }
        Button(onClick = { state.submit {} }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Trocar senha")
        }
    }

@Composable
private fun FormPanel(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun PlainField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    modifier: Modifier,
) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    singleLine = true,
    modifier = modifier.testTag(tag),
)

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    modifier = Modifier.fillMaxWidth().testTag(tag),
)

@Composable
private fun Message(
    text: String,
    tag: String,
) = Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag))

@Composable
private fun Hint(
    text: String,
    tag: String,
) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.testTag(tag),
)
