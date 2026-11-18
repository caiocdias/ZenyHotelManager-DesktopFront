package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Page
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RESERVATION = "20000000-0000-0000-0000-000000000001"

@OptIn(ExperimentalCoroutinesApi::class)
class FolioListStateTest {
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
