package com.zeny.desktop.design.table

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val PAGE_SIZE = 50

/** Página de 50 inteiros a partir de [from]. */
private fun page(
    from: Int,
    next: String?,
) = Page((from until from + PAGE_SIZE).toList(), next)

/** `fetch` falso que segura cada resposta num `CompletableDeferred` até o teste soltá-la. */
private class HeldFetch<T> {
    val cursors = mutableListOf<String?>()
    val replies = mutableListOf<CompletableDeferred<Page<T>>>()

    fun pager() =
        CursorPager<T>(PAGE_SIZE) { cursor, _ ->
            cursors += cursor
            val reply = CompletableDeferred<Page<T>>()
            replies += reply
            reply.await()
        }
}

private data class Snapshot(
    val rows: List<Int>,
    val loading: Boolean,
)

/** `pagerFactory` que entrega os pagers em ordem e anota o estado da tabela a cada troca de pager. */
private class RecordingPagers(
    fetches: List<HeldFetch<Int>>,
) {
    private val pending = ArrayDeque(fetches)
    lateinit var state: TableState<Int>
    val seenOnReplace = mutableListOf<Snapshot>()

    fun next(): CursorPager<Int> {
        if (::state.isInitialized) seenOnReplace += Snapshot(state.rows, state.loading)
        return pending.removeFirst().pager()
    }
}

/** `fetch` falso que responde na hora, pelo cursor recebido. */
private class ImmediateFetch<T>(
    private val answer: (cursor: String?) -> Page<T>,
) {
    val cursors = mutableListOf<String?>()

    fun pager() =
        CursorPager<T>(PAGE_SIZE) { cursor, _ ->
            cursors += cursor
            answer(cursor)
        }
}

private data class Guest(
    val id: Int,
    val name: String,
)

class TableStateTest {
    @Test
    fun concurrentLoadMoreFetchesOnce() =
        runTest(timeout = 5.seconds) {
            val fetch = HeldFetch<Int>()
            val state = TableState(fetch::pager) { it }

            val first = launch { state.loadMore() }
            runCurrent()
            assertTrue(state.loading)
            val second = launch { state.loadMore() }
            runCurrent()

            assertTrue(second.isCompleted)
            assertEquals(1, fetch.cursors.size)
            fetch.replies[0].complete(page(0, "c1"))
            runCurrent()
            assertTrue(first.isCompleted)
            assertEquals((0 until 50).toList(), state.rows)
            assertFalse(state.loading)
            assertTrue(state.hasMore)
        }

    @Test
    fun duplicateKeysAreDropped() =
        runTest(timeout = 5.seconds) {
            val fetch = ImmediateFetch { cursor -> if (cursor == null) page(0, "c1") else page(25, "c2") }
            val state = TableState(fetch::pager) { it }

            state.loadMore()
            state.loadMore()

            assertEquals((0 until 75).toList(), state.rows)
            assertTrue(state.hasMore)
        }

    @Test
    fun pageWithOnlyDuplicatesStops() =
        runTest(timeout = 5.seconds) {
            val fetch = ImmediateFetch { cursor -> page(0, "c-${cursor.orEmpty()}x") }
            val state = TableState(fetch::pager) { it }

            state.loadMore()
            state.loadMore()
            assertFalse(state.hasMore)
            state.loadMore()

            assertEquals(2, fetch.cursors.size)
            assertEquals((0 until 50).toList(), state.rows)
        }

    @Test
    fun emptyPageWithCursorStops() =
        runTest(timeout = 5.seconds) {
            val fetch = ImmediateFetch { cursor -> if (cursor == null) page(0, "c1") else Page(emptyList(), "c2") }
            val state = TableState(fetch::pager) { it }

            state.loadMore()
            state.loadMore()
            assertFalse(state.hasMore)
            state.loadMore()

            assertEquals(2, fetch.cursors.size)
        }

    @Test
    fun failedPageKeepsRowsAndRetriesSameCursor() =
        runTest(timeout = 5.seconds) {
            var failNext = false
            val fetch =
                ImmediateFetch { cursor ->
                    if (failNext) {
                        failNext = false
                        throw ApiError.Network(IOException("sem rede"))
                    }
                    if (cursor == null) page(0, "c1") else page(50, "c2")
                }
            val state = TableState(fetch::pager) { it }

            state.loadMore()
            failNext = true
            state.loadMore()

            assertIs<ApiError.Network>(state.error)
            assertEquals((0 until 50).toList(), state.rows)
            assertFalse(state.loading)
            assertTrue(state.hasMore)

            state.loadMore()

            assertEquals(listOf(null, "c1", "c1"), fetch.cursors)
            assertEquals((0 until 100).toList(), state.rows)
            assertNull(state.error)
        }

    @Test
    fun cancelledLoadMoreAllowsNextLoad() =
        runTest(timeout = 5.seconds) {
            val fetch = HeldFetch<Int>()
            val state = TableState(fetch::pager) { it }

            val cancelled = launch { state.loadMore() }
            runCurrent()
            cancelled.cancel()
            runCurrent()
            assertTrue(cancelled.isCancelled)
            assertFalse(state.loading)

            val next = launch { state.loadMore() }
            runCurrent()
            assertEquals(listOf<String?>(null, null), fetch.cursors)
            fetch.replies[1].complete(page(0, "c1"))
            runCurrent()

            assertTrue(next.isCompleted)
            assertEquals((0 until 50).toList(), state.rows)
            assertFalse(state.loading)
            assertNull(state.error)
        }

    @Test
    fun reloadDiscardsInFlightPage() =
        runTest(timeout = 5.seconds) {
            val old = HeldFetch<Int>()
            val new = HeldFetch<Int>()
            val pagers = RecordingPagers(listOf(old, new))
            val state = TableState(pagers::next) { it }
            pagers.state = state

            val loading = launch { state.loadMore() }
            runCurrent()
            val reloading = launch { state.reload() }
            runCurrent()
            // O reload não espera a página antiga: cancela o fetch dela e já pede a primeira do pager novo.
            assertEquals(listOf<String?>(null), new.cursors)
            old.replies[0].complete(page(0, "velho"))
            runCurrent()

            assertFalse(loading.isCancelled)
            assertTrue(loading.isCompleted)
            assertTrue(state.rows.isEmpty())
            assertTrue(state.loading)
            assertEquals(listOf(Snapshot(emptyList(), loading = true)), pagers.seenOnReplace)

            new.replies[0].complete(page(100, "novo"))
            runCurrent()

            assertTrue(reloading.isCompleted)
            assertEquals((100 until 150).toList(), state.rows)
            assertEquals(1, new.cursors.size)
            assertFalse(state.loading)
            assertTrue(state.hasMore)
        }

    @Test
    fun reloadDiscardsPageThatArrivedBeforeIt() =
        runTest(timeout = 5.seconds) {
            val old = HeldFetch<Int>()
            val new = HeldFetch<Int>()
            val pagers = RecordingPagers(listOf(old, new))
            val state = TableState(pagers::next) { it }
            pagers.state = state

            launch { state.loadMore() }
            runCurrent()
            // A página antiga chega, mas a carga só retoma depois que o reload já começou.
            old.replies[0].complete(page(0, "velho"))
            launch { state.reload() }
            runCurrent()

            assertEquals(listOf(Snapshot(emptyList(), loading = true)), pagers.seenOnReplace)
            assertTrue(state.rows.isEmpty())
            assertTrue(state.loading)
            new.replies[0].complete(page(100, "novo"))
            runCurrent()

            assertEquals((100 until 150).toList(), state.rows)
            assertFalse(state.loading)
        }

    @Test
    fun consecutiveReloadsReplaceThePagerOnce() =
        runTest(timeout = 5.seconds) {
            val old = HeldFetch<Int>()
            val new = HeldFetch<Int>()
            val unused = HeldFetch<Int>()
            val pagers = RecordingPagers(listOf(old, new, unused))
            val state = TableState(pagers::next) { it }
            pagers.state = state

            launch { state.loadMore() }
            runCurrent()
            val first = launch { state.reload() }
            val second = launch { state.reload() }
            runCurrent()

            assertTrue(first.isCompleted)
            assertEquals(1, pagers.seenOnReplace.size)
            assertEquals(listOf<String?>(null), new.cursors)
            new.replies[0].complete(page(100, "novo"))
            runCurrent()

            assertTrue(second.isCompleted)
            assertEquals((100 until 150).toList(), state.rows)
            assertTrue(unused.cursors.isEmpty())
            assertFalse(state.loading)
        }

    @Test
    fun callerCancelledWhileReloadDiscardsItsPageStillCancels() =
        runTest(timeout = 5.seconds) {
            val old = HeldFetch<Int>()
            val new = HeldFetch<Int>()
            val pagers = RecordingPagers(listOf(old, new))
            val state = TableState(pagers::next) { it }
            pagers.state = state
            var resumedAfterLoad = false

            val loading =
                launch {
                    state.loadMore()
                    resumedAfterLoad = true
                }
            runCurrent()
            launch { state.reload() }
            loading.cancel()
            runCurrent()

            assertTrue(loading.isCancelled)
            assertFalse(resumedAfterLoad)
            assertEquals(listOf<String?>(null), new.cursors)
            new.replies[0].complete(page(100, "novo"))
            runCurrent()
            assertEquals((100 until 150).toList(), state.rows)
        }

    @Test
    fun nonApiErrorPropagatesAndClearsLoading() =
        runTest(timeout = 5.seconds) {
            val fetch = ImmediateFetch<Int> { error("falha de programação") }
            val state = TableState(fetch::pager) { it }

            assertFailsWith<IllegalStateException> { state.loadMore() }

            assertFalse(state.loading)
            assertNull(state.error)
            assertTrue(state.rows.isEmpty())
        }

    @Test
    fun stopsAtLastPage() =
        runTest(timeout = 5.seconds) {
            val fetch = ImmediateFetch { cursor -> if (cursor == null) page(0, "c1") else page(50, null) }
            val state = TableState(fetch::pager) { it }

            state.loadMore()
            assertTrue(state.hasMore)
            state.loadMore()
            assertFalse(state.hasMore)
            state.loadMore()

            assertEquals(listOf(null, "c1"), fetch.cursors)
            assertEquals((0 until 100).toList(), state.rows)
        }

    @Test
    fun sortAndFilterAreLocal() =
        runTest(timeout = 5.seconds) {
            val guests = listOf(Guest(1, "Ana"), Guest(2, "bruno"), Guest(3, "Mariana"), Guest(4, "Carlos"))
            val fetch = ImmediateFetch { Page(guests, "c1") }
            val state = TableState(fetch::pager) { it.id }
            val columns =
                listOf(
                    Column<Guest>("id", "Código", comparator = compareBy { it.id }) { it.id.toString() },
                    Column<Guest>("name", "Nome", comparator = compareBy { it.name.lowercase() }) { it.name },
                )
            state.loadMore()

            assertEquals(guests, state.visibleRows(columns))
            state.filter = "AN"
            assertEquals(listOf(guests[0], guests[2]), state.visibleRows(columns))
            state.sort = "name" to false
            assertEquals(listOf(guests[2], guests[0]), state.visibleRows(columns))
            state.filter = ""
            state.sort = "name" to true
            assertEquals(listOf(guests[0], guests[1], guests[3], guests[2]), state.visibleRows(columns))
            state.filter = "4"
            assertEquals(listOf(guests[3]), state.visibleRows(columns))

            assertEquals(1, fetch.cursors.size)
            assertEquals(guests, state.rows)
        }
}
