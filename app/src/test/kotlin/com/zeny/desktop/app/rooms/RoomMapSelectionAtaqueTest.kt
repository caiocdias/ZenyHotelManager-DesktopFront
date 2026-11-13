package com.zeny.desktop.app.rooms

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ataque à seleção e aos erros do [RoomMapState]: detalhe que acompanha o mapa, 403 que degrada e erros em pt-BR. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomMapSelectionAtaqueTest {
    private val a = roomId(1)
    private val b = roomId(2)
    private val c = roomId(3)

    // ---------------------------------------------------------------- seleção

    @Test
    fun selectionClearsWhenResetRemovesTheRoom() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            state.select(b)
            runCurrent()
            assertNotNull(state.selected)

            source.server.remove(b)
            source.emit(RealtimeEvent.Reset)
            runCurrent()

            assertNull(state.selected)
        }

    @Test
    fun selectionClearsWhenMessageTurnsRoomInactive() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            state.select(b)
            runCurrent()

            source.put(roomStatus(b, "102", version = 2, active = false))
            source.message(b, 2)
            runCurrent()

            assertNull(state.selected)
        }

    @Test
    fun selectingUnknownOrInactiveRoomOpensNothing() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.put(roomStatus(c, "103", active = false))
            val state = attackLoaded(source)

            state.select(roomId(9))
            state.select(c)
            runCurrent()

            assertNull(state.selected)
            assertEquals(emptyList(), source.roomCalls)
        }

    @Test
    fun selectingHiddenByFilterStillOpensDetail() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            state.toggleStatus(RoomDisplayStatus.DIRTY)

            state.select(a)
            runCurrent()

            assertEquals(
                a,
                state.selected
                    ?.status
                    ?.roomId
                    ?.toString(),
            )
        }

    @Test
    fun detailReadArrivingAfterMessageKeepsTheNewerStatus() =
        runTest {
            val fake = FakeRoomsSource().withRooms101And102()
            val source = HeldRoomSource(fake)
            val state = attackLoaded(source, fake)

            state.select(a)
            runCurrent()
            assertNull(state.selected?.room)

            fake.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            fake.message(a, 2)
            runCurrent()
            assertEquals(2, state.selected?.status?.version)

            source.heldRoom
                .single()
                .second
                .complete(Unit)
            runCurrent()

            val detail = assertNotNull(state.selected)
            assertEquals(a, detail.room?.id?.toString())
            assertEquals(2, detail.status.version, "o cadastro que chega depois não volta o status")
            assertEquals(HousekeepingStatus.DIRTY, detail.status.housekeepingStatus)
        }

    @Test
    fun switchingSelectionDiscardsThePreviousDetailRead() =
        runTest {
            val fake = FakeRoomsSource().withRooms101And102()
            val source = HeldRoomSource(fake)
            val state = attackLoaded(source, fake)

            state.select(a)
            runCurrent()
            state.select(b)
            runCurrent()
            assertEquals(listOf(a, b), source.heldRoom.map { it.first })

            source.heldRoom[0].second.complete(Unit)
            runCurrent()
            val pending = assertNotNull(state.selected)
            assertEquals(b, pending.status.roomId.toString())
            assertNull(pending.room, "o cadastro da UH anterior não entra no detalhe da atual")

            source.heldRoom[1].second.complete(Unit)
            runCurrent()
            assertEquals(
                b,
                state.selected
                    ?.room
                    ?.id
                    ?.toString(),
            )
        }

    @Test
    fun clearSelectionDuringDetailReadKeepsItClosed() =
        runTest {
            val fake = FakeRoomsSource().withRooms101And102()
            val source = HeldRoomSource(fake)
            val state = attackLoaded(source, fake)

            state.select(a)
            runCurrent()
            state.clearSelection()
            source.heldRoom
                .single()
                .second
                .complete(Unit)
            runCurrent()

            assertNull(state.selected)
        }

    @Test
    fun detailServerErrorShowsPortugueseErrorAndKeepsTheMap() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.roomError = serverErrorProblem()
            val state = attackLoaded(source)

            state.select(a)
            runCurrent()

            val error = assertNotNull(state.error)
            assertEquals("O servidor falhou ao processar o pedido. Tente de novo em instantes.", error.userMessage)
            assertFalse("INTERNAL_ERROR" in error.userMessage)
            assertFalse("NullPointerException" in error.userMessage)
            assertEquals(listOf("101", "102"), state.shownNumbers())

            source.roomError = null
            state.retry()
            runCurrent()
            assertNull(state.error)
            assertFalse(state.loading)
            assertEquals(listOf("101", "102"), state.shownNumbers())
        }

    @Test
    fun detailIncompatibleResponseShowsVersionMessage() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.roomError = incompatibleResponse()
            val state = attackLoaded(source)

            state.select(a)
            runCurrent()

            assertEquals(VERSION_MESSAGE, state.error?.userMessage)
        }

    // ---------------------------------------------------------------- erros

    @Test
    fun roomTypesErrorOtherThanForbiddenIsShownNotDegraded() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.roomTypesError = serverErrorProblem()
            val state = attackLoaded(source)

            val error = assertNotNull(state.error)
            assertEquals("O servidor falhou ao processar o pedido. Tente de novo em instantes.", error.userMessage)
            assertFalse(state.loading)
        }

    @Test
    fun roomTypesIncompatibleResponseShowsVersionMessage() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.roomTypesError = incompatibleResponse()
            val state = attackLoaded(source)

            assertTrue(state.error is ApiError.IncompatibleResponse)
            assertEquals(VERSION_MESSAGE, state.error?.userMessage)
        }

    @Test
    fun unexpectedExceptionFromStatusesBecomesNetworkMessageWithoutDetails() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.statusesError = IllegalStateException("stack secreto em RoomRepository:42")
            val state = attackLoaded(source)

            val error = assertNotNull(state.error)
            assertTrue(error is ApiError.Network)
            assertFalse("RoomRepository" in error.userMessage)
        }

    @Test
    fun incompatibleResponseDuringLiveUpdateShowsVersionMessage() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.statusError = incompatibleResponse()
            source.statusesError = incompatibleResponse()

            source.message(a, 2)
            runCurrent()

            assertTrue(state.error is ApiError.IncompatibleResponse)
            assertEquals(VERSION_MESSAGE, state.error?.userMessage)
            assertEquals(listOf("101", "102"), state.shownNumbers(), "o erro não apaga o quadro já carregado")
        }

    @Test
    fun forbiddenStatusesIsAnErrorNotADegradation() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.statusesError = forbiddenProblem()
            val state = attackLoaded(source)

            assertEquals("Você não tem permissão para esta ação.", state.error?.userMessage)
            assertEquals(emptyList(), state.floors)
        }
}
