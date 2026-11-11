package com.zeny.desktop.design.table

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test

private const val EMPTY_WAIT_MS = 5_000L

private data class EmptyRow(
    val id: Int,
)

private val rowColumns = listOf(Column<EmptyRow>("id", "Código") { "item ${it.id}" })

@OptIn(ExperimentalTestApi::class)
class DataTableEmptyTest {
    @Test
    fun showsMessageWhenTheListEndedEmpty() =
        runComposeUiTest {
            val state = TableState({ CursorPager<EmptyRow>(10) { _, _ -> Page(emptyList(), null) } }) { it.id }
            setContent {
                ZenyTheme { Box(Modifier.width(400.dp).height(200.dp)) { DataTable(state, rowColumns) } }
            }
            waitUntil(timeoutMillis = EMPTY_WAIT_MS) { !state.hasMore && !state.loading }
            waitForIdle()
            onNodeWithTag("table-empty").assertTextEquals("Nenhum registro encontrado.")
        }

    @Test
    fun showsMessageWhenTheFilterMatchesNothing() =
        runComposeUiTest {
            val state =
                TableState(
                    { CursorPager<EmptyRow>(10) { _, _ -> Page(listOf(EmptyRow(1), EmptyRow(2)), null) } },
                ) { it.id }
            setContent {
                ZenyTheme {
                    Box(
                        Modifier.width(400.dp).height(200.dp),
                    ) { DataTable(state, rowColumns, emptyMessage = "Nada aqui.") }
                }
            }
            waitUntil(timeoutMillis = EMPTY_WAIT_MS) { state.rows.size == 2 }
            onNodeWithTag("table-empty").assertDoesNotExist()
            onNodeWithTag("table-filter").performTextInput("zzz")
            waitForIdle()
            onNodeWithTag("table-empty").assertTextEquals("Nada aqui.")
        }
}
