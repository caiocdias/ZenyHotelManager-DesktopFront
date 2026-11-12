package com.zeny.desktop.design.table

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.theme.ZenyTheme
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val WAIT_MS = 5_000L

private data class Row(
    val id: Int,
    val name: String,
)

private val sortableColumns =
    listOf(
        Column<Row>("id", "Código", comparator = compareBy { it.id }) { "n${it.id}" },
        Column<Row>("name", "Nome", comparator = compareBy { it.name }) { it.name },
        Column<Row>("plain", "Sem ordem") { "x" },
    )

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.show(state: TableState<Row>) {
    setContent { ZenyTheme { Box(Modifier.width(600.dp).height(400.dp)) { DataTable(state, sortableColumns) } } }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.orderOf(vararg ids: Int): List<Int> =
    ids.sortedBy { onNodeWithTag("row-$it").fetchSemanticsNode().positionInRoot.y }

private fun unsortedState() =
    TableState(
        {
            CursorPager<Row>(50) { _, _ -> Page(listOf(Row(2, "b"), Row(3, "c"), Row(1, "a")), null) }
        },
    ) { it.id }

@OptIn(ExperimentalTestApi::class)
class DataTableAttackUiTest {
    @Test
    fun headerClickSortsAscendingThenDescendingWithArrow() =
        runComposeUiTest {
            val state = unsortedState()
            show(state)
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == 3 }
            waitForIdle()
            assertEquals(listOf(2, 3, 1), orderOf(1, 2, 3))

            onNodeWithTag("header-id").performClick()
            waitForIdle()
            assertEquals(listOf(1, 2, 3), orderOf(1, 2, 3))
            onNodeWithTag("header-id").assertTextEquals("Código ▲")

            onNodeWithTag("header-id").performClick()
            waitForIdle()
            assertEquals(listOf(3, 2, 1), orderOf(1, 2, 3))
            onNodeWithTag("header-id").assertTextEquals("Código ▼")

            onNodeWithTag("header-id").performClick()
            waitForIdle()
            assertEquals(listOf(1, 2, 3), orderOf(1, 2, 3))
        }

    @Test
    fun clickingAnotherHeaderRestartsAscendingAndMovesTheArrow() =
        runComposeUiTest {
            val state = unsortedState()
            show(state)
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == 3 }
            waitForIdle()
            onNodeWithTag("header-id").performClick()
            onNodeWithTag("header-id").performClick()
            waitForIdle()
            onNodeWithTag("header-name").performClick()
            waitForIdle()
            assertEquals("name" to true, state.sort)
            assertEquals(listOf(1, 2, 3), orderOf(1, 2, 3))
            onNodeWithTag("header-name").assertTextEquals("Nome ▲")
            onNodeWithTag("header-id").assertTextEquals("Código")
        }

    @Test
    fun headerWithoutComparatorDoesNotSort() =
        runComposeUiTest {
            val state = unsortedState()
            show(state)
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == 3 }
            waitForIdle()
            onNodeWithTag("header-plain").performClick()
            waitForIdle()
            assertNull(state.sort)
            assertEquals(listOf(2, 3, 1), orderOf(1, 2, 3))
            onNodeWithTag("header-plain").assertTextEquals("Sem ordem")
        }

    @Test
    fun firstPageErrorIsNotRetriedBehindTheUsersBack() =
        runComposeUiTest {
            var fetches = 0
            val state =
                TableState(
                    {
                        CursorPager<Row>(50) { _, _ ->
                            fetches++
                            throw ApiError.Network(IOException("boom-interno"))
                        }
                    },
                ) { it.id }
            show(state)
            waitUntil(timeoutMillis = WAIT_MS) { fetches >= 1 }
            waitForIdle()
            assertEquals(1, fetches, "só o botão Tentar de novo pode pedir a página de novo")
            onNodeWithTag("table-error").assertExists()
        }

    @Test
    fun errorOnFirstPageShowsUserMessageWithoutCauseAndRetryReloads() =
        runComposeUiTest {
            var healthy = false
            val state =
                TableState(
                    {
                        CursorPager<Row>(50) { _, _ ->
                            if (!healthy) throw ApiError.Network(IOException("boom-interno"))
                            Page(listOf(Row(1, "a"), Row(2, "b")), null)
                        }
                    },
                ) { it.id }
            show(state)
            waitUntil(timeoutMillis = WAIT_MS) { state.error != null }
            waitForIdle()
            onNodeWithTag("table-error").assertExists()
            onNodeWithText("Não foi possível falar com o servidor. Confira o endereço e a rede.").assertExists()
            onNodeWithText("boom-interno", substring = true).assertDoesNotExist()

            healthy = true
            onNodeWithTag("table-retry").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == 2 }
            waitForIdle()
            assertEquals(listOf(1, 2), state.rows.map { it.id })
            onNodeWithTag("table-error").assertDoesNotExist()
        }

    @Test
    fun retryAfterErrorWithRowsLoadsMoreWithoutDuplicatingRows() =
        runComposeUiTest {
            val cursors = mutableListOf<String?>()
            var healthy = false
            val state =
                TableState(
                    {
                        CursorPager<Row>(2) { cursor, _ ->
                            cursors += cursor
                            if (cursor == null) {
                                Page(listOf(Row(1, "a"), Row(2, "b")), "c1")
                            } else {
                                if (!healthy) throw ApiError.Network(IOException("x"))
                                Page(listOf(Row(3, "c"), Row(4, "d")), null)
                            }
                        }
                    },
                ) { it.id }
            // Filtro ativo desde o início: só o botão pede a segunda página.
            state.filter = "n"
            show(state)
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == 2 }
            waitForIdle()
            onNodeWithTag("load-more").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { state.error != null }
            waitForIdle()
            assertEquals(2, state.rows.size)
            onNodeWithTag("table-error").assertExists()

            healthy = true
            onNodeWithTag("table-retry").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == 4 }
            waitForIdle()
            assertEquals(listOf(null, "c1", "c1"), cursors)
            assertEquals(listOf(1, 2, 3, 4), state.rows.map { it.id })
            onNodeWithTag("table-error").assertDoesNotExist()
        }

    @Test
    fun loadMoreButtonIsAbsentWithoutFilterAndWhenNothingIsLeft() =
        runComposeUiTest {
            val state = unsortedState()
            show(state)
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == 3 }
            waitForIdle()
            onNodeWithTag("load-more").assertDoesNotExist()
            onNodeWithTag("table-filter").performTextInput("a")
            waitForIdle()
            assertFalse(state.hasMore)
            onNodeWithTag("load-more").assertDoesNotExist()
        }

    @Test
    fun loadMoreWithFilterAppendsOnlyNewRowsAndKeepsFilterApplied() =
        runComposeUiTest {
            val state =
                TableState(
                    {
                        CursorPager<Row>(2) { cursor, _ ->
                            if (cursor == null) {
                                Page(listOf(Row(1, "ana"), Row(2, "bia")), "p2")
                            } else {
                                Page(listOf(Row(2, "bia"), Row(3, "ana2")), null)
                            }
                        }
                    },
                ) { it.id }
            state.filter = "ana"
            show(state)
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == 2 }
            waitForIdle()
            onNodeWithTag("row-2").assertDoesNotExist()
            onNodeWithTag("load-more").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == 3 }
            waitForIdle()
            assertEquals(listOf(1, 2, 3), state.rows.map { it.id })
            onNodeWithTag("row-1").assertExists()
            onNodeWithTag("row-3").assertExists()
            onNodeWithTag("row-2").assertDoesNotExist()
            onNodeWithTag("load-more").assertDoesNotExist()
        }
}
