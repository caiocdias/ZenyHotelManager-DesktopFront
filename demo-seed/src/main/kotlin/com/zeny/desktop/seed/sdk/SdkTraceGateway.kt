@file:OptIn(RawSdkAccess::class, ExperimentalUuidApi::class)

package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.TraceRequest
import com.zeny.desktop.seed.frontdesk.TraceGateway
import com.zeny.desktop.seed.frontdesk.TraceRef
import de.quati.kotlin.util.Option
import java.time.LocalDate
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val PAGE_SIZE = 200

/** Setores e recados sobre o `sdk`. Nenhuma das operações usadas leva `Idempotency-Key` no contrato. */
class SdkTraceGateway(
    private val seed: SeedCore,
) : TraceGateway {
    override suspend fun departmentIdsByKey(): Map<String, String> =
        allPages { cursor, limit ->
            val page = seed.executor.execute { seed.apis.staff.listDepartments(cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.mapNotNull { department ->
            (department.key as? Option.Some)?.value?.let { it.value to department.id.toString() }
        }.toMap()

    override suspend fun traces(
        reservationId: String?,
        roomId: String?,
    ): List<TraceRef> {
        val reservation = reservationId?.let(Uuid::parse)
        val room = roomId?.let(Uuid::parse)
        return allPages { cursor, limit ->
            val page =
                seed.executor
                    .execute {
                        seed.apis.frontdesk.listTraces(null, null, null, reservation, room, cursor, limit, it.headers)
                    }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.map { TraceRef(it.id.toString(), it.departmentId.toString()) }
    }

    override suspend fun create(
        departmentId: String,
        dueOn: LocalDate,
        text: String,
        reservationId: String?,
        roomId: String?,
    ): String {
        val request =
            TraceRequest(
                departmentId = Uuid.parse(departmentId),
                dueOn = dueOn.toString(),
                reservationId = reservationId?.let { Option.Some(Uuid.parse(it)) } ?: Option.Undefined,
                roomId = roomId?.let { Option.Some(Uuid.parse(it)) } ?: Option.Undefined,
                text = text,
            )
        return seed.executor
            .execute { seed.apis.frontdesk.createTrace(request, it.headers) }
            .value.id
            .toString()
    }
}

private suspend fun <I> allPages(fetch: suspend (cursor: String?, limit: Int) -> Page<I>): List<I> {
    val pager = CursorPager(PAGE_SIZE, fetch)
    val all = mutableListOf<I>()
    while (pager.hasMore) all += pager.next()
    return all
}
