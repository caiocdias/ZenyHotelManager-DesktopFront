package com.zeny.desktop.app.rooms

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Ataque ao protocolo do [RoomMapState]: ready/reset, falha e fim da assinatura, retry e close(). */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomMapProtocolAtaqueTest {
    private val a = roomId(1)
    private val b = roomId(2)
    private val c = roomId(3)

    // ---------------------------------------------------------------- protocolo

    @Test
    fun messageBeforeFirstReadyNeitherLoadsNorRefetches() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackMap(source)
            runCurrent()

            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(a, 2)
            source.message(b, 7, type = "rooms.out-of-order")
            source.message(c, 1)
            runCurrent()

            assertEquals(0, source.statusesCalls, "nada carrega antes do primeiro ready/reset")
            assertEquals(emptyList(), source.statusCalls)
            assertTrue(state.loading)

            source.emit(RealtimeEvent.Ready)
            runCurrent()

            assertEquals(1, source.statusesCalls, "o ready carrega uma vez; as mensagens anteriores não pedem mais")
            assertEquals(2, state.tileOf(a).version)
            assertEquals(RoomDisplayStatus.DIRTY, state.tileOf(a).status)
        }

    @Test
    fun resetDuringInitialLoadReloadsOnceMoreAfterIt() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.holdStatuses = true
            val state = attackMap(source)
            runCurrent()
            source.emit(RealtimeEvent.Ready)
            runCurrent()
            assertEquals(1, source.statusesCalls)

            source.put(roomStatus(b, "102", occupancy = Occupancy.OCCUPIED, version = 3))
            source.emit(RealtimeEvent.Reset)
            runCurrent()
            assertEquals(1, source.statusesCalls, "o reset espera a carga em voo")

            source.holdStatuses = false
            source.heldStatuses.single().complete(Unit)
            runCurrent()

            assertEquals(2, source.statusesCalls)
            assertFalse(state.loading)
            assertEquals(3, state.tileOf(b).version)
            assertEquals(RoomDisplayStatus.OCCUPIED, state.tileOf(b).status)
        }

    @Test
    fun repeatedReadyDuringInitialLoadDoesNotQueueAnotherLoad() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.holdStatuses = true
            val state = attackMap(source)
            runCurrent()
            repeat(3) { source.emit(RealtimeEvent.Ready) }
            runCurrent()

            source.holdStatuses = false
            source.heldStatuses.single().complete(Unit)
            runCurrent()

            assertEquals(1, source.statusesCalls)
            assertEquals(listOf("101", "102"), state.shownNumbers())
        }

    @Test
    fun failedBeforeFirstReadyStopsLoadingWithPortugueseError() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackMap(source)
            runCurrent()

            source.end(forbiddenProblem())
            runCurrent()

            assertFalse(state.loading)
            assertEquals("Você não tem permissão para esta ação.", state.error?.userMessage)
            assertEquals(0, source.statusesCalls)
            assertEquals(emptyList(), state.floors)
        }

    @Test
    fun failedWhileInitialLoadInFlightIsNotErasedWhenLoadCompletes() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.holdStatuses = true
            val state = attackMap(source)
            runCurrent()
            source.emit(RealtimeEvent.Ready)
            runCurrent()
            val failure = forbiddenProblem()

            source.end(failure)
            runCurrent()
            assertSame(failure, state.error)

            source.heldStatuses.single().complete(Unit)
            runCurrent()

            assertSame(failure, state.error, "a carga que termina depois não apaga a falha do stream")
            assertFalse(state.loading)
            assertEquals(listOf("101", "102"), state.shownNumbers())
        }

    @Test
    fun failedAfterLoadKeepsTilesAndStopsListening() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)

            source.end(IOException("connection reset by peer 10.0.0.7"))
            runCurrent()

            val error = assertNotNull(state.error)
            assertTrue(error is ApiError.Network, "falha que não é ApiError vira erro de rede")
            assertEquals("Não foi possível falar com o servidor. Confira o endereço e a rede.", error.userMessage)
            assertFalse(state.loading)
            assertEquals(listOf("101", "102"), state.shownNumbers())
            assertEquals(0, source.activeSubscriptions)
        }

    @Test
    fun retryAfterFailedPressedTwiceSubscribesOnlyOnce() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.end(forbiddenProblem())
            runCurrent()

            state.retry()
            state.retry()
            runCurrent()

            assertEquals(2, source.subscriptions)
            assertEquals(1, source.activeSubscriptions)
            assertTrue(state.loading, "espera o ready da assinatura nova")
            assertNull(state.error)
            assertEquals(1, source.statusesCalls, "nada carrega antes do ready da assinatura nova")

            source.emit(RealtimeEvent.Ready)
            runCurrent()

            assertFalse(state.loading)
            assertNull(state.error)
            assertEquals(2, source.statusesCalls)
        }

    @Test
    fun retryAfterFailedThatFailsAgainShowsTheNewError() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.end(forbiddenProblem())
            runCurrent()

            state.retry()
            runCurrent()
            val second = ApiError.Problem(404, "NOT_FOUND", null, emptyList())
            source.end(second)
            runCurrent()

            assertSame(second, state.error)
            assertFalse(state.loading)
        }

    @Test
    fun sessionEndStopsApplyingButRetryResubscribesAndReloadsOnReady() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)

            source.end()
            runCurrent()
            assertEquals(0, source.activeSubscriptions)
            assertNull(state.error)
            assertFalse(state.loading)

            state.retry()
            runCurrent()
            assertEquals(2, source.subscriptions)
            assertEquals(1, source.statusesCalls)

            source.put(roomStatus(c, "103"))
            source.emit(RealtimeEvent.Ready)
            runCurrent()

            assertEquals(2, source.statusesCalls)
            assertEquals(listOf("101", "102", "103"), state.shownNumbers())
        }

    @Test
    fun retryWhileSubscribedReloadsWithoutResubscribing() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.statusesError = serverErrorProblem()
            source.emit(RealtimeEvent.Reset)
            runCurrent()
            assertNotNull(state.error)

            source.statusesError = null
            state.retry()
            runCurrent()

            assertEquals(1, source.subscriptions)
            assertEquals(3, source.statusesCalls)
            assertNull(state.error)
            assertFalse(state.loading)
        }

    // ---------------------------------------------------------------- close()

    @Test
    fun closeDuringInitialLoadAppliesNothing() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.holdStatuses = true
            val state = attackMap(source)
            runCurrent()
            source.emit(RealtimeEvent.Ready)
            runCurrent()

            state.close()
            source.heldStatuses.single().complete(Unit)
            runCurrent()

            assertEquals(emptyList(), state.floors)
            assertNull(state.error)
            assertEquals(0, source.activeSubscriptions)
        }

    @Test
    fun closeDuringRefetchAppliesNothing() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.holdStatus = true
            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(a, 2)
            runCurrent()

            state.close()
            source.heldStatus
                .single()
                .second
                .complete(Unit)
            runCurrent()

            assertEquals(1, state.tileOf(a).version)
            assertEquals(RoomDisplayStatus.CLEAN, state.tileOf(a).status)
            assertEquals(1, source.statusesCalls, "a rebusca cancelada não pede recarga")
        }

    @Test
    fun closeDuringDetailReadAppliesNothing() =
        runTest {
            val fake = FakeRoomsSource().withRooms101And102()
            val source = HeldRoomSource(fake)
            val state = attackLoaded(source, fake)
            state.select(a)
            runCurrent()

            state.close()
            source.heldRoom
                .single()
                .second
                .complete(Unit)
            runCurrent()

            assertNull(state.selected?.room)
            assertNull(state.error)
        }

    @Test
    fun closeDropsThePendingReload() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.holdStatuses = true
            source.emit(RealtimeEvent.Reset)
            source.message(a, 9, type = "rooms.out-of-order")
            runCurrent()
            assertEquals(2, source.statusesCalls)

            state.close()
            source.holdStatuses = false
            source.heldStatuses.single().complete(Unit)
            source.emit(RealtimeEvent.Reset)
            runCurrent()

            assertEquals(2, source.statusesCalls, "nem a pendente nem um evento novo recarregam depois do close")
        }

    @Test
    fun cancellingTheUiScopeAlsoStopsTheMap() =
        runTest {
            val ui = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val source = FakeRoomsSource().withRooms101And102()
            RoomMapState(source, ui)
            runCurrent()
            assertEquals(1, source.activeSubscriptions)

            ui.cancel()
            runCurrent()

            assertEquals(0, source.activeSubscriptions, "o mapa não sobrevive ao escopo da UI")
        }

    @Test
    fun closeCancelsOnlyTheMapNotTheUiScope() =
        runTest {
            val ui = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val first = FakeRoomsSource().withRooms101And102()
            val second = FakeRoomsSource().withRooms101And102()
            val closed = RoomMapState(first, ui)
            val other = RoomMapState(second, ui)
            runCurrent()

            closed.close()
            runCurrent()

            assertTrue(ui.isActive, "o close() não derruba o escopo de quem criou")
            assertEquals(0, first.activeSubscriptions)
            assertEquals(1, second.activeSubscriptions)
            second.emit(RealtimeEvent.Ready)
            runCurrent()
            assertEquals(listOf("101", "102"), other.shownNumbers())
        }
}
