package com.zeny.desktop.app.traces

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.core.facade.NewTrace
import com.zeny.desktop.core.facade.TraceChange
import com.zeny.desktop.core.facade.TraceFilter
import com.zeny.desktop.core.facade.TraceVersion
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** As operações em que o falso aceita uma falha injetada. */
enum class TraceOp {
    BUSINESS_DAY,
    PAGE,
    TRACE,
    CREATE,
    UPDATE,
    RESOLVE,
    DEPARTMENTS,
    RESERVATION_CODE,
    ROOMS,
}

/** Uma chamada registrada pelo falso. Nenhuma guarda o texto do recado. */
sealed interface TraceCall {
    data class Page(
        val filter: TraceFilter,
        val cursor: String?,
        val limit: Int,
    ) : TraceCall

    data class Trace(
        val id: String,
    ) : TraceCall

    data class Create(
        val input: NewTrace,
    ) : TraceCall

    data class Update(
        val id: String,
        val change: TraceChange,
        val etag: String,
    ) : TraceCall

    data class Resolve(
        val id: String,
    ) : TraceCall

    data object Departments : TraceCall

    data class ReservationCode(
        val id: String,
    ) : TraceCall

    data object Rooms : TraceCall
}

/** O código de problema do servidor para recado já resolvido. */
fun alreadyResolved() = ApiError.Problem(409, "TRACE_ALREADY_RESOLVED", null, emptyList())

fun traceNotFound() = ApiError.Problem(404, "TRACE_NOT_FOUND", null, emptyList())

/**
 * Servidor de recados em memória para os testes. Cada recado tem versão e `ETag`; o `ETag` muda a cada alteração
 * (edição, resolução ou [externalEdit]). `update` com `etag` diferente do atual falha com `PreconditionFailed` (412);
 * alterar ou resolver recado resolvido falha com `409 TRACE_ALREADY_RESOLVED`. O [reservationCodeGate] segura as
 * leituras de `reservationCode`, e [reservationCodeInFlight] e [maxReservationCodeInFlight] contam as que estão
 * em voo.
 */
@Suppress("TooManyFunctions") // espelha a porta
class FakeTracesSource : TracesSource {
    /** O conjunto que a casca entregaria aos recados destes testes; a porta não lê mais permissões (ADR 0013). */
    var permissions: Set<String> = emptySet()
    var businessDay: BusinessDay = BusinessDay(BusinessDate(LocalDate.of(2026, 11, 14)), null)
    var departments: List<Department> = emptyList()
    var rooms: List<RoomOption> = emptyList()

    /** O `code` de cada reserva, por id; reserva ausente do mapa falha com `UNKNOWN_RESERVATION`. */
    val reservationCodes: MutableMap<String, String> = mutableMapOf()

    /** Quando não nulo, `reservationCode` espera por ele antes de responder. */
    var reservationCodeGate: CompletableDeferred<Unit>? = null

    /** Quando não nulo, `trace` espera por ele antes de responder. */
    var traceGate: CompletableDeferred<Unit>? = null

    /** Quando não nulo, `create` espera por ele antes de agir. */
    var createGate: CompletableDeferred<Unit>? = null

    /** Quando não nulo, `update` espera por ele antes de agir. */
    var updateGate: CompletableDeferred<Unit>? = null

    /** Quando não nulo, `resolve` espera por ele antes de agir. */
    var resolveGate: CompletableDeferred<Unit>? = null

    /** Cada `page` toma o primeiro item da fila (se houver) e espera por ele antes de responder. */
    val pageGates: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque()

    private val inFlight = AtomicInteger(0)
    private val maxInFlight = AtomicInteger(0)
    val reservationCodeInFlight: Int get() = inFlight.get()
    val maxReservationCodeInFlight: Int get() = maxInFlight.get()

    val calls: MutableList<TraceCall> = mutableListOf()

    private val traces = LinkedHashMap<String, TraceResponse>()
    private val etags = HashMap<String, String>()
    private val failures = HashMap<TraceOp, ArrayDeque<ApiError>>()
    private var counter = 0

    /** A próxima chamada de [op] falha com [error]; chamar de novo enfileira. */
    fun failNext(
        op: TraceOp,
        error: ApiError,
    ) {
        failures.getOrPut(op) { ArrayDeque() }.addLast(error)
    }

    /** Põe um recado já existente no servidor, com `ETag` novo. */
    fun seed(trace: TraceResponse): TraceVersion {
        traces[trace.id.toString()] = trace
        return TraceVersion(trace, bump(trace.id.toString()))
    }

    /** O recado como está no servidor agora, sem registrar chamada. */
    fun current(id: String): TraceVersion? = traces[id]?.let { TraceVersion(it, etags.getValue(id)) }

    /** Simula outra pessoa alterando o recado: nova versão e novo `ETag`. */
    fun externalEdit(
        id: String,
        text: String,
    ): TraceVersion {
        val old = traces.getValue(id)
        val next = old.copy(text = text, version = old.version + 1)
        traces[id] = next
        return TraceVersion(next, bump(id))
    }

    override suspend fun businessDay(): BusinessDay {
        fail(TraceOp.BUSINESS_DAY)
        return businessDay
    }

    override suspend fun page(
        filter: TraceFilter,
        cursor: String?,
        limit: Int,
    ): Page<TraceResponse> {
        calls += TraceCall.Page(filter, cursor, limit)
        pageGates.removeFirstOrNull()?.await()
        fail(TraceOp.PAGE)
        val all = traces.values.filter { matches(it, filter) }
        val from = cursor?.toInt() ?: 0
        val items = all.drop(from).take(limit)
        val next = (from + items.size).takeIf { it < all.size }?.toString()
        return Page(items, next)
    }

    override suspend fun trace(id: String): TraceVersion {
        calls += TraceCall.Trace(id)
        traceGate?.await()
        fail(TraceOp.TRACE)
        return current(id) ?: throw traceNotFound()
    }

    override suspend fun create(input: NewTrace): TraceVersion {
        calls += TraceCall.Create(input)
        createGate?.await()
        fail(TraceOp.CREATE)
        val id = Uuid.random()
        val department = departments.firstOrNull { it.id == input.departmentId }
        val room = input.roomId?.let { roomId -> rooms.firstOrNull { it.id == roomId } }
        val trace =
            TraceResponse(
                createdAt = "2026-11-14T10:00:00Z",
                createdBy = Uuid.random(),
                departmentId = Uuid.parse(input.departmentId),
                departmentName = department?.name ?: "Setor",
                dueOn = input.dueOn.toApi(),
                id = id,
                reservationId = input.reservationId?.let { Option.Some(Uuid.parse(it)) } ?: Option.Undefined,
                roomId = input.roomId?.let { Option.Some(Uuid.parse(it)) } ?: Option.Undefined,
                roomNumber = room?.let { Option.Some(it.number) } ?: Option.Undefined,
                status = "OPEN",
                text = input.text,
                version = 1,
            )
        return seed(trace)
    }

    override suspend fun update(
        id: String,
        change: TraceChange,
        etag: String,
    ): TraceVersion {
        calls += TraceCall.Update(id, change, etag)
        updateGate?.await()
        fail(TraceOp.UPDATE)
        val old = matchingVersion(id, etag)
        if (old.status == "RESOLVED") throw alreadyResolved()
        val department = departments.firstOrNull { it.id == change.departmentId }
        val next =
            old.copy(
                departmentId = Uuid.parse(change.departmentId),
                departmentName = department?.name ?: old.departmentName,
                dueOn = change.dueOn.toApi(),
                text = change.text,
                version = old.version + 1,
            )
        traces[id] = next
        return TraceVersion(next, bump(id))
    }

    override suspend fun resolve(id: String): TraceResponse {
        calls += TraceCall.Resolve(id)
        resolveGate?.await()
        fail(TraceOp.RESOLVE)
        val old = traces[id] ?: throw traceNotFound()
        if (old.status == "RESOLVED") throw alreadyResolved()
        val next =
            old.copy(
                status = "RESOLVED",
                resolvedAt = Option.Some("2026-11-14T11:00:00Z"),
                version = old.version + 1,
            )
        traces[id] = next
        bump(id)
        return next
    }

    override suspend fun departments(): List<Department> {
        calls += TraceCall.Departments
        fail(TraceOp.DEPARTMENTS)
        return departments
    }

    override suspend fun reservationCode(id: String): String {
        calls += TraceCall.ReservationCode(id)
        val now = inFlight.incrementAndGet()
        maxInFlight.updateAndGet { maxOf(it, now) }
        try {
            reservationCodeGate?.await()
            fail(TraceOp.RESERVATION_CODE)
            return reservationCodes[id] ?: throw ApiError.Problem(404, "UNKNOWN_RESERVATION", null, emptyList())
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override suspend fun rooms(): List<RoomOption> {
        calls += TraceCall.Rooms
        fail(TraceOp.ROOMS)
        return rooms
    }

    /** O recado, se existe e o `etag` confere com o atual (senão 404 ou 412). */
    private fun matchingVersion(
        id: String,
        etag: String,
    ): TraceResponse {
        val trace = traces[id] ?: throw traceNotFound()
        if (etag != etags.getValue(id)) throw ApiError.PreconditionFailed(null)
        return trace
    }

    private fun fail(op: TraceOp) {
        failures[op]?.removeFirstOrNull()?.let { throw it }
    }

    private fun bump(id: String): String {
        counter += 1
        return "etag-$counter".also { etags[id] = it }
    }

    private fun matches(
        trace: TraceResponse,
        filter: TraceFilter,
    ): Boolean =
        (filter.departmentId == null || trace.departmentId.toString() == filter.departmentId) &&
            (filter.dueOn == null || trace.dueOn == filter.dueOn?.toApi()) &&
            (filter.status == null || trace.status == filter.status?.name) &&
            (filter.reservationId == null || idOf(trace.reservationId) == filter.reservationId) &&
            (filter.roomId == null || idOf(trace.roomId) == filter.roomId)

    private fun idOf(option: Option<Uuid?>): String? = (option as? Option.Some)?.value?.toString()
}
