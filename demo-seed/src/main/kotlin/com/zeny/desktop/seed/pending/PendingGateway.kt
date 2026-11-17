package com.zeny.desktop.seed.pending

import java.math.BigDecimal
import java.time.LocalDate

/** Reserva achada pelo `externalId`: a situação crua e a UH alocada, como o `ReservationRef` do `BookingGateway`. */
data class PendingReservationRef(
    val id: String,
    val status: String,
    val roomId: String?,
)

/** O plano de tarifa só no canal `WEBSITE`, para um tipo de UH, com a política de garantia e a de cancelamento. */
data class PendingPlanSpec(
    val code: String,
    val name: String,
    val guaranteePolicyId: String,
    val cancellationPolicyId: String,
    val roomTypeId: String,
)

/** As duas diárias de um tipo de UH, de `from` a `to` (inclusive). */
data class PendingPriceSpec(
    val roomTypeId: String,
    val oneAdult: BigDecimal,
    val twoAdults: BigDecimal,
    val from: LocalDate,
    val to: LocalDate,
)

/** Uma reserva a criar: a origem é o valor do contrato (`WEBSITE`, `FRONT_DESK`). */
data class PendingReservationSpec(
    val externalId: String,
    val holderGuestId: String,
    val roomTypeId: String,
    val ratePlanId: String,
    val origin: String,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
)

/** O que o seeder de pendências usa do servidor; cada busca devolve o id, ou nulo se não existe. */
@Suppress("TooManyFunctions") // porta que espelha as operações do contrato usadas pelo seeder
interface PendingGateway {
    suspend fun guaranteePolicyId(name: String): String?

    suspend fun createCardGuaranteePolicy(name: String): String

    suspend fun cancellationPolicyId(name: String): String?

    suspend fun ratePlanId(code: String): String?

    /** Plano só no canal WEBSITE, para um tipo de UH. */
    suspend fun createWebsiteRatePlan(spec: PendingPlanSpec): String

    /** Grava `oneAdult` e `twoAdults` do plano de `from` a `to` (inclusive); regravar é seguro. */
    suspend fun writePrices(
        ratePlanId: String,
        prices: PendingPriceSpec,
    )

    suspend fun guestId(cpf: String): String?

    suspend fun findReservation(externalId: String): PendingReservationRef?

    suspend fun createReservation(spec: PendingReservationSpec): PendingReservationRef

    suspend fun assignRoom(
        reservationId: String,
        roomId: String,
    )

    /** `true` se abriu; `false` se o período já estava fora de ordem (`409 OUT_OF_ORDER_OVERLAP`). */
    suspend fun openOutOfOrder(
        roomId: String,
        from: LocalDate,
        to: LocalDate,
        reason: String,
    ): Boolean
}
