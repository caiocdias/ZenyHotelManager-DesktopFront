package com.zeny.desktop.app.rooms

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.sdk.model.RoomResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

// Apoio dos testes de ataque do mapa (`RoomMap*AtaqueTest`).

const val VERSION_MESSAGE = "O servidor respondeu num formato que esta versão do app não reconhece. Atualize o app."

fun forbiddenProblem() = ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList())

fun serverErrorProblem() = ApiError.Problem(500, "INTERNAL_ERROR", "NullPointerException em RoomService", emptyList())

fun incompatibleResponse() = ApiError.IncompatibleResponse(IllegalArgumentException("Unknown enum value 'BLOCKED'"))

/** UHs 101 e 102 ([roomId] 1 e 2), andar 1, versão 1. */
fun FakeRoomsSource.withRooms101And102() =
    apply {
        put(roomStatus(roomId(1), "101", version = 1), roomStatus(roomId(2), "102", version = 1))
    }

/** O `room(id)` só responde quando o teste solta o portão; o resto é o [fake]. */
class HeldRoomSource(
    val fake: FakeRoomsSource,
) : RoomsSource by fake {
    val heldRoom = mutableListOf<Pair<String, CompletableDeferred<Unit>>>()

    override suspend fun room(id: String): RoomResponse {
        val gate = CompletableDeferred<Unit>()
        heldRoom += id to gate
        gate.await()
        return fake.room(id)
    }
}

/** No `backgroundScope`, como no `RoomMapStateTest`: por isso `runCurrent`. */
fun TestScope.attackMap(source: RoomsSource) = RoomMapState(source, backgroundScope)

@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.attackLoaded(
    source: RoomsSource,
    fake: FakeRoomsSource,
) = attackMap(source).also {
    runCurrent()
    fake.emit(RealtimeEvent.Ready)
    runCurrent()
}

fun TestScope.attackLoaded(source: FakeRoomsSource) = attackLoaded(source, source)

fun RoomMapState.allTiles() = floors.flatMap { it.second }

fun RoomMapState.tileOf(id: String) = allTiles().single { it.roomId == id }

fun RoomMapState.shownNumbers() = allTiles().map { it.number }

fun zeroCounters() = RoomDisplayStatus.entries.associateWith { 0 }
