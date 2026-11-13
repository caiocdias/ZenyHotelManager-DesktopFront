package com.zeny.desktop.app.rooms

import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ataque à consistência do [RoomMapState]: rebusca e recarga intercaladas, versões e agrupamento por andar. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomMapConsistencyAtaqueTest {
    private val a = roomId(1)
    private val b = roomId(2)
    private val c = roomId(3)

    // ---------------------------------------------------------------- consistência

    @Test
    fun burstOfSameRoomAnsweredOutOfOrderKeepsHighestVersion() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.holdStatus = true
            val byVersion =
                mapOf(
                    2L to HousekeepingStatus.DIRTY,
                    3L to HousekeepingStatus.CLEAN,
                    4L to HousekeepingStatus.INSPECTED,
                )

            for ((version, housekeeping) in byVersion) {
                source.put(roomStatus(a, "101", housekeeping = housekeeping, version = version))
                source.message(a, version)
                runCurrent()
            }
            assertEquals(3, source.heldStatus.size)

            for (i in listOf(2, 0, 1)) {
                source.heldStatus[i].second.complete(Unit)
                runCurrent()
            }

            assertEquals(4, state.tileOf(a).version)
            assertEquals(RoomDisplayStatus.INSPECTED, state.tileOf(a).status)
            assertEquals(1, source.statusesCalls)
        }

    @Test
    fun lateRefetchAfterResetRemovedRoomDoesNotBringItBack() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.holdStatus = true

            source.put(roomStatus(b, "102", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(b, 2)
            runCurrent()
            source.server.remove(b)
            source.emit(RealtimeEvent.Reset)
            runCurrent()
            assertEquals(listOf("101"), state.shownNumbers())

            source.heldStatus
                .single()
                .second
                .complete(Unit)
            runCurrent()

            assertEquals(listOf("101"), state.shownNumbers(), "a rebusca atrasada não devolve a UH removida")
        }

    @Test
    fun lateRefetchDoesNotUndoNewerVersionFromReset() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.holdStatus = true

            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(a, 2)
            runCurrent()
            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.INSPECTED, version = 3))
            source.emit(RealtimeEvent.Reset)
            runCurrent()
            assertEquals(3, state.tileOf(a).version)

            source.heldStatus
                .single()
                .second
                .complete(Unit)
            runCurrent()

            assertEquals(3, state.tileOf(a).version)
            assertEquals(RoomDisplayStatus.INSPECTED, state.tileOf(a).status)
        }

    @Test
    fun lateActiveRefetchDoesNotReviveRoomThatResetFoundInactive() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.holdStatus = true

            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(a, 2)
            runCurrent()
            source.put(roomStatus(a, "101", version = 3, active = false))
            source.emit(RealtimeEvent.Reset)
            runCurrent()
            assertEquals(listOf("102"), state.shownNumbers())

            source.heldStatus
                .single()
                .second
                .complete(Unit)
            runCurrent()

            assertEquals(listOf("102"), state.shownNumbers())
        }

    @Test
    fun inactiveRoomReactivatedByMessageComesBack() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)

            source.put(roomStatus(b, "102", version = 2, active = false))
            source.message(b, 2)
            runCurrent()
            assertEquals(listOf("101"), state.shownNumbers())

            source.put(roomStatus(b, "102", housekeeping = HousekeepingStatus.DIRTY, version = 3))
            source.message(b, 3)
            runCurrent()

            assertEquals(listOf("101", "102"), state.shownNumbers())
            assertEquals(3, state.tileOf(b).version)
            assertEquals(RoomDisplayStatus.DIRTY, state.tileOf(b).status)
        }

    @Test
    fun refetchWithoutHigherVersionIsNotApplied() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)

            // O servidor ainda devolve a versão 1 (com outro conteúdo): não é mais nova que o ladrilho.
            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 1))
            source.message(a, 2)
            runCurrent()

            assertEquals(listOf(a), source.statusCalls)
            assertEquals(1, state.tileOf(a).version)
            assertEquals(RoomDisplayStatus.CLEAN, state.tileOf(a).status)
        }

    @Test
    fun newRoomMessageDuringReloadInFlightAppearsAfterPendingReload() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.holdStatuses = true
            source.emit(RealtimeEvent.Reset)
            runCurrent()

            source.put(roomStatus(c, "103"))
            source.message(c, 1)
            runCurrent()
            assertEquals(2, source.statusesCalls)

            source.holdStatuses = false
            source.heldStatuses.single().complete(Unit)
            runCurrent()

            assertEquals(3, source.statusesCalls)
            assertEquals(listOf("101", "102", "103"), state.shownNumbers())
            assertEquals(emptyList(), source.statusCalls)
        }

    @Test
    fun reloadAfterCoalescedBurstStillRuns() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            source.holdStatuses = true
            repeat(3) { source.message(a, 10L + it, type = "rooms.out-of-order") }
            runCurrent()
            source.holdStatuses = false
            source.heldStatuses[0].complete(Unit)
            runCurrent()
            assertEquals(3, source.statusesCalls)

            source.put(roomStatus(c, "103"))
            source.emit(RealtimeEvent.Reset)
            runCurrent()

            assertEquals(4, source.statusesCalls, "terminada a rajada, um reset novo recarrega")
            assertEquals(listOf("101", "102", "103"), state.shownNumbers())
        }

    // ---------------------------------------------------------------- agrupamento

    @Test
    fun floorsThatCompareEqualNumericallyAreNotMerged() =
        runTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(roomId(1), "201", floor = "2"),
                roomStatus(roomId(2), "0201", floor = "02"),
                roomStatus(roomId(3), "101", floor = "1"),
                roomStatus(roomId(4), "0101", floor = "1"),
                roomStatus(roomId(5), "101", floor = "1"),
                roomStatus(roomId(6), "9", floor = "123456789012345678901234567890"),
            )
            val state = attackLoaded(source)

            val floors = state.floors.map { it.first }
            assertEquals(4, floors.size, "\"02\" e \"2\" são andares distintos: $floors")
            assertEquals("Andar 1", floors.first())
            assertEquals(setOf("Andar 02", "Andar 2"), floors.subList(1, 3).toSet())
            assertEquals("Andar 123456789012345678901234567890", floors.last())
            assertEquals(6, state.allTiles().size, "nenhuma UH some no agrupamento, nem as de número repetido")
            assertEquals(
                listOf("101", "101"),
                state.floors
                    .first()
                    .second
                    .map { it.number }
                    .filter { it == "101" },
            )
        }

    @Test
    fun blankOrNullFloorGoesToNoFloorAtTheEnd() =
        runTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(roomId(1), "B", floor = "  "),
                roomStatus(roomId(2), "A").copy(floor = Option.Some(null)),
                roomStatus(roomId(3), "C", floor = null),
                roomStatus(roomId(4), "301", floor = "3"),
            )
            val state = attackLoaded(source)

            assertEquals(listOf("Andar 3", "Sem andar"), state.floors.map { it.first })
            assertEquals(
                listOf("A", "B", "C"),
                state.floors
                    .last()
                    .second
                    .map { it.number },
            )
        }
}
