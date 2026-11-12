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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Ataques ao TableState (Task 5): concorrência no carregamento, gerações, cancelamento, ordenação e filtro.
 * Os testes de interleaving usam o observador de snapshot do Compose para pôr um `reload` exatamente entre
 * dois passos do `loadMore`, como faria outra thread, sem sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableStateAttackTest {
    /** `fetch` que segura cada resposta até o teste soltá-la; cada `pager()` é um pager novo sobre o mesmo fetch. */
    private class Held {
        val cursors = mutableListOf<String?>()
        val replies = mutableListOf<CompletableDeferred<Page<Int>>>()

        fun pager() =
            CursorPager<Int>(SIZE) { cursor, _ ->
                cursors += cursor
                val reply = CompletableDeferred<Page<Int>>()
                replies += reply
                reply.await()
            }

        /** Solta a última resposta pedida com a página que corresponde ao cursor dela. */
        fun answerLast(offset: Int = 0) = replies.last().complete(pageFor(cursors.last(), offset))
    }

    /** Estado da tabela no instante em que o `reload` troca o pager. */
    private data class Seen(
        val rows: List<Int>,
        val loading: Boolean,
        val error: ApiError?,
    )

    private data class Guest(
        val id: Int,
        val name: String,
    )

    private data class Item(
        val pager: Int,
        val n: Int,
    )

    private val idColumn = Column<Guest>("id", "Código", comparator = compareBy { it.id }) { it.id.toString() }
    private val nameColumn = Column<Guest>("name", "Nome", comparator = compareBy { it.name }) { it.name }
    private val plainColumn = Column<Guest>("plain", "Sem ordem") { it.name }

    private suspend fun TableState<Guest>.loaded(): TableState<Guest> = also { loadMore() }

    /** Tabela de uma página só com [guests], na ordem do servidor. */
    private fun guests(vararg guests: Guest): TableState<Guest> {
        val single = Page(guests.toList(), null)
        return TableState({ CursorPager(SIZE) { _, _ -> single } }) { it.id }
    }

    /** Roda [block] em threads reais do pool, fora do agendador virtual do `runTest`. */
    private suspend fun onRealThreads(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        block: suspend CoroutineScope.() -> Unit,
    ) = withContext(dispatcher, block)

    /**
     * Começa um `loadMore` sem despachar e, no primeiro acesso dele a estado observável (leitura de `hasMore`
     * se [onRead], senão a escrita de `loading`), começa um `reload` no mesmo ponto, como outra thread faria.
     */
    private fun TestScope.loadMoreInterruptedByReload(
        state: TableState<Int>,
        onRead: Boolean,
    ): Job {
        var reload: Job? = null
        val interrupt: (Any) -> Unit = {
            if (reload == null) reload = launch(start = CoroutineStart.UNDISPATCHED) { state.reload() }
        }
        Snapshot.observe(readObserver = interrupt.takeIf { onRead }, writeObserver = interrupt.takeIf { !onRead }) {
            launch(start = CoroutineStart.UNDISPATCHED) { state.loadMore() }
        }
        return checkNotNull(reload) { "o loadMore não tocou em estado observável" }
    }

    // ---- reload e gerações ----

    @Test
    fun loadMoreDuringReloadDoesNotFetch() =
        runTest(timeout = 5.seconds) {
            val old = Held()
            val new = Held()
            val state = TableState(Pagers(old::pager, new::pager)::next) { it }

            launch { state.reload() }
            runCurrent()
            val extra = launch { state.loadMore() }
            runCurrent()

            assertTrue(extra.isCompleted)
            assertEquals(listOf<String?>(null), new.cursors)
            assertTrue(old.cursors.isEmpty())
            assertTrue(state.loading)
            new.answerLast()
            runCurrent()
            assertEquals((0 until 50).toList(), state.rows)
        }

    @Test
    fun reloadDuringReloadKeepsOnlyTheNewestPager() =
        runTest(timeout = 5.seconds) {
            val fetches = List(3) { Held() }
            val pagers = Pagers(fetches[0]::pager, fetches[1]::pager, fetches[2]::pager)
            val state = TableState(pagers::next) { it }

            val loading = launch { state.loadMore() }
            runCurrent()
            val first = launch { state.reload() }
            runCurrent()
            val second = launch { state.reload() }
            runCurrent()

            assertEquals(3, pagers.created)
            // O primeiro reload perdeu a vez, mas quem o chamou termina normalmente, sem ser cancelado.
            assertTrue(first.isCompleted && !first.isCancelled)
            assertTrue(loading.isCompleted && !loading.isCancelled)
            fetches[0].replies[0].complete(page(0, "velho"))
            fetches[1].replies[0].complete(page(500, "meio"))
            runCurrent()
            assertTrue(state.rows.isEmpty())
            assertTrue(state.loading)

            fetches[2].answerLast(offset = 1000)
            runCurrent()

            assertTrue(second.isCompleted && !second.isCancelled)
            assertEquals((1000 until 1050).toList(), state.rows)
            assertEquals(1, fetches[2].cursors.size)
            assertFalse(state.loading)
        }

    @Test
    fun discardedLoadMoreDoesNotCancelCallerScope() =
        runTest(timeout = 5.seconds) {
            val old = Held()
            val new = Held()
            val state = TableState(Pagers(old::pager, new::pager)::next) { it }
            val siblingGate = CompletableDeferred<Unit>()
            var afterLoad = false

            val parent =
                launch {
                    launch { siblingGate.await() }
                    state.loadMore()
                    afterLoad = true
                }
            runCurrent()
            launch { state.reload() }
            runCurrent()

            assertTrue(afterLoad)
            assertTrue(parent.isActive)
            siblingGate.complete(Unit)
            new.answerLast()
            runCurrent()
            assertTrue(parent.isCompleted && !parent.isCancelled)
        }

    @Test
    fun cancelledReloadCallerPropagatesAndLeavesStateUsable() =
        runTest(timeout = 5.seconds) {
            val old = ImmediatePages()
            val new = Held()
            val state = TableState(Pagers(old::pager, new::pager)::next) { it }
            state.loadMore()

            val reloading = launch { state.reload() }
            runCurrent()
            reloading.cancel()
            runCurrent()

            assertTrue(reloading.isCancelled)
            assertFalse(state.loading)
            assertTrue(state.rows.isEmpty())
            assertNull(state.error)
            launch { state.loadMore() }
            runCurrent()
            // O pager novo não avançou: a próxima carga pede a primeira página dele de novo.
            assertEquals(listOf<String?>(null, null), new.cursors)
            new.answerLast()
            runCurrent()
            assertEquals((0 until 50).toList(), state.rows)
        }

    @Test
    fun cancelledReloadWaitingForTheLockDoesNotLeaveLoadingStuck() =
        runTest(timeout = 5.seconds) {
            val old = Held()
            val state = TableState(Pagers(old::pager, Held()::pager)::next) { it }

            val loading = launch { state.loadMore() }
            runCurrent()
            // O reload cancela o fetch antigo e espera o lock; quem o chamou é cancelado antes de pegá-lo.
            val reloading = launch(start = CoroutineStart.UNDISPATCHED) { state.reload() }
            reloading.cancel()
            runCurrent()

            assertTrue(reloading.isCancelled)
            assertTrue(loading.isCompleted)
            assertTrue(state.rows.isEmpty())
            assertFalse(state.loading, "nenhuma carga em andamento, mas loading ficou true")
        }

    @Test
    fun cancelledReloadDoesNotSkipAPage() =
        runTest(timeout = 5.seconds) {
            val fetch = Held()
            val state = TableState(fetch::pager) { it }

            launch { state.loadMore() }
            runCurrent()
            // A primeira página chega (o pager avança), mas a carga só retoma depois que um reload começou;
            // quem chamou o reload é cancelado enquanto ele espera o lock.
            fetch.answerLast()
            val reloading = launch { state.reload() }
            launch { reloading.cancel() }
            runCurrent()
            assertTrue(reloading.isCancelled)

            launch { state.loadMore() }
            runCurrent()
            fetch.answerLast()
            runCurrent()

            assertEquals((0 until 50).toList(), state.rows.take(50), "a tabela pulou a primeira página")
        }

    @Test
    fun reloadStartedBeforeLoadMoreReadsTheGenerationDiscardsThatPage() =
        runTest(timeout = 5.seconds) {
            val old = Held()
            val new = Held()
            val pagers = Pagers(old::pager, new::pager)
            val state = TableState(pagers::next) { it }
            pagers.watch = state

            loadMoreInterruptedByReload(state, onRead = true)
            runCurrent()
            if (old.replies.isNotEmpty()) old.replies[0].complete(page(0, "velho"))
            runCurrent()

            // Quando o reload troca o pager, nenhuma linha do pager antigo foi publicada e loading não caiu.
            assertEquals(listOf(Seen(emptyList(), true, null)), pagers.seenOnReplace, "estado visto na troca de pager")
            new.answerLast(offset = 100)
            runCurrent()
            assertEquals((100 until 150).toList(), state.rows)
            assertFalse(state.loading)
        }

    @Test
    fun reloadStartedBeforeLoadMoreRegistersItsFetchDoesNotWaitForIt() =
        runTest(timeout = 5.seconds) {
            val old = Held()
            val new = Held()
            val state = TableState(Pagers(old::pager, new::pager)::next) { it }

            val reloading = loadMoreInterruptedByReload(state, onRead = false)
            runCurrent()

            assertEquals(listOf<String?>(null), new.cursors, "o reload ficou esperando a página do pager antigo")
            new.answerLast(offset = 100)
            runCurrent()
            assertTrue(reloading.isCompleted)
            assertEquals((100 until 150).toList(), state.rows)
            assertFalse(state.loading)
        }

    @Test
    fun reloadAcceptsKeysSeenBeforeReload() =
        runTest(timeout = 5.seconds) {
            val fetch = ImmediatePages()
            val state = TableState(fetch::pager) { it }
            state.loadMore()

            state.reload()

            assertEquals((0 until 50).toList(), state.rows)
            assertTrue(state.hasMore)
        }

    @Test
    fun reloadAfterLastPageLoadsAgain() =
        runTest(timeout = 5.seconds) {
            val fetch = ImmediatePages(last = 0)
            val state = TableState(fetch::pager) { it }
            state.loadMore()
            assertFalse(state.hasMore)

            state.reload()

            assertEquals(listOf<String?>(null, null), fetch.cursors)
            assertEquals((0 until 50).toList(), state.rows)
            assertFalse(state.hasMore)
        }

    // ---- erros ----

    @Test
    fun errorThenReloadClearsErrorAndLoads() =
        runTest(timeout = 5.seconds) {
            val broken = ImmediatePages { throw ApiError.Network(IOException("sem rede")) }
            val good = Held()
            val state = TableState(Pagers(broken::pager, good::pager)::next) { it }
            state.loadMore()
            assertIs<ApiError.Network>(state.error)

            val reloading = launch { state.reload() }
            runCurrent()
            assertNull(state.error)
            assertTrue(state.loading)
            good.answerLast()
            runCurrent()

            assertTrue(reloading.isCompleted)
            assertNull(state.error)
            assertEquals((0 until 50).toList(), state.rows)
        }

    @Test
    fun errorOfDiscardedPageIsNotReported() =
        runTest(timeout = 5.seconds) {
            val old = Held()
            val new = Held()
            val pagers = Pagers(old::pager, new::pager)
            val state = TableState(pagers::next) { it }
            pagers.watch = state

            launch { state.loadMore() }
            runCurrent()
            // O erro chega antes do reload, mas a carga só o trata depois que o reload começou.
            old.replies[0].completeExceptionally(ApiError.Network(IOException("sem rede")))
            launch { state.reload() }
            runCurrent()

            // Nem por um instante o erro da página descartada aparece, e loading não cai.
            assertEquals(listOf(Seen(emptyList(), true, null)), pagers.seenOnReplace)
            assertNull(state.error)
            assertTrue(state.loading)
            new.answerLast()
            runCurrent()
            assertNull(state.error)
            assertEquals((0 until 50).toList(), state.rows)
        }

    @Test
    fun nonApiErrorInReloadPropagatesAndClearsLoading() =
        runTest(timeout = 5.seconds) {
            val loaded = ImmediatePages()
            val broken = ImmediatePages { error("falha de programação") }
            val state = TableState(Pagers(loaded::pager, broken::pager)::next) { it }
            state.loadMore()

            assertFailsWith<IllegalStateException> { state.reload() }

            assertFalse(state.loading)
            assertNull(state.error)
            assertTrue(state.rows.isEmpty())
        }

    @Test
    fun nonApiErrorKeepsTheCursorForRetry() =
        runTest(timeout = 5.seconds) {
            var fail = false
            val fetch =
                ImmediatePages { cursor ->
                    if (fail) {
                        fail = false
                        error("falha de programação")
                    }
                    pageFor(cursor)
                }
            val state = TableState(fetch::pager) { it }
            state.loadMore()
            fail = true

            assertFailsWith<IllegalStateException> { state.loadMore() }
            state.loadMore()

            assertEquals(listOf(null, "c1", "c1"), fetch.cursors)
            assertEquals((0 until 100).toList(), state.rows)
            assertFalse(state.loading)
        }

    // ---- chaves ----

    @Test
    fun duplicateKeyInsideOnePageKeepsTheFirst() =
        runTest(timeout = 5.seconds) {
            val state = guests(Guest(1, "Ana"), Guest(1, "Outra Ana"), Guest(2, "Bia"))
            state.loadMore()

            assertEquals(listOf(Guest(1, "Ana"), Guest(2, "Bia")), state.rows)
        }

    // ---- concorrência em threads reais ----

    @Test
    fun concurrentLoadMoreOnRealThreadsFetchesOnce() =
        runTest(timeout = 10.seconds) {
            onRealThreads {
                val calls = AtomicInteger()
                val fetching = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val state =
                    TableState({
                        CursorPager<Int>(SIZE) { _, _ ->
                            calls.incrementAndGet()
                            fetching.complete(Unit)
                            release.await()
                            page(0, "c1")
                        }
                    }) { it }
                val start = CompletableDeferred<Unit>()
                val returned = Channel<Unit>(Channel.UNLIMITED)
                val jobs =
                    List(CALLERS) {
                        launch {
                            start.await()
                            state.loadMore()
                            returned.send(Unit)
                        }
                    }

                start.complete(Unit)
                fetching.await()
                // Todos menos o que pegou o lock voltam sem pedir página, com a primeira ainda segurada.
                repeat(CALLERS - 1) { returned.receive() }
                assertEquals(1, calls.get())
                release.complete(Unit)
                jobs.joinAll()

                assertEquals(1, calls.get())
                assertEquals((0 until 50).toList(), state.rows)
                assertFalse(state.loading)
            }
        }

    @Test
    fun mixedLoadMoreAndReloadOnRealThreadsEndConsistent() =
        runTest(timeout = 20.seconds) {
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
                // Mesma chave em pagers diferentes: o reload precisa esquecer as chaves antigas.
                val state = TableState(factory) { it.n }

                repeat(ROUNDS) { round ->
                    val start = CompletableDeferred<Unit>()
                    val jobs =
                        List(LOADERS) { launch { start.await().also { state.loadMore() } } } +
                            List(RELOADERS) { launch { start.await().also { state.reload() } } }
                    start.complete(Unit)
                    jobs.joinAll()

                    val current = pagerIds.get()
                    assertFalse(state.loading, "rodada $round: loading ficou true")
                    assertNull(state.error, "rodada $round")
                    assertTrue(state.rows.all { it.pager == current }, "rodada $round: linha de pager antigo")
                    assertEquals((0 until state.rows.size).toList(), state.rows.map { it.n }, "rodada $round")
                    assertTrue(state.rows.isNotEmpty(), "rodada $round: o último reload não carregou")
                }
            }
        }

    // ---- desempenho ----

    @Test
    fun manyPagesLoadWithoutQuadraticDedup() =
        runTest(timeout = 5.seconds) {
            val fetch = ImmediatePages(last = MANY_PAGES - 1)
            val state = TableState(fetch::pager) { it }

            while (state.hasMore) state.loadMore()

            assertEquals(MANY_PAGES, fetch.cursors.size)
            assertEquals(MANY_PAGES * SIZE, state.rows.size)
            assertEquals(MANY_PAGES * SIZE - 1, state.rows.last())
        }

    // ---- ordenação ----

    @Test
    fun sortIsStableAmongEqualValuesInBothDirections() =
        runTest(timeout = 5.seconds) {
            val rows = listOf(Guest(1, "Ana"), Guest(2, "Bia"), Guest(3, "Ana"), Guest(4, "Bia"))
            val state = guests(*rows.toTypedArray()).loaded()
            val columns = listOf(idColumn, nameColumn)

            state.sort = "name" to true
            assertEquals(listOf(1, 3, 2, 4), state.visibleRows(columns).map { it.id })
            state.sort = "name" to false
            assertEquals(listOf(2, 4, 1, 3), state.visibleRows(columns).map { it.id })
        }

    @Test
    fun sortByUnknownOrPlainColumnKeepsServerOrder() =
        runTest(timeout = 5.seconds) {
            val rows = listOf(Guest(3, "Caio"), Guest(1, "Ana"), Guest(2, "Bia"))
            val state = guests(*rows.toTypedArray()).loaded()
            val columns = listOf(idColumn, nameColumn, plainColumn)

            state.sort = "plain" to true
            assertEquals(rows, state.visibleRows(columns))
            state.sort = "nao-existe" to false
            assertEquals(rows, state.visibleRows(columns))
            // A coluna de `sort` precisa estar entre as passadas: sem ela, a ordem é a do servidor.
            state.sort = "id" to true
            assertEquals(rows, state.visibleRows(listOf(nameColumn)))
            assertEquals(rows.sortedBy { it.id }, state.visibleRows(columns))
        }

    // ---- filtro ----

    @Test
    fun filterIgnoresCaseOfAccentedLetters() =
        runTest(timeout = 5.seconds) {
            val rows = listOf(Guest(1, "João"), Guest(2, "AÇÃO SOCIAL"), Guest(3, "Conceição"), Guest(4, "Joana"))
            val state = guests(*rows.toTypedArray()).loaded()
            val columns = listOf(idColumn, nameColumn)

            state.filter = "JOÃO"
            assertEquals(listOf(1), state.visibleRows(columns).map { it.id })
            state.filter = "çã"
            assertEquals(listOf(2, 3), state.visibleRows(columns).map { it.id })
        }

    @Test
    fun filterMatchingNothingIsEmptyAndKeepsRows() =
        runTest(timeout = 5.seconds) {
            val rows = listOf(Guest(1, "Ana"), Guest(2, "Bia"))
            val state = guests(*rows.toTypedArray()).loaded()

            state.filter = "zzz"

            assertTrue(state.visibleRows(listOf(idColumn, nameColumn)).isEmpty())
            assertEquals(rows, state.rows)
        }

    @Test
    fun blankFilterShowsEverythingAndSpacesAroundAreIgnored() =
        runTest(timeout = 5.seconds) {
            val rows = listOf(Guest(1, "Ana Paula"), Guest(2, "Bia"))
            val state = guests(*rows.toTypedArray()).loaded()
            val columns = listOf(idColumn, nameColumn)

            state.filter = "   "
            assertEquals(rows, state.visibleRows(columns))
            state.filter = "  ana  "
            assertEquals(listOf(1), state.visibleRows(columns).map { it.id })
            // Espaço do meio conta: "a p" casa "Ana Paula" e não "Bia".
            state.filter = "a p"
            assertEquals(listOf(1), state.visibleRows(columns).map { it.id })
        }

    @Test
    fun filterLooksOnlyAtTheGivenColumns() =
        runTest(timeout = 5.seconds) {
            val rows = listOf(Guest(17, "Ana"), Guest(2, "Bia"))
            val state = guests(*rows.toTypedArray()).loaded()

            state.filter = "17"

            assertEquals(listOf(17), state.visibleRows(listOf(idColumn, nameColumn)).map { it.id })
            assertTrue(state.visibleRows(listOf(nameColumn)).isEmpty())
        }

    /** `fetch` que responde na hora; por padrão, três páginas de 50 pelos cursores `null`, `c1`, `c2`. */
    private class ImmediatePages(
        private val answer: (String?) -> Page<Int> = { pageFor(it) },
    ) {
        constructor(last: Int) : this({ cursor ->
            val index = cursor?.removePrefix("c")?.toInt() ?: 0
            page(index * SIZE, if (index < last) "c${index + 1}" else null)
        })

        val cursors = mutableListOf<String?>()

        fun pager() =
            CursorPager<Int>(SIZE) { cursor, _ ->
                cursors += cursor
                answer(cursor)
            }
    }

    /** `pagerFactory` que entrega um pager de cada fábrica, em ordem, e conta as chamadas. */
    private class Pagers(
        vararg factories: () -> CursorPager<Int>,
    ) {
        private val pending = ArrayDeque(factories.toList())
        var created = 0
        var watch: TableState<Int>? = null
        val seenOnReplace = mutableListOf<Seen>()

        fun next(): CursorPager<Int> {
            created++
            watch?.let { seenOnReplace += Seen(it.rows, it.loading, it.error) }
            return pending.removeFirst()()
        }
    }

    private companion object {
        const val SIZE = 50
        const val CALLERS = 64
        const val LOADERS = 4
        const val RELOADERS = 2
        const val ROUNDS = 300
        const val LAST_PAGE = 3
        const val MANY_PAGES = 2000

        fun page(
            from: Int,
            next: String?,
        ) = Page((from until from + SIZE).toList(), next)

        /** `null` → 0..49 (c1); `c1` → 50..99 (c2); `c2` → 100..149 (fim); [offset] desloca os valores. */
        fun pageFor(
            cursor: String?,
            offset: Int = 0,
        ): Page<Int> =
            when (cursor) {
                null -> page(offset, "c1")
                "c1" -> page(offset + SIZE, "c2")
                else -> page(offset + 2 * SIZE, null)
            }
    }
}
