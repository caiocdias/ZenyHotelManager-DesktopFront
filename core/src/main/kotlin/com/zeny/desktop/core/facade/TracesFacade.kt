package com.zeny.desktop.core.facade

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.http.ResponseFormatException
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.client.FrontdeskApi
import com.zeny.desktop.sdk.client.StaffApi
import com.zeny.desktop.sdk.model.ListTracesParamStatus
import com.zeny.desktop.sdk.model.TraceChangeRequest
import com.zeny.desktop.sdk.model.TraceRequest
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

/** Maior página que o contrato aceita: os setores vêm inteiros, com o mínimo de idas ao servidor. */
private const val DEPARTMENTS_PAGE_SIZE = 200

/** O estado que a lista de recados filtra. */
enum class TraceStatusFilter { OPEN, RESOLVED }

/** Os filtros da lista de recados; filtro nulo não vai na consulta. */
data class TraceFilter(
    val departmentId: String? = null,
    val dueOn: BusinessDate? = null,
    val status: TraceStatusFilter? = null,
    val reservationId: String? = null,
    val roomId: String? = null,
)

/** O recado com o `ETag` da resposta, que a edição devolve no `If-Match`. O texto do recado fica fora do `toString`. */
data class TraceVersion(
    val trace: TraceResponse,
    val etag: String,
) {
    override fun toString(): String = "TraceVersion(id=${trace.id}, status=${trace.status}, etag=$etag)"
}

/** Um setor que recebe recados. */
data class Department(
    val id: String,
    val name: String,
)

/**
 * O recado novo, vinculado a uma reserva **ou** a uma UH: exatamente um de `reservationId` e `roomId`, senão
 * `IllegalArgumentException` antes da rede. O texto fica fora do `toString`.
 */
data class NewTrace(
    val departmentId: String,
    val dueOn: BusinessDate,
    val text: String,
    val reservationId: String?,
    val roomId: String?,
) {
    override fun toString(): String =
        "NewTrace(departmentId=$departmentId, dueOn=${dueOn.toApi()}, reservationId=$reservationId, roomId=$roomId)"
}

/** A edição de um recado; `assigneeUserId` nulo não vai. O texto fica fora do `toString`. */
data class TraceChange(
    val departmentId: String,
    val dueOn: BusinessDate,
    val text: String,
    val assigneeUserId: String?,
) {
    override fun toString(): String =
        "TraceChange(departmentId=$departmentId, dueOn=${dueOn.toApi()}, assigneeUserId=$assigneeUserId)"
}

/**
 * Os recados da recepção e os setores que os recebem. Nenhuma operação de recado leva `Idempotency-Key`. Um `id` que
 * não é UUID lança `IllegalArgumentException` antes de ir à rede, sem o valor na mensagem.
 */
class TracesFacade internal constructor(
    private val frontdesk: FrontdeskApi,
    private val staff: StaffApi,
    private val executor: ApiExecutor,
) {
    suspend fun page(
        filter: TraceFilter,
        cursor: String?,
        limit: Int,
    ): Page<TraceResponse> {
        val department = filter.departmentId?.let { idOf(it, "setor") }
        val reservation = filter.reservationId?.let { idOf(it, "reserva") }
        val room = filter.roomId?.let { idOf(it, "UH") }
        val dueOn = filter.dueOn?.toApi()
        val status = filter.status?.let { ListTracesParamStatus.fromSerial(it.name) }
        val page =
            executor
                .execute {
                    frontdesk.listTraces(department, dueOn, status, reservation, room, cursor, limit, it.headers)
                }.value
        return Page(page.items, page.nextCursor.valueOrNull())
    }

    /** O recado com o `ETag`; sem `ETag`, o servidor quebrou o contrato: `IncompatibleResponse`. */
    suspend fun trace(id: String): TraceVersion {
        val traceId = idOf(id, "recado")
        return versionOf(executor.execute { frontdesk.getTrace(traceId, it.headers) })
    }

    /** Cria o recado. Falha de rede ou resposta incompatível não é repetida aqui: não há chave de idempotência. */
    suspend fun create(input: NewTrace): TraceVersion {
        require((input.reservationId == null) != (input.roomId == null)) {
            "o recado liga a uma reserva ou a uma UH, não a ambas nem a nenhuma"
        }
        val request =
            TraceRequest(
                assigneeUserId = Option.Undefined,
                departmentId = idOf(input.departmentId, "setor"),
                dueOn = input.dueOn.toApi(),
                reservationId = input.reservationId?.let { Option.Some(idOf(it, "reserva")) } ?: Option.Undefined,
                roomId = input.roomId?.let { Option.Some(idOf(it, "UH")) } ?: Option.Undefined,
                text = input.text,
            )
        return versionOf(executor.execute { frontdesk.createTrace(request, it.headers) })
    }

    /** Edita com `If-Match = etag`; `412` vira `ApiError.PreconditionFailed`. */
    suspend fun update(
        id: String,
        change: TraceChange,
        etag: String,
    ): TraceVersion {
        val traceId = idOf(id, "recado")
        val assignee = change.assigneeUserId?.let { Option.Some(idOf(it, "responsável")) } ?: Option.Undefined
        val request =
            TraceChangeRequest(
                assigneeUserId = assignee,
                departmentId = idOf(change.departmentId, "setor"),
                dueOn = change.dueOn.toApi(),
                text = change.text,
            )
        return versionOf(executor.execute { frontdesk.updateTrace(traceId, etag, request, it.headers) })
    }

    suspend fun resolve(id: String): TraceResponse {
        val traceId = idOf(id, "recado")
        return executor.execute { frontdesk.resolveTrace(traceId, it.headers) }.value
    }

    /** Todos os setores, página a página, na ordem do servidor. */
    suspend fun departments(): List<Department> {
        val pager =
            CursorPager(DEPARTMENTS_PAGE_SIZE) { cursor, limit ->
                val page = executor.execute { staff.listDepartments(cursor, limit, it.headers) }.value
                Page(page.items.map { Department(it.id.toString(), it.name) }, page.nextCursor.valueOrNull())
            }
        val all = mutableListOf<Department>()
        while (pager.hasMore) all += pager.next()
        return all
    }

    private fun versionOf(response: ApiResponse<TraceResponse>): TraceVersion {
        val etag =
            response.etag?.takeIf { it.isNotBlank() }
                ?: throw ApiError.IncompatibleResponse(ResponseFormatException(IllegalStateException("sem ETag")))
        return TraceVersion(response.value, etag)
    }

    private fun idOf(
        id: String,
        what: String,
    ): Uuid = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de $what inválido")

    /** Cursor ausente, nulo ou em branco é o fim da lista. */
    private fun Option<String?>.valueOrNull(): String? = (this as? Option.Some)?.value?.takeIf { it.isNotBlank() }
}
