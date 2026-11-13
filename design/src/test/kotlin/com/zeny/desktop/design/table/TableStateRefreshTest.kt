package com.zeny.desktop.design.table

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Uma reserva fictícia: a `key` da tabela é o `id`; a `version` muda quando a reserva muda no servidor. */
private data class Booking(
    val id: Int,
    val version: Int = 1,
)

/** Um pedido de página ao fetch falso: de qual pager (na ordem de criação), com qual cursor e a resposta. */
private class Call(
    val pager: Int,
    val cursor: String?,
    val reply: CompletableDeferred<Page<Booking>>,
)

/** `pagerFactory` falsa: cada pager pede ao mesmo fetch, que segura cada resposta até o teste soltá-la. */
private class HeldPagers {
    val calls = mutableListOf<Call>()
    var created = 0
        private set

    fun pager(): CursorPager<Booking> {
        val index = created++
        return CursorPager(50) { cursor, _ ->
            val reply = CompletableDeferred<Page<Booking>>()
            calls += Call(index, cursor, reply)
            reply.await()
        }
    }

    fun answer(
        next: String?,
        vararg bookings: Booking,
    ) = calls.last().reply.complete(Page(bookings.toList(), next))

    fun fail() = calls.last().reply.completeExceptionally(ApiError.Network(IOException("sem rede")))
}

@OptIn(ExperimentalCoroutinesApi::class)
class TableStateRefreshTest {
    private val pagers = HeldPagers()
    private val state = TableState(pagers::pager) { it.id }

    /** Carrega a primeira página da lista antiga: pager 0, sem cursor. */
    private fun TestScope.loadOld(
        next: String?,
        vararg bookings: Booking,
    ) {
        launch { state.loadMore() }
        runCurrent()
        pagers.answer(next, *bookings)
        runCurrent()
    }

    /** Um `refresh()` cuja primeira página falha com `ApiError`. */
    private fun TestScope.failRefresh() {
        launch { state.refresh() }
        runCurrent()
        pagers.fail()
        runCurrent()
    }

    @Test
    fun refreshKeepsRowsUntilNewPage() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))

            val refreshing = launch { state.refresh() }
            runCurrent()

            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            assertTrue(state.loading)
            assertTrue(state.hasMore)
            assertEquals(1, pagers.calls.last().pager)
            assertNull(pagers.calls.last().cursor)

            pagers.answer(null, Booking(3), Booking(4))
            runCurrent()

            assertTrue(refreshing.isCompleted)
            assertEquals(listOf(Booking(3), Booking(4)), state.rows)
            assertFalse(state.loading)
            assertFalse(state.hasMore)
            assertNull(state.error)
        }

    @Test
    fun refreshReplacesHasMoreAndKeys() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1), Booking(2))
            assertFalse(state.hasMore)

            launch { state.refresh() }
            runCurrent()
            pagers.answer("n1", Booking(2, version = 2), Booking(3))
            runCurrent()

            assertEquals(listOf(Booking(2, version = 2), Booking(3)), state.rows)
            assertTrue(state.hasMore)

            launch { state.loadMore() }
            runCurrent()
            assertEquals(1, pagers.calls.last().pager)
            assertEquals("n1", pagers.calls.last().cursor)
            // A reserva 1 só existia na lista antiga: a chave dela saiu junto e ela volta como linha nova.
            pagers.answer(null, Booking(1), Booking(4))
            runCurrent()

            assertEquals(listOf(Booking(2, version = 2), Booking(3), Booking(1), Booking(4)), state.rows)
            assertFalse(state.hasMore)
        }

    @Test
    fun refreshErrorKeepsRows() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))

            failRefresh()

            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            assertIs<ApiError.Network>(state.error)
            assertFalse(state.loading)
            assertTrue(state.hasMore)
        }

    @Test
    fun retryAfterRefreshErrorRefreshes() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1), Booking(2))
            assertFalse(state.hasMore)
            failRefresh()

            val retrying = launch { state.loadMore() }
            runCurrent()

            assertEquals(3, pagers.created)
            assertEquals(2, pagers.calls.last().pager)
            assertNull(pagers.calls.last().cursor)
            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            assertTrue(state.loading)
            assertIs<ApiError.Network>(state.error)

            pagers.answer(null, Booking(1, version = 2), Booking(3))
            runCurrent()

            assertTrue(retrying.isCompleted)
            assertEquals(listOf(Booking(1, version = 2), Booking(3)), state.rows)
            assertNull(state.error)
            assertFalse(state.loading)
            assertFalse(state.hasMore)

            // Feita a atualização, não há mais pendência: com `hasMore` falso, `loadMore` não pede nada.
            val callsBefore = pagers.calls.size
            state.loadMore()
            assertEquals(callsBefore, pagers.calls.size)
        }

    @Test
    fun retryFailingAgainKeepsRowsAndStaysPending() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1))
            failRefresh()

            launch { state.loadMore() }
            runCurrent()
            pagers.fail()
            runCurrent()

            assertEquals(listOf(Booking(1)), state.rows)
            assertIs<ApiError.Network>(state.error)
            assertFalse(state.loading)

            launch { state.loadMore() }
            runCurrent()
            assertEquals(3, pagers.calls.last().pager)
            assertNull(pagers.calls.last().cursor)
            pagers.answer(null, Booking(5))
            runCurrent()

            assertEquals(listOf(Booking(5)), state.rows)
            assertNull(state.error)
        }

    @Test
    fun cancelledRefreshLeavesTableUsable() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1), Booking(2))

            val refreshing = launch { state.refresh() }
            runCurrent()
            refreshing.cancel()
            runCurrent()

            assertTrue(refreshing.isCancelled)
            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            assertFalse(state.loading)
            assertNull(state.error)

            launch { state.loadMore() }
            runCurrent()
            assertEquals(2, pagers.calls.last().pager)
            assertNull(pagers.calls.last().cursor)
            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            pagers.answer(null, Booking(2, version = 2), Booking(3))
            runCurrent()

            assertEquals(listOf(Booking(2, version = 2), Booking(3)), state.rows)
            assertFalse(state.loading)
        }

    @Test
    fun cancelledRefreshWaitingForTheLockLeavesTableUsable() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))
            launch { state.loadMore() }
            runCurrent()
            assertEquals("c1", pagers.calls.last().cursor)

            // O refresh cancela o fetch do loadMore e espera o lock; quem o chamou é cancelado antes de pegá-lo.
            val refreshing = launch(start = CoroutineStart.UNDISPATCHED) { state.refresh() }
            refreshing.cancel()
            runCurrent()

            assertTrue(refreshing.isCancelled)
            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            assertFalse(state.loading)
            assertEquals(1, pagers.created)

            launch { state.loadMore() }
            runCurrent()
            assertEquals(1, pagers.calls.last().pager)
            assertNull(pagers.calls.last().cursor)
            pagers.answer(null, Booking(7))
            runCurrent()

            assertEquals(listOf(Booking(7)), state.rows)
        }

    @Test
    fun newerReloadCancelsRefresh() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1), Booking(2))
            launch { state.refresh() }
            runCurrent()
            val refreshCall = pagers.calls.last()

            launch { state.reload() }
            runCurrent()
            refreshCall.reply.complete(Page(listOf(Booking(9)), null))
            runCurrent()

            assertEquals(emptyList(), state.rows)
            assertTrue(state.loading)

            pagers.answer(null, Booking(3))
            runCurrent()

            assertEquals(listOf(Booking(3)), state.rows)
            assertFalse(state.loading)
        }

    @Test
    fun newerRefreshCancelsRefresh() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1), Booking(2))
            launch { state.refresh() }
            runCurrent()
            val olderCall = pagers.calls.last()

            launch { state.refresh() }
            runCurrent()
            olderCall.reply.complete(Page(listOf(Booking(9)), null))
            runCurrent()

            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            assertTrue(state.loading)

            pagers.answer(null, Booking(3))
            runCurrent()

            assertEquals(listOf(Booking(3)), state.rows)
            assertFalse(state.loading)
        }

    @Test
    fun refreshCancelsInFlightLoadMore() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))
            val loading = launch { state.loadMore() }
            runCurrent()
            val loadMoreCall = pagers.calls.last()

            launch { state.refresh() }
            runCurrent()

            assertTrue(loading.isCompleted)
            assertFalse(loading.isCancelled)
            assertEquals(1, pagers.calls.last().pager)
            loadMoreCall.reply.complete(Page(listOf(Booking(50)), null))
            runCurrent()
            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            assertTrue(state.loading)

            pagers.answer(null, Booking(7))
            runCurrent()

            assertEquals(listOf(Booking(7)), state.rows)
            assertFalse(state.loading)
        }

    @Test
    fun reloadStillEmpties() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))

            launch { state.reload() }
            runCurrent()

            assertEquals(emptyList(), state.rows)
            assertTrue(state.loading)

            pagers.answer(null, Booking(3))
            runCurrent()

            assertEquals(listOf(Booking(3)), state.rows)
        }

    @Test
    fun reloadAfterRefreshErrorClearsPending() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1))
            failRefresh()

            launch { state.reload() }
            runCurrent()
            pagers.answer(null, Booking(3))
            runCurrent()
            assertNull(state.error)
            assertFalse(state.hasMore)

            val callsBefore = pagers.calls.size
            state.loadMore()

            assertEquals(callsBefore, pagers.calls.size)
            assertEquals(listOf(Booking(3)), state.rows)
        }

    @Test
    fun loadMoreUnchangedWithoutPendingRefresh() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))

            launch { state.loadMore() }
            runCurrent()
            assertEquals(1, pagers.created)
            assertEquals(0, pagers.calls.last().pager)
            assertEquals("c1", pagers.calls.last().cursor)
            pagers.answer(null, Booking(3))
            runCurrent()

            assertEquals(listOf(Booking(1), Booking(2), Booking(3)), state.rows)
            assertFalse(state.hasMore)

            val callsBefore = pagers.calls.size
            state.loadMore()
            assertEquals(callsBefore, pagers.calls.size)
        }
}
