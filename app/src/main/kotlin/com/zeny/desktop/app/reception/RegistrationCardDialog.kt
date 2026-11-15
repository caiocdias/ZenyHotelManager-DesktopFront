package com.zeny.desktop.app.reception

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.pdf.PdfViewer

private const val DIALOG_FRACTION = 0.92f
private const val CARD_PERMISSION = "frontdesk.stays.checkin"
private val CARD_STATUSES = setOf("CONFIRMED", "IN_HOUSE", "CHECKED_OUT")

/**
 * A ficha de registro num diálogo grande: carregando, a mensagem de erro (com "Tentar de novo" e "Fechar") ou o
 * visualizador de PDF. Quem abre é dono do [state] e o fecha em [onClose]; o download começa ao entrar.
 */
@Composable
internal fun RegistrationCardDialog(
    state: RegistrationCardState,
    onClose: () -> Unit,
) {
    LaunchedEffect(state) { state.start() }
    // Sem visualizador (carregando ou com erro) o Esc sai; com ele, quem fecha é o "Fechar" do próprio visualizador.
    Dialog(onDismissRequest = {
        if (state.viewer ==
            null
        ) {
            onClose()
        }
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxSize(DIALOG_FRACTION).testTag("registration-card-dialog"),
            shape = MaterialTheme.shapes.medium,
        ) {
            val viewer = state.viewer
            val error = state.error
            when {
                viewer != null -> PdfViewer(state.title, viewer.document, viewer.actions, onClose)
                error != null -> CardError(error, onRetry = state::retry, onClose = onClose)
                else -> CardLoading(onClose)
            }
        }
    }
}

@Composable
private fun CardError(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("registration-card-error"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry, modifier = Modifier.testTag("registration-card-retry")) {
                Text("Tentar de novo")
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.testTag("registration-card-close")) {
                Text("Fechar")
            }
        }
    }
}

/** A reserva (id e código) de que a ficha é pedida. */
internal data class CardTarget(
    val reservationId: String,
    val code: String,
)

private class OpenCard(
    val ownerId: String?,
    val state: RegistrationCardState,
)

/**
 * A ficha aberta da tela. Ela é da reserva que estava aberta no detalhe ao pedir: trocar de reserva, fechar o detalhe
 * ou sair da composição (área, sessão) fecha o [RegistrationCardState]. Cada abertura cria um estado novo.
 */
@Stable
internal class RegistrationCardUi(
    private val detail: ReservationDetailState,
    private val factory: () -> ((String, String) -> RegistrationCardState)?,
) {
    private var current by mutableStateOf<OpenCard?>(null)

    val available: Boolean get() = factory() != null

    /** O botão do detalhe: só com a permissão do check-in e a reserva confirmada, hospedada ou encerrada. */
    fun action(permissions: Set<String>): (() -> Unit)? {
        val reservation = detail.reservation?.reservation
        val allowed = available && CARD_PERMISSION in permissions && reservation?.status in CARD_STATUSES
        return reservation?.takeIf { allowed }?.let { { open(CardTarget(it.id.toString(), it.code)) } }
    }

    fun open(target: CardTarget) {
        val make = factory() ?: return
        current?.state?.close()
        current = OpenCard(detail.openId, make(target.reservationId, target.code))
    }

    @Composable
    fun Show() {
        val shown = current?.takeIf { it.ownerId == detail.openId }
        DisposableEffect(shown) {
            onDispose {
                shown?.state?.close()
                if (current === shown) current = null
            }
        }
        shown?.let { RegistrationCardDialog(it.state) { current = null } }
    }
}

@Composable
internal fun rememberRegistrationCard(
    detail: ReservationDetailState,
    factory: () -> ((String, String) -> RegistrationCardState)?,
): RegistrationCardUi = remember(detail) { RegistrationCardUi(detail, factory) }

@Composable
private fun CardLoading(onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { LoadingState() }
        OutlinedButton(onClick = onClose, modifier = Modifier.testTag("registration-card-close")) { Text("Cancelar") }
    }
}
