package com.zeny.desktop.app.folio

import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.FolioResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RESERVATION = "20000000-0000-0000-0000-000000000001"
private const val READ = "billing.folios.read"
private const val CREATE = "billing.postings.create"
private const val REVERSE = "billing.postings.reverse"

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra
class FolioListStateTest {
    /** A aba "Contas" com a lista lida e a conta [FOLIO_ID] aberta no painel. */
    private fun TestScope.accounts(source: FakeFolioSource): FolioAccounts =
        FolioAccounts(wiringOf(source, setOf(READ, CREATE, REVERSE)), backgroundScope).also {
            it.list.start()
            it.select(FOLIO_ID.toString())
            runCurrent()
        }

    @Test
    fun refreshRereadsTheFirstPage() =
        runTest {
            val source = FakeFolioSource()
            source.pages = { cursor ->
                if (cursor ==
                    null
                ) {
                    Page(listOf(folioOf(number = "F-1")), "c-2")
                } else {
                    Page(listOf(folioOf(OTHER_FOLIO_ID)), null)
                }
            }
            val list = FolioListState(source, backgroundScope)
            list.start()
            runCurrent()
            list.loadMore()
            runCurrent()
            assertEquals(2, list.rows.size)

            source.pages = { Page(listOf(folioOf(number = "F-1", balance = "99.00")), "c-2") }
            list.refresh()
            runCurrent()

            assertEquals(listOf("R$ 99,00"), list.rows.map { it.balance })
            assertTrue(list.hasMore)
            assertEquals(listOf(null, "c-2", null), source.pageCalls.map { it.first })
        }

    @Test
    fun refreshDropsTheLateAnswerOfThePageInFlight() =
        runTest {
            val source = FakeFolioSource()
            val list = FolioListState(source, backgroundScope)
            list.start()
            runCurrent()
            val late = LateAnswer<Page<FolioResponse>>()
            source.pages = { late.await() }
            list.refresh()
            runCurrent()

            source.pages = { Page(listOf(folioOf(balance = "50.00")), null) }
            list.refresh()
            runCurrent()
            late.answer(Page(listOf(folioOf(balance = "1.00")), null))
            runCurrent()

            assertEquals(listOf("R$ 50,00"), list.rows.map { it.balance })
            assertFalse(list.loading)
        }

    @Test
    fun postingInThePanelRefreshesTheListRow() =
        runTest {
            val source = FakeFolioSource()
            source.pages = { Page(listOf(folioOf(balance = "150.00")), null) }
            val accounts = accounts(source)
            val folio = assertNotNull(accounts.open)
            folio.startPosting()
            runCurrent()
            val form = assertNotNull(folio.posting)
            form.codeId = form.codes.first().id
            form.unitPrice = "25,00"
            source.pages = { Page(listOf(folioOf(balance = "175.00", exceeded = true)), null) }
            source.folio = { folioOf(balance = "175.00", exceeded = true) }

            form.confirm()
            runCurrent()

            val row = accounts.list.rows.single()
            assertEquals("R$ 175,00", row.balance)
            assertTrue(row.limitExceeded)
            assertEquals(folio.view?.balance, row.balance)
        }

    @Test
    fun reversalInThePanelRefreshesTheListRow() =
        runTest {
            val source = FakeFolioSource()
            source.postings = { listOf(postingOf(description = "Frigobar")) }
            val accounts = accounts(source)
            val folio = assertNotNull(accounts.open)
            folio.startReversal(folio.postings.single().id)
            val form = assertNotNull(folio.reversal)
            form.reason = "Cobrado em dobro"
            source.pages = { Page(listOf(folioOf(balance = "130.00")), null) }

            form.confirm()
            runCurrent()

            assertEquals(listOf("R$ 130,00"), accounts.list.rows.map { it.balance })
        }

    @Test
    fun folioClosedInThePanelLeavesTheList() =
        runTest {
            val source = FakeFolioSource()
            source.post = { throw ApiError.Problem(409, "FOLIO_CLOSED", null, emptyList()) }
            val accounts = accounts(source)
            val folio = assertNotNull(accounts.open)
            folio.startPosting()
            runCurrent()
            val form = assertNotNull(folio.posting)
            form.codeId = form.codes.first().id
            form.unitPrice = "25,00"
            source.pages = { Page(emptyList(), null) }
            source.folio = { folioOf(status = FolioResponse.Status.CLOSED) }

            form.confirm()
            runCurrent()

            assertTrue(accounts.list.rows.isEmpty(), "a conta encerrada continua na lista das abertas")
        }

    @Test
    fun loadMoreFollowsTheCursor() =
        runTest {
            val source = FakeFolioSource()
            source.pages = { cursor ->
                if (cursor == null) {
                    Page(listOf(folioOf(number = "F-1", balance = "10.00", exceeded = true)), "c-2")
                } else {
                    Page(listOf(folioOf(OTHER_FOLIO_ID, number = "F-2")), null)
                }
            }
            val list = FolioListState(source, backgroundScope)

            list.start()
            runCurrent()

            assertEquals(listOf("F-1"), list.rows.map { it.number })
            assertTrue(list.hasMore)
            val first = list.rows.single()
            assertEquals("R$ 10,00", first.balance)
            assertEquals("Reserva", first.owner)
            assertTrue(first.limitExceeded)

            list.loadMore()
            runCurrent()

            assertEquals(listOf("F-1", "F-2"), list.rows.map { it.number })
            assertFalse(list.hasMore)
            assertEquals(listOf(null, "c-2"), source.pageCalls.map { it.first })
        }

    @Test
    fun pageFailureShowsErrorAndKeepsRows() =
        runTest {
            val source = FakeFolioSource()
            source.pages = { throw ApiError.Network(RuntimeException("sem rede")) }
            val list = FolioListState(source, backgroundScope)

            list.start()
            runCurrent()

            assertNotNull(list.error)
            assertTrue(list.rows.isEmpty())
        }

    @Test
    fun reservationWithoutFolioSaysSo() =
        runTest {
            val source = FakeFolioSource()
            source.byReservation = { emptyList() }
            val state = ReservationFoliosState(source, backgroundScope, RESERVATION)

            state.start()
            runCurrent()

            assertEquals("Esta reserva ainda não tem conta.", state.empty)
            assertNull(state.chosen)
            assertEquals(listOf(RESERVATION), source.reservationCalls)
        }

    @Test
    fun reservationWithOneFolioOpensIt() =
        runTest {
            val source = FakeFolioSource()
            val state = ReservationFoliosState(source, backgroundScope, RESERVATION)

            state.start()
            runCurrent()

            assertNull(state.empty)
            assertEquals(FOLIO_ID.toString(), state.chosen)
        }

    @Test
    fun reservationWithSeveralFoliosOffersTheChoice() =
        runTest {
            val source = FakeFolioSource()
            source.byReservation = { listOf(folioOf(number = "F-1"), folioOf(OTHER_FOLIO_ID, number = "F-2")) }
            val state = ReservationFoliosState(source, backgroundScope, RESERVATION)

            state.start()
            runCurrent()

            assertNull(state.chosen)
            assertEquals(listOf("F-1", "F-2"), state.folios.map { it.number })

            state.choose(OTHER_FOLIO_ID.toString())

            assertEquals(OTHER_FOLIO_ID.toString(), state.chosen)
        }
}
