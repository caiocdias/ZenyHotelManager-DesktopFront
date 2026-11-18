package com.zeny.desktop.app.reception

import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.DayList.ARRIVALS
import com.zeny.desktop.core.facade.DayList.DEPARTURES
import com.zeny.desktop.core.facade.DayList.IN_HOUSE
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.ListedAssignmentResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val NETWORK_MESSAGE = "Não foi possível falar com o servidor. Confira o endereço e a rede."
private const val FORBIDDEN_MESSAGE = "Você não tem permissão para esta ação."

private fun reservationId(n: Int) = Uuid.parse("20000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

private fun guestId(n: Int) = Uuid.parse("30000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

@Suppress("LongParameterList") // fixture: cada campo da linha que os testes variam, com padrão
private fun item(
    n: Int,
    holder: Int = n,
    status: String = "CONFIRMED",
    room: String? = null,
    dayUse: Boolean = false,
    arrivalOverdue: Boolean = false,
    departureOverdue: Boolean = false,
) = ReservationListItemResponse(
    adults = 2,
    arrival = "2026-11-13",
    arrivalOverdue = arrivalOverdue,
    bookingCode = "B-$n",
    bookingId = Uuid.random(),
    channel = "DIRECT",
    children = 1,
    code = "R-$n",
    dayUse = dayUse,
    departure = "2026-11-15",
    departureOverdue = departureOverdue,
    folioId = Uuid.random(),
    holderGuestId = guestId(holder),
    id = reservationId(n),
    origin = "FRONT_DESK",
    ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
    ratePlanId = Uuid.random(),
    roomAssignment =
        room?.let {
            Option.Some(ListedAssignmentResponse(false, Uuid.random(), false, Uuid.random(), Option.Some(it)))
        } ?: Option.Undefined,
    roomTypeId = Uuid.parse(typeId(1)),
    segment = "LEISURE",
    status = status,
    total = "500.00",
    version = 1,
)

private fun forbidden() = ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList())

private fun network() = ApiError.Network(IOException("offline"))

private fun roomsMessage(n: Int) = RealtimeEvent.Message("rooms", "rooms.room-status", "room-$n", n.toLong())

@OptIn(ExperimentalCoroutinesApi::class)
class DayPanelStateTest {
    private val date = BusinessDate.parse("2026-11-13")

    /** Uma página da lista; cada titular ganha um nome fictício no servidor de mentira. */
    private fun FakeReceptionSource.list(
        list: DayList,
        items: List<ReservationListItemResponse>,
        cursor: String? = null,
        next: String? = null,
    ) = apply {
        pages[list to cursor] = Page(items, next)
        items.forEach { holders.putIfAbsent(it.holderGuestId.toString(), HolderSummary("Hóspede ${it.code}", null)) }
    }

    private fun source() =
        FakeReceptionSource().apply {
            businessDay = BusinessDay(date, null)
            types += roomType(typeId(1), "Standard")
        }

    /** O estado vive no `backgroundScope`, como o mapa de UHs: a assinatura não segura o fim do teste. */
    private fun TestScope.panel(source: FakeReceptionSource) =
        DayPanelState(source, backgroundScope, source.permissions).also { runCurrent() }

    private fun DayPanelState.rows(list: DayList) = tabs.getValue(list).rows

    private fun DayPanelState.labels() = DayList.entries.map(::tabLabel)

    private fun DayPanelState.tabMessage(list: DayList) = tabs.getValue(list).error?.userMessage

    private fun TestScope.releaseHeldPages(source: FakeReceptionSource) {
        while (source.heldDayPages.any { !it.isCompleted }) {
            source.heldDayPages.forEach { it.complete(Unit) }
            runCurrent()
        }
    }

    @Test
    fun loadsThreeTabsForBusinessDate() =
        runTest {
            val other = BusinessDate.parse("2026-12-01")
            val source =
                source().apply {
                    businessDay = BusinessDay(other, null)
                    list(ARRIVALS, (1..4).map { item(it) })
                    list(DEPARTURES, (11..12).map { item(it) })
                    list(IN_HOUSE, (21..27).map { item(it) })
                }
            val state = panel(source)

            assertEquals(BusinessDay(other, null), state.day)
            assertNull(state.error)
            assertEquals(ARRIVALS, state.selectedTab)
            assertEquals(DayList.entries.toSet(), source.dayPageCalls.map { it.list }.toSet())
            assertEquals(3, source.dayPageCalls.size)
            assertTrue(source.dayPageCalls.all { it.date == other && it.cursor == null })
            assertEquals(listOf(200, 200, 200), source.dayPageLimits)
            assertEquals(listOf("Chegadas (4)", "Saídas (2)", "Hospedados (7)"), state.labels())
            assertEquals(
                listOf("Reserva", "Titular", "UH", "Tipo", "Chegada", "Saída", "Hóspedes", "Situação"),
                state.columns.map { it.header },
            )
        }

    @Test
    fun noPageBeforeBusinessDay() =
        runTest {
            val source = source().list(ARRIVALS, listOf(item(1)))
            source.holdBusinessDay = true
            val state = panel(source)
            assertEquals(listOf("Chegadas", "Saídas", "Hospedados"), state.labels())

            // A `DataTable` pede a primeira página assim que aparece, antes de a data responder.
            backgroundScope.launch { state.tabs.getValue(ARRIVALS).loadMore() }
            runCurrent()
            assertEquals(emptyList(), source.dayPageCalls)
            assertNull(state.day)

            source.heldBusinessDays.single().complete(Unit)
            runCurrent()

            assertEquals(1, source.dayPageCalls.count { it.list == ARRIVALS })
            assertTrue(source.dayPageCalls.all { it.date == date })
            assertEquals("Chegadas (1)", state.tabLabel(ARRIVALS))
        }

    @Test
    fun emptyListShowsZero() =
        runTest {
            val state = panel(source())

            assertEquals("Chegadas (0)", state.tabLabel(ARRIVALS))
            assertNull(state.tabs.getValue(ARRIVALS).error)
            assertNull(state.error)
        }

    @Test
    fun tabErrorKeepsOthers() =
        runTest {
            val source =
                source().apply {
                    list(ARRIVALS, listOf(item(1)))
                    list(IN_HOUSE, listOf(item(2), item(3)))
                    dayPageErrors[DEPARTURES] = network()
                }
            val state = panel(source)

            assertEquals(NETWORK_MESSAGE, state.tabMessage(DEPARTURES))
            assertNull(state.tabs.getValue(ARRIVALS).error)
            assertNull(state.tabs.getValue(IN_HOUSE).error)
            assertEquals(listOf("Chegadas (1)", "Saídas", "Hospedados (2)"), state.labels())
            assertNull(state.error)
        }

    @Test
    fun forbiddenListsShowMessage() =
        runTest {
            val source = source().apply { dayPageError = forbidden() }
            val state = panel(source)

            DayList.entries.forEach { assertEquals(FORBIDDEN_MESSAGE, state.tabMessage(it)) }
            assertNull(state.error)
            assertEquals(BusinessDay(date, null), state.day)
        }

    @Test
    fun refreshAfterErrorRecovers() =
        runTest {
            val source = source().list(ARRIVALS, listOf(item(1), item(2)))
            source.dayPageError = network()
            val state = panel(source)
            assertEquals(NETWORK_MESSAGE, state.tabMessage(ARRIVALS))

            source.dayPageError = null
            state.refresh()
            runCurrent()

            DayList.entries.forEach { assertNull(state.tabs.getValue(it).error) }
            assertEquals(listOf("Chegadas (2)", "Saídas (0)", "Hospedados (0)"), state.labels())
        }

    @Test
    fun businessDayErrorShowsError() =
        runTest {
            val source = source().list(ARRIVALS, listOf(item(1)))
            source.businessDayError = network()
            val state = panel(source)

            assertEquals(NETWORK_MESSAGE, state.error?.userMessage)
            assertNull(state.day)
            assertEquals(emptyList(), source.dayPageCalls)

            source.businessDayError = null
            state.refresh()
            runCurrent()

            assertNull(state.error)
            assertEquals(BusinessDay(date, null), state.day)
            assertEquals("Chegadas (1)", state.tabLabel(ARRIVALS))
        }

    @Test
    fun holderNameFromSummary() =
        runTest {
            val source = source().list(ARRIVALS, listOf(item(1, room = "101"), item(2)))
            source.holders[guestId(1).toString()] = HolderSummary("Ana Souza", "CPF ***.***.***-01")
            val state = panel(source)

            assertEquals(
                listOf(
                    ReservationRow(
                        id = reservationId(1).toString(),
                        code = "R-1",
                        holder = "Ana Souza",
                        room = "101",
                        roomType = "Standard",
                        arrival = "13/11/2026",
                        departure = "15/11/2026",
                        guests = "2 adultos e 1 criança",
                        status = "Confirmada",
                    ),
                    ReservationRow(
                        id = reservationId(2).toString(),
                        code = "R-2",
                        holder = "Hóspede R-2",
                        room = "—",
                        roomType = "Standard",
                        arrival = "13/11/2026",
                        departure = "15/11/2026",
                        guests = "2 adultos e 1 criança",
                        status = "Confirmada",
                    ),
                ),
                state.rows(ARRIVALS),
            )
            val row = state.rows(ARRIVALS).first()
            assertEquals(
                listOf(
                    "R-1",
                    "Ana Souza",
                    "101",
                    "Standard",
                    "13/11/2026",
                    "15/11/2026",
                    "2 adultos e 1 criança",
                    "Confirmada",
                ),
                state.columns.map { it.cell(row) },
            )
        }

    @Test
    fun guestForbiddenShowsDash() =
        runTest {
            for (status in listOf(403, 404)) {
                val source = source().list(ARRIVALS, listOf(item(1)))
                source.holderError = ApiError.Problem(status, null, null, emptyList())
                val state = panel(source)

                assertEquals("—", state.rows(ARRIVALS).single().holder)
                assertNull(state.tabs.getValue(ARRIVALS).error)

                // Guardado: a atualização não pergunta de novo.
                state.refresh()
                runCurrent()
                assertEquals(listOf(guestId(1).toString()), source.holderCalls)
                assertEquals("—", state.rows(ARRIVALS).single().holder)
                state.close()
            }
        }

    @Test
    fun guestNetworkErrorFailsTab() =
        runTest {
            val source = source().list(ARRIVALS, listOf(item(1))).list(IN_HOUSE, emptyList())
            source.holderError = network()
            val state = panel(source)

            assertEquals(NETWORK_MESSAGE, state.tabMessage(ARRIVALS))
            assertEquals(emptyList(), state.rows(ARRIVALS))
            assertNull(state.tabs.getValue(IN_HOUSE).error)
            assertNull(state.error)
        }

    @Test
    fun holderFetchedOncePerId() =
        runTest {
            val source =
                source()
                    .list(ARRIVALS, listOf(item(1, holder = 7)))
                    .list(DEPARTURES, listOf(item(2, holder = 7)))
            val state = panel(source)

            assertEquals(listOf(guestId(7).toString()), source.holderCalls)
            assertEquals("Hóspede R-1", state.rows(ARRIVALS).single().holder)
            assertEquals("Hóspede R-1", state.rows(DEPARTURES).single().holder)
            assertEquals(1, source.roomTypesCalls)
        }

    @Test
    fun holdersResolvedFourAtATime() =
        runTest {
            val source = source().list(ARRIVALS, (1..6).map { item(it) })
            source.holdHolder = true
            val state = panel(source)

            assertEquals(4, source.heldHolders.size)
            source.heldHolders.forEach { it.second.complete(Unit) }
            runCurrent()
            assertEquals(6, source.heldHolders.size)
            source.heldHolders.forEach { it.second.complete(Unit) }
            runCurrent()

            assertEquals("Chegadas (6)", state.tabLabel(ARRIVALS))
        }

    @Test
    fun roomTypeForbiddenShowsDash() =
        runTest {
            val source = source().list(ARRIVALS, listOf(item(1))).list(IN_HOUSE, listOf(item(2)))
            source.roomTypesError = forbidden()
            val state = panel(source)

            assertEquals("—", state.rows(ARRIVALS).single().roomType)
            assertEquals("—", state.rows(IN_HOUSE).single().roomType)
            assertNull(state.tabs.getValue(ARRIVALS).error)
            assertEquals(1, source.roomTypesCalls)
        }

    @Test
    fun statusCellCarriesAlerts() =
        runTest {
            val source =
                source().list(
                    ARRIVALS,
                    listOf(
                        item(1, dayUse = true, arrivalOverdue = true),
                        item(2, status = "IN_HOUSE", departureOverdue = true),
                        item(3, dayUse = true, arrivalOverdue = true, departureOverdue = true),
                        item(4, status = "MYSTERY"),
                    ),
                )
            val state = panel(source)

            assertEquals(
                listOf(
                    "Confirmada · Day use · Chegada pendente",
                    "Hospedada · Saída pendente",
                    "Confirmada · Day use · Chegada pendente · Saída pendente",
                    "Outro (MYSTERY)",
                ),
                state.rows(ARRIVALS).map { it.status },
            )
            val table = state.tabs.getValue(ARRIVALS)
            table.filter = "chegada pendente"
            assertEquals(listOf("R-1", "R-3"), table.visibleRows(state.columns).map { it.code })
        }

    @Test
    fun labelShowsPlusWhileMorePages() =
        runTest {
            val source =
                source()
                    .list(ARRIVALS, (1..200).map { item(it) }, next = "c2")
                    .list(ARRIVALS, (201..230).map { item(it) }, cursor = "c2")
            val state = panel(source)
            assertEquals("Chegadas (200+)", state.tabLabel(ARRIVALS))

            state.tabs.getValue(ARRIVALS).loadMore()
            runCurrent()

            assertEquals("Chegadas (230)", state.tabLabel(ARRIVALS))
            assertEquals(listOf(null, "c2"), source.dayPageCalls.filter { it.list == ARRIVALS }.map { it.cursor })
        }

    @Test
    fun subscribesTopicsFromTheReceivedSet() =
        runTest {
            val cases =
                listOf(
                    setOf("rooms.status.read") to listOf(setOf("rooms")),
                    setOf("endofday.runs.read") to listOf(setOf("end-of-day")),
                    setOf("rooms.status.read", "endofday.runs.read") to listOf(setOf("rooms", "end-of-day")),
                    setOf("reservations.reservations.read") to emptyList(),
                    emptySet<String>() to emptyList(),
                )
            for ((permissions, expected) in cases) {
                val source = source()
                val state = DayPanelState(source, backgroundScope, permissions).also { runCurrent() }
                assertEquals(expected, source.subscribedTopics, "permissões $permissions")
                if (expected.isEmpty()) assertEquals(0, source.subscriptions, "events chamado com $permissions")
                assertEquals(permissions, state.permissions)
                assertEquals(BusinessDay(date, null), state.day)
                state.close()
            }
        }

    @Test
    fun roomsBurstIsDebounced() =
        runTest {
            val source = source()
            val state = panel(source)
            assertEquals(1, source.businessDayCalls)
            val start = currentTime

            repeat(5) {
                if (it > 0) advanceTimeBy(250)
                source.emit(roomsMessage(it))
                runCurrent()
            }
            assertEquals(start + 1000, currentTime)
            assertEquals(1, source.businessDayCalls)

            advanceTimeBy(start + 1999 - currentTime)
            runCurrent()
            assertEquals(1, source.businessDayCalls)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, source.businessDayCalls)

            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, source.businessDayCalls)
            assertNull(state.error)
        }

    @Test
    fun endOfDayRefreshesWithNewDate() =
        runTest {
            val source = source().list(ARRIVALS, listOf(item(1)))
            val state = panel(source)
            val next = BusinessDate.parse("2026-11-14")
            source.businessDay = BusinessDay(next, null)
            source.dayPageCalls.clear()

            source.emit(RealtimeEvent.Message("end-of-day", "endofday.run-completed", "run-1", 1))
            runCurrent()

            assertEquals(BusinessDay(next, null), state.day)
            assertEquals(3, source.dayPageCalls.size)
            assertTrue(source.dayPageCalls.all { it.date == next })
        }

    @Test
    fun onlyFirstReadyRefreshes() =
        runTest {
            val source = source()
            panel(source)

            source.emit(RealtimeEvent.Ready)
            runCurrent()
            assertEquals(2, source.businessDayCalls)

            source.emit(RealtimeEvent.Ready)
            runCurrent()
            assertEquals(2, source.businessDayCalls)

            source.emit(RealtimeEvent.Reset)
            runCurrent()
            assertEquals(3, source.businessDayCalls)
        }

    @Test
    fun burstOfTriggersCoalesces() =
        runTest {
            val source = source().list(ARRIVALS, listOf(item(1)))
            source.holdDayPage = true
            val state = panel(source)
            assertEquals(1, source.businessDayCalls)

            state.onWindowFocus(false)
            state.onWindowFocus(true)
            state.refresh()
            source.emit(RealtimeEvent.Reset)
            runCurrent()
            releaseHeldPages(source)

            assertEquals(2, source.businessDayCalls)
            assertEquals(6, source.dayPageCalls.size)
            assertEquals("Chegadas (1)", state.tabLabel(ARRIVALS))
        }

    @Test
    fun refreshKeepsRowsVisible() =
        runTest {
            val source = source().list(ARRIVALS, (1..4).map { item(it) })
            val state = panel(source)
            source.list(ARRIVALS, (1..5).map { item(it) })
            source.holdDayPage = true

            state.refresh()
            runCurrent()
            assertEquals(3, source.heldDayPages.size)
            assertEquals(4, state.rows(ARRIVALS).size)
            assertEquals("Chegadas (4)", state.tabLabel(ARRIVALS))

            releaseHeldPages(source)
            assertEquals("Chegadas (5)", state.tabLabel(ARRIVALS))
        }

    @Test
    fun focusRefreshesOnlyOnGain() =
        runTest {
            val source = source()
            val state = panel(source)

            state.onWindowFocus(true)
            runCurrent()
            assertEquals(1, source.businessDayCalls)

            state.onWindowFocus(false)
            runCurrent()
            assertEquals(1, source.businessDayCalls)

            state.onWindowFocus(true)
            runCurrent()
            assertEquals(2, source.businessDayCalls)

            state.onWindowFocus(true)
            runCurrent()
            assertEquals(2, source.businessDayCalls)
        }

    @Test
    fun streamFailureKeepsPanel() =
        runTest {
            for (failure in listOf(network(), null)) {
                val source = source().list(ARRIVALS, listOf(item(1)))
                val state = panel(source)

                source.end(failure)
                runCurrent()

                assertEquals(0, source.activeSubscriptions)
                assertEquals(1, source.subscriptions)
                assertNull(state.error)
                assertEquals("Chegadas (1)", state.tabLabel(ARRIVALS))

                source.list(ARRIVALS, listOf(item(1), item(2)))
                state.refresh()
                runCurrent()
                assertEquals(2, source.businessDayCalls)
                assertEquals("Chegadas (2)", state.tabLabel(ARRIVALS))
                state.close()
            }
        }

    @Test
    fun closeCancelsSubscriptionAndLoads() =
        runTest {
            val source = source().list(ARRIVALS, listOf(item(1)))
            source.holdDayPage = true
            val state = panel(source)
            source.emit(roomsMessage(1))
            runCurrent()

            state.close()
            runCurrent()
            assertEquals(0, source.activeSubscriptions)
            assertEquals(1, source.cancelledSubscriptions)

            source.heldDayPages.forEach { it.complete(Unit) }
            state.refresh()
            state.onWindowFocus(false)
            state.onWindowFocus(true)
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(1, source.businessDayCalls)
            assertEquals(3, source.dayPageCalls.size)
            assertEquals("Chegadas", state.tabLabel(ARRIVALS))
        }

    @Test
    fun selectChangesTab() =
        runTest {
            val state = panel(source())

            state.select(IN_HOUSE)

            assertEquals(IN_HOUSE, state.selectedTab)
        }

    @Test
    fun rowToStringHidesHolder() {
        val row =
            ReservationRow(
                id = reservationId(1).toString(),
                code = "R-1",
                holder = "Maria Fictícia",
                room = "101",
                roomType = "Standard",
                arrival = "13/11/2026",
                departure = "15/11/2026",
                guests = "2 adultos",
                status = "Confirmada",
            )

        assertFalse("Maria" in row.toString())
        assertTrue("R-1" in row.toString())
    }
}
