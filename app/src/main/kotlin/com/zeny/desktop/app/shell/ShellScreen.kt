package com.zeny.desktop.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zeny.desktop.design.feedback.EmptyState
import com.zeny.desktop.design.feedback.ErrorState
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.layout.AppScaffold
import com.zeny.desktop.design.layout.NavItem
import com.zeny.desktop.design.theme.ZenyColors

private const val NO_SCREENS_YET = "As telas do seu perfil chegam nas próximas versões."
private const val PICK_AN_AREA = "Selecione uma área no menu."

/**
 * Casca do app: menu das áreas que o usuário vê (as sem tela com "(em breve)", só no modo de desenvolvimento da
 * casca) e topo com hotel, data operacional, usuário e "Sair". "Sair" fica disponível também no erro.
 */
@Composable
fun ShellScreen(
    state: ShellState,
    onLogout: () -> Unit,
) {
    val items =
        state.areas.map { (area, implemented) ->
            NavItem(area.id, if (implemented) area.label else "${area.label} (em breve)")
        }
    AppScaffold(
        items = items,
        selected = state.selected?.id,
        onSelect = { id -> Area.entries.firstOrNull { it.id == id }?.let(state::select) },
        topBar = { TopBar(state, onLogout) },
    ) {
        val error = state.error
        // Com área escolhida, mostra só o nome dela; a E06 o troca pela tela da área.
        when {
            state.loading -> LoadingState()
            error != null -> ErrorState(error, onRetry = state::reload)
            state.areas.none { (_, implemented) -> implemented } -> EmptyState(NO_SCREENS_YET)
            else -> EmptyState(state.selected?.label ?: PICK_AN_AREA)
        }
    }
}

@Composable
private fun TopBar(
    state: ShellState,
    onLogout: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(ZenyColors.Surface).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Nome longo do hotel ou do usuário encolhe com reticências; a data e "Sair" ficam sempre à vista.
        Text(
            state.hotelName,
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.businessDate.isNotEmpty()) {
            Text("Data operacional: ${state.businessDate}", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            state.userName,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedButton(onClick = onLogout) { Text("Sair") }
    }
}
