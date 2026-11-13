package com.zeny.desktop.app.rooms

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.RealtimeEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** O tipo sem nome legível (`403`, nome em branco) aparece por um rótulo neutro e estável, nunca pelo id. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomMapTypeLabelsTest {
    private val a = roomId(1)
    private val b = roomId(2)
    private val c = roomId(3)

    private fun forbidden() = ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList())

    private fun TestScope.mapState(source: FakeRoomsSource) = RoomMapState(source, backgroundScope)

    private fun TestScope.loaded(source: FakeRoomsSource) =
        mapState(source).also {
            runCurrent()
            source.emit(RealtimeEvent.Ready)
            runCurrent()
        }

    private fun RoomMapState.numbers() = floors.flatMap { it.second }.map { it.number }

    @Test
    fun unreadableTypeNamesGetStableNeutralLabels() =
        runTest {
            val source = FakeRoomsSource()
            source.roomTypesError = forbidden()
            source.put(
                roomStatus(a, "101", type = typeId(2)),
                roomStatus(b, "102", type = typeId(1)),
                roomStatus(c, "103", type = typeId(2)),
            )
            val state = loaded(source)

            assertEquals(mapOf(typeId(1) to "Tipo 1", typeId(2) to "Tipo 2"), state.roomTypeNames)
            state.toggleType(typeId(2))
            assertEquals(listOf("101", "103"), state.numbers(), "o filtro segue pelo id")

            // A UH do tipo 1 some: o tipo 2 não troca de rótulo.
            source.server.remove(b)
            source.emit(RealtimeEvent.Reset)
            runCurrent()
            assertEquals(mapOf(typeId(2) to "Tipo 2"), state.roomTypeNames)
        }

    @Test
    fun blankTypeNameGetsNeutralLabel() =
        runTest {
            val source = FakeRoomsSource()
            source.types[1] = roomType(typeId(2), " ")
            source.put(roomStatus(a, "101", type = typeId(1)), roomStatus(b, "102", type = typeId(2)))
            val state = loaded(source)

            assertEquals(mapOf(typeId(1) to "Standard", typeId(2) to "Tipo 1"), state.roomTypeNames)
        }
}
