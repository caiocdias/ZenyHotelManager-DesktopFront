package com.zeny.desktop.app.traces

import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.core.facade.NewTrace
import com.zeny.desktop.core.facade.TraceChange
import com.zeny.desktop.core.facade.TraceFilter
import com.zeny.desktop.core.facade.TraceVersion
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.TraceResponse

/** Uma UH ativa que o recado pode escolher. Não é a UH "pronta, vaga e livre" do walk-in. */
data class RoomOption(
    val id: String,
    val number: String,
)

/**
 * O que os recados usam de um servidor. Os erros vêm como `ApiError`. Nenhuma operação de recado leva chave de
 * idempotência: a criação não é repetida sozinha. O texto do recado nunca vai a log nem a mensagem de exceção.
 */
@Suppress("TooManyFunctions") // porta que espelha as operações de recado sobre o `core`
interface TracesSource {
    suspend fun permissions(): Set<String>

    suspend fun businessDay(): BusinessDay

    suspend fun page(
        filter: TraceFilter,
        cursor: String?,
        limit: Int,
    ): Page<TraceResponse>

    suspend fun trace(id: String): TraceVersion

    suspend fun create(input: NewTrace): TraceVersion

    suspend fun update(
        id: String,
        change: TraceChange,
        etag: String,
    ): TraceVersion

    suspend fun resolve(id: String): TraceResponse

    suspend fun departments(): List<Department>

    /** O `code` da reserva (o recado só guarda o id). */
    suspend fun reservationCode(id: String): String

    /** As UHs ativas, em ordem de número. */
    suspend fun rooms(): List<RoomOption>
}
