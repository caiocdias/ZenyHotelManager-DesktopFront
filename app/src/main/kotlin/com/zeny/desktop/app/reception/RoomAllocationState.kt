package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomSuggestionResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Os dados da reserva de que o check-in precisa, tirados do detalhe: a UH e o "não mover" vêm da alocação. */
data class CheckInSeed(
    val reservationId: String,
    val holderGuestId: String,
    val status: String,
    val adults: Int,
    val children: Int,
    val roomId: String?,
    val roomNumber: String?,
    val doNotMove: Boolean,
)

/** Ausente ou nulo: sem UH e sem "não mover". */
fun ReservationDetailResponse.toCheckInSeed(): CheckInSeed {
    val assignment = (reservation.roomAssignment as? Option.Some)?.value
    return CheckInSeed(
        reservationId = reservation.id.toString(),
        holderGuestId = holderGuestId.toString(),
        status = reservation.status,
        adults = reservation.adults,
        children = reservation.childAges.size,
        roomId = assignment?.roomId?.toString(),
        roomNumber = (assignment?.roomNumber as? Option.Some)?.value?.takeIf { it.isNotBlank() },
        doNotMove = assignment?.doNotMove ?: false,
    )
}

/** Uma UH sugerida; [ready] é a flag `readyForCheckIn` do servidor e [readiness] o rótulo dela. */
data class RoomOption(
    val roomId: String,
    val number: String,
    val floor: String?,
    val current: Boolean,
    val outOfService: Boolean,
    val matchesFloorPreference: Boolean,
    val readiness: ChipModel,
    val ready: Boolean,
)

/**
 * Estado da alocação de UH de uma reserva: sugestões com a prontidão de cada UH e as ações alocar, soltar e
 * "não mover".
 * A UH atual e o "não mover" começam pela [seed] (uma UH fora das sugestões continua à vista) e depois seguem a
 * `roomAssignment` da resposta de cada ação. Só uma ação por vez.
 *
 * Como o `DayPanelState`: as mutações correm no escopo da UI (uma thread só). [close] cancela só o que é daqui.
 */
class RoomAllocationState(
    private val source: ReceptionSource,
    scope: CoroutineScope,
    private val seed: CheckInSeed,
    val canManage: Boolean,
    private val onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    var currentRoomId by mutableStateOf(seed.roomId)
        private set
    var currentRoomNumber by mutableStateOf(seed.roomNumber)
        private set
    var doNotMove by mutableStateOf(seed.doNotMove)
        private set
    var options by mutableStateOf<List<RoomOption>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var loadJob: Job? = null
    private var generation = 0
    private var closed = false

    fun load() {
        if (!canManage || closed) return
        loadJob?.cancel()
        val mine = ++generation
        loading = true
        loadJob =
            ownScope.launch {
                try {
                    val result = fetchOptions()
                    if (mine == generation) applyLoad(result)
                } finally {
                    if (mine == generation) loading = false
                }
            }
    }

    fun assign(roomId: String) = act { source.assignRoom(seed.reservationId, roomId, null) }

    fun unassign() {
        if (currentRoomId != null) act { source.unassignRoom(seed.reservationId) }
    }

    fun toggleDoNotMove() {
        if (currentRoomId == null) return
        val next = !doNotMove
        act { source.setDoNotMove(seed.reservationId, next) }
    }

    fun close() {
        closed = true
        jobs.cancel()
    }

    private fun act(call: suspend () -> ReservationDetailResponse) {
        if (!canManage || busy || closed) return
        busy = true
        error = null
        loadJob?.cancel()
        generation++
        loading = false
        ownScope.launch {
            try {
                val assignment = call().reservation.roomAssignment
                val value = (assignment as? Option.Some)?.value
                currentRoomId = value?.roomId?.toString()
                currentRoomNumber = (value?.roomNumber as? Option.Some)?.value?.takeIf { it.isNotBlank() }
                doNotMove = value?.doNotMove ?: false
                onChanged()
                applyLoad(fetchOptions())
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                error = ActionText.error(apiErrorOf(e))
            } finally {
                busy = false
            }
        }
    }

    private fun applyLoad(result: Result<List<RoomOption>>) {
        result
            .onSuccess {
                options = it
                error = null
            }.onFailure { error = ActionText.error(apiErrorOf(it)) }
    }

    private suspend fun fetchOptions(): Result<List<RoomOption>> =
        try {
            coroutineScope {
                val suggestions = async { source.roomSuggestions(seed.reservationId) }
                val statuses = async { statusesById() }
                val byId = statuses.await()
                Result.success(suggestions.await().map { it.toOption(byId[it.roomId.toString()]) })
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            Result.failure(e)
        }

    /** Sem acesso aos status (`ApiError.Problem`), as UHs ficam com "Status desconhecido", sem erro. */
    private suspend fun statusesById(): Map<String, RoomStatusResponse> =
        try {
            source.roomStatuses().associateBy { it.roomId.toString() }
        } catch (_: ApiError.Problem) {
            emptyMap()
        }
}

private fun RoomSuggestionResponse.toOption(status: RoomStatusResponse?) =
    RoomOption(
        roomId = roomId.toString(),
        number = number,
        floor = (floor as? Option.Some)?.value,
        current = current,
        outOfService = outOfService,
        matchesFloorPreference = matchesFloorPreference,
        readiness = ActionText.readiness(status),
        ready = status?.readyForCheckIn ?: false,
    )

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
