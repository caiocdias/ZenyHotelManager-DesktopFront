package com.zeny.desktop.app.traces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.core.facade.NewTrace
import com.zeny.desktop.core.facade.TraceChange
import com.zeny.desktop.core.facade.TraceVersion
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.InputResult
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val MAX_TEXT = 500
private const val OPEN_STATUS = "OPEN"
private const val ALREADY_RESOLVED = "TRACE_ALREADY_RESOLVED"
private const val TRACE_INVALID = "TRACE_INVALID"
private const val HTTP_CONFLICT = 409
private const val TEXT_REQUIRED = "Escreva o recado (até 500 caracteres)."
private const val DEPARTMENT_REQUIRED = "Escolha o setor."
private const val ROOM_REQUIRED = "Escolha a UH."
private const val DUE_INVALID = "Data inválida. Use dd/mm/aaaa."
private const val DUE_BEFORE_TODAY = "O vencimento não pode ser antes da data operacional."
private const val RESERVATION_FIELD = "reservationId"

/** A quem o recado do formulário se liga: uma reserva, uma UH escolhida no formulário, ou um recado existente. */
sealed interface TraceTarget {
    data class ForReservation(
        val reservationId: String,
        val code: String,
    ) : TraceTarget

    data object ForRoom : TraceTarget

    data class Existing(
        val traceId: String,
    ) : TraceTarget
}

/** O recado lido para a edição: o que o formulário guarda para conferir mudanças e devolver ao salvar. */
private data class LoadedTrace(
    val version: TraceVersion,
    val dueOn: BusinessDate,
    val assigneeUserId: String?,
    val assigneeName: String?,
)

/**
 * Estado do formulário de recado (criação e edição). Escopo próprio, cancelado no [close]; com [busy], as entradas e
 * o [save] são no-op; resultado que chega depois do [close] é descartado.
 *
 * Criação: chama `create` uma vez. Falha de rede, resposta incompatível ou sessão encerrada deixa o resultado
 * desconhecido: avisa, atualiza a lista ([onSaved]) e não repete sozinha; o formulário fica livre para nova tentativa
 * só por clique (um recado duplicado custa pouco e se resolve na lista, ao contrário do walk-in, que trava).
 *
 * Edição: guarda o `ETag` lido. No `412`, relê o recado, troca campos e `ETag` e avisa, sem salvar; se a releitura
 * falha ou traz o recado resolvido, o salvamento fica bloqueado. O vencimento só é conferido contra a data
 * operacional quando muda. O texto do recado nunca vai a `toString`.
 */
@Suppress("TooManyFunctions") // entradas, carga, validação e resultado do salvamento
class TraceFormState(
    private val source: TracesSource,
    scope: CoroutineScope,
    val target: TraceTarget,
    private val departments: List<Department>,
    private val departmentsDenied: Boolean,
    private val onSaved: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    private var textValue by mutableStateOf("")
    private var dueValue by mutableStateOf("")
    private var departmentValue by mutableStateOf<String?>(null)
    private var roomValue by mutableStateOf<String?>(null)

    var rooms by mutableStateOf<List<RoomOption>>(emptyList())
        private set
    var assigneeName by mutableStateOf<String?>(null)
        private set
    var fieldErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set

    private var loading by mutableStateOf(false)
    private var etag by mutableStateOf<String?>(null)
    private var resolved by mutableStateOf(false)
    private var currentDepartment by mutableStateOf<Department?>(null)

    private var today: BusinessDate? = null
    private var originalDue: BusinessDate? = null
    private var assigneeUserId: String? = null
    private var closed = false
    private var generation = 0

    private val editing: Boolean get() = target is TraceTarget.Existing
    private val locked: Boolean get() = closed || busy || loading || saved

    var text: String
        get() = textValue
        set(value) {
            if (!locked) textValue = value
        }

    /** `dd/MM/aaaa`. */
    var dueText: String
        get() = dueValue
        set(value) {
            if (!locked) dueValue = value
        }

    /** Só um setor de [departmentOptions]; `null` limpa a escolha. */
    var departmentId: String?
        get() = departmentValue
        set(value) {
            if (!locked && (value == null || departmentOptions.any { it.id == value })) departmentValue = value
        }

    /** Só no recado de UH, e só uma UH de [rooms]; `null` limpa a escolha. */
    var roomId: String?
        get() = roomValue
        set(value) {
            if (!locked && (value == null || isRoom(value))) roomValue = value
        }

    /** Os setores do seletor; na edição sem setores legíveis, só o setor atual. */
    val departmentOptions: List<Department>
        get() {
            val current = currentDepartment
            return if (editing && departmentsDenied && current != null) listOf(current) else departments
        }

    private fun isRoom(id: String) = target is TraceTarget.ForRoom && rooms.any { it.id == id }

    val canSave: Boolean
        get() = !locked && !resolved && (!editing || etag != null)

    fun start() {
        if (closed) return
        val mine = ++generation
        loading = true
        ownScope.launch {
            try {
                readToday()
                when (target) {
                    is TraceTarget.Existing -> reread(target.traceId, mine, null)
                    is TraceTarget.ForRoom -> loadRooms()
                    is TraceTarget.ForReservation -> Unit
                }
                if (!editing) today?.let { dueValue = DateInput.format(it) }
            } finally {
                if (mine == generation) loading = false
            }
        }
    }

    fun save() {
        if (locked || !canSave) return
        busy = true
        ownScope.launch {
            try {
                if (today == null) readToday()
                val now = today
                val input = now?.let { validate(it) }
                if (input != null) send(input)
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        closed = true
        busy = false
        jobs.cancel()
    }

    private suspend fun readToday() {
        attempt { source.businessDay().date }.fold({ today = it }, { error = TraceText.message(apiErrorOf(it)) })
    }

    private suspend fun loadRooms() {
        attempt { source.rooms() }.fold({ if (!closed) rooms = it }, { error = TraceText.message(apiErrorOf(it)) })
    }

    /** Lê o recado e o aplica; a falha apaga o `ETag` e bloqueia o salvamento. [notice] fica se a leitura dá certo. */
    private suspend fun reread(
        id: String,
        mine: Int,
        notice: String?,
    ) {
        val outcome = attempt { source.trace(id).let { loaded(it) } }
        if (closed || mine != generation) return
        outcome.fold({ apply(it, notice) }, { applyFailure(it) })
    }

    private fun loaded(version: TraceVersion): LoadedTrace {
        val trace = version.trace
        return LoadedTrace(
            version,
            BusinessDate.parse(trace.dueOn),
            (trace.assigneeUserId as? Option.Some)?.value?.toString(),
            (trace.assigneeName as? Option.Some)?.value,
        )
    }

    private fun apply(
        loaded: LoadedTrace,
        notice: String?,
    ) {
        val trace = loaded.version.trace
        textValue = trace.text
        dueValue = DateInput.format(loaded.dueOn)
        originalDue = loaded.dueOn
        departmentValue = trace.departmentId.toString()
        currentDepartment = Department(trace.departmentId.toString(), trace.departmentName)
        assigneeUserId = loaded.assigneeUserId
        assigneeName = loaded.assigneeName
        etag = loaded.version.etag
        resolved = trace.status != OPEN_STATUS
        error = null
        fieldErrors = emptyMap()
        this.notice = if (resolved) TraceText.message(problem(ALREADY_RESOLVED)) else notice
    }

    private fun applyFailure(cause: Throwable) {
        etag = null
        notice = null
        error = TraceText.message(apiErrorOf(cause))
    }

    /** Os erros locais vão a [fieldErrors] (e a rede não é chamada); sem eles, a entrada pronta para enviar. */
    private fun validate(today: BusinessDate): TraceInput? {
        val errors = linkedMapOf<String, String>()
        val trimmed = textValue.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TEXT) errors["text"] = TEXT_REQUIRED
        val department = departmentValue
        if (department == null) errors["departmentId"] = DEPARTMENT_REQUIRED
        if (target is TraceTarget.ForRoom && roomValue == null) errors["roomId"] = ROOM_REQUIRED
        val due = (DateInput.parse(dueValue) as? InputResult.Valid)?.value
        when {
            due == null -> errors["dueOn"] = DUE_INVALID
            due.value < today.value && (!editing || due != originalDue) -> errors["dueOn"] = DUE_BEFORE_TODAY
        }
        fieldErrors = errors
        error = null
        notice = null
        return if (errors.isEmpty() && department != null && due != null) TraceInput(trimmed, department, due) else null
    }

    private suspend fun send(input: TraceInput) {
        val outcome = attempt { submit(input) }
        if (closed) return
        outcome.fold({ finish() }, { fail(apiErrorOf(it)) })
    }

    private suspend fun submit(input: TraceInput): TraceVersion =
        when (target) {
            is TraceTarget.Existing -> {
                source.update(
                    target.traceId,
                    TraceChange(input.departmentId, input.dueOn, input.text, assigneeUserId),
                    checkNotNull(etag),
                )
            }

            is TraceTarget.ForReservation -> {
                source.create(NewTrace(input.departmentId, input.dueOn, input.text, target.reservationId, null))
            }

            is TraceTarget.ForRoom -> {
                source.create(NewTrace(input.departmentId, input.dueOn, input.text, null, roomValue))
            }
        }

    private fun finish() {
        saved = true
        onSaved()
    }

    private suspend fun fail(failure: ApiError) {
        val code = (failure as? ApiError.Problem)?.code
        when {
            !editing && resultUnknown(failure) -> {
                error = TraceText.UNKNOWN_CREATE_RESULT
                onSaved()
            }

            failure is ApiError.PreconditionFailed && target is TraceTarget.Existing -> {
                val mine = ++generation
                reread(target.traceId, mine, TraceText.message(failure))
            }

            code == ALREADY_RESOLVED -> {
                resolved = true
                error = TraceText.message(failure)
                onSaved()
            }

            code == TRACE_INVALID && placeInvalidFields(failure) -> {
                Unit
            }

            else -> {
                error = TraceText.message(failure)
            }
        }
    }

    /** `true` se algum campo do `errors[]` é de um campo do formulário. */
    private fun placeInvalidFields(failure: ApiError.Problem): Boolean {
        val placed =
            failure.fieldErrors
                .mapNotNull { fieldText(it.field)?.let { text -> it.field to text } }
                .toMap()
        if (placed.isNotEmpty()) fieldErrors = placed
        return placed.isNotEmpty()
    }

    private fun fieldText(field: String): String? =
        when (field) {
            "text" -> TEXT_REQUIRED
            "dueOn" -> DUE_BEFORE_TODAY
            RESERVATION_FIELD -> TraceText.message(problem("UNKNOWN_RESERVATION"))
            else -> null
        }

    private fun resultUnknown(failure: ApiError): Boolean =
        failure is ApiError.Network || failure is ApiError.IncompatibleResponse || failure is ApiError.SessionEnded

    // O texto do recado nunca vai a log nem a `toString`.
    override fun toString(): String = "TraceFormState(target=${target::class.simpleName}, busy=$busy, saved=$saved)"
}

private data class TraceInput(
    val text: String,
    val departmentId: String,
    val dueOn: BusinessDate,
) {
    override fun toString(): String = "TraceInput(departmentId=$departmentId, dueOn=${dueOn.toApi()})"
}

private fun problem(code: String) = ApiError.Problem(HTTP_CONFLICT, code, null, emptyList())

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
