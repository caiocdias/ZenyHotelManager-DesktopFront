package com.zeny.desktop.app.rooms

import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.sdk.model.RoomResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * O que o mapa de UHs usa do servidor: as leituras das UHs e o stream. Os erros vêm como `ApiError`. [events] é frio:
 * coletar assina os [topics] e cancelar sai; completa normalmente no fim da sessão e com o `ApiError` numa falha.
 */
interface RoomsSource {
    suspend fun statuses(): List<RoomStatusResponse>

    suspend fun roomTypes(): List<RoomTypeResponse>

    suspend fun room(id: String): RoomResponse

    suspend fun status(id: String): RoomStatusResponse

    fun events(topics: Set<String>): Flow<RealtimeEvent>

    val realtimeStatus: StateFlow<RealtimeStatus>
}
