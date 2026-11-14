package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.sdk.client.GuestsApi
import com.zeny.desktop.sdk.model.GuestResponse
import kotlin.uuid.Uuid

/**
 * A leitura do perfil de um hóspede. O documento vem sempre mascarado pelo servidor. Um `id` que não é UUID lança
 * `IllegalArgumentException` antes de ir à rede, sem o `id` na mensagem.
 */
class GuestsFacade internal constructor(
    private val guests: GuestsApi,
    private val executor: ApiExecutor,
) {
    suspend fun guest(id: String): GuestResponse {
        val guestId = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de hóspede inválido")
        return executor.execute { guests.getGuest(guestId, it.headers) }.value
    }
}
