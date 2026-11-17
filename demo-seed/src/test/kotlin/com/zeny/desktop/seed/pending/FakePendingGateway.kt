package com.zeny.desktop.seed.pending

import java.time.LocalDate

data class OutOfOrderCall(
    val roomId: String,
    val from: LocalDate,
    val to: LocalDate,
    val reason: String,
)

/** Falso em memória da porta de pendências; `calls` guarda a ordem das escritas (nunca das buscas). */
@Suppress("TooManyFunctions") // porta que espelha as operações usadas pelo seeder
class FakePendingGateway : PendingGateway {
    val calls = mutableListOf<String>()
    val guaranteePolicies = mutableMapOf<String, String>()
    val cancellationPolicies = mutableMapOf<String, String>()
    val ratePlans = mutableMapOf<String, String>()
    val plans = mutableListOf<PendingPlanSpec>()
    val priceWrites = mutableListOf<Pair<String, PendingPriceSpec>>()
    val guests = mutableMapOf<String, String>()
    val reservations = mutableMapOf<String, PendingReservationRef>()
    val reservationSpecs = mutableListOf<PendingReservationSpec>()
    val assignments = mutableListOf<Pair<String, String>>()
    val outOfOrders = mutableListOf<OutOfOrderCall>()
    private var sequence = 0

    private fun nextId(prefix: String) = "$prefix-${++sequence}"

    override suspend fun guaranteePolicyId(name: String) = guaranteePolicies[name]

    override suspend fun createCardGuaranteePolicy(name: String): String {
        calls += "createCardGuaranteePolicy"
        return nextId("gp").also { guaranteePolicies[name] = it }
    }

    override suspend fun cancellationPolicyId(name: String) = cancellationPolicies[name]

    override suspend fun ratePlanId(code: String) = ratePlans[code]

    override suspend fun createWebsiteRatePlan(spec: PendingPlanSpec): String {
        calls += "createWebsiteRatePlan"
        plans += spec
        return nextId("rp").also { ratePlans[spec.code] = it }
    }

    override suspend fun writePrices(
        ratePlanId: String,
        prices: PendingPriceSpec,
    ) {
        calls += "writePrices"
        priceWrites += ratePlanId to prices
    }

    override suspend fun guestId(cpf: String) = guests[cpf]

    override suspend fun findReservation(externalId: String) = reservations[externalId]

    override suspend fun createReservation(spec: PendingReservationSpec): PendingReservationRef {
        calls += "createReservation ${spec.externalId}"
        check(spec.externalId !in reservations) { "409 externalId duplicado" }
        reservationSpecs += spec
        return PendingReservationRef(nextId("res"), "CONFIRMED", null).also { reservations[spec.externalId] = it }
    }

    override suspend fun assignRoom(
        reservationId: String,
        roomId: String,
    ) {
        calls += "assignRoom"
        assignments += reservationId to roomId
        reservations.entries.first { it.value.id == reservationId }.let { it.setValue(it.value.copy(roomId = roomId)) }
    }

    override suspend fun openOutOfOrder(
        roomId: String,
        from: LocalDate,
        to: LocalDate,
        reason: String,
    ): Boolean {
        calls += "openOutOfOrder"
        if (outOfOrders.any { it.roomId == roomId && it.from == from && it.to == to }) return false
        outOfOrders += OutOfOrderCall(roomId, from, to, reason)
        return true
    }
}
