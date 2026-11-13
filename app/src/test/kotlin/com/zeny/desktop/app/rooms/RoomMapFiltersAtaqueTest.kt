package com.zeny.desktop.app.rooms

import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.sdk.model.OutOfOrderOverlayResponse
import com.zeny.desktop.sdk.model.RoomResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ataque aos filtros e contadores do [RoomMapState] e à ausência de dado de hóspede no estado. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomMapFiltersAtaqueTest {
    private val a = roomId(1)
    private val b = roomId(2)
    private val c = roomId(3)

    // ---------------------------------------------------------------- filtros e contadores

    @Test
    fun typeAndStatusFiltersCombineAndCountersFollowOnlyType() =
        runTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101", type = typeId(1), housekeeping = HousekeepingStatus.DIRTY),
                roomStatus(b, "102", type = typeId(1)),
                roomStatus(c, "103", type = typeId(2), housekeeping = HousekeepingStatus.DIRTY),
            )
            val state = attackLoaded(source)

            state.toggleType(typeId(1))
            state.toggleStatus(RoomDisplayStatus.DIRTY)

            assertEquals(listOf("101"), state.shownNumbers())
            assertEquals(
                zeroCounters() + mapOf(RoomDisplayStatus.DIRTY to 1, RoomDisplayStatus.CLEAN to 1),
                state.counters,
            )

            state.toggleType(typeId(2))
            assertEquals(listOf("101", "103"), state.shownNumbers())
            assertEquals(
                zeroCounters() + mapOf(RoomDisplayStatus.DIRTY to 2, RoomDisplayStatus.CLEAN to 1),
                state.counters,
            )
        }

    @Test
    fun statusFilterMatchesMainStatusOrHousekeepingOfBlockedAndOccupiedRooms() =
        runTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(
                    a,
                    "101",
                    housekeeping = HousekeepingStatus.DIRTY,
                    outOfOrder = OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER,
                ),
                roomStatus(b, "102", occupancy = Occupancy.OCCUPIED),
                roomStatus(c, "103", housekeeping = HousekeepingStatus.INSPECTED),
            )
            val state = attackLoaded(source)

            state.toggleStatus(RoomDisplayStatus.OUT_OF_ORDER)
            assertEquals(listOf("101"), state.shownNumbers())
            state.toggleStatus(RoomDisplayStatus.OUT_OF_ORDER)

            state.toggleStatus(RoomDisplayStatus.DIRTY)
            assertEquals(listOf("101"), state.shownNumbers(), "a UH bloqueada e suja passa pela governança")
            state.toggleStatus(RoomDisplayStatus.DIRTY)

            state.toggleStatus(RoomDisplayStatus.OCCUPIED)
            assertEquals(listOf("102"), state.shownNumbers())
            state.toggleStatus(RoomDisplayStatus.OCCUPIED)

            state.toggleStatus(RoomDisplayStatus.CLEAN)
            // A ocupada e limpa passa pela governança; a inspecionada não.
            assertEquals(listOf("102"), state.shownNumbers())
            state.toggleStatus(RoomDisplayStatus.CLEAN)

            state.toggleStatus(RoomDisplayStatus.OUT_OF_SERVICE)
            assertEquals(emptyList(), state.shownNumbers())
        }

    @Test
    fun cleanFilterDoesNotMatchCleanAwaitingInspection() =
        runTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101").copy(inspectionPending = true),
                roomStatus(b, "102"),
            )
            val state = attackLoaded(source)

            state.toggleStatus(RoomDisplayStatus.CLEAN)
            assertEquals(listOf("102"), state.shownNumbers())

            state.toggleStatus(RoomDisplayStatus.CLEAN)
            state.toggleStatus(RoomDisplayStatus.CLEAN_PENDING_INSPECTION)
            assertEquals(listOf("101"), state.shownNumbers())
        }

    @Test
    fun countersAndFiltersIgnoreInactiveRooms() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.put(roomStatus(c, "103", type = typeId(2), occupancy = Occupancy.OCCUPIED, active = false))
            val state = attackLoaded(source)

            assertEquals(zeroCounters() + mapOf(RoomDisplayStatus.CLEAN to 2), state.counters)
            state.toggleStatus(RoomDisplayStatus.OCCUPIED)
            assertEquals(emptyList(), state.shownNumbers())
            state.toggleStatus(RoomDisplayStatus.OCCUPIED)
            state.toggleType(typeId(2))
            assertEquals(emptyList(), state.shownNumbers())
            assertEquals(zeroCounters(), state.counters)
        }

    @Test
    fun filtersStayAppliedToLiveUpdatesAndReloads() =
        runTest {
            val source = FakeRoomsSource().withRooms101And102()
            val state = attackLoaded(source)
            state.toggleStatus(RoomDisplayStatus.DIRTY)
            assertEquals(emptyList(), state.shownNumbers())

            source.put(roomStatus(b, "102", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(b, 2)
            runCurrent()
            assertEquals(listOf("102"), state.shownNumbers())

            source.put(roomStatus(c, "103", housekeeping = HousekeepingStatus.DIRTY))
            source.emit(RealtimeEvent.Reset)
            runCurrent()
            assertEquals(listOf("102", "103"), state.shownNumbers())
            assertEquals(setOf(RoomDisplayStatus.DIRTY), state.statusFilter)
            assertEquals(
                zeroCounters() + mapOf(RoomDisplayStatus.CLEAN to 1, RoomDisplayStatus.DIRTY to 2),
                state.counters,
            )
        }

    @Test
    fun typeFilterWhoseRoomsDisappearedShowsNothingAndCanBeCleared() =
        runTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101", type = typeId(1)), roomStatus(b, "102", type = typeId(2)))
            val state = attackLoaded(source)
            state.toggleType(typeId(2))
            assertEquals(listOf("102"), state.shownNumbers())

            source.server.remove(b)
            source.types.removeAt(1)
            source.emit(RealtimeEvent.Reset)
            runCurrent()

            assertEquals(emptyList(), state.floors)
            assertEquals(zeroCounters(), state.counters)

            state.toggleType(typeId(2))
            assertEquals(emptySet(), state.typeFilter)
            assertEquals(listOf("101"), state.shownNumbers())
        }

    // ---------------------------------------------------------------- LGPD

    private fun fieldNames(type: Class<*>) =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()

    @Test
    fun stateHoldsNoGuestPersonalData() {
        assertEquals(
            setOf("roomId", "number", "roomTypeId", "status", "housekeeping", "label", "version"),
            fieldNames(RoomTile::class.java),
        )
        assertEquals(setOf("room", "status"), fieldNames(RoomDetail::class.java))

        // O detalhe guarda os modelos do contrato: nenhum campo deles pode identificar hóspede.
        val personal = Regex("(?i)name|email|phone|document|cpf|birth|guest(?!count)|reservation|booking|holder")
        val models =
            listOf(RoomStatusResponse::class.java, RoomResponse::class.java, OutOfOrderOverlayResponse::class.java)
        for (model in models) {
            val leaking = fieldNames(model).filter { personal.containsMatchIn(it) }
            assertEquals(emptyList(), leaking, "campo de hóspede em ${model.simpleName}")
        }
    }

    @Test
    fun tileLabelsCarryOnlyStatusText() =
        runTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101", occupancy = Occupancy.OCCUPIED, housekeeping = HousekeepingStatus.DIRTY)
                    .copy(guestCount = Option.Some(3)),
            )
            val state = attackLoaded(source)

            val allowed = RoomDisplayStatus.entries.map { it.label }.toSet()
            for (part in state.tileOf(a).label.split(" · ")) {
                assertTrue(part in allowed, "rótulo com texto fora dos status: $part")
            }
        }
}
