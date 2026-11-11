package com.zeny.desktop.design.table

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PAGE_SIZE = 50
private const val PAGES = 3
private const val TOTAL = PAGE_SIZE * PAGES
private const val WAIT_MS = 5_000L

private data class Item(
    val id: Int,
)

private val columns = listOf(Column<Item>("id", "Código", comparator = compareBy { it.id }) { "item ${it.id}" })

private class Server {
    var fetches = 0

    fun pager() =
        CursorPager<Item>(PAGE_SIZE) { cursor, _ ->
            fetches++
            val from = cursor?.toInt() ?: 0
            val next = from + PAGE_SIZE
            Page((from until next).map(::Item), if (next < TOTAL) next.toString() else null)
        }
}

private val rowTag =
    SemanticsMatcher("tag começa com row-") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("row-") == true
    }

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.presentRowTags() =
    onAllNodes(rowTag).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }

@OptIn(ExperimentalTestApi::class)
class DataTableUiTest {
    @Test
    fun loadsNextPagesAtTheEndWithoutRepeatingRows() =
        runComposeUiTest {
            val server = Server()
            val state = TableState(server::pager) { it.id }
            setContent {
                ZenyTheme {
                    Box(Modifier.width(400.dp).height(200.dp)) { DataTable(state, columns) }
                }
            }
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.isNotEmpty() }
            repeat(PAGES + 2) {
                waitForIdle()
                val tags = presentRowTags()
                assertEquals(tags.size, tags.toSet().size)
                onNodeWithTag("table-list").performScrollToIndex(state.rows.size - 1)
                waitForIdle()
            }
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == TOTAL }
            assertEquals(PAGES, server.fetches)
            assertEquals(
                TOTAL,
                state.rows
                    .map { it.id }
                    .toSet()
                    .size,
            )
            onNodeWithTag("table-list").performScrollToIndex(TOTAL - 1)
            waitForIdle()
            onNodeWithTag("row-149").assertExists()
        }

    @Test
    fun clickingARowCallsOnRowClickWithItsItem() =
        runComposeUiTest {
            val state = TableState(Server()::pager) { it.id }
            val clicked = mutableListOf<Item>()
            setContent {
                ZenyTheme {
                    Box(Modifier.width(400.dp).height(200.dp)) {
                        DataTable(state, columns, onRowClick = { clicked += it })
                    }
                }
            }
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.isNotEmpty() }
            waitForIdle()
            onNodeWithTag("row-1").performClick()
            assertEquals(listOf(Item(1)), clicked)
        }

    @Test
    fun activeFilterStopsAutoLoadAndOffersLoadMoreButton() =
        runComposeUiTest {
            val server = Server()
            val state = TableState(server::pager) { it.id }
            setContent {
                ZenyTheme {
                    Box(Modifier.width(400.dp).height(300.dp)) { DataTable(state, columns) }
                }
            }
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.isNotEmpty() }
            waitForIdle()
            onNodeWithTag("table-filter").performTextInput("item 3")
            waitForIdle()
            assertEquals(1, server.fetches)
            onNodeWithTag("table-list").performScrollToIndex(state.visibleRows(columns).size)
            onNodeWithTag("load-more").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { state.rows.size == PAGE_SIZE * 2 }
            assertEquals(2, server.fetches)
        }
}
