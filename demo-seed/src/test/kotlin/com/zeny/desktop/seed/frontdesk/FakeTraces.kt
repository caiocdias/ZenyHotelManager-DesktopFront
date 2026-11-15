package com.zeny.desktop.seed.frontdesk

import com.zeny.desktop.core.error.ApiError
import java.time.LocalDate

data class CreatedTrace(
    val id: String,
    val departmentId: String,
    val dueOn: LocalDate,
    val text: String,
    val reservationId: String?,
    val roomId: String?,
)

/** Falso em memória da porta de recados; `failures` são lançados, em ordem, pelas próximas criações. */
class FakeTraces(
    private val departments: Map<String, String> =
        mapOf(
            "RECEPTION" to "dep-reception",
            "HOUSEKEEPING" to "dep-housekeeping",
            "MAINTENANCE" to "dep-maintenance",
        ),
) : TraceGateway {
    val traces = mutableListOf<CreatedTrace>()
    val failures = ArrayDeque<ApiError>()
    var createCalls = 0
        private set

    override suspend fun departmentIdsByKey(): Map<String, String> = departments

    override suspend fun traces(
        reservationId: String?,
        roomId: String?,
    ): List<TraceRef> =
        traces
            .filter {
                (reservationId != null && it.reservationId == reservationId) ||
                    (roomId != null && it.roomId == roomId)
            }.map { TraceRef(it.id, it.departmentId) }

    override suspend fun create(
        departmentId: String,
        dueOn: LocalDate,
        text: String,
        reservationId: String?,
        roomId: String?,
    ): String {
        createCalls++
        failures.removeFirstOrNull()?.let { throw it }
        require((reservationId == null) != (roomId == null)) { "recado liga a reserva ou a UH, não a ambas" }
        val id = "trace-${traces.size + 1}"
        traces += CreatedTrace(id, departmentId, dueOn, text, reservationId, roomId)
        return id
    }
}
