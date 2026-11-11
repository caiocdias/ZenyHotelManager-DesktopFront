package com.zeny.desktop.design.feedback

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zeny.desktop.design.theme.ZenyColors

@Composable
@Suppress("LongParameterList") // assinatura fixada pelo plano (rótulos e destrutivo com padrão)
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirmar",
    dismissLabel: String = "Cancelar",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            val colors =
                if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = ZenyColors.Danger,
                        contentColor = ZenyColors.OnDanger,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            Button(onClick = onConfirm, colors = colors) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** Conteúdo de um aviso: a mensagem e o tom que o colore. */
internal class ToastVisuals(
    override val message: String,
    val tone: StatusTone,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = true
    override val duration: SnackbarDuration = SnackbarDuration.Short
}

/** Fila de avisos: cada `show` espera a vez, então as mensagens saem na ordem em que foram pedidas. */
class ToastState {
    internal val hostState = SnackbarHostState()

    suspend fun show(
        message: String,
        tone: StatusTone = StatusTone.INFO,
    ) {
        hostState.showSnackbar(ToastVisuals(message, tone))
    }
}

@Composable
fun ToastHost(
    state: ToastState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(state.hostState, modifier) { data ->
        val tone = (data.visuals as? ToastVisuals)?.tone ?: StatusTone.INFO
        Snackbar(
            snackbarData = data,
            containerColor = tone.container(),
            contentColor = tone.content(),
            dismissActionContentColor = tone.content(),
            shape = MaterialTheme.shapes.small,
        )
    }
}
