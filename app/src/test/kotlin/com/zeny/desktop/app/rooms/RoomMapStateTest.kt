package com.zeny.desktop.app.rooms

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomMapStateTest {
    private val a = roomId(1)
    private val b = roomId(2)
    private val c = roomId(3)

    private fun forbidden() = ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList())

    private fun FakeRoomsSource.withTwoRooms() =
        apply {
            put(roomStatus(a, "101", version = 1), roomStatus(b, "102", version = 1))
        }

    /**
     * O estado vive no `backgroundScope`: a assinatura não termina sozinha e não segura o fim do teste. Por isso
     * `runCurrent`, que roda também o trabalho de fundo (o `advanceUntilIdle` para quando só resta ele).
     */
    private fun TestScope.mapState(source: FakeRoomsSource) = RoomMapState(source, backgroundScope)

    private fun TestScope.loaded(source: FakeRoomsSource) =
        mapState(source).also {
            runCurrent()
            source.emit(RealtimeEvent.Ready)
            runCurrent()
        }

    private fun RoomMapState.tiles() = floors.flatMap { it.second }

    private fun RoomMapState.tile(id: String) = tiles().single { it.roomId == id }

    private fun RoomMapState.numbers() = tiles().map { it.number }

    @Test
    fun loadsAfterReadyNotBefore() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = mapState(source)
            runCurrent()

            assertEquals(listOf(setOf("rooms")), source.subscribedTopics)
            assertEquals(0, source.statusesCalls)
            assertTrue(state.loading)
            assertEquals(emptyList(), state.floors)

            source.emit(RealtimeEvent.Ready)
            runCurrent()

            assertEquals(1, source.statusesCalls)
            assertFalse(state.loading)
            assertNull(state.error)
            assertEquals(listOf("101", "102"), state.numbers())
        }

    @Test
    fun firstResetAlsoLoads() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = mapState(source)
            runCurrent()

            source.emit(RealtimeEvent.Reset)
            runCurrent()

            assertEquals(1, source.statusesCalls)
            assertFalse(state.loading)
            assertEquals(listOf("101", "102"), state.numbers())
        }

    @Test
    fun laterReadyDoesNotReload() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            loaded(source)

            source.emit(RealtimeEvent.Ready)
            runCurrent()

            assertEquals(1, source.statusesCalls)
        }

    @Test
    fun groupsByFloorAndSortsByNumber() =
        runTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(roomId(1), "1001", floor = "10"),
                roomStatus(roomId(2), "202", floor = "2", type = typeId(2)),
                roomStatus(roomId(3), "201", floor = "2"),
                roomStatus(roomId(4), "110", floor = "1"),
                roomStatus(roomId(5), "99", floor = "1"),
                roomStatus(roomId(6), "101", floor = "1"),
                roomStatus(roomId(7), "Anexo", floor = null),
            )
            val state = loaded(source)

            assertEquals(listOf("Andar 1", "Andar 2", "Andar 10", "Sem andar"), state.floors.map { it.first })
            assertEquals(
                listOf(listOf("99", "101", "110"), listOf("201", "202"), listOf("1001"), listOf("Anexo")),
                state.floors.map { (_, tiles) -> tiles.map { it.number } },
            )
            assertEquals(
                RoomTile(roomId(2), "202", typeId(2), RoomDisplayStatus.CLEAN, RoomDisplayStatus.CLEAN, "Limpa", 1),
                state.tile(roomId(2)),
            )
            assertEquals(mapOf(typeId(1) to "Standard", typeId(2) to "Luxo"), state.roomTypeNames)
        }

    @Test
    fun messageRefetchesOnlyThatRoom() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)

            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(a, 2)
            runCurrent()

            assertEquals(listOf(a), source.statusCalls)
            assertEquals(1, source.statusesCalls)
            assertEquals(RoomDisplayStatus.DIRTY, state.tile(a).status)
            assertEquals("Suja", state.tile(a).label)
            assertEquals(2, state.tile(a).version)
            assertEquals(1, state.tile(b).version)
        }

    @Test
    fun olderMessageVersionSkipsRefetch() =
        runTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101", version = 3))
            loaded(source)

            source.message(a, 3)
            source.message(a, 2)
            runCurrent()

            assertEquals(emptyList(), source.statusCalls)
        }

    @Test
    fun staleRefetchDoesNotOverwriteNewer() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            source.holdStatus = true

            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(a, 2)
            runCurrent()
            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.INSPECTED, version = 3))
            source.message(a, 3)
            runCurrent()
            assertEquals(2, source.heldStatus.size)

            source.heldStatus[1].second.complete(Unit)
            runCurrent()
            source.heldStatus[0].second.complete(Unit)
            runCurrent()

            assertEquals(3, state.tile(a).version)
            assertEquals(RoomDisplayStatus.INSPECTED, state.tile(a).status)
        }

    @Test
    fun reloadDoesNotOverwriteNewerRoom() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            source.holdStatuses = true

            source.emit(RealtimeEvent.Reset)
            runCurrent()
            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(a, 2)
            runCurrent()
            assertEquals(2, state.tile(a).version)

            source.holdStatuses = false
            source.heldStatuses.single().complete(Unit)
            runCurrent()

            assertEquals(2, state.tile(a).version)
            assertEquals(RoomDisplayStatus.DIRTY, state.tile(a).status)
        }

    @Test
    fun messageDuringInitialLoadIsApplied() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            source.holdStatuses = true
            val state = mapState(source)
            runCurrent()
            source.emit(RealtimeEvent.Ready)
            runCurrent()

            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(a, 2)
            runCurrent()
            source.holdStatuses = false
            source.heldStatuses.single().complete(Unit)
            runCurrent()

            assertEquals(2, source.statusesCalls)
            assertEquals(2, state.tile(a).version)
            assertEquals(RoomDisplayStatus.DIRTY, state.tile(a).status)
        }

    @Test
    fun messageForNewRoomReloads() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)

            source.put(roomStatus(c, "103", version = 1))
            source.message(c, 1)
            runCurrent()

            assertEquals(2, source.statusesCalls)
            assertEquals(emptyList(), source.statusCalls)
            assertEquals(listOf("101", "102", "103"), state.numbers())
        }

    @Test
    fun resetReloadsEverything() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)

            source.put(
                roomStatus(a, "101", occupancy = Occupancy.OCCUPIED, version = 5),
                roomStatus(b, "102", housekeeping = HousekeepingStatus.DIRTY, version = 4),
            )
            source.types[1] = roomType(typeId(2), "Luxo Vista Mar")
            source.emit(RealtimeEvent.Reset)
            runCurrent()

            assertEquals(2, source.statusesCalls)
            assertEquals(2, source.roomTypesCalls)
            assertEquals(RoomDisplayStatus.OCCUPIED, state.tile(a).status)
            assertEquals("Ocupada · Limpa", state.tile(a).label)
            assertEquals(RoomDisplayStatus.DIRTY, state.tile(b).status)
            assertEquals("Luxo Vista Mar", state.roomTypeNames[typeId(2)])
        }

    @Test
    fun reloadRemovesMissingRooms() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            source.put(roomStatus(c, "103"))
            val state = loaded(source)

            source.server.remove(b)
            source.put(roomStatus(c, "103", version = 2, active = false))
            source.emit(RealtimeEvent.Reset)
            runCurrent()

            assertEquals(listOf("101"), state.numbers())
        }

    @Test
    fun roomTurnedInactiveLeavesMap() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)

            source.put(roomStatus(b, "102", version = 2, active = false))
            source.message(b, 2)
            runCurrent()

            assertEquals(listOf("101"), state.numbers())
        }

    @Test
    fun hasRoomsCountsActiveRoomsIgnoringFilters() =
        runTest {
            val empty = loaded(FakeRoomsSource())
            assertFalse(empty.hasRooms)

            val onlyInactive = FakeRoomsSource().apply { put(roomStatus(a, "101", active = false)) }
            assertFalse(loaded(onlyInactive).hasRooms)

            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            assertTrue(state.hasRooms)

            state.toggleType(typeId(2))
            state.toggleStatus(RoomDisplayStatus.DIRTY)
            assertEquals(emptyList(), state.floors)
            assertTrue(state.hasRooms)

            source.server.clear()
            source.emit(RealtimeEvent.Reset)
            runCurrent()
            assertFalse(state.hasRooms)
        }

    @Test
    fun outOfOrderMessagesCoalesce() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            loaded(source)
            source.holdStatuses = true

            repeat(3) { source.message(a, 10L + it, type = "rooms.out-of-order") }
            runCurrent()
            assertEquals(2, source.statusesCalls, "uma recarga em voo")

            source.heldStatuses[0].complete(Unit)
            runCurrent()
            assertEquals(3, source.statusesCalls, "a pendente roda ao fim da que estava em voo")

            source.heldStatuses[1].complete(Unit)
            runCurrent()
            assertEquals(3, source.statusesCalls, "nenhuma depois")
            assertEquals(emptyList(), source.statusCalls)
        }

    @Test
    fun unknownMessageTypeReloads() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            loaded(source)

            source.message(a, 99, type = "rooms.something-new")
            runCurrent()

            assertEquals(2, source.statusesCalls)
            assertEquals(emptyList(), source.statusCalls)
        }

    @Test
    fun failedRefetchReloads() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            source.statusError = ApiError.Problem(404, "NOT_FOUND", null, emptyList())

            source.server.remove(a)
            source.message(a, 2)
            runCurrent()

            assertEquals(2, source.statusesCalls)
            assertNull(state.error)
            assertEquals(listOf("102"), state.numbers())
        }

    @Test
    fun countersFollowTypeFilter() =
        runTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101", type = typeId(1)),
                roomStatus(b, "102", type = typeId(1), housekeeping = HousekeepingStatus.DIRTY),
                roomStatus(c, "103", type = typeId(2), occupancy = Occupancy.OCCUPIED),
            )
            val state = loaded(source)
            val zero = RoomDisplayStatus.entries.associateWith { 0 }

            assertEquals(
                zero +
                    mapOf(
                        RoomDisplayStatus.CLEAN to 1,
                        RoomDisplayStatus.DIRTY to 1,
                        RoomDisplayStatus.OCCUPIED to 1,
                    ),
                state.counters,
            )

            state.toggleType(typeId(1))
            assertEquals(setOf(typeId(1)), state.typeFilter)
            assertEquals(listOf("101", "102"), state.numbers())
            assertEquals(zero + mapOf(RoomDisplayStatus.CLEAN to 1, RoomDisplayStatus.DIRTY to 1), state.counters)

            state.toggleType(typeId(1))
            assertEquals(emptySet(), state.typeFilter)
            assertEquals(listOf("101", "102", "103"), state.numbers())
        }

    @Test
    fun statusFilterMatchesHousekeepingToo() =
        runTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101", occupancy = Occupancy.OCCUPIED, housekeeping = HousekeepingStatus.DIRTY),
                roomStatus(b, "102", housekeeping = HousekeepingStatus.DIRTY),
                roomStatus(c, "103"),
            )
            val state = loaded(source)
            val counters = state.counters

            state.toggleStatus(RoomDisplayStatus.DIRTY)

            assertEquals(setOf(RoomDisplayStatus.DIRTY), state.statusFilter)
            assertEquals(listOf("101", "102"), state.numbers())
            assertEquals(counters, state.counters, "contadores não seguem o filtro de status")

            state.toggleStatus(RoomDisplayStatus.CLEAN)
            assertEquals(listOf("101", "102", "103"), state.numbers())

            state.toggleStatus(RoomDisplayStatus.DIRTY)
            state.toggleStatus(RoomDisplayStatus.CLEAN)
            assertEquals(emptySet(), state.statusFilter)
            assertEquals(listOf("101", "102", "103"), state.numbers())
        }

    @Test
    fun selectedDetailUpdatesWithMessage() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)

            state.select(a)
            runCurrent()
            val opened = assertNotNull(state.selected)
            assertEquals(a, opened.status.roomId.toString())
            assertEquals(a, opened.room?.id.toString())
            assertEquals(listOf(a), source.roomCalls)

            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(a, 2)
            source.put(roomStatus(b, "102", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(b, 2)
            runCurrent()

            val updated = assertNotNull(state.selected)
            assertEquals(a, updated.status.roomId.toString())
            assertEquals(2, updated.status.version)
            assertEquals(HousekeepingStatus.DIRTY, updated.status.housekeepingStatus)
            assertEquals(opened.room, updated.room)

            state.clearSelection()
            assertNull(state.selected)
        }

    @Test
    fun registryForbiddenDegrades() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            source.roomTypesError = forbidden()
            source.roomError = forbidden()
            val state = loaded(source)

            assertNull(state.error)
            assertEquals(listOf("101", "102"), state.numbers())
            assertEquals(mapOf(typeId(1) to "Tipo 1"), state.roomTypeNames)

            state.select(a)
            runCurrent()

            assertNull(state.error)
            val detail = assertNotNull(state.selected)
            assertNull(detail.room)
            assertEquals(a, detail.status.roomId.toString())
        }

    @Test
    fun incompatibleStatusShowsPortugueseError() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            source.statusesError =
                ApiError.IncompatibleResponse(IllegalArgumentException("Unknown enum value 'BLOCKED'"))
            val state = loaded(source)

            assertFalse(state.loading)
            val error = assertNotNull(state.error)
            assertTrue(error is ApiError.IncompatibleResponse)
            assertEquals(
                "O servidor respondeu num formato que esta versão do app não reconhece. Atualize o app.",
                error.userMessage,
            )

            source.statusesError = null
            state.retry()
            assertTrue(state.loading)
            runCurrent()

            assertNull(state.error)
            assertFalse(state.loading)
            assertEquals(listOf("101", "102"), state.numbers())
        }

    @Test
    fun failedShowsPortugueseError() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            val failure = forbidden()

            source.end(failure)
            runCurrent()

            assertSame(failure, state.error)
            assertEquals("Você não tem permissão para esta ação.", state.error?.userMessage)
            assertEquals(0, source.activeSubscriptions)

            state.retry()
            runCurrent()
            assertEquals(2, source.subscriptions)
            source.emit(RealtimeEvent.Ready)
            runCurrent()

            assertNull(state.error)
            assertEquals(2, source.statusesCalls)
        }

    @Test
    fun sessionEndStopsListeningWithoutError() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)

            source.end()
            runCurrent()

            assertNull(state.error)
            assertEquals(0, source.activeSubscriptions)
            assertEquals(listOf("101", "102"), state.numbers())
        }

    @Test
    fun closeCancelsAndStopsListening() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            source.holdStatuses = true
            source.emit(RealtimeEvent.Reset)
            runCurrent()
            assertEquals(2, source.statusesCalls)

            state.close()
            runCurrent()
            assertEquals(0, source.activeSubscriptions)
            source.server.remove(b)
            source.heldStatuses.single().complete(Unit)
            source.realtimeStatus.value = RealtimeStatus.Reconnecting(1, 1_000)
            state.retry()
            state.select(a)
            runCurrent()

            assertEquals(listOf("101", "102"), state.numbers(), "a recarga cancelada não se aplica")
            assertEquals(2, source.statusesCalls)
            assertEquals(1, source.subscriptions)
            assertEquals(emptyList(), source.roomCalls)
            assertEquals(RealtimeStatus.Idle, state.live, "o tempo real deixa de ser observado")
        }

    @Test
    fun reconnectingStatusExposed() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            source.realtimeStatus.value = RealtimeStatus.Live
            val state = loaded(source)
            assertEquals(RealtimeStatus.Live, state.live)

            source.realtimeStatus.value = RealtimeStatus.Reconnecting(2, 2_000)
            runCurrent()
            assertEquals(RealtimeStatus.Reconnecting(2, 2_000), state.live)

            source.realtimeStatus.value = RealtimeStatus.Live
            runCurrent()
            assertEquals(RealtimeStatus.Live, state.live)
        }

    @Test
    fun sessionEndBeforeFirstSyncStopsLoading() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = mapState(source)
            runCurrent()

            source.end()
            runCurrent()

            assertFalse(state.loading, "sem ready nem reset, não há carga a esperar")
            assertNull(state.error)
            assertEquals(0, source.statusesCalls)
        }

    @Test
    fun retriedSubscriptionEndingBeforeSyncStopsLoading() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            source.end(forbidden())
            runCurrent()

            state.retry()
            runCurrent()
            assertTrue(state.loading)
            source.end()
            runCurrent()

            assertFalse(state.loading)
            assertNull(state.error)
        }

    @Test
    fun typeFilterKeepsVanishedTypeListed() =
        runTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101", type = typeId(1)), roomStatus(b, "102", type = typeId(2)))
            val state = loaded(source)
            state.toggleType(typeId(2))

            source.server.remove(b)
            source.types.removeAt(1)
            source.emit(RealtimeEvent.Reset)
            runCurrent()

            assertEquals(mapOf(typeId(1) to "Standard", typeId(2) to "Tipo 1"), state.roomTypeNames)
        }

    @Test
    fun typeFilterKeepsVanishedTypeWithoutRegistry() =
        runTest {
            val source = FakeRoomsSource()
            source.roomTypesError = forbidden()
            source.put(roomStatus(a, "101", type = typeId(1)), roomStatus(b, "102", type = typeId(2)))
            val state = loaded(source)
            state.toggleType(typeId(2))

            source.put(roomStatus(b, "102", type = typeId(2), version = 2, active = false))
            source.message(b, 2)
            runCurrent()

            assertEquals(mapOf(typeId(1) to "Tipo 1", typeId(2) to "Tipo 2"), state.roomTypeNames)

            state.toggleType(typeId(2))
            assertEquals(mapOf(typeId(1) to "Tipo 1"), state.roomTypeNames, "sem filtro, o tipo sumido sai")
        }

    @Test
    fun retryRereadsMissingDetail() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            source.roomError = ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList())
            val state = loaded(source)
            state.select(a)
            runCurrent()
            assertNotNull(state.error)
            assertNull(state.selected?.room)

            source.roomError = null
            state.retry()
            runCurrent()

            assertNull(state.error)
            assertEquals(listOf(a, a), source.roomCalls)
            val detail = assertNotNull(state.selected)
            assertEquals(a, assertNotNull(detail.room).id.toString())
        }

    @Test
    fun retryKeepsDetailAlreadyRead() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            val state = loaded(source)
            state.select(a)
            runCurrent()

            state.retry()
            runCurrent()

            assertEquals(listOf(a), source.roomCalls)
            assertNotNull(state.selected?.room)
        }

    @Test
    fun retryRereadOfDetailIsDroppedWhenAnotherRoomIsSelected() =
        runTest {
            val source = FakeRoomsSource().withTwoRooms()
            source.roomError = ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList())
            val state = loaded(source)
            state.select(a)
            runCurrent()

            source.roomError = null
            state.retry()
            state.select(b)
            runCurrent()

            val detail = assertNotNull(state.selected)
            assertEquals(b, detail.status.roomId.toString())
            assertEquals(b, assertNotNull(detail.room).id.toString())
        }
}
