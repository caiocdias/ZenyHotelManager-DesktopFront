package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.core.error.ApiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DASH = "—"
private val NOW = Instant.parse("2026-11-13T12:00:00Z")

private fun guestId(n: Int) = "30000000-0000-0000-0000-" + n.toString().padStart(12, '0')

private fun problem(
    status: Int,
    code: String = "X",
) = ApiError.Problem(status, code, null, emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class PendingListsStateTest {
    private fun source() =
        FakePendingSource().apply {
            repeat(4) { holders[guestId(it + 1)] = HolderSummary("Hóspede ${it + 1}", null) }
        }

    private fun TestScope.state(
        source: FakePendingSource,
        pageSize: Int = 50,
    ) = PendingListsState(source, backgroundScope, { NOW }, pageSize)

    private fun TestScope.started(
        source: FakePendingSource,
        pageSize: Int = 50,
    ) = state(source, pageSize).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.loadMore(
        state: PendingListsState,
        list: PendingList,
    ) {
        backgroundScope.launch { state.table(list).loadMore() }
        runCurrent()
    }

    private fun PendingListsState.codes(list: PendingList) = table(list).rows.map { it.code }

    @Test
    fun eachListLoadsItsOwnPage() =
        runTest {
            val source = source()
            source.guarantees +=
                pendingGuaranteeOf(code = "G-1", holderGuestId = guestId(1), requiredDeposit = "300.00")
            source.deposits += depositShortfallOf(code = "D-1", holderGuestId = guestId(2))
            source.noShows += searchItemOf(code = "N-1", holderGuestId = guestId(3))
            source.reallocations += reallocationOf(code = "A-1", roomNumber = "208")
            val state = started(source)

            assertEquals(listOf("G-1"), state.codes(PendingList.GUARANTEES))
            assertEquals(listOf("D-1"), state.codes(PendingList.DEPOSITS))
            assertEquals(listOf("N-1"), state.codes(PendingList.NO_SHOWS))
            assertEquals(listOf("A-1"), state.codes(PendingList.REALLOCATION))
            assertEquals(listOf("Reserva", "Titular", "Período", "Situação", "Prazo"), state.columns.map { it.header })
            val guarantee = state.table(PendingList.GUARANTEES).rows.single()
            assertEquals("Hóspede 1", guarantee.holder)
            assertEquals("20/11/2026 a 22/11/2026 · 2 noites", guarantee.period)
            assertEquals("Pré-reserva · Cartão · exigido R$ 300,00", guarantee.detail)
            assertEquals("15/11/2026 14:00", guarantee.due)
            val reallocation = state.table(PendingList.REALLOCATION).rows.single()
            assertEquals(DASH, reallocation.holder)
            assertEquals("UH 208 fora de ordem na estadia", reallocation.detail)
            assertEquals(1, source.depositCalls)
            assertNull(state.table(PendingList.DEPOSITS).error)
            assertTrue(state.table(PendingList.DEPOSITS).hasMore.not())
        }

    @Test
    fun guaranteesAskWideWindow() =
        runTest {
            val source = source().apply { guarantees += pendingGuaranteeOf(holderGuestId = guestId(1)) }
            val state = started(source)

            assertEquals(NOW.plusSeconds(730L * 24 * 60 * 60), source.guaranteeCalls.single().to)
            assertEquals(50, source.guaranteeCalls.single().limit)
            assertNull(source.guaranteeCalls.single().cursor)
            assertEquals(1, state.table(PendingList.GUARANTEES).rows.size)
        }

    @Test
    fun guaranteeWindowIsRecomputedOnEveryFirstPage() =
        runTest {
            var now = NOW
            val source = source().apply { guarantees += pendingGuaranteeOf(holderGuestId = guestId(1)) }
            val state = PendingListsState(source, backgroundScope, { now })
            state.start()
            runCurrent()
            now = NOW.plusSeconds(3600)
            state.refresh()
            runCurrent()

            assertEquals(2, source.guaranteeCalls.size)
            assertEquals(NOW.plusSeconds(3600), source.guaranteeCalls[1].to - java.time.Duration.ofDays(730))
        }

    @Test
    fun selectedTableFollowsSelection() =
        runTest {
            val source = source()
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = guestId(1))
            source.noShows += searchItemOf(code = "N-1", holderGuestId = guestId(2))
            val state = started(source)

            assertEquals(PendingList.GUARANTEES, state.selected)
            assertEquals(listOf("G-1"), state.table.rows.map { it.code })
            state.select(PendingList.NO_SHOWS)
            assertEquals(PendingList.NO_SHOWS, state.selected)
            assertEquals(listOf("N-1"), state.table.rows.map { it.code })
            assertTrue(state.table === state.table(PendingList.NO_SHOWS))
        }

    @Test
    fun fourListsLoadingTogetherKeepHolders() =
        runTest {
            val source = source()
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = guestId(1))
            source.deposits += depositShortfallOf(code = "D-1", holderGuestId = guestId(2))
            source.noShows += searchItemOf(code = "N-1", holderGuestId = guestId(3))
            source.noShows += searchItemOf(code = "N-2", holderGuestId = guestId(1))
            source.reallocations += reallocationOf(code = "A-1")
            val gate = source.hold(FakePendingSource.Op.HOLDER)
            val state = state(source)
            state.start()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("Hóspede 1"), state.table(PendingList.GUARANTEES).rows.map { it.holder })
            assertEquals(listOf("Hóspede 2"), state.table(PendingList.DEPOSITS).rows.map { it.holder })
            assertEquals(listOf("Hóspede 3", "Hóspede 1"), state.table(PendingList.NO_SHOWS).rows.map { it.holder })
            assertEquals(listOf(DASH), state.table(PendingList.REALLOCATION).rows.map { it.holder })
            assertEquals(source.holderCalls.distinct(), source.holderCalls, "cada titular é lido uma vez só")
            assertEquals(3, source.holderCalls.size)
        }

    @Test
    fun rowToStringHidesHolder() =
        runTest {
            val source = source().apply { holders[guestId(1)] = HolderSummary("Fulana Secreta", null) }
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = guestId(1))
            val state = started(source)

            val row = state.table(PendingList.GUARANTEES).rows.single()
            assertEquals("Fulana Secreta", row.holder)
            assertTrue("Fulana" !in row.toString())
            assertTrue("G-1" in row.toString())
            assertTrue("Fulana" !in state.toString())
        }

    @Test
    fun oneListFailingKeepsOthers() =
        runTest {
            val source = source().apply { failures[FakePendingSource.Op.DEPOSITS] = problem(500) }
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = guestId(1))
            source.noShows += searchItemOf(code = "N-1", holderGuestId = guestId(2))
            source.reallocations += reallocationOf(code = "A-1")
            val state = started(source)

            assertNotNull(state.table(PendingList.DEPOSITS).error)
            assertTrue(state.table(PendingList.DEPOSITS).rows.isEmpty())
            assertEquals(listOf("G-1"), state.codes(PendingList.GUARANTEES))
            assertEquals(listOf("N-1"), state.codes(PendingList.NO_SHOWS))
            assertEquals(listOf("A-1"), state.codes(PendingList.REALLOCATION))
            assertNull(state.table(PendingList.GUARANTEES).error)
        }

    @Test
    fun refreshReloadsOnlySelected() =
        runTest {
            val source = source()
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = guestId(1))
            source.noShows += searchItemOf(code = "N-1", holderGuestId = guestId(2))
            val state = started(source)
            state.select(PendingList.NO_SHOWS)
            source.noShows += searchItemOf(code = "N-2", holderGuestId = guestId(3))

            state.refresh()
            runCurrent()

            assertEquals(listOf("N-1", "N-2"), state.codes(PendingList.NO_SHOWS))
            assertEquals(2, source.noShowCalls.size)
            assertEquals(1, source.guaranteeCalls.size)
            assertEquals(1, source.depositCalls)
            assertEquals(1, source.reallocationCalls.size)
        }

    @Test
    fun holderIsCachedAndFailureIsDash() =
        runTest {
            val source = source()
            source.holderFailures[guestId(2)] = problem(404)
            source.holderFailures[guestId(3)] = problem(403)
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = guestId(1))
            source.guarantees += pendingGuaranteeOf(code = "G-2", holderGuestId = guestId(2))
            source.guarantees += pendingGuaranteeOf(code = "G-3", holderGuestId = guestId(3))
            source.noShows += searchItemOf(code = "N-1", holderGuestId = guestId(1))
            source.noShows += searchItemOf(code = "N-2", holderGuestId = guestId(2))
            val state = started(source)
            state.refresh()
            runCurrent()

            assertEquals(listOf("Hóspede 1", DASH, DASH), state.table(PendingList.GUARANTEES).rows.map { it.holder })
            assertEquals(listOf("Hóspede 1", DASH), state.table(PendingList.NO_SHOWS).rows.map { it.holder })
            assertEquals(3, source.holderCalls.size)
            assertEquals(setOf(guestId(1), guestId(2), guestId(3)), source.holderCalls.toSet())
        }

    @Test
    fun holderFailureOtherThanForbiddenOrNotFoundFailsTheList() =
        runTest {
            val source = source()
            source.holderFailures[guestId(1)] = problem(500)
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = guestId(1))
            source.reallocations += reallocationOf(code = "A-1")
            val state = started(source)

            assertNotNull(state.table(PendingList.GUARANTEES).error)
            assertEquals(listOf("A-1"), state.codes(PendingList.REALLOCATION))
        }

    @Test
    fun nextPageUsesCursor() =
        runTest {
            val source = source()
            (1..3).forEach { n ->
                source.guarantees += pendingGuaranteeOf(code = "G-$n", holderGuestId = guestId(1))
                source.noShows += searchItemOf(code = "N-$n", holderGuestId = guestId(1))
                source.reallocations += reallocationOf(code = "A-$n")
            }
            val state = started(source, pageSize = 2)

            assertEquals(listOf("G-1", "G-2"), state.codes(PendingList.GUARANTEES))
            loadMore(state, PendingList.GUARANTEES)
            loadMore(state, PendingList.NO_SHOWS)
            loadMore(state, PendingList.REALLOCATION)

            assertEquals(listOf("G-1", "G-2", "G-3"), state.codes(PendingList.GUARANTEES))
            assertEquals(listOf("N-1", "N-2", "N-3"), state.codes(PendingList.NO_SHOWS))
            assertEquals(listOf("A-1", "A-2", "A-3"), state.codes(PendingList.REALLOCATION))
            assertEquals(listOf(null, "2"), source.guaranteeCalls.map { it.cursor })
            assertEquals(listOf(null, "2"), source.noShowCalls.map { it.cursor })
            assertEquals(listOf(null, "2"), source.reallocationCalls.map { it.cursor })
            assertEquals(source.guaranteeCalls[0].to, source.guaranteeCalls[1].to)
        }

    @Test
    fun noZoneLeavesInstantsAsDash() =
        runTest {
            val source = source().apply { failures[FakePendingSource.Op.BUSINESS_DAY] = problem(403) }
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = guestId(1))
            val state = started(source)

            assertEquals(listOf(DASH), state.table(PendingList.GUARANTEES).rows.map { it.due })
        }

    @Test
    fun closeDropsLateAnswers() =
        runTest {
            val source = source()
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = guestId(1))
            source.noShows += searchItemOf(code = "N-1", holderGuestId = guestId(2))
            val guaranteeGate = source.hold(FakePendingSource.Op.GUARANTEES)
            val noShowGate = source.hold(FakePendingSource.Op.NO_SHOWS)
            val state = state(source)
            state.start()
            runCurrent()

            state.close()
            guaranteeGate.complete(Unit)
            noShowGate.complete(Unit)
            runCurrent()

            PendingList.entries.forEach {
                assertTrue(state.table(it).rows.isEmpty(), "$it recebeu resposta depois do close")
                assertNull(state.table(it).error)
            }
            assertTrue(source.holderCalls.isEmpty())

            state.start()
            state.refresh()
            runCurrent()
            assertEquals(1, source.guaranteeCalls.size)
        }
}
