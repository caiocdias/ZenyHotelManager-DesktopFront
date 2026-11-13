package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.client.RoomsApi
import com.zeny.desktop.sdk.model.RoomResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

/** Maior página que o contrato aceita: as listas vêm inteiras, com o mínimo de idas ao servidor. */
private const val PAGE_SIZE = 200

/**
 * As UHs: o painel de status, os tipos e a consulta de uma UH. As listas são lidas por inteiro, página a página.
 * Um `id` que não é UUID lança `IllegalArgumentException` antes de ir à rede.
 */
class RoomsFacade internal constructor(
    private val rooms: RoomsApi,
    private val executor: ApiExecutor,
) {
    suspend fun statuses(): List<RoomStatusResponse> =
        readAll { cursor, limit ->
            val page = executor.execute { rooms.listRoomStatuses(cursor = cursor, limit = limit, block = it.headers) }
            Page(page.value.items, page.value.nextCursor.valueOrNull())
        }

    suspend fun roomTypes(): List<RoomTypeResponse> =
        readAll { cursor, limit ->
            val page = executor.execute { rooms.listRoomTypes(cursor = cursor, limit = limit, block = it.headers) }
            Page(page.value.items, page.value.nextCursor.valueOrNull())
        }

    suspend fun room(id: String): RoomResponse {
        val roomId = uuidOf(id)
        return executor.execute { rooms.getRoom(roomId, it.headers) }.value
    }

    suspend fun status(id: String): RoomStatusResponse {
        val roomId = uuidOf(id)
        return executor.execute { rooms.getRoomStatus(roomId, it.headers) }.value
    }

    private suspend fun <I> readAll(fetch: suspend (cursor: String?, limit: Int) -> Page<I>): List<I> {
        val pager = CursorPager(PAGE_SIZE, fetch)
        val all = mutableListOf<I>()
        while (pager.hasMore) all += pager.next()
        return all
    }

    private fun uuidOf(id: String): Uuid = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de UH inválido")

    private fun Option<String?>.valueOrNull(): String? = (this as? Option.Some)?.value
}
