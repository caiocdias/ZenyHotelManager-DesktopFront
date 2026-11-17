package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.core.error.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RA_DASH = "—"
private val RA_NOW = Instant.parse("2026-11-13T12:00:00Z")
private val RA_WINDOW = Duration.ofDays(730)
private val RA_ALL = PendingList.entries
private val RA_PAGED = listOf(PendingList.GUARANTEES, PendingList.NO_SHOWS, PendingList.REALLOCATION)

private fun raGuest(n: Int) = "32000000-0000-0000-0000-" + n.toString().padStart(12, '0')

/** As seis ordens em que as respostas das chamadas 1, 2 e 3 podem chegar. */
private fun raOrders(): List<List<Int>> {
    val ids = listOf(1, 2, 3)
    return ids.flatMap { a ->
        ids.filter { it != a }.flatMap { b -> ids.filter { it != a && it != b }.map { c -> listOf(a, b, c) } }
    }
}

/**
 * Ataque ao `refreshAll()` da M3 E06 (Task 7): quatro cargas em paralelo sobre o cache dos titulares, o fuso, a
 * janela das garantias e a geração de cada `TableState`. Entrelaçamentos que o desenvolvedor não fechou: lista em
 * erro com outra em `loadMore`, o botão `refresh()` antes e depois, `select()` com respostas pendentes, `close()` no
 * meio, três chamadas com respostas fora de ordem e o pager novo depois de a lista inteira ter sido paginada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por entrelaçamento
class PendingListsRefreshAllAtaqueTest {
    private fun source() =
        FakePendingSource().apply {
            repeat(6) { holders[raGuest(it + 1)] = HolderSummary("Hóspede ${it + 1}", null) }
        }

    private fun TestScope.state(
        source: FakePendingSource,
        now: () -> Instant = { RA_NOW },
        pageSize: Int = 50,
    ) = PendingListsState(source, backgroundScope, now, pageSize)

    private fun TestScope.started(
        source: FakePendingSource,
        now: () -> Instant = { RA_NOW },
        pageSize: Int = 50,
    ) = state(source, now, pageSize).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.loadMoreOf(
        state: PendingListsState,
        lists: Collection<PendingList>,
    ) {
        lists.forEach { backgroundScope.launch { state.table(it).loadMore() } }
        runCurrent()
    }

    private fun PendingListsState.codes(list: PendingList) = table(list).rows.map { it.code }

    private fun FakePendingSource.put(
        list: PendingList,
        code: String,
        guest: Int = 1,
    ) {
        when (list) {
            PendingList.GUARANTEES -> guarantees += pendingGuaranteeOf(code = code, holderGuestId = raGuest(guest))
            PendingList.DEPOSITS -> deposits += depositShortfallOf(code = code, holderGuestId = raGuest(guest))
            PendingList.NO_SHOWS -> noShows += searchItemOf(code = code, holderGuestId = raGuest(guest))
            PendingList.REALLOCATION -> reallocations += reallocationOf(code = code)
        }
    }

    /** O servidor passa a ter, na [list], só as linhas com os [codes]. */
    private fun FakePendingSource.set(
        list: PendingList,
        vararg codes: String,
    ) {
        when (list) {
            PendingList.GUARANTEES -> guarantees.clear()
            PendingList.DEPOSITS -> deposits.clear()
            PendingList.NO_SHOWS -> noShows.clear()
            PendingList.REALLOCATION -> reallocations.clear()
        }
        codes.forEach { put(list, it) }
    }

    /** Em cada lista, só as linhas `<LISTA>-<sufixo>` de cada sufixo. */
    private fun FakePendingSource.setAll(vararg suffixes: String) =
        RA_ALL.forEach { list -> set(list, *suffixes.map { "${list.name}-$it" }.toTypedArray()) }

    private fun FakePendingSource.reads(list: PendingList) =
        when (list) {
            PendingList.GUARANTEES -> guaranteeCalls.size
            PendingList.DEPOSITS -> depositCalls
            PendingList.NO_SHOWS -> noShowCalls.size
            PendingList.REALLOCATION -> reallocationCalls.size
        }

    private fun opOf(list: PendingList) =
        when (list) {
            PendingList.GUARANTEES -> FakePendingSource.Op.GUARANTEES
            PendingList.DEPOSITS -> FakePendingSource.Op.DEPOSITS
            PendingList.NO_SHOWS -> FakePendingSource.Op.NO_SHOWS
            PendingList.REALLOCATION -> FakePendingSource.Op.REALLOCATION
        }

    /** Prende as próximas leituras das [lists]; as já presas seguem nos portões antigos. */
    private fun FakePendingSource.holdLists(lists: Collection<PendingList> = RA_ALL): List<CompletableDeferred<Unit>> =
        lists.map { hold(opOf(it)) }

    private fun PendingListsState.assertSettled(
        list: PendingList,
        vararg expected: String,
    ) {
        assertEquals(expected.toList(), codes(list), "$list")
        assertNull(table(list).error, "$list erro")
        assertFalse(table(list).loading, "$list ainda carregando")
    }

    private fun PendingListsState.guaranteeDue() = table(PendingList.GUARANTEES).rows.single().due

    private fun PendingListsState.depositDetail() = table(PendingList.DEPOSITS).rows.single().detail

    private fun PendingListsState.dues() = guaranteeDue() to depositDetail()

    // ---------------------------------------------------------------- entrelaçamentos entre listas

    @Test
    fun refreshAllRecoversAFailedListWhileAnotherIsLoadingMoreAndDropsThatPage() =
        runTest {
            val source = source()
            RA_ALL.forEach { source.set(it, "${it.name}-1", "${it.name}-2", "${it.name}-3") }
            source.failures[FakePendingSource.Op.DEPOSITS] = ApiError.Unexpected(502)
            val state = started(source, pageSize = 2)
            assertNotNull(state.table(PendingList.DEPOSITS).error)
            val pageGate = source.hold(FakePendingSource.Op.NO_SHOWS)
            loadMoreOf(state, listOf(PendingList.NO_SHOWS))
            assertEquals("2", source.noShowCalls.last().cursor, "o loadMore estava em voo")

            source.failures.clear()
            source.set(PendingList.NO_SHOWS, "NO_SHOWS-N1", "NO_SHOWS-N2", "NO_SHOWS-N3")
            val refreshGate = source.hold(FakePendingSource.Op.NO_SHOWS)
            state.refreshAll()
            runCurrent()
            refreshGate.complete(Unit)
            runCurrent()
            pageGate.complete(Unit)
            runCurrent()

            state.assertSettled(PendingList.DEPOSITS, "DEPOSITS-1", "DEPOSITS-2", "DEPOSITS-3")
            state.assertSettled(PendingList.NO_SHOWS, "NO_SHOWS-N1", "NO_SHOWS-N2")
            state.assertSettled(PendingList.GUARANTEES, "GUARANTEES-1", "GUARANTEES-2")
            state.assertSettled(PendingList.REALLOCATION, "REALLOCATION-1", "REALLOCATION-2")
            assertTrue(state.table(PendingList.NO_SHOWS).hasMore)
        }

    @Test
    fun aNonApiFailureInOneListDoesNotBlockTheOthersNorTheNextRefresh() =
        runTest {
            val source = source()
            source.setAll("1")
            val state = started(source)
            source.setAll("1", "2")
            source.failures[FakePendingSource.Op.DEPOSITS] = IllegalStateException("falha de infraestrutura")

            state.refreshAll()
            runCurrent()

            (RA_ALL - PendingList.DEPOSITS).forEach { state.assertSettled(it, "${it.name}-1", "${it.name}-2") }
            assertEquals(listOf("DEPOSITS-1"), state.codes(PendingList.DEPOSITS))
            assertFalse(state.table(PendingList.DEPOSITS).loading, "a lista que falhou ficou carregando")

            source.failures.clear()
            state.select(PendingList.DEPOSITS)
            state.refresh()
            runCurrent()
            state.assertSettled(PendingList.DEPOSITS, "DEPOSITS-1", "DEPOSITS-2")
        }

    @Test
    fun refreshButtonJustBeforeRefreshAllKeepsTheRefreshAllAnswer() =
        runTest {
            val source = source()
            source.setAll("0")
            val state = started(source)
            val buttonGate = source.holdLists(listOf(PendingList.GUARANTEES))
            state.refresh()
            runCurrent()
            val allGates = source.holdLists()
            state.refreshAll()
            runCurrent()

            source.setAll("OLD")
            buttonGate.forEach { it.complete(Unit) }
            runCurrent()
            assertEquals(listOf("GUARANTEES-0"), state.codes(PendingList.GUARANTEES), "a resposta do botão entrou")
            source.setAll("NEW1", "NEW2")
            allGates.forEach { it.complete(Unit) }
            runCurrent()

            RA_ALL.forEach { state.assertSettled(it, "${it.name}-NEW1", "${it.name}-NEW2") }
            assertEquals(3, source.reads(PendingList.GUARANTEES))
            assertEquals(2, source.reads(PendingList.DEPOSITS))
        }

    @Test
    fun refreshButtonJustAfterRefreshAllKeepsTheButtonAnswerOnThatList() =
        runTest {
            val source = source()
            source.setAll("0")
            val state = started(source)
            val allGates = source.holdLists()
            state.refreshAll()
            runCurrent()
            val buttonGate = source.holdLists(listOf(PendingList.GUARANTEES))
            state.refresh()
            runCurrent()

            source.setAll("OLD")
            allGates.forEach { it.complete(Unit) }
            runCurrent()
            assertEquals(listOf("GUARANTEES-0"), state.codes(PendingList.GUARANTEES), "a resposta velha entrou")
            source.set(PendingList.GUARANTEES, "GUARANTEES-BTN1", "GUARANTEES-BTN2")
            buttonGate.forEach { it.complete(Unit) }
            runCurrent()

            state.assertSettled(PendingList.GUARANTEES, "GUARANTEES-BTN1", "GUARANTEES-BTN2")
            (RA_ALL - PendingList.GUARANTEES).forEach { state.assertSettled(it, "${it.name}-OLD") }
        }

    @Test
    fun selectAfterRefreshAllShowsTheOldRowsUntilTheNewPageComesAndReadsNothing() =
        runTest {
            val source = source()
            source.setAll("1")
            val state = started(source)
            val gates = source.holdLists()
            state.refreshAll()
            runCurrent()
            val reads = RA_ALL.map { source.reads(it) }

            state.select(PendingList.REALLOCATION)
            runCurrent()

            assertEquals(listOf("REALLOCATION-1"), state.table.rows.map { it.code }, "a lista nova mostra o que tinha")
            assertTrue(state.table.loading)
            assertEquals(reads, RA_ALL.map { source.reads(it) }, "trocar de lista não lê")
            source.setAll("2")
            gates.forEach { it.complete(Unit) }
            runCurrent()
            RA_ALL.forEach { state.assertSettled(it, "${it.name}-2") }
            assertEquals(PendingList.REALLOCATION, state.selected)
            assertEquals(reads, RA_ALL.map { source.reads(it) })
        }

    @Test
    fun closeInTheMiddleOfRefreshAllLeavesEveryListAsItWasAndReadsNothingMore() =
        runTest {
            val source = source()
            source.setAll("1")
            val state = started(source)
            val holders = source.holderCalls.size
            val gates = source.holdLists()
            state.refreshAll()
            runCurrent()
            source.setAll("2")
            state.close()
            gates.forEach { it.complete(Unit) }
            runCurrent()

            RA_ALL.forEach { list ->
                assertEquals(listOf("${list.name}-1"), state.codes(list), "$list recebeu a resposta depois do close")
                assertNull(state.table(list).error)
            }
            assertEquals(holders, source.holderCalls.size)
            val reads = RA_ALL.map { source.reads(it) }
            state.refreshAll()
            runCurrent()
            assertEquals(reads, RA_ALL.map { source.reads(it) })
        }

    @Test
    fun closeWhileRefreshAllIsReadingTheHolderDropsTheNewPagesAndErrors() =
        runTest {
            val source = source()
            source.put(PendingList.GUARANTEES, "G-1", guest = 1)
            source.put(PendingList.NO_SHOWS, "N-1", guest = 2)
            val state = started(source)
            source.guarantees[0] = pendingGuaranteeOf(code = "G-2", holderGuestId = raGuest(5))
            source.noShows[0] = searchItemOf(code = "N-2", holderGuestId = raGuest(6))
            val gate = source.hold(FakePendingSource.Op.HOLDER)
            state.refreshAll()
            runCurrent()
            assertTrue(source.holderCalls.any { it == raGuest(5) || it == raGuest(6) }, "o titular estava em voo")

            state.close()
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("G-1"), state.codes(PendingList.GUARANTEES))
            assertEquals(listOf("N-1"), state.codes(PendingList.NO_SHOWS))
            RA_ALL.forEach { assertNull(state.table(it).error) }
        }

    @Test
    fun threeRefreshAllWithAnswersInAnyOrderKeepTheThirdAnswer() {
        raOrders().forEach { order ->
            runTest {
                val source = source()
                source.setAll("0")
                val state = started(source)
                val gates =
                    (1..3).map {
                        source.holdLists().also {
                            state.refreshAll()
                            runCurrent()
                        }
                    }

                order.forEachIndexed { step, n ->
                    source.setAll("A$n")
                    gates[n - 1].forEach { it.complete(Unit) }
                    runCurrent()
                    // Só a terceira chamada pode trocar as linhas; depois dela, nenhuma outra as muda.
                    val expected = if (3 in order.take(step + 1)) "A3" else "0"
                    RA_ALL.forEach {
                        assertEquals(listOf("${it.name}-$expected"), state.codes(it), "ordem $order, passo $step")
                    }
                }

                RA_ALL.forEach { state.assertSettled(it, "${it.name}-A3") }
                RA_ALL.forEach { assertEquals(4, source.reads(it), "$it, ordem $order") }
            }
        }
    }

    // ---------------------------------------------------------------- estado compartilhado

    @Test
    fun aStaleAnswerCannotBringBackARowTheNewOneLacksThroughTheHolderCache() =
        runTest {
            val source = source()
            source.put(PendingList.GUARANTEES, "G-1", guest = 1)
            source.put(PendingList.GUARANTEES, "G-2", guest = 2)
            source.put(PendingList.NO_SHOWS, "N-1", guest = 2)
            val state = started(source)
            assertEquals(2, source.holderCalls.size)

            // A resposta velha (G-1, G-2) fica presa; a nova só tem G-3, de um titular que o cache não conhece.
            val first = source.holdLists(listOf(PendingList.GUARANTEES))
            state.refreshAll()
            runCurrent()
            source.set(PendingList.GUARANTEES)
            source.put(PendingList.GUARANTEES, "G-3", guest = 3)
            val second = source.holdLists(listOf(PendingList.GUARANTEES))
            state.refreshAll()
            runCurrent()
            second.forEach { it.complete(Unit) }
            runCurrent()
            first.forEach { it.complete(Unit) }
            runCurrent()

            state.assertSettled(PendingList.GUARANTEES, "G-3")
            assertEquals(listOf("Hóspede 3"), state.table(PendingList.GUARANTEES).rows.map { it.holder })
            assertEquals(3, source.holderCalls.size, "cada titular é lido uma vez, em todas as cargas")
            assertEquals(3, source.holderCalls.toSet().size)
        }

    @Test
    fun theHolderOfARowThatLeftAListStaysOutOfItsNewRows() =
        runTest {
            val source = source()
            source.put(PendingList.NO_SHOWS, "N-1", guest = 4)
            source.put(PendingList.NO_SHOWS, "N-2", guest = 5)
            val state = started(source)

            source.set(PendingList.NO_SHOWS)
            source.put(PendingList.NO_SHOWS, "N-2", guest = 5)
            state.refreshAll()
            runCurrent()

            state.assertSettled(PendingList.NO_SHOWS, "N-2")
            assertEquals(listOf("Hóspede 5"), state.table(PendingList.NO_SHOWS).rows.map { it.holder })
            assertEquals(2, source.holderCalls.size, "o titular que saiu não é lido de novo")
        }

    @Test
    fun parallelLoadsReadTheZoneOnceAndShowTheSameDueText() =
        runTest {
            val source = source()
            source.put(PendingList.GUARANTEES, "G-1")
            source.put(PendingList.DEPOSITS, "D-1")
            val state = started(source)
            val before = state.dues()
            assertEquals(1, source.businessDayCalls)

            state.refreshAll()
            state.refreshAll()
            runCurrent()

            assertEquals(1, source.businessDayCalls, "o fuso lido uma vez, não por carga")
            assertEquals(before, state.dues())
            assertTrue(before.first != RA_DASH && !before.second.endsWith("em $RA_DASH"), "sem fuso: $before")
        }

    @Test
    fun aZoneThatFailedAtStartIsReadAgainByRefreshAllAndTheInstantsStopBeingDashes() =
        runTest {
            val source = source().apply { failures[FakePendingSource.Op.BUSINESS_DAY] = ApiError.Unexpected(502) }
            source.put(PendingList.GUARANTEES, "G-1")
            source.put(PendingList.DEPOSITS, "D-1")
            val state = started(source)
            assertEquals(RA_DASH, state.guaranteeDue())
            val failedReads = source.businessDayCalls

            source.failures.clear()
            state.refreshAll()
            runCurrent()

            val (guarantee, deposit) = state.dues()
            assertTrue(guarantee != RA_DASH, "garantia sem instante")
            assertFalse(deposit.endsWith("em $RA_DASH"), "depósito sem instante")
            assertEquals(failedReads + 1, source.businessDayCalls, "uma leitura só, a que deu certo")
            state.assertSettled(PendingList.GUARANTEES, "G-1")
        }

    @Test
    fun refreshAllAsksTheGuaranteesOnceWithTheWindowOfThatMoment() =
        runTest {
            var now = RA_NOW
            val source = source()
            RA_ALL.forEach { source.set(it, "${it.name}-1") }
            val state = started(source, { now })

            now = RA_NOW.plus(Duration.ofHours(7))
            state.refreshAll()
            runCurrent()
            state.refreshAll()
            runCurrent()

            assertEquals(3, source.guaranteeCalls.size, "start, mais uma por refreshAll")
            assertEquals(RA_NOW.plus(RA_WINDOW), source.guaranteeCalls[0].to)
            assertEquals(now.plus(RA_WINDOW), source.guaranteeCalls[1].to)
            assertEquals(now.plus(RA_WINDOW), source.guaranteeCalls[2].to)
            assertEquals(listOf(null, null, null), source.guaranteeCalls.map { it.cursor })
        }

    @Test
    fun loadMoreAfterRefreshAllContinuesFromTheNewFirstPageEvenAfterTheWholeListWasPaged() =
        runTest {
            val source = source()
            RA_ALL.forEach { list -> (1..3).forEach { source.put(list, "${list.name}-$it") } }
            val state = started(source, pageSize = 2)
            loadMoreOf(state, RA_PAGED)
            RA_PAGED.forEach { assertFalse(state.table(it).hasMore, "$it já paginada por inteiro") }

            RA_ALL.forEach { list -> source.set(list, *(1..5).map { "${list.name}-N$it" }.toTypedArray()) }
            state.refreshAll()
            runCurrent()
            RA_PAGED.forEach { assertTrue(state.table(it).hasMore, "$it") }
            loadMoreOf(state, RA_PAGED)

            RA_PAGED.forEach { list ->
                assertEquals((1..4).map { "${list.name}-N$it" }, state.codes(list), "$list")
            }
            assertEquals(listOf(null, "2", null, "2"), source.noShowCalls.map { it.cursor })
        }

    @Test
    fun zoneReadInFlightIsSharedByTheLoadsThatNeedItInsteadOfReadingFourTimes() =
        runTest {
            val source = source().apply { failures[FakePendingSource.Op.BUSINESS_DAY] = ApiError.Unexpected(502) }
            source.put(PendingList.GUARANTEES, "G-1")
            source.put(PendingList.DEPOSITS, "D-1")
            val state = started(source)
            val failedReads = source.businessDayCalls
            source.failures.clear()
            val gate = source.hold(FakePendingSource.Op.BUSINESS_DAY)

            state.refreshAll()
            runCurrent()

            assertEquals(failedReads + 1, source.businessDayCalls, "uma leitura do fuso em voo, a outra espera")
            gate.complete(Unit)
            runCurrent()
            assertTrue(state.guaranteeDue() != RA_DASH)
            assertFalse(state.depositDetail().endsWith("em $RA_DASH"))
            assertEquals(failedReads + 1, source.businessDayCalls, "a segunda carga reusa o fuso lido")
        }

    @Test
    fun loadMoreAfterRefreshAllUsesTheWindowOfThatRefreshEvenIfTheClockMoved() =
        runTest {
            var now = RA_NOW
            val source = source()
            (1..3).forEach { source.put(PendingList.GUARANTEES, "G-$it") }
            val state = started(source, { now }, pageSize = 2)
            val refreshNow = RA_NOW.plus(Duration.ofHours(3))
            now = refreshNow
            state.refreshAll()
            runCurrent()

            now = RA_NOW.plus(Duration.ofHours(9))
            loadMoreOf(state, listOf(PendingList.GUARANTEES))

            val calls = source.guaranteeCalls
            assertEquals(listOf(null, null, "2"), calls.map { it.cursor })
            assertEquals(refreshNow.plus(RA_WINDOW), calls[2].to, "a página seguinte é da janela do refreshAll")
            state.assertSettled(PendingList.GUARANTEES, "G-1", "G-2", "G-3")
        }

    // ---------------------------------------------------------------- dado pessoal

    @Test
    fun toStringAfterRefreshAllStillHidesTheHolders() =
        runTest {
            val source = source()
            source.put(PendingList.GUARANTEES, "G-1", guest = 1)
            source.put(PendingList.NO_SHOWS, "N-1", guest = 2)
            val state = started(source)
            state.refreshAll()
            runCurrent()

            val text = state.toString()
            assertFalse(text.contains("Hóspede"))
            assertFalse(text.contains(raGuest(1)))
            assertEquals("PendingListsState(***)", text)
        }

    @Test
    fun aHolderFailureDuringRefreshAllLeavesNoGuestNameInTheListError() =
        runTest {
            val source = source()
            source.put(PendingList.GUARANTEES, "G-1", guest = 1)
            val state = started(source)
            source.holders[raGuest(1)] = HolderSummary("Maria da Silva", null)
            source.guarantees[0] = pendingGuaranteeOf(code = "G-1", holderGuestId = raGuest(3))
            source.holderFailures[raGuest(3)] = ApiError.Unexpected(500)

            state.refreshAll()
            runCurrent()

            val error = assertNotNull(state.table(PendingList.GUARANTEES).error)
            assertFalse(error.message.orEmpty().contains("Maria"))
            assertFalse(error.message.orEmpty().contains("Hóspede"))
            assertEquals(listOf("G-1"), state.codes(PendingList.GUARANTEES))
        }

    @Test
    fun aHolderFailureWhoseMessageCarriesTheNameAndDocumentDoesNotLeakIntoTheListError() =
        runTest {
            val source = source()
            source.put(PendingList.GUARANTEES, "G-1", guest = 1)
            val state = started(source)
            source.guarantees[0] = pendingGuaranteeOf(code = "G-1", holderGuestId = raGuest(3))
            val leaking = IOException("falha ao ler Maria da Silva CPF 123.456.789-00")
            source.holderFailures[raGuest(3)] = ApiError.Network(leaking)

            state.refreshAll()
            runCurrent()

            val error = assertNotNull(state.table(PendingList.GUARANTEES).error)
            listOf(error.message.orEmpty(), error.toString()).forEach {
                assertFalse(it.contains("Maria"), "nome na mensagem: $it")
                assertFalse(it.contains("123.456"), "documento na mensagem: $it")
            }
        }
}
