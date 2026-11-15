package com.zeny.desktop.seed.frontdesk

import java.time.LocalDate

/** Um recado já existente: o id e o setor, o bastante para decidir se falta criar. */
data class TraceRef(
    val id: String,
    val departmentId: String,
)

/** Setores e recados no servidor. Erros sobem como `ApiError`; as listas vêm inteiras. */
interface TraceGateway {
    /** Os setores com chave, por chave (`RECEPTION`, `HOUSEKEEPING`...) -> id. */
    suspend fun departmentIdsByKey(): Map<String, String>

    /** Os recados ligados à reserva ou à UH dados (qualquer situação). */
    suspend fun traces(
        reservationId: String?,
        roomId: String?,
    ): List<TraceRef>

    /** Cria o recado ligado a uma reserva **ou** a uma UH e devolve o id. */
    suspend fun create(
        departmentId: String,
        dueOn: LocalDate,
        text: String,
        reservationId: String?,
        roomId: String?,
    ): String
}
