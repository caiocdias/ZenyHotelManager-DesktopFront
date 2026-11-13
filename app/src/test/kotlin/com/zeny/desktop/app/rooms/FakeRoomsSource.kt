package com.zeny.desktop.app.rooms

import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.sdk.model.OutOfOrderOverlayResponse
import com.zeny.desktop.sdk.model.RoomResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import com.zeny.desktop.sdk.model.RoomStatusResponse.ServiceFlag
import com.zeny.desktop.sdk.model.RoomTypeResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.uuid.Uuid

fun roomId(n: Int): String = Uuid.parse("00000000-0000-0000-0000-" + n.toString().padStart(12, '0')).toString()

fun typeId(n: Int): String = Uuid.parse("10000000-0000-0000-0000-" + n.toString().padStart(12, '0')).toString()

@Suppress("LongParameterList") // fixture: cada campo do status que os testes variam, com padrão
fun roomStatus(
    id: String,
    number: String,
    floor: String? = "1",
    type: String = typeId(1),
    housekeeping: HousekeepingStatus = HousekeepingStatus.CLEAN,
    occupancy: Occupancy = Occupancy.VACANT,
    version: Long = 1,
    active: Boolean = true,
    outOfOrder: OutOfOrderOverlayResponse.Kind? = null,
) = RoomStatusResponse(
    active = active,
    changedAt = "2026-11-12T10:00:00Z",
    floor = floor?.let { Option.Some(it) } ?: Option.Undefined,
    housekeepingStatus = housekeeping,
    inspectionPending = false,
    number = number,
    occupancy = occupancy,
    outOfOrder =
        outOfOrder?.let {
            Option.Some(OutOfOrderOverlayResponse("2026-11-12", Uuid.random(), it, "2026-11-10"))
        } ?: Option.Undefined,
    readyForCheckIn = false,
    roomId = Uuid.parse(id),
    roomTypeId = Uuid.parse(type),
    serviceFlag = ServiceFlag.NONE,
    version = version,
)

fun roomType(
    id: String,
    name: String,
) = RoomTypeResponse(
    active = true,
    code = name.uppercase().take(3),
    createdAt = "2026-11-01T10:00:00Z",
    displayOrder = 1,
    featureIds = emptyList(),
    id = Uuid.parse(id),
    maxAdults = 2,
    maxChildren = 1,
    name = name,
    standardAdults = 2,
    standardChildren = 0,
    updatedAt = "2026-11-01T10:00:00Z",
)

fun roomOf(status: RoomStatusResponse) =
    RoomResponse(
        active = status.active,
        createdAt = "2026-11-01T10:00:00Z",
        featureIds = emptyList(),
        floor = status.floor,
        id = status.roomId,
        notes = Option.Some("Vista para o mar"),
        number = status.number,
        roomTypeId = status.roomTypeId,
        updatedAt = "2026-11-01T10:00:00Z",
    )

/**
 * Servidor de mentira para o mapa: o estado das UHs fica em [server]; cada leitura tira a foto do estado no momento
 * da chamada e, com o `hold` ligado, só responde quando o teste solta o portão (respostas atrasadas, fora de ordem).
 * Os eventos são postos pelo teste com [emit] e a assinatura termina com [end] (com erro: `Failed`).
 */
class FakeRoomsSource : RoomsSource {
    val server = linkedMapOf<String, RoomStatusResponse>()
    val types = mutableListOf(roomType(typeId(1), "Standard"), roomType(typeId(2), "Luxo"))

    var statusesError: Exception? = null
    var roomTypesError: Exception? = null
    var roomError: Exception? = null
    var statusError: Exception? = null

    var holdStatuses = false
    var holdStatus = false
    val heldStatuses = mutableListOf<CompletableDeferred<Unit>>()
    val heldStatus = mutableListOf<Pair<String, CompletableDeferred<Unit>>>()

    var statusesCalls = 0
    var roomTypesCalls = 0
    val statusCalls = mutableListOf<String>()
    val roomCalls = mutableListOf<String>()

    var subscriptions = 0
    var activeSubscriptions = 0
    val subscribedTopics = mutableListOf<Set<String>>()
    private var channel = Channel<RealtimeEvent>(Channel.UNLIMITED)

    override val realtimeStatus = MutableStateFlow<RealtimeStatus>(RealtimeStatus.Idle)

    fun put(vararg statuses: RoomStatusResponse) {
        statuses.forEach { server[it.roomId.toString()] = it }
    }

    fun emit(event: RealtimeEvent) {
        channel.trySend(event)
    }

    fun message(
        id: String,
        version: Long,
        type: String = "rooms.room-status",
    ) = emit(RealtimeEvent.Message("rooms", type, id, version))

    fun end(error: Throwable? = null) {
        channel.close(error)
        // A assinatura seguinte (um `retry`) recebe um canal novo.
        channel = Channel(Channel.UNLIMITED)
    }

    override suspend fun statuses(): List<RoomStatusResponse> {
        statusesCalls++
        val snapshot = server.values.toList()
        val error = statusesError
        if (holdStatuses) CompletableDeferred<Unit>().also { heldStatuses += it }.await()
        error?.let { throw it }
        return snapshot
    }

    override suspend fun roomTypes(): List<RoomTypeResponse> {
        roomTypesCalls++
        roomTypesError?.let { throw it }
        return types.toList()
    }

    override suspend fun room(id: String): RoomResponse {
        roomCalls += id
        roomError?.let { throw it }
        return roomOf(server.getValue(id))
    }

    override suspend fun status(id: String): RoomStatusResponse {
        statusCalls += id
        val snapshot = server[id]
        val error = statusError
        if (holdStatus) CompletableDeferred<Unit>().also { heldStatus += id to it }.await()
        error?.let { throw it }
        return checkNotNull(snapshot) { "UH $id fora do servidor de mentira" }
    }

    override fun events(topics: Set<String>): Flow<RealtimeEvent> =
        flow {
            subscriptions++
            activeSubscriptions++
            subscribedTopics += topics
            val events = channel
            try {
                for (event in events) emit(event)
            } finally {
                activeSubscriptions--
            }
        }
}
