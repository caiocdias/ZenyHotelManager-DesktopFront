package com.zeny.desktop.app.traces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.core.facade.TraceFilter
import com.zeny.desktop.core.facade.TraceStatusFilter
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.table.TableState
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.format.DateTimeFormatter

private const val PAGE_LIMIT = 50
private const val PARALLEL_CODE_READS = 4
private const val MANAGE_PERMISSION = "frontdesk.traces.manage"
private const val RESOLVE_PERMISSION = "frontdesk.traces.resolve"
private const val OPEN_STATUS = "OPEN"
private const val HTTP_FORBIDDEN = 403
private const val HTTP_CONFLICT = 409
private const val ALREADY_RESOLVED = "TRACE_ALREADY_RESOLVED"
private val dueFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** O filtro de vencimento: qualquer um, ou os que vencem na data operacional. */
enum class DueFilter { ANY, TODAY }

/** Uma linha da lista de recados, já em texto. [link] é vazio quando a lista é de uma só reserva. */
data class TraceRow(
    val id: String,
    val due: String,
    val department: String,
    val link: String,
    val text: String,
    val status: ChipModel,
    val assignee: String?,
    val open: Boolean,
    val reservationId: String?,
) {
    // O texto do recado nunca vai a log nem a `toString`.
    override fun toString(): String =
        "TraceRow(id=$id, due=$due, department=$department, link=$link, status=$status, open=$open)"
}

/**
 * Estado da aba "Recados" do painel (abertos por padrão) e da seção do detalhe da reserva ([fixedReservationId]:
 * todos os recados dela, sem filtro de situação). Escopo próprio, cancelado no [close]; com [busy], as ações são
 * no-op; resultado que chega depois do [close] é descartado.
 *
 * O código de cada reserva vem de `reservationCode`, com cache enquanto o estado vive e até
 * [PARALLEL_CODE_READS] leituras em paralelo; leitura que falha vira "Reserva" sem código.
 */
@Suppress("TooManyFunctions") // as ações da tela e um passo por regra da carga
class TracesListState(
    private val source: TracesSource,
    scope: CoroutineScope,
    private val fixedReservationId: String? = null,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val stateScope = CoroutineScope(scope.coroutineContext + jobs)

    private val reservationCodes = mutableMapOf<String, String>()
    private val lookups = Mutex()
    private var closed = false

    /**
     * Chamado depois de um `resolve` que mudou o recado no servidor (sucesso ou `409 TRACE_ALREADY_RESOLVED`), com a
     * lista própria já atualizada: quem mostra outra lista dos mesmos recados a atualiza aqui.
     */
    var onResolved: (() -> Unit)? = null

    var departmentFilter by mutableStateOf<String?>(null)
        private set
    var statusFilter by mutableStateOf<TraceStatusFilter?>(
        if (fixedReservationId == null) TraceStatusFilter.OPEN else null,
    )
        private set
    var dueFilter by mutableStateOf(DueFilter.ANY)
        private set

    var departments by mutableStateOf<List<Department>>(emptyList())
        private set
    var departmentsDenied by mutableStateOf(false)
        private set
    var departmentsError by mutableStateOf<ApiError?>(null)
        private set
    private var departmentsLoaded by mutableStateOf(false)
    private var departmentsLoading = false

    var permissions by mutableStateOf<Set<String>>(emptySet())
        private set
    var busy by mutableStateOf(false)
        private set
    private var actionError by mutableStateOf<ApiError?>(null)

    /** A falha da última ação, ou da carga da tabela. */
    val error: ApiError? get() = actionError ?: table.error

    val table: TableState<TraceRow> = TableState({ pagerOf(currentFilters()) }, TraceRow::id)

    val canCreate: Boolean
        get() = MANAGE_PERMISSION in permissions && departmentsLoaded && !departmentsDenied && departmentsError == null

    fun canEdit(row: TraceRow): Boolean = row.open && MANAGE_PERMISSION in permissions

    fun canResolve(row: TraceRow): Boolean = row.open && RESOLVE_PERMISSION in permissions

    /** Lê permissões e setores e carrega a primeira página; cada parte falha sozinha. */
    fun start() {
        if (closed) return
        stateScope.launch { readPermissions() }
        loadDepartments()
        reloadTable()
    }

    fun retryDepartments() {
        if (!closed) loadDepartments()
    }

    fun setDepartment(id: String?) {
        departmentFilter = id
        reloadTable()
    }

    fun setStatus(status: TraceStatusFilter?) {
        statusFilter = status
        reloadTable()
    }

    fun setDue(due: DueFilter) {
        dueFilter = due
        reloadTable()
    }

    /** Atualiza a lista sem esvaziá-la; a falha da última ação sai de cena (a tela refez a leitura). */
    fun refresh() {
        if (closed) return
        actionError = null
        stateScope.launch { quietly { table.refresh() } }
    }

    /** Resolve o recado e atualiza a lista; `409 TRACE_ALREADY_RESOLVED` também atualiza, e mostra o texto. */
    fun resolve(id: String) {
        if (closed || busy) return
        busy = true
        actionError = null
        stateScope.launch {
            var changed = false
            try {
                source.resolve(id)
                changed = true
            } catch (e: ApiError.Problem) {
                actionError = e
                changed = e.status == HTTP_CONFLICT && e.code == ALREADY_RESOLVED
            } catch (e: ApiError) {
                actionError = e
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                actionError = ApiError.Network(e)
            }
            try {
                quietly { table.refresh() }
            } finally {
                busy = false
            }
            if (changed && !closed) onResolved?.invoke()
        }
    }

    /** A tela saiu de cena: cancela as cargas e descarta o que chegar depois. */
    fun close() {
        closed = true
        jobs.cancel()
    }

    override fun toString(): String =
        "TracesListState(rows=${table.rows.size}, busy=$busy, departmentFilter=$departmentFilter, " +
            "statusFilter=$statusFilter, dueFilter=$dueFilter)"

    private fun currentFilters() = Triple(departmentFilter, statusFilter, dueFilter)

    private fun reloadTable() {
        if (closed) return
        actionError = null
        stateScope.launch { quietly { table.reload() } }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // A falha que não é da API (a tabela guarda a atualização pendente) não derruba o estado.
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun readPermissions() {
        permissions =
            try {
                source.permissions()
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                emptySet()
            }
    }

    private fun loadDepartments() {
        if (departmentsLoading) return
        departmentsLoading = true
        departmentsError = null
        stateScope.launch {
            try {
                departments = source.departments()
                departmentsDenied = false
                departmentsLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                val api = e as? ApiError ?: ApiError.Network(e)
                if (api is ApiError.Problem && api.status == HTTP_FORBIDDEN) {
                    departmentsDenied = true
                } else {
                    departmentsError = api
                }
            } finally {
                departmentsLoading = false
            }
        }
    }

    /** Uma listagem presa aos filtros do instante em que nasceu; a data operacional é lida na primeira página. */
    private fun pagerOf(filters: Triple<String?, TraceStatusFilter?, DueFilter>): CursorPager<TraceRow> {
        val (department, status, due) = filters
        var date: BusinessDate? = null
        return CursorPager(PAGE_LIMIT) { cursor, limit ->
            apiErrors {
                val dueOn =
                    if (due == DueFilter.TODAY) date ?: source.businessDay().date.also { date = it } else null
                val filter = TraceFilter(department, dueOn, status, fixedReservationId)
                rowsOf(source.page(filter, cursor, limit))
            }
        }
    }

    private suspend fun <T> apiErrors(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw e as? ApiError ?: ApiError.Network(e)
        }

    private suspend fun rowsOf(page: Page<TraceResponse>): Page<TraceRow> {
        val codes = if (fixedReservationId == null) codesOf(page.items) else emptyMap()
        val rows =
            page.items.map { trace ->
                val reservation = (trace.reservationId as? Option.Some)?.value?.toString()
                val link = if (fixedReservationId == null) TraceText.link(trace, codes[reservation]) else ""
                rowOf(trace, link, reservation)
            }
        return Page(rows, page.nextCursor)
    }

    /** Uma página por vez resolve os códigos que faltam: a mesma leitura não sai duas vezes. */
    private suspend fun codesOf(items: List<TraceResponse>): Map<String, String> {
        val ids = items.mapNotNull { (it.reservationId as? Option.Some)?.value?.toString() }.distinct()
        return lookups.withLock {
            val slots = Semaphore(PARALLEL_CODE_READS)
            coroutineScope {
                ids.filter { it !in reservationCodes }.forEach { id ->
                    launch { slots.withPermit { readCode(id)?.let { reservationCodes[id] = it } } }
                }
            }
            reservationCodes.toMap()
        }
    }

    private suspend fun readCode(id: String): String? =
        try {
            source.reservationCode(id)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            null
        }
}

private fun rowOf(
    trace: TraceResponse,
    link: String,
    reservationId: String?,
) = TraceRow(
    id = trace.id.toString(),
    due = dueText(trace.dueOn),
    department = trace.departmentName,
    link = link,
    text = trace.text,
    status = TraceText.status(trace.status),
    assignee = (trace.assigneeName as? Option.Some)?.value,
    open = trace.status == OPEN_STATUS,
    reservationId = reservationId,
)

private fun dueText(iso: String): String =
    try {
        BusinessDate.parse(iso).value.format(dueFormat)
    } catch (_: IllegalArgumentException) {
        "—"
    }
