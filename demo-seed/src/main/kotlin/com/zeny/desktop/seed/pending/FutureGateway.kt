package com.zeny.desktop.seed.pending

/** O que o seeder das reservas futuras usa do servidor. */
interface FutureGateway {
    suspend fun guestId(cpf: String): String?

    suspend fun findReservation(externalId: String): PendingReservationRef?

    suspend fun createReservation(spec: PendingReservationSpec): PendingReservationRef

    /** Cancela sem isentar a multa; `reason` é o texto do enum do contrato. */
    suspend fun cancelReservation(
        reservationId: String,
        reason: String,
    )
}
