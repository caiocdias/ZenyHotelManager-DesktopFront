package com.zeny.desktop.design.table

import androidx.compose.runtime.snapshots.Snapshot
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Ataques ao `refresh()` da TableState (M2 E01, Task 4): intercalações com `reload`, `loadMore` e outro `refresh`
 * com páginas soltas fora de ordem, cancelamento de quem chamou em cada ponto, a regra da atualização pendente e
 * exceção que não é `ApiError`. Tempo virtual; o último teste usa threads reais, sem sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableStateRefreshAttackTest {
    /** Uma reserva fictícia: a `key` é o `id`; a `version` muda quando a reserva muda no servidor. */
    private data class Booking(
        val id: Int,
        val version: Int = 1,
    )

    /** Um pedido de página: de qual pager (na ordem de criação), com qual cursor e a resposta segurada. */
    private class Call(
        val pager: Int,
        val cursor: String?,
        val reply: CompletableDeferred<Page<Booking>>,
    ) {
        fun answer(
            next: String?,
            vararg bookings: Booking,
        ) = reply.complete(Page(bookings.toList(), next))

        fun fail() = reply.completeExceptionally(ApiError.Network(IOException("sem rede")))
    }

    /** `pagerFactory` falsa: cada pager pede ao mesmo fetch, que segura cada resposta até o teste soltá-la. */
    private class HeldPagers {
        val calls = mutableListOf<Call>()
        var created = 0
            private set

        fun pager(): CursorPager<Booking> {
            val index = created++
            return CursorPager(SIZE) { cursor, _ ->
                val reply = CompletableDeferred<Page<Booking>>()
                calls += Call(index, cursor, reply)
                reply.await()
            }
        }

        fun last() = calls.last()
    }

    /** Linha do teste em threads reais: de qual pager veio e a posição na listagem dele. */
    private data class Item(
        val pager: Int,
        val n: Int,
    )

    private val pagers = HeldPagers()
    private val state = TableState(pagers::pager) { it.id }

    /** Carrega a primeira página da lista antiga: pager 0, sem cursor. */
    private fun TestScope.loadOld(
        next: String?,
        vararg bookings: Booking,
    ) {
        launch { state.loadMore() }
        runCurrent()
        pagers.last().answer(next, *bookings)
        runCurrent()
    }

    /** Roda [block] em threads reais do pool, fora do agendador virtual do `runTest`. */
    private suspend fun onRealThreads(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        block: suspend CoroutineScope.() -> Unit,
    ) = withContext(dispatcher, block)

    /** Um `refresh()` cuja primeira página falha com `ApiError`: deixa a atualização pendente. */
    private fun TestScope.failRefresh() {
        launch { state.refresh() }
        runCurrent()
        pagers.last().fail()
        runCurrent()
    }

    // ---- páginas soltas fora de ordem ----

    @Test
    fun olderRefreshPageAnsweredAfterTheNewerOneNeverEnters() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1), Booking(2))
            launch { state.refresh() }
            runCurrent()
            val older = pagers.last()
            launch { state.refresh() }
            runCurrent()
            val newer = pagers.last()
            assertEquals(2, newer.pager)

            newer.answer(null, Booking(3))
            runCurrent()
            older.answer(null, Booking(9))
            runCurrent()

            assertEquals(listOf(Booking(3)), state.rows)
            assertFalse(state.loading)
            assertNull(state.error)
            // O refresh descartado não deixou pendência: com `hasMore` falso, `loadMore` não pede nada.
            val callsBefore = pagers.calls.size
            state.loadMore()
            assertEquals(callsBefore, pagers.calls.size)
        }

    @Test
    fun refreshPageAnsweredAfterANewerReloadNeverEntersNorLeavesPending() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1), Booking(2))
            launch { state.refresh() }
            runCurrent()
            val refreshCall = pagers.last()
            launch { state.reload() }
            runCurrent()
            val reloadCall = pagers.last()

            reloadCall.answer("r1", Booking(3))
            runCurrent()
            refreshCall.answer(null, Booking(9))
            runCurrent()

            assertEquals(listOf(Booking(3)), state.rows)
            assertTrue(state.hasMore)
            assertFalse(state.loading)

            // Sem pendência, o `loadMore` continua o pager do reload, no cursor dele, sem pager novo.
            launch { state.loadMore() }
            runCurrent()
            assertEquals(3, pagers.created)
            assertEquals(reloadCall.pager, pagers.last().pager)
            assertEquals("r1", pagers.last().cursor)
            pagers.last().answer(null, Booking(4))
            runCurrent()
            assertEquals(listOf(Booking(3), Booking(4)), state.rows)
        }

    @Test
    fun refreshAfterReloadKeepsTheEmptyTableAndIgnoresTheLateReloadPage() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))
            launch { state.reload() }
            runCurrent()
            val reloadCall = pagers.last()
            launch { state.refresh() }
            runCurrent()

            // O reload já esvaziou; o refresh não traz as linhas antigas de volta.
            assertEquals(emptyList(), state.rows)
            assertTrue(state.loading)
            assertEquals(2, pagers.last().pager)

            pagers.last().answer(null, Booking(5))
            runCurrent()
            reloadCall.answer(null, Booking(9))
            runCurrent()

            assertEquals(listOf(Booking(5)), state.rows)
            assertFalse(state.loading)
        }

    @Test
    fun oldLoadMorePageAnsweredAfterTheRefreshNeverEnters() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))
            val loading = launch { state.loadMore() }
            runCurrent()
            val loadMoreCall = pagers.last()
            launch { state.refresh() }
            runCurrent()

            pagers.last().answer("n1", Booking(3), Booking(4))
            runCurrent()
            loadMoreCall.answer(null, Booking(50))
            runCurrent()

            assertTrue(loading.isCompleted)
            assertFalse(loading.isCancelled)
            assertEquals(listOf(Booking(3), Booking(4)), state.rows)
            assertTrue(state.hasMore)
            assertFalse(state.loading)
        }

    // ---- loadMore depois de um refresh bem-sucedido ----

    @Test
    fun loadMoreAfterRefreshUsesTheNewCursorAndDropsKeysOfTheNewPageOnly() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))
            launch { state.refresh() }
            runCurrent()
            pagers.last().answer("n1", Booking(2, version = 2), Booking(3))
            runCurrent()

            launch { state.loadMore() }
            runCurrent()
            assertEquals(2, pagers.created)
            assertEquals(1, pagers.last().pager)
            assertEquals("n1", pagers.last().cursor)
            pagers.last().answer(null, Booking(3, version = 9), Booking(1), Booking(4))
            runCurrent()

            // A 3 já veio na página nova (fica a primeira); a 1 só existia na lista antiga e volta como nova.
            assertEquals(listOf(Booking(2, version = 2), Booking(3), Booking(1), Booking(4)), state.rows)
            assertFalse(state.hasMore)
        }

    @Test
    fun loadMoreDuringRefreshDoesNotFetch() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))
            launch { state.refresh() }
            runCurrent()
            val callsBefore = pagers.calls.size

            state.loadMore()

            assertEquals(callsBefore, pagers.calls.size)
            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            assertTrue(state.loading)
            pagers.last().answer(null, Booking(3))
            runCurrent()
            assertEquals(listOf(Booking(3)), state.rows)
        }

    // ---- conteúdo da primeira página nova ----

    @Test
    fun refreshWithAnEmptyNewListEmptiesTheTableAndEndsTheListing() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))
            launch { state.refresh() }
            runCurrent()

            // Página sem nenhuma linha encerra a listagem, mesmo com cursor seguinte.
            pagers.last().answer("n1")
            runCurrent()

            assertEquals(emptyList(), state.rows)
            assertFalse(state.hasMore)
            assertFalse(state.loading)
            assertNull(state.error)
            val callsBefore = pagers.calls.size
            state.loadMore()
            assertEquals(callsBefore, pagers.calls.size)
        }

    @Test
    fun refreshPageWithARepeatedKeyKeepsTheFirst() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(5), Booking(6))
            launch { state.refresh() }
            runCurrent()

            pagers.last().answer(null, Booking(5, version = 2), Booking(5, version = 3), Booking(6))
            runCurrent()

            assertEquals(listOf(Booking(5, version = 2), Booking(6)), state.rows)
        }

    @Test
    fun refreshNeverShowsAnEmptyTableOnTheWay() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))
            val seen = mutableListOf<List<Booking>>()
            // A cada escrita em estado observável, anota as linhas daquele instante.
            val probe = Snapshot.registerGlobalWriteObserver { seen += state.rows }
            try {
                launch { state.refresh() }
                runCurrent()
                pagers.last().answer(null, Booking(3))
                runCurrent()

                assertEquals(listOf(Booking(3)), state.rows)
                assertTrue(seen.isNotEmpty(), "o observador não viu nenhuma escrita")
                assertTrue(seen.none { it.isEmpty() }, "a tabela passou por vazia: $seen")

                // Controle: a mesma sonda vê o reload esvaziar a tabela.
                launch { state.reload() }
                runCurrent()
                assertTrue(seen.any { it.isEmpty() }, "a sonda não enxerga a tabela vazia")
                pagers.last().answer(null, Booking(4))
                runCurrent()
            } finally {
                probe.dispose()
            }
        }

    // ---- cancelamento de quem chamou o refresh ----

    @Test
    fun refreshCancelledWithTheLockBeforeTheFetchStaysPending() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1), Booking(2))
            val callsBefore = pagers.calls.size

            // Já cancelado ao pegar o lock livre: o fetch nem começa.
            val refreshing =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    this.coroutineContext.job.cancel()
                    state.refresh()
                }
            runCurrent()

            assertTrue(refreshing.isCancelled)
            assertEquals(callsBefore, pagers.calls.size)
            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            assertFalse(state.loading)
            assertNull(state.error)

            launch { state.loadMore() }
            runCurrent()
            assertNull(pagers.last().cursor)
            assertEquals(pagers.created - 1, pagers.last().pager)
            pagers.last().answer(null, Booking(3))
            runCurrent()

            assertEquals(listOf(Booking(3)), state.rows)
        }

    @Test
    fun refreshCancelledInTheLockQueueBehindAnOlderRefreshStaysPending() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1), Booking(2))
            launch { state.refresh() }
            runCurrent()
            val olderCall = pagers.last()

            val newer = launch(start = CoroutineStart.UNDISPATCHED) { state.refresh() }
            newer.cancel()
            runCurrent()
            olderCall.answer(null, Booking(9))
            runCurrent()

            assertTrue(newer.isCancelled)
            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            assertFalse(state.loading)
            assertNull(state.error)
            assertEquals(2, pagers.created)

            // A pendência repete a atualização mesmo com `hasMore` falso na lista antiga.
            launch { state.loadMore() }
            runCurrent()
            assertEquals(2, pagers.last().pager)
            assertNull(pagers.last().cursor)
            pagers.last().answer(null, Booking(4))
            runCurrent()

            assertEquals(listOf(Booking(4)), state.rows)
            assertFalse(state.loading)
        }

    @Test
    fun refreshCancelledInTheLockQueueBehindANewerReloadLeavesNoPending() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))
            launch { state.loadMore() }
            runCurrent()

            val refreshing = launch(start = CoroutineStart.UNDISPATCHED) { state.refresh() }
            launch(start = CoroutineStart.UNDISPATCHED) { state.reload() }
            refreshing.cancel()
            runCurrent()

            assertTrue(refreshing.isCancelled)
            assertEquals(emptyList(), state.rows)
            assertTrue(state.loading)
            assertEquals(1, pagers.last().pager)
            pagers.last().answer("r1", Booking(7))
            runCurrent()
            assertEquals(listOf(Booking(7)), state.rows)
            assertFalse(state.loading)

            // Sem pendência do refresh cancelado: o `loadMore` segue o pager do reload.
            launch { state.loadMore() }
            runCurrent()
            assertEquals(2, pagers.created)
            assertEquals(1, pagers.last().pager)
            assertEquals("r1", pagers.last().cursor)
            pagers.last().answer(null, Booking(8))
            runCurrent()
            assertEquals(listOf(Booking(7), Booking(8)), state.rows)
        }

    // ---- a repetição pendente pelo loadMore ----

    @Test
    fun cancelledRetryOfAPendingRefreshStaysPending() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1))
            failRefresh()

            val retrying = launch { state.loadMore() }
            runCurrent()
            assertEquals(2, pagers.last().pager)
            retrying.cancel()
            runCurrent()

            assertTrue(retrying.isCancelled)
            assertEquals(listOf(Booking(1)), state.rows)
            assertFalse(state.loading)

            launch { state.loadMore() }
            runCurrent()
            assertEquals(3, pagers.last().pager)
            assertNull(pagers.last().cursor)
            pagers.last().answer(null, Booking(4))
            runCurrent()

            assertEquals(listOf(Booking(4)), state.rows)
            assertNull(state.error)
        }

    @Test
    fun newerRefreshDuringTheRetryOfAPendingRefreshWins() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1))
            failRefresh()
            val retrying = launch { state.loadMore() }
            runCurrent()
            val retryCall = pagers.last()

            launch { state.refresh() }
            runCurrent()
            assertTrue(retrying.isCompleted)
            assertFalse(retrying.isCancelled)
            pagers.last().answer(null, Booking(5))
            runCurrent()
            retryCall.answer(null, Booking(9))
            runCurrent()

            assertEquals(listOf(Booking(5)), state.rows)
            assertNull(state.error)
            assertFalse(state.loading)
            val callsBefore = pagers.calls.size
            state.loadMore()
            assertEquals(callsBefore, pagers.calls.size)
        }

    @Test
    fun reloadDuringTheRetryOfAPendingRefreshWinsAndUndoesThePending() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1))
            failRefresh()
            launch { state.loadMore() }
            runCurrent()
            val retryCall = pagers.last()

            launch { state.reload() }
            runCurrent()
            assertEquals(emptyList(), state.rows)
            val reloadCall = pagers.last()
            reloadCall.answer("r1", Booking(5))
            runCurrent()
            retryCall.answer(null, Booking(9))
            runCurrent()

            assertEquals(listOf(Booking(5)), state.rows)
            assertNull(state.error)
            launch { state.loadMore() }
            runCurrent()
            assertEquals(reloadCall.pager, pagers.last().pager)
            assertEquals("r1", pagers.last().cursor)
            pagers.last().answer(null, Booking(6))
            runCurrent()
            assertEquals(listOf(Booking(5), Booking(6)), state.rows)
        }

    // ---- reload desfaz a pendência ----

    @Test
    fun failedReloadAlsoUndoesThePendingRefresh() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1))
            failRefresh()
            launch { state.reload() }
            runCurrent()
            pagers.last().fail()
            runCurrent()
            assertEquals(emptyList(), state.rows)
            assertIs<ApiError.Network>(state.error)

            // Tentar de novo pede o mesmo cursor ao pager do reload, sem criar outro.
            launch { state.loadMore() }
            runCurrent()
            assertEquals(3, pagers.created)
            assertEquals(2, pagers.last().pager)
            assertNull(pagers.last().cursor)
            pagers.last().answer(null, Booking(3))
            runCurrent()
            assertEquals(listOf(Booking(3)), state.rows)
            assertNull(state.error)
        }

    @Test
    fun reloadCancelledInTheLockQueueAlsoUndoesThePendingRefresh() =
        runTest(timeout = 5.seconds) {
            loadOld(null, Booking(1))
            failRefresh()
            launch { state.loadMore() }
            runCurrent()
            assertEquals(3, pagers.created)

            val reloading = launch(start = CoroutineStart.UNDISPATCHED) { state.reload() }
            reloading.cancel()
            runCurrent()

            assertTrue(reloading.isCancelled)
            assertEquals(emptyList(), state.rows)
            assertNull(state.error)
            assertFalse(state.loading)
            assertEquals(4, pagers.created)

            // O reload abandonado já trocou o pager; o `loadMore` usa esse, sem repetir atualização nenhuma.
            launch { state.loadMore() }
            runCurrent()
            assertEquals(4, pagers.created)
            assertEquals(3, pagers.last().pager)
            assertNull(pagers.last().cursor)
            pagers.last().answer(null, Booking(3))
            runCurrent()
            assertEquals(listOf(Booking(3)), state.rows)
        }

    // ---- exceção que não é ApiError ----

    @Test
    fun nonApiErrorInRefreshPropagatesKeepsRowsAndLeavesItPending() =
        runTest(timeout = 5.seconds) {
            loadOld("c1", Booking(1), Booking(2))
            var failure: Throwable? = null
            launch { failure = runCatching { state.refresh() }.exceptionOrNull() }
            runCurrent()
            pagers.last().reply.completeExceptionally(IllegalStateException("falha de programação"))
            runCurrent()

            assertIs<IllegalStateException>(failure)
            assertEquals(listOf(Booking(1), Booking(2)), state.rows)
            assertTrue(state.hasMore)
            assertNull(state.error)
            assertFalse(state.loading)

            // A tabela não trava nem acrescenta página do pager velho: o `loadMore` repete a atualização.
            launch { state.loadMore() }
            runCurrent()
            assertEquals(2, pagers.last().pager)
            assertNull(pagers.last().cursor)
            pagers.last().answer(null, Booking(3))
            runCurrent()

            assertEquals(listOf(Booking(3)), state.rows)
        }

    // ---- threads reais ----

    @Test
    fun mixedRefreshReloadLoadMoreAndCancelledRefreshesOnRealThreadsEndConsistent() =
        runTest(timeout = 30.seconds) {
            onRealThreads {
                val pagerIds = AtomicInteger()
                val factory = {
                    val id = pagerIds.incrementAndGet()
                    CursorPager<Item>(SIZE) { cursor, _ ->
                        yield()
                        val index = cursor?.toInt() ?: 0
                        val next = if (index < LAST_PAGE) "${index + 1}" else null
                        Page((index * SIZE until (index + 1) * SIZE).map { Item(id, it) }, next)
                    }
                }
                // Mesma chave em pagers diferentes: a troca precisa esquecer as chaves antigas.
                val table = TableState(factory) { it.n }
                var replayedRounds = 0

                repeat(ROUNDS) { round ->
                    val start = CompletableDeferred<Unit>()
                    val refreshers = List(REFRESHERS) { launch { start.await().also { table.refresh() } } }
                    val canceller =
                        launch {
                            start.await()
                            yield()
                            refreshers[round % REFRESHERS].cancel()
                        }
                    val jobs =
                        refreshers + canceller +
                            List(LOADERS) { launch { start.await().also { table.loadMore() } } } +
                            launch { start.await().also { table.reload() } }
                    start.complete(Unit)
                    jobs.joinAll()

                    assertFalse(table.loading, "rodada $round: loading ficou true")
                    assertNull(table.error, "rodada $round")
                    // Repete a atualização se ela ficou pendente; senão acrescenta do pager atual.
                    val before = pagerIds.get()
                    table.loadMore()

                    val current = pagerIds.get()
                    if (current != before) replayedRounds++
                    assertFalse(table.loading, "rodada $round: loading ficou true depois do loadMore")
                    assertTrue(table.rows.isNotEmpty(), "rodada $round: a tabela travou vazia")
                    assertTrue(table.rows.all { it.pager == current }, "rodada $round: linha de pager antigo")
                    assertEquals((0 until table.rows.size).toList(), table.rows.map { it.n }, "rodada $round")
                }
                // Só informativo (não é asserção, para não depender do agendamento): quantas rodadas pararam pendentes.
                println("rodadas que terminaram com a atualização pendente: $replayedRounds de $ROUNDS")
            }
        }

    private companion object {
        const val SIZE = 50
        const val LAST_PAGE = 3
        const val ROUNDS = 300
        const val REFRESHERS = 2
        const val LOADERS = 3
    }
}
