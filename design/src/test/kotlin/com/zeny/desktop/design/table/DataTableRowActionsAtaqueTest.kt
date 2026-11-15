package com.zeny.desktop.design.table

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

private const val WAIT_MS = 5_000L

private data class Line(
    val id: Int,
    val name: String,
)

private val lineColumns =
    listOf(Column<Line>("name", "Nome", comparator = compareBy { it.name }) { it.name })

private fun lineState(vararg lines: Line) =
    TableState({
        CursorPager<Line>(50) { _, _ -> Page(lines.toList(), null) }
    }) { it.id }

@OptIn(ExperimentalTestApi::class)
class DataTableRowActionsAtaqueTest {
    private fun ComposeUiTest.table(
        state: TableState<Line>,
        onRowClick: (Line) -> Unit = {},
        rowActions: (@Composable (Int, Line) -> Unit)? = null,
    ) {
        setContent {
            ZenyTheme {
                Box(Modifier.width(900.dp).height(400.dp)) {
                    DataTable(state, lineColumns, onRowClick = onRowClick, rowActions = rowActions)
                }
            }
        }
        waitUntil(timeoutMillis = WAIT_MS) { state.rows.isNotEmpty() }
        waitForIdle()
    }

    @Test
    fun clickingRowActionDoesNotTriggerRowClick() =
        runComposeUiTest {
            val clicked = mutableListOf<Int>()
            val acted = mutableListOf<Int>()
            table(
                lineState(Line(1, "Ana"), Line(2, "Bia")),
                onRowClick = { clicked += it.id },
                rowActions = { index, _ ->
                    OutlinedButton(
                        onClick = { acted += index },
                        modifier = Modifier.testTag("act-$index"),
                    ) { Text("Agir") }
                },
            )
            onNodeWithTag("act-1").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(listOf(1), acted)
            assertEquals(emptyList(), clicked, "o clique na ação também acionou a linha")
            onNodeWithText("Ana").performClick()
            assertEquals(listOf(1), clicked)
            assertEquals(listOf(1), acted)
        }

    @Test
    fun actionIndexFollowsVisibleOrderAfterSorting() =
        runComposeUiTest {
            val state = lineState(Line(1, "Bia"), Line(2, "Ana"))
            val seen = mutableMapOf<Int, String>()
            table(state, rowActions = { index, row ->
                seen[index] = row.name
                Text("acao-${row.name}", Modifier.testTag("act-$index"))
            })
            onNodeWithText("Nome").performClick()
            waitForIdle()
            // Depois da ordenação, o índice 0 tem de ser a linha que aparece primeiro.
            val first = seen.getValue(0)
            assertEquals("Ana", first)
            onNodeWithTag("act-0", useUnmergedTree = true).assertIsDisplayed()
        }

    @Test
    fun withoutRowActionsNoActionColumnIsDrawn() =
        runComposeUiTest {
            val clicked = mutableListOf<Int>()
            table(lineState(Line(1, "Ana")), onRowClick = { clicked += it.id })
            onNodeWithText("Ana").performClick()
            assertEquals(listOf(1), clicked)
            onNodeWithTag("row-1").assertIsDisplayed()
            // Sem rowActions, a linha toda é só a célula: a largura da linha é a do conteúdo, sem faixa reservada.
            val row = onNodeWithTag("row-1").fetchSemanticsNode().size.width
            val table = onNodeWithTag("table-list").fetchSemanticsNode().size.width
            assertEquals(table, row)
            val cell = onNodeWithText("Ana").fetchSemanticsNode().size.width
            // A célula ocupa a linha menos o espaçamento: nenhuma coluna de 190dp reservada à direita.
            assert(cell >= row * 9 / 10) { "célula $cell de $row: sobrou faixa reservada" }
        }
}
