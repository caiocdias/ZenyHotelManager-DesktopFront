package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.core.error.ApiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PA_DASH = "—"
private val PA_NOW = Instant.parse("2026-11-13T12:00:00Z")
private val PA_WINDOW = Duration.ofDays(730)

private fun paGuest(n: Int) = "31000000-0000-0000-0000-" + n.toString().padStart(12, '0')

/**
 * Ataque ao `PendingListsState` e aos `PendingText` da M3 E04: cargas que se cruzam (refresh com a primeira carga em
 * voo, titulares que chegam fora de ordem, close no meio da leitura do titular), a janela das garantias que segue a
 * carga vigente e os valores do contrato que o front não conhece.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingListsAtaqueTest {
    private fun source() =
        FakePendingSource().apply {
            repeat(4) { holders[paGuest(it + 1)] = HolderSummary("Titular ${it + 1}", null) }
        }

    private fun TestScope.state(
        source: FakePendingSource,
        now: () -> Instant = { PA_NOW },
        pageSize: Int = 50,
    ) = PendingListsState(source, backgroundScope, now, pageSize)

    private fun PendingListsState.codes(list: PendingList) = table(list).rows.map { it.code }

    @Test
    fun refreshWhileTheFirstLoadIsInFlightKeepsOneCopyOfEachRow() =
        runTest {
            val source = source()
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = paGuest(1))
            source.guarantees += pendingGuaranteeOf(code = "G-2", holderGuestId = paGuest(2))
            val gate = source.hold(FakePendingSource.Op.GUARANTEES)
            val state = state(source)

            state.start()
            runCurrent()
            state.refresh()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("G-1", "G-2"), state.codes(PendingList.GUARANTEES))
            assertNull(state.table(PendingList.GUARANTEES).error)
            assertFalse(state.table(PendingList.GUARANTEES).loading)
        }

    @Test
    fun holdersArriveOutOfOrderWithoutBeingSwapped() =
        runTest {
            val source = source()
            // As garantias ficam presas; os no-shows resolvem os mesmos titulares antes, na ordem inversa.
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = paGuest(1))
            source.guarantees += pendingGuaranteeOf(code = "G-2", holderGuestId = paGuest(2))
            source.noShows += searchItemOf(code = "N-2", holderGuestId = paGuest(2))
            source.noShows += searchItemOf(code = "N-1", holderGuestId = paGuest(1))
            source.deposits += depositShortfallOf(code = "D-3", holderGuestId = paGuest(3))
            val gate = source.hold(FakePendingSource.Op.GUARANTEES)
            val state = state(source)

            state.start()
            runCurrent()
            assertEquals(listOf("Titular 2", "Titular 1"), state.table(PendingList.NO_SHOWS).rows.map { it.holder })
            gate.complete(Unit)
            runCurrent()

            val byCode = PendingList.entries.flatMap { state.table(it).rows }.associate { it.code to it.holder }
            assertEquals(
                mapOf(
                    "G-1" to "Titular 1",
                    "G-2" to "Titular 2",
                    "N-1" to "Titular 1",
                    "N-2" to "Titular 2",
                    "D-3" to "Titular 3",
                ),
                byCode,
            )
            assertEquals(3, source.holderCalls.size, "o cache vale entre as listas")
        }

    @Test
    fun closeWhileTheHolderIsBeingReadDropsThePage() =
        runTest {
            val source = source()
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = paGuest(1))
            source.noShows += searchItemOf(code = "N-1", holderGuestId = paGuest(2))
            val gate = source.hold(FakePendingSource.Op.HOLDER)
            val state = state(source)

            state.start()
            runCurrent()
            assertTrue(source.holderCalls.isNotEmpty(), "a leitura do titular estava em voo")
            state.close()
            gate.complete(Unit)
            runCurrent()

            PendingList.entries.forEach {
                assertTrue(state.table(it).rows.isEmpty(), "$it mostrou linha depois do close")
                assertNull(state.table(it).error, "$it mostrou erro depois do close")
            }
        }

    @Test
    fun nextPageAfterARefreshUsesTheWindowOfThatRefresh() =
        runTest {
            var now = PA_NOW
            val source = source()
            (1..3).forEach { source.guarantees += pendingGuaranteeOf(code = "G-$it", holderGuestId = paGuest(1)) }
            val state = state(source, { now }, pageSize = 2)
            state.start()
            runCurrent()

            now = PA_NOW.plus(Duration.ofHours(5))
            state.refresh()
            runCurrent()
            backgroundScope.launch { state.table(PendingList.GUARANTEES).loadMore() }
            runCurrent()

            val calls = source.guaranteeCalls
            assertEquals(listOf(null, null, "2"), calls.map { it.cursor })
            assertEquals(PA_NOW.plus(PA_WINDOW), calls[0].to)
            assertEquals(now.plus(PA_WINDOW), calls[1].to)
            assertEquals(calls[1].to, calls[2].to, "a página seguinte é da mesma janela da primeira")
            assertEquals(listOf("G-1", "G-2", "G-3"), state.codes(PendingList.GUARANTEES))
        }

    @Test
    fun failedListRecoversOnRetryAndOnlyItIsReloaded() =
        runTest {
            val source = source().apply { failures[FakePendingSource.Op.REALLOCATION] = ApiError.Unexpected(502) }
            source.guarantees += pendingGuaranteeOf(code = "G-1", holderGuestId = paGuest(1))
            source.reallocations += reallocationOf(code = "A-1", roomNumber = null)
            val state = state(source)
            state.start()
            runCurrent()
            assertNotNull(state.table(PendingList.REALLOCATION).error)

            source.failures.remove(FakePendingSource.Op.REALLOCATION)
            state.select(PendingList.REALLOCATION)
            state.refresh()
            runCurrent()

            assertNull(state.table(PendingList.REALLOCATION).error)
            assertEquals(listOf("A-1"), state.codes(PendingList.REALLOCATION))
            val row = state.table.rows.single()
            assertEquals("UH alocada fora de ordem na estadia", row.detail)
            assertEquals(PA_DASH, row.holder)
            assertEquals(1, source.guaranteeCalls.size)
            assertEquals(1, source.depositCalls)
            assertEquals(1, source.noShowCalls.size)
        }

    @Test
    fun unknownGuaranteeTypeIsOtherAndOverdueWithoutZoneStaysFlagged() {
        val item =
            pendingGuaranteeOf(kind = "UNGUARANTEED", guaranteeType = "VOUCHER", overdue = true)

        val (detail, due) = PendingText.guarantee(item, zone = null)

        assertEquals("Sem garantia · Outro (VOUCHER)", detail)
        assertEquals("$PA_DASH (vencida)", due)
    }

    @Test
    fun receivedWithoutRequiredIsNotShownAndValuesAreTheServers() {
        val withBoth = pendingGuaranteeOf(requiredDeposit = "0.00", depositReceived = "0.00")
        val receivedOnly = pendingGuaranteeOf(depositReceived = "50.00")

        assertEquals(
            "Pré-reserva · Cartão · exigido R$ 0,00 · recebido R$ 0,00",
            PendingText.guarantee(withBoth, null).first,
        )
        assertEquals("Pré-reserva · Cartão", PendingText.guarantee(receivedOnly, null).first)
    }
}
