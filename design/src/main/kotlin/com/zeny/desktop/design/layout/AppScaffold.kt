@file:Suppress("MatchingDeclarationName") // arquivo agrupa NavItem, navState e AppScaffold, nome fixado pelo plano

package com.zeny.desktop.design.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zeny.desktop.design.theme.ZenyColors

/** Entrada do menu lateral. */
data class NavItem(
    val id: String,
    val label: String,
)

/** Pares (item, selecionado) na ordem dada; um `selected` desconhecido não marca nenhum. */
fun navState(
    items: List<NavItem>,
    selected: String?,
): List<Pair<NavItem, Boolean>> = items.map { it to (it.id == selected) }

private val NavWidth = 220.dp

@Composable
fun AppScaffold(
    items: List<NavItem>,
    selected: String?,
    onSelect: (String) -> Unit,
    topBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize().background(ZenyColors.Background)) {
        Column(
            Modifier
                .width(NavWidth)
                .fillMaxHeight()
                .background(ZenyColors.Surface)
                .padding(vertical = 12.dp),
        ) {
            navState(items, selected).forEach { (item, isSelected) ->
                val container = if (isSelected) ZenyColors.Primary else ZenyColors.Surface
                val textColor = if (isSelected) ZenyColors.OnPrimary else ZenyColors.OnSurface
                Box(
                    Modifier
                        .testTag("nav-${item.id}")
                        .fillMaxWidth()
                        .background(container)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(item.id) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(item.label, color = textColor, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            topBar()
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) { content() }
        }
    }
}
