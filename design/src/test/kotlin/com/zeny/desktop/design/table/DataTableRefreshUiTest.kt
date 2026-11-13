package com.zeny.desktop.design.table

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Tentar de novo" da `DataTable` depois de um `refresh()` que falhou (M2 E01, Task 4). */
@OptIn(ExperimentalTestApi::class)
class DataTableRefreshUiTest {
    private data class Guest(
        val id: Int,
        val name: String,
    )

    private val columns = listOf(Column<Guest>("name", "Nome") { it.name })

    @Test
    fun retryAfterAFailedRefreshReplacesTheRowsInsteadOfAppending() =
        runComposeUiTest {
            // Pager 0: lista antiga de uma página só; pager 1: o refresh, que falha; pager 2: a lista nova.
            val requests = mutableListOf<Pair<Int, String?>>()
            var created = 0
            val state =
                TableState(
                    {
                        val index = created++
                        CursorPager<Guest>(50) { cursor, _ ->
                            requests += index to cursor
                            when (index) {
                                0 -> Page(listOf(Guest(1, "Ana"), Guest(2, "Bia")), null)
                                1 -> throw ApiError.Network(IOException("boom-interno"))
                                else -> Page(listOf(Guest(2, "Bia Nova"), Guest(3, "Caio")), null)
                            }
                        }
                    },
                ) { it.id }
            lateinit var scope: CoroutineScope
            setContent {
                scope = rememberCoroutineScope()
                ZenyTheme { Box(Modifier.width(600.dp).height(400.dp)) { DataTable(state, columns) } }
            }
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == 2 }
            waitForIdle()

            runOnIdle { scope.launch { state.refresh() } }
            waitUntil(timeoutMillis = WAIT_MS) { state.error != null }
            waitForIdle()
            // O refresh falhou sem esvaziar: as linhas antigas seguem à vista, com o rodapé de erro.
            onNodeWithTag("row-1").assertExists()
            onNodeWithTag("table-error").assertExists()

            onNodeWithTag("table-retry").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { state.error == null && !state.loading }
            waitForIdle()

            // A lista antiga não tinha mais páginas: o retry pede a primeira de um pager novo, sem cursor.
            assertEquals(listOf<Pair<Int, String?>>(0 to null, 1 to null, 2 to null), requests)
            assertEquals(listOf(2, 3), state.rows.map { it.id })
            onNodeWithTag("row-1").assertDoesNotExist()
            onNodeWithText("Bia Nova").assertExists()
            onNodeWithTag("row-3").assertExists()
            onNodeWithTag("table-error").assertDoesNotExist()
        }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
