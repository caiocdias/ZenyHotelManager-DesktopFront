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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val ATK_NETWORK_MESSAGE = "Não foi possível falar com o servidor. Confira o endereço e a rede."

private fun atkReservationId(n: Int) = Uuid.parse("40000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

private fun atkGuestId(n: Int) = Uuid.parse("50000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

/** Uma linha fictícia das listas do dia; só o que os ataques variam tem parâmetro. */
private fun atkItem(
    n: Int,
    holder: Int = n,
    assignment: Option<ListedAssignmentResponse> = Option.Undefined,
    roomTypeId: String = typeId(1),
) = ReservationListItemResponse(
    adults = 1,
    arrival = "2026-11-13",
    arrivalOverdue = false,
    bookingCode = "B-$n",
    bookingId = Uuid.random(),
    channel = "DIRECT",
    children = 0,
    code = "R-$n",
    dayUse = false,
    departure = "2026-11-14",
    departureOverdue = false,
    folioId = Uuid.random(),
    holderGuestId = atkGuestId(holder),
    id = atkReservationId(n),
    origin = "FRONT_DESK",
    ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
    ratePlanId = Uuid.random(),
    roomAssignment = assignment,
    roomTypeId = Uuid.parse(roomTypeId),
    segment = "LEISURE",
    status = "CONFIRMED",
    total = "300.00",
    version = 1,
)

private fun assignmentWith(number: Option<String?>) =
    Option.Some(ListedAssignmentResponse(false, Uuid.random(), false, Uuid.random(), number))

private fun atkNetwork() = ApiError.Network(IOException("offline"))

private fun atkRooms(n: Int) = RealtimeEvent.Message("rooms", "rooms.room-status", "room-$n", n.toLong())

private fun atkEndOfDay(n: Int) = RealtimeEvent.Message("end-of-day", "endofday.run-completed", "run-$n", n.toLong())

/**
 * Ataque ao [DayPanelState]: coalescência de gatilhos, janela de `rooms`, data operacional, titulares e tipos,
 * `close()` e escopo, e dados de hóspede fora de texto técnico.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayPanelStateAtaqueTest {
    private val date = BusinessDate.parse("2026-11-13")
    private val nextDate = BusinessDate.parse("2026-11-14")

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

    private fun TestScope.panel(source: FakeReceptionSource) =
        DayPanelState(source, backgroundScope).also { runCurrent() }

    private fun DayPanelState.codes(list: DayList) = tabs.getValue(list).rows.map { it.code }

    private fun DayPanelState.tabMessage(list: DayList) = tabs.getValue(list).error?.userMessage

    /** Solta as leituras do dia presas, uma a uma, conferindo que nunca há duas em voo. */
    private fun TestScope.releaseBusinessDays(source: FakeReceptionSource) {
        runCurrent()
        while (source.heldBusinessDays.any { !it.isCompleted }) {
            assertEquals(1, source.heldBusinessDays.count { !it.isCompleted }, "duas atualizações em voo")
            source.heldBusinessDays.first { !it.isCompleted }.complete(Unit)
            runCurrent()
        }
    }

    private fun TestScope.releaseHeldPages(source: FakeReceptionSource) {
        while (source.heldDayPages.any { !it.isCompleted }) {
            source.heldDayPages.forEach { it.complete(Unit) }
            runCurrent()
        }
    }

    // ------------------------------------------------------------ coalescência

    @Test
    fun everyTriggerDuringLoadCoalescesIntoOneMore() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1)))
            source.holdBusinessDay = true
            val state = panel(source)
            assertEquals(1, source.heldBusinessDays.size)

            repeat(3) { state.refresh() }
            state.onWindowFocus(false)
            state.onWindowFocus(true)
            source.emit(RealtimeEvent.Ready)
            source.emit(RealtimeEvent.Reset)
            source.emit(atkEndOfDay(1))
            source.emit(atkRooms(1))
            runCurrent()
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(1, source.businessDayCalls, "nenhum gatilho começa outra carga com uma em voo")

            releaseBusinessDays(source)

            assertEquals(2, source.businessDayCalls, "uma em voo e uma pendente, venha de onde vier")
            assertEquals(6, source.dayPageCalls.size, "três listas por carga, duas cargas")
            assertEquals("Chegadas (1)", state.tabLabel(ARRIVALS))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, source.businessDayCalls)
        }

    @Test
    fun triggerDuringPendingRunIsNotLost() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1)))
            source.holdBusinessDay = true
            val state = panel(source)
            state.refresh()
            runCurrent()
            source.heldBusinessDays[0].complete(Unit)
            runCurrent()
            assertEquals(2, source.businessDayCalls, "a pendente começa assim que a primeira termina")

            // O fechamento do dia acontece com a pendente já em voo: ele vira outra pendente, não some.
            source.businessDay = BusinessDay(nextDate, null)
            source.emit(atkEndOfDay(1))
            runCurrent()
            assertEquals(2, source.businessDayCalls)
            releaseBusinessDays(source)

            assertEquals(3, source.businessDayCalls)
            assertEquals(BusinessDay(nextDate, null), state.day)
            assertEquals(List(3) { nextDate }, source.dayPageCalls.takeLast(3).map { it.date })
        }

    @Test
    fun resetAlwaysRefreshes() =
        runTest {
            val source = source()
            panel(source)

            source.emit(RealtimeEvent.Reset)
            runCurrent()
            source.emit(RealtimeEvent.Reset)
            runCurrent()

            assertEquals(3, source.businessDayCalls)
        }

    // ------------------------------------------------------------ janela de rooms

    @Test
    fun secondRoomsBurstOpensNewWindow() =
        runTest {
            val source = source()
            panel(source)

            source.emit(atkRooms(1))
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(2, source.businessDayCalls)

            advanceTimeBy(500)
            source.emit(atkRooms(2))
            runCurrent()
            advanceTimeBy(500)
            source.emit(atkRooms(3))
            runCurrent()
            advanceTimeBy(1_499)
            runCurrent()
            assertEquals(2, source.businessDayCalls, "a nova janela conta da primeira mensagem dela (t+2 500)")

            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, source.businessDayCalls)
        }

    @Test
    fun endOfDayDoesNotWaitForRoomsWindow() =
        runTest {
            val source = source()
            panel(source)

            source.emit(atkRooms(1))
            runCurrent()
            advanceTimeBy(500)
            source.emit(atkEndOfDay(1))
            runCurrent()
            assertEquals(2, source.businessDayCalls, "o fechamento do dia atualiza na hora")

            advanceTimeBy(1_500)
            runCurrent()
            assertEquals(3, source.businessDayCalls, "a janela de rooms segue a conta dela")
        }

    @Test
    fun otherTopicsDoNotRefresh() =
        runTest {
            val source = source()
            panel(source)

            source.emit(RealtimeEvent.Message("notifications", "notifications.created", "n-1", 1))
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(1, source.businessDayCalls)
        }

    // ------------------------------------------------------------ fim da assinatura

    @Test
    fun streamEndIsNotRetried() =
        runTest {
            val source = source()
            val state = panel(source)

            source.end(atkNetwork())
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(1, source.subscriptions, "o painel para de ouvir; não assina de novo")
            assertEquals(1, source.businessDayCalls)
            assertNull(state.error)
        }

    @Test
    fun streamEndingWithNonApiFailureKeepsPanel() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1)))
            val state = panel(source)

            source.end(IllegalStateException("queda do canal"))
            runCurrent()
            state.refresh()
            runCurrent()

            assertNull(state.error)
            assertEquals(0, source.activeSubscriptions)
            assertEquals(2, source.businessDayCalls)
            assertEquals("Chegadas (1)", state.tabLabel(ARRIVALS))
        }

    // ------------------------------------------------------------ data operacional

    @Test
    fun noPageWhileBusinessDayFails() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1)))
            source.businessDayError = atkNetwork()
            val state = panel(source)

            // A `DataTable` das três abas pede página enquanto o dia não chegou.
            DayList.entries.forEach { list -> backgroundScope.launch { state.tabs.getValue(list).loadMore() } }
            runCurrent()
            assertEquals(emptyList(), source.dayPageCalls)
            assertNotNull(state.error)

            source.businessDayError = null
            state.refresh()
            runCurrent()

            assertEquals(DayList.entries.toSet(), source.dayPageCalls.map { it.list }.toSet())
            assertEquals(3, source.dayPageCalls.size, "o pedido que esperava a data não sai junto com a carga")
            assertTrue(source.dayPageCalls.all { it.date == date })
            assertEquals("Chegadas (1)", state.tabLabel(ARRIVALS))
        }

    @Test
    fun endOfDayKeepsOldRowsUntilNewDatePageArrives() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1), atkItem(2)))
            val state = panel(source)
            source.businessDay = BusinessDay(nextDate, null)
            source.list(ARRIVALS, listOf(atkItem(3)))
            source.holdDayPage = true
            source.dayPageCalls.clear()

            source.emit(atkEndOfDay(1))
            runCurrent()

            assertEquals(listOf("R-1", "R-2"), state.codes(ARRIVALS), "a tabela não pisca vazia")
            assertEquals("Chegadas (2)", state.tabLabel(ARRIVALS))
            assertEquals(3, source.dayPageCalls.size)
            assertTrue(source.dayPageCalls.all { it.date == nextDate })

            releaseHeldPages(source)
            assertEquals(listOf("R-3"), state.codes(ARRIVALS))
            assertEquals("Chegadas (1)", state.tabLabel(ARRIVALS))
        }

    @Test
    fun nextPageUsesDateOfCurrentLoad() =
        runTest {
            val source =
                source()
                    .list(ARRIVALS, (1..3).map { atkItem(it) }, next = "c2")
                    .list(ARRIVALS, (4..5).map { atkItem(it) }, cursor = "c2")
            val state = panel(source)
            // O servidor já virou o dia, mas nenhuma carga nova aconteceu: a página 2 é da carga vigente.
            source.businessDay = BusinessDay(nextDate, null)

            state.tabs.getValue(ARRIVALS).loadMore()
            runCurrent()

            assertEquals(DayPageCall(ARRIVALS, date, "c2"), source.dayPageCalls.last())
            assertEquals(200, source.dayPageLimits.last())
            assertEquals(1, source.roomTypesCalls, "a página 2 é da mesma carga: tipos não são relidos")
            assertEquals("Chegadas (5)", state.tabLabel(ARRIVALS))

            source.emit(atkEndOfDay(1))
            runCurrent()
            assertEquals(DayPageCall(ARRIVALS, nextDate, null), source.dayPageCalls.last { it.list == ARRIVALS })
            assertEquals("Chegadas (3+)", state.tabLabel(ARRIVALS))

            state.tabs.getValue(ARRIVALS).loadMore()
            runCurrent()
            assertEquals(DayPageCall(ARRIVALS, nextDate, "c2"), source.dayPageCalls.last())
            assertEquals("Chegadas (5)", state.tabLabel(ARRIVALS))
        }

    // ------------------------------------------------------------ titulares

    @Test
    fun holderFailureKindsFailTab() =
        runTest {
            val failures =
                listOf(
                    ApiError.SessionEnded(null),
                    ApiError.IncompatibleResponse(IllegalStateException("formato")),
                    ApiError.Problem(500, null, null, emptyList()),
                    ApiError.Problem(401, null, null, emptyList()),
                    ApiError.Unexpected(502),
                )
            for (failure in failures) {
                val source = source().list(ARRIVALS, listOf(atkItem(1))).list(IN_HOUSE, listOf(atkItem(2)))
                source.holderErrors[atkGuestId(1).toString()] = failure
                val state = panel(source)

                assertEquals(failure.userMessage, state.tabMessage(ARRIVALS), "falha ${failure::class.simpleName}")
                assertEquals(emptyList(), state.codes(ARRIVALS))
                assertNull(state.tabMessage(IN_HOUSE))
                assertEquals("Hospedados (1)", state.tabLabel(IN_HOUSE))
                assertNull(state.error)
                state.close()
            }
        }

    @Test
    fun forbiddenHolderDashesOnlyItsRow() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1), atkItem(2)))
            source.holderErrors[atkGuestId(1).toString()] = ApiError.Problem(403, null, null, emptyList())
            val state = panel(source)

            assertEquals(
                listOf("—", "Hóspede R-2"),
                state.tabs
                    .getValue(ARRIVALS)
                    .rows
                    .map { it.holder },
            )
            assertNull(state.tabMessage(ARRIVALS))
        }

    @Test
    fun failedHolderIsReadAgainButMissingOneIsNot() =
        runTest {
            val missing = atkGuestId(1).toString()
            val flaky = atkGuestId(2).toString()
            val source = source().list(ARRIVALS, listOf(atkItem(1))).list(DEPARTURES, listOf(atkItem(2)))
            source.holderErrors[missing] = ApiError.Problem(404, null, null, emptyList())
            source.holderErrors[flaky] = atkNetwork()
            val state = panel(source)
            assertEquals(ATK_NETWORK_MESSAGE, state.tabMessage(DEPARTURES))
            assertEquals(
                "—",
                state.tabs
                    .getValue(ARRIVALS)
                    .rows
                    .single()
                    .holder,
            )

            source.holderErrors.remove(flaky)
            state.refresh()
            runCurrent()

            assertEquals(1, source.holderCalls.count { it == missing }, "o 404 fica guardado")
            assertEquals(2, source.holderCalls.count { it == flaky }, "a falha de rede não fica guardada")
            assertNull(state.tabMessage(DEPARTURES))
            assertEquals(
                "Hóspede R-2",
                state.tabs
                    .getValue(DEPARTURES)
                    .rows
                    .single()
                    .holder,
            )
        }

    @Test
    fun knownHolderNotReadAgainOnRefresh() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1))).list(IN_HOUSE, listOf(atkItem(2)))
            val state = panel(source)
            assertEquals(2, source.holderCalls.size)

            state.refresh()
            runCurrent()
            source.emit(atkEndOfDay(1))
            runCurrent()

            assertEquals(2, source.holderCalls.size)
            assertEquals(
                "Hóspede R-1",
                state.tabs
                    .getValue(ARRIVALS)
                    .rows
                    .single()
                    .holder,
            )
        }

    @Test
    fun sameHolderTwiceInPageReadOnce() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1, holder = 9), atkItem(2, holder = 9)))
            val state = panel(source)

            assertEquals(listOf(atkGuestId(9).toString()), source.holderCalls)
            assertEquals("Chegadas (2)", state.tabLabel(ARRIVALS))
        }

    @Test
    fun refreshSwapsRowsOnlyAfterHoldersResolve() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1)))
            val state = panel(source)
            source.list(ARRIVALS, listOf(atkItem(1), atkItem(2)))
            source.holdHolder = true

            state.refresh()
            runCurrent()
            assertEquals(listOf(atkGuestId(2).toString()), source.heldHolders.map { it.first })
            assertEquals(listOf("R-1"), state.codes(ARRIVALS), "sem linha pela metade enquanto o titular não chega")
            assertEquals("Chegadas (1)", state.tabLabel(ARRIVALS))

            source.heldHolders.forEach { it.second.complete(Unit) }
            runCurrent()
            assertEquals(
                listOf("Hóspede R-1", "Hóspede R-2"),
                state.tabs
                    .getValue(ARRIVALS)
                    .rows
                    .map { it.holder },
            )
        }

    @Test
    fun holderErrorOnRefreshKeepsOldRows() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1)))
            val state = panel(source)
            source.list(ARRIVALS, listOf(atkItem(1), atkItem(2)))
            source.holderErrors[atkGuestId(2).toString()] = atkNetwork()

            state.refresh()
            runCurrent()

            assertEquals(listOf("R-1"), state.codes(ARRIVALS))
            assertEquals(ATK_NETWORK_MESSAGE, state.tabMessage(ARRIVALS))
            assertNull(state.error)
        }

    // ------------------------------------------------------------ tipos e UH

    @Test
    fun roomTypesReadAgainOnEachLoad() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1)))
            val state = panel(source)
            assertEquals(1, source.roomTypesCalls)
            source.types.clear()
            source.types += roomType(typeId(1), "Luxo")

            state.refresh()
            runCurrent()

            assertEquals(2, source.roomTypesCalls)
            assertEquals(
                "Luxo",
                state.tabs
                    .getValue(ARRIVALS)
                    .rows
                    .single()
                    .roomType,
            )
        }

    @Test
    fun unknownOrBlankRoomTypeShowsDash() =
        runTest {
            val source = source()
            source.types += roomType(typeId(2), "  ")
            source.list(
                ARRIVALS,
                listOf(atkItem(1), atkItem(2, roomTypeId = typeId(2)), atkItem(3, roomTypeId = typeId(9))),
            )
            val state = panel(source)

            assertEquals(
                listOf("Standard", "—", "—"),
                state.tabs
                    .getValue(ARRIVALS)
                    .rows
                    .map { it.roomType },
            )
        }

    @Test
    fun assignmentWithoutNumberShowsDash() =
        runTest {
            val source =
                source().list(
                    ARRIVALS,
                    listOf(
                        atkItem(1, assignment = assignmentWith(Option.Undefined)),
                        atkItem(2, assignment = assignmentWith(Option.Some(null))),
                        atkItem(3, assignment = assignmentWith(Option.Some("  "))),
                        atkItem(4, assignment = assignmentWith(Option.Some("204"))),
                    ),
                )
            val state = panel(source)

            assertEquals(
                listOf("—", "—", "—", "204"),
                state.tabs
                    .getValue(ARRIVALS)
                    .rows
                    .map { it.room },
            )
        }

    // ------------------------------------------------------------ close() e escopo

    @Test
    fun closeDuringBusinessDayLeavesNoErrorAndNoPages() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1)))
            source.holdBusinessDay = true
            val state = panel(source)

            state.close()
            runCurrent()
            source.heldBusinessDays.forEach { it.complete(Unit) }
            runCurrent()

            assertNull(state.error, "o cancelamento não vira erro de rede")
            assertNull(state.day)
            assertEquals(emptyList(), source.dayPageCalls)
        }

    @Test
    fun closeDropsPendingUpdate() =
        runTest {
            val source = source()
            source.holdBusinessDay = true
            val state = panel(source)
            state.refresh()
            runCurrent()

            state.close()
            runCurrent()
            source.heldBusinessDays.forEach { it.complete(Unit) }
            runCurrent()

            assertEquals(1, source.businessDayCalls)
            assertEquals(emptyList(), source.dayPageCalls)
        }

    @Test
    fun closeDuringHolderLookupLeavesNoTabError() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1)))
            source.holdHolder = true
            val state = panel(source)
            assertEquals(1, source.heldHolders.size)

            state.close()
            runCurrent()
            source.heldHolders.forEach { it.second.complete(Unit) }
            runCurrent()

            assertNull(state.tabMessage(ARRIVALS))
            assertEquals(emptyList(), state.codes(ARRIVALS))
        }

    @Test
    fun closeKeepsCallerScopeAlive() =
        runTest {
            val parent = Job(backgroundScope.coroutineContext[Job])
            val scope = CoroutineScope(backgroundScope.coroutineContext + parent)
            val sibling = scope.launch { awaitCancellation() }
            val source = source()
            val state = DayPanelState(source, scope)
            runCurrent()

            state.close()
            runCurrent()

            assertTrue(parent.isActive, "o close() cancela só o que é do painel")
            assertTrue(sibling.isActive)
            assertEquals(0, source.activeSubscriptions)
        }

    @Test
    fun cancellingCallerScopeStopsPanel() =
        runTest {
            val parent = Job(backgroundScope.coroutineContext[Job])
            val scope = CoroutineScope(backgroundScope.coroutineContext + parent)
            val source = source()
            val state = DayPanelState(source, scope)
            runCurrent()
            assertEquals(1, source.activeSubscriptions)

            parent.cancel()
            runCurrent()
            state.refresh()
            source.emit(atkEndOfDay(1))
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(0, source.activeSubscriptions)
            assertEquals(1, source.cancelledSubscriptions)
            assertEquals(1, source.businessDayCalls)
        }

    // ------------------------------------------------------------ dados de hóspede

    @Test
    fun panelTextsCarryNoHolderName() =
        runTest {
            val source = source().list(ARRIVALS, listOf(atkItem(1)))
            source.holders[atkGuestId(1).toString()] = HolderSummary("Beatriz Fictícia", "CPF ***.***.***-09")
            val state = panel(source)
            assertEquals(
                "Beatriz Fictícia",
                state.tabs
                    .getValue(ARRIVALS)
                    .rows
                    .single()
                    .holder,
            )

            val texts =
                listOf(
                    state.toString(),
                    state.tabs.toString(),
                    state.tabs
                        .getValue(ARRIVALS)
                        .rows
                        .toString(),
                ) + DayList.entries.map(state::tabLabel)

            texts.forEach { text ->
                assertFalse("Beatriz" in text, "nome do titular em: $text")
                assertFalse("***" in text, "documento em: $text")
            }
        }
}
