package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.rooms.NaturalOrder
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.facade.RoomMoveInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.RoomMoveResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val IN_HOUSE = "IN_HOUSE"
private const val KEY_IN_USE = "IDEMPOTENCY_KEY_IN_USE"
private const val TRANSITION_NOT_ALLOWED = "RESERVATION_TRANSITION_NOT_ALLOWED"
private const val CONFLICT = 409
private val MOVE_REASONS = setOf("GUEST_REQUEST", "OUT_OF_ORDER", "ROOM_ISSUE", "OPERATIONAL")
private val MOVE_TARIFFS = setOf("KEEP", "RECALCULATE")

// A UH escolhida não serve mais: ela sai e a lista é relida.
private val ROOM_REFUSALS =
    setOf(
        "ROOM_NOT_READY",
        "ROOM_ALREADY_ASSIGNED",
        "ROOM_OUT_OF_ORDER",
        "ROOM_TYPE_MISMATCH",
        "ROOM_MOVE_SAME_ROOM",
        "ROOM_INACTIVE",
        "UNKNOWN_ROOM",
    )

private const val STAY_ENDED_TEXT = "A estadia desta reserva não tem mais noites; a UH não é mais trocada."

/** O que uma `Intent` da troca cobre: mudou a UH, o motivo, a tarifa ou o `ETag`, é outra intenção. */
private data class MoveIntentInputs(
    val roomId: String?,
    val reason: String,
    val tariff: String,
    val etag: String?,
)

/** A leitura fresca da troca: a versão da reserva e as UHs candidatas (ou o motivo de não haver). */
private data class MoveLoad(
    val version: ReservationVersion,
    val currentRoomNumber: String?,
    val rooms: List<WalkInRoom>,
    val notice: String?,
)

/**
 * Estado do diálogo de troca de UH: lê a reserva com o `ETag`, o dia operacional e as UHs do mesmo tipo prontas e
 * livres nas noites que restam, e confirma com `If-Match` e uma `Idempotency-Key` por intenção.
 *
 * A mesma `Intent` vale enquanto o resultado é desconhecido (rede, resposta incompatível, sessão encerrada,
 * `IDEMPOTENCY_KEY_IN_USE`) ou a aprovação foi cancelada; qualquer resposta definitiva, ou a mudança de UH, motivo,
 * tarifa ou `ETag`, gera outra. A repetição aprovada é do [approve] (decisão D2): o estado não rotaciona por causa
 * do token. Um `412` refaz a carga inteira. Como o `WalkInState`: as mutações correm no escopo da UI (uma thread
 * só), [close] cancela só o que é daqui, e uma carga velha não sobrescreve a mais nova.
 */
@Suppress("TooManyFunctions") // etapas do fluxo: carga, entradas, confirmação e a chave
class RoomMoveState(
    private val source: ReceptionSource,
    scope: CoroutineScope,
    private val reservationId: String,
    private val approve: suspend (ApprovableAction<RoomMoveResponse>) -> ApiResponse<RoomMoveResponse>?,
    private val onChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    var currentRoomNumber by mutableStateOf<String?>(null)
        private set
    var rooms by mutableStateOf<List<WalkInRoom>>(emptyList())
        private set

    /** A carga ou a recarga está em voo: as entradas e a confirmação viram no-op. */
    var loading by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** O texto do resumo da troca feita. */
    var done by mutableStateOf<String?>(null)
        private set

    private var roomChoice by mutableStateOf<WalkInRoom?>(null)
    private var reasonChoice by mutableStateOf("GUEST_REQUEST")
    private var tariffChoice by mutableStateOf("KEEP")
    private var etag by mutableStateOf<String?>(null)
    private var inHouse by mutableStateOf(false)

    /** Só uma UH da lista; `null` limpa a escolha. */
    var room: WalkInRoom?
        get() = roomChoice
        set(value) {
            if (locked || (value != null && value !in rooms)) return
            roomChoice = value
            dropIntentIfInputsChanged()
        }

    /** `GUEST_REQUEST`, `OUT_OF_ORDER`, `ROOM_ISSUE` ou `OPERATIONAL`. */
    var reason: String
        get() = reasonChoice
        set(value) {
            if (locked || value !in MOVE_REASONS) return
            reasonChoice = value
            dropIntentIfInputsChanged()
        }

    /** `KEEP` ou `RECALCULATE`. */
    var tariff: String
        get() = tariffChoice
        set(value) {
            if (locked || value !in MOVE_TARIFFS) return
            tariffChoice = value
            dropIntentIfInputsChanged()
        }

    val canConfirm: Boolean
        get() = !closed && etag != null && roomChoice != null && inHouse && !loading && !busy && done == null

    private var loadJob: Job? = null
    private var generation = 0
    private var closed = false
    private var intent: Intent? = null
    private var intentInputs: MoveIntentInputs? = null

    private val locked: Boolean get() = closed || loading || busy || done != null

    fun start() {
        if (closed) return
        load(null)
    }

    fun confirm() {
        if (closed || !canConfirm) return
        val target = roomChoice
        val tag = etag
        if (target == null || tag == null) return
        busy = true
        val previousError = error
        error = null
        val input = RoomMoveInput(target.roomId, reasonChoice, tariffChoice)
        val current = currentIntent()
        ownScope.launch {
            try {
                val response = approve(source.moveRoom(reservationId, input, tag, current))
                if (response == null) error = previousError else finish(response.value)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                fail(apiErrorOf(e))
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        closed = true
        jobs.cancel()
    }

    private fun finish(response: RoomMoveResponse) {
        dropIntent()
        error = null
        done = ActionText.moveSummary(response)
        onChanged()
    }

    private fun fail(failure: ApiError) {
        if (!resultUnknown(failure)) dropIntent()
        val text = ActionText.moveError(failure)
        val code = (failure as? ApiError.Problem)?.code
        error = text
        when {
            failure is ApiError.PreconditionFailed -> {
                load(text)
            }

            code in ROOM_REFUSALS -> {
                roomChoice = null
                load(text)
            }

            code == TRANSITION_NOT_ALLOWED -> {
                inHouse = false
                onChanged()
            }
        }
    }

    /** O servidor pode ter feito a troca sem a resposta chegar: a repetição leva a mesma chave. */
    private fun resultUnknown(failure: ApiError): Boolean =
        failure is ApiError.Network ||
            failure is ApiError.IncompatibleResponse ||
            failure is ApiError.SessionEnded ||
            (failure is ApiError.Problem && failure.code == KEY_IN_USE)

    /** A carga inteira; a mais nova vence. Com [notice], ele continua na tela, salvo se a própria carga falhar. */
    private fun load(notice: String?) {
        val mine = ++generation
        loading = true
        loadJob?.cancel()
        loadJob =
            ownScope.launch {
                try {
                    val outcome = attempt { read() }
                    if (mine == generation) outcome.fold({ apply(it, notice) }, { applyFailure(it) })
                } finally {
                    if (mine == generation) loading = false
                }
            }
    }

    private suspend fun read(): MoveLoad {
        val version = source.reservationVersion(reservationId)
        val reservation = version.reservation.reservation
        val assignment = (reservation.roomAssignment as? Option.Some)?.value
        val currentId = assignment?.roomId?.toString()
        val number = (assignment?.roomNumber as? Option.Some)?.value
        val today = source.businessDay().date
        val period = remainingPeriod(today, reservation.arrival, reservation.departure, reservation.dayUse)
        val notice =
            when {
                reservation.status != IN_HOUSE -> ActionText.moveError(problem(TRANSITION_NOT_ALLOWED))
                period == null -> STAY_ENDED_TEXT
                else -> null
            }
        val candidates =
            if (notice == null && period != null) {
                candidates(period, reservation.roomTypeId.toString(), currentId)
            } else {
                emptyList()
            }
        return MoveLoad(version, number, candidates, notice)
    }

    private suspend fun candidates(
        period: Pair<BusinessDate, BusinessDate>,
        typeId: String,
        currentId: String?,
    ): List<WalkInRoom> =
        coroutineScope {
            val statuses = async { source.roomStatuses() }
            val assigned = async { source.assignedRoomIds(period.first, period.second) }
            val taken = assigned.await()
            statuses
                .await()
                .filter {
                    it.isMoveCandidate(typeId) && it.roomId.toString() !in taken &&
                        it.roomId.toString() != currentId
                }.map { WalkInRoom(it.roomId.toString(), it.number) }
                .sortedWith(compareBy(NaturalOrder) { it.number })
        }

    private fun apply(
        loaded: MoveLoad,
        notice: String?,
    ) {
        etag = loaded.version.etag
        inHouse = loaded.version.reservation.reservation.status == IN_HOUSE
        currentRoomNumber = loaded.currentRoomNumber
        rooms = loaded.rooms
        roomChoice = roomChoice?.takeIf { it in loaded.rooms }
        error = loaded.notice ?: notice
        dropIntentIfInputsChanged()
    }

    private fun applyFailure(cause: Throwable) {
        etag = null
        currentRoomNumber = null
        rooms = emptyList()
        roomChoice = null
        error = ActionText.error(apiErrorOf(cause))
        dropIntentIfInputsChanged()
    }

    private fun currentInputs() = MoveIntentInputs(roomChoice?.roomId, reasonChoice, tariffChoice, etag)

    private fun currentIntent(): Intent {
        dropIntentIfInputsChanged()
        return intent ?: Intent().also {
            intent = it
            intentInputs = currentInputs()
        }
    }

    private fun dropIntentIfInputsChanged() {
        if (intentInputs != currentInputs()) dropIntent()
    }

    private fun dropIntent() {
        intent = null
        intentInputs = null
    }
}

/**
 * As noites que restam: pernoite com `hoje < saída` vai de hoje até a véspera da saída; day use só vale no dia da
 * chegada. Sem noite restante, `null`.
 */
private fun remainingPeriod(
    today: BusinessDate,
    arrival: String,
    departure: String,
    dayUse: Boolean,
): Pair<BusinessDate, BusinessDate>? =
    if (dayUse) {
        (today to today).takeIf { BusinessDate.parse(arrival) == today }
    } else {
        val end = BusinessDate.parse(departure)
        (today to BusinessDate(end.value.minusDays(1))).takeIf { today.value < end.value }
    }

private fun RoomStatusResponse.isMoveCandidate(typeId: String) =
    roomTypeId.toString() == typeId &&
        active &&
        occupancy == RoomStatusResponse.Occupancy.VACANT &&
        readyForCheckIn

private fun problem(code: String) = ApiError.Problem(CONFLICT, code, null, emptyList())

private suspend fun <T> attempt(call: suspend () -> T): Result<T> =
    try {
        Result.success(call())
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
