package com.zeny.desktop.seed.pending

/** Falso em memória da porta das reservas futuras; `calls` guarda a ordem das escritas (nunca das buscas). */
class FakeFutureGateway : FutureGateway {
    val calls = mutableListOf<String>()
    val guests = mutableMapOf<String, String>()
    val reservations = mutableMapOf<String, PendingReservationRef>()
    val reservationSpecs = mutableListOf<PendingReservationSpec>()
    val cancellations = mutableListOf<Pair<String, String>>()
    private var sequence = 0

    override suspend fun guestId(cpf: String) = guests[cpf]

    override suspend fun findReservation(externalId: String) = reservations[externalId]

    override suspend fun createReservation(spec: PendingReservationSpec): PendingReservationRef {
        calls += "createReservation ${spec.externalId}"
        check(spec.externalId !in reservations) { "409 externalId duplicado" }
        reservationSpecs += spec
        return PendingReservationRef("res-${++sequence}", "CONFIRMED", null).also { reservations[spec.externalId] = it }
    }

    override suspend fun cancelReservation(
        reservationId: String,
        reason: String,
    ) {
        calls += "cancelReservation $reservationId"
        cancellations += reservationId to reason
        val entry = reservations.entries.first { it.value.id == reservationId }
        entry.setValue(entry.value.copy(status = "CANCELLED"))
    }
}
