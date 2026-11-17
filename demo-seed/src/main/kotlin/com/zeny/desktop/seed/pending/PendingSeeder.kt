package com.zeny.desktop.seed.pending

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoData
import java.math.BigDecimal
import java.time.LocalDate

/** O que uma execução fez: itens criados e itens que já existiam. */
data class PendingSeedResult(
    val created: Int,
    val existing: Int,
)

/**
 * Semeia o que as listas de pendências precisam: um plano com garantia por cartão vendido só no site, uma
 * pré-reserva nele e uma reserva alocada numa UH que entra em fora de ordem. Confere antes de criar, e os
 * `externalId` ficam fora do prefixo `DEMO-SEED-` que travas, roteiros e testes contam.
 */
class PendingSeeder(
    private val gateway: PendingGateway,
) {
    private class Resolved(
        val cancellationPolicyId: String,
        val holderGuestId: String,
        val standardTypeId: String,
        val luxuryTypeId: String,
        val roomId: String,
    )

    suspend fun seed(
        data: DemoData,
        config: SeededConfig,
        businessDate: LocalDate,
    ): PendingSeedResult {
        val resolved = resolve(data, config)
        var created = 0
        var existing = 0

        fun count(wasCreated: Boolean) = if (wasCreated) created++ else existing++

        val guaranteeId = seedGuaranteePolicy().also { count(it.second) }.first
        val planId = seedPlan(guaranteeId, resolved).also { count(it.second) }.first
        gateway.writePrices(planId, prices(resolved.standardTypeId, businessDate))
        count(seedPreBooking(planId, resolved, businessDate))
        count(seedReservation(config.ratePlanId, resolved, businessDate))
        return PendingSeedResult(created, existing)
    }

    private suspend fun resolve(
        data: DemoData,
        config: SeededConfig,
    ): Resolved {
        val cancellationId =
            gateway.cancellationPolicyId(data.cancellationPolicy.name)
                ?: throw SeedFailure(
                    "Política de cancelamento \"${data.cancellationPolicy.name}\" não encontrada; " +
                        "o demo-seed não cria pendências sem ela.",
                )
        val holderCpf = data.guests.first { it.key == HOLDER_KEY }.cpf
        val holderId =
            gateway.guestId(holderCpf)
                ?: throw SeedFailure(
                    "Hóspede de exemplo das pendências não encontrado; o demo-seed não cria pendências sem ele.",
                )
        return Resolved(
            cancellationPolicyId = cancellationId,
            holderGuestId = holderId,
            standardTypeId = config.roomTypeIds[STANDARD] ?: missing("Tipo de UH $STANDARD"),
            luxuryTypeId = config.roomTypeIds[LUXURY] ?: missing("Tipo de UH $LUXURY"),
            roomId = config.roomIds[ROOM_NUMBER] ?: missing("UH $ROOM_NUMBER"),
        )
    }

    private fun missing(what: String): Nothing =
        throw SeedFailure("$what não encontrado(a); o demo-seed não cria pendências sem ele(a).")

    /** O id e se foi criada agora. */
    private suspend fun seedGuaranteePolicy(): Pair<String, Boolean> {
        val existing = gateway.guaranteePolicyId(GUARANTEE_POLICY_NAME)
        return if (existing != null) {
            existing to false
        } else {
            gateway.createCardGuaranteePolicy(GUARANTEE_POLICY_NAME) to true
        }
    }

    private suspend fun seedPlan(
        guaranteeId: String,
        resolved: Resolved,
    ): Pair<String, Boolean> {
        val existing = gateway.ratePlanId(PLAN_CODE)
        if (existing != null) return existing to false
        val spec =
            PendingPlanSpec(
                code = PLAN_CODE,
                name = PLAN_NAME,
                guaranteePolicyId = guaranteeId,
                cancellationPolicyId = resolved.cancellationPolicyId,
                roomTypeId = resolved.standardTypeId,
            )
        return gateway.createWebsiteRatePlan(spec) to true
    }

    private fun prices(
        roomTypeId: String,
        businessDate: LocalDate,
    ) = PendingPriceSpec(
        roomTypeId = roomTypeId,
        oneAdult = DAILY_RATE,
        twoAdults = DAILY_RATE,
        from = businessDate,
        to = businessDate.plusDays(PRICE_LAST_DAY),
    )

    private suspend fun seedPreBooking(
        planId: String,
        resolved: Resolved,
        businessDate: LocalDate,
    ): Boolean {
        if (gateway.findReservation(PRE_BOOKING_ID) != null) return false
        gateway.createReservation(
            PendingReservationSpec(
                externalId = PRE_BOOKING_ID,
                holderGuestId = resolved.holderGuestId,
                roomTypeId = resolved.standardTypeId,
                ratePlanId = planId,
                origin = "WEBSITE",
                arrival = businessDate.plusDays(PRE_BOOKING_ARRIVAL_DAY),
                departure = businessDate.plusDays(PRE_BOOKING_DEPARTURE_DAY),
                adults = ADULTS,
            ),
        )
        return true
    }

    /**
     * A reserva só recebe a alocação e o fora de ordem enquanto está `CONFIRMED` e sem UH ou já na UH do seed;
     * cancelada, hospedada ou movida pela demo, não se mexe nela. Devolve se a reserva foi criada agora.
     */
    private suspend fun seedReservation(
        balcaoPlanId: String,
        resolved: Resolved,
        businessDate: LocalDate,
    ): Boolean {
        val found = gateway.findReservation(RESERVATION_ID)
        val ref =
            found
                ?: gateway.createReservation(
                    PendingReservationSpec(
                        externalId = RESERVATION_ID,
                        holderGuestId = resolved.holderGuestId,
                        roomTypeId = resolved.luxuryTypeId,
                        ratePlanId = balcaoPlanId,
                        origin = "FRONT_DESK",
                        arrival = businessDate.plusDays(RESERVATION_ARRIVAL_DAY),
                        departure = businessDate.plusDays(RESERVATION_DEPARTURE_DAY),
                        adults = ADULTS,
                    ),
                )
        val untouched = ref.status != CONFIRMED || (ref.roomId != null && ref.roomId != resolved.roomId)
        if (!untouched) {
            if (ref.roomId == null) gateway.assignRoom(ref.id, resolved.roomId)
            gateway.openOutOfOrder(
                resolved.roomId,
                businessDate.plusDays(RESERVATION_ARRIVAL_DAY),
                businessDate.plusDays(RESERVATION_DEPARTURE_DAY),
                OUT_OF_ORDER_REASON,
            )
        }
        return found == null
    }

    private companion object {
        const val HOLDER_KEY = "G12"
        const val STANDARD = "STD"
        const val LUXURY = "LUX"
        const val ROOM_NUMBER = "208"
        const val GUARANTEE_POLICY_NAME = "Garantia por cartão (demo)"
        const val PLAN_CODE = "GARANTIDA"
        const val PLAN_NAME = "Tarifa garantida"
        const val PRE_BOOKING_ID = "DEMO-PEND-P1"
        const val RESERVATION_ID = "DEMO-PEND-R1"
        const val OUT_OF_ORDER_REASON = "Manutenção (demo)"
        const val CONFIRMED = "CONFIRMED"
        const val ADULTS = 2
        const val PRICE_LAST_DAY = 59L
        const val PRE_BOOKING_ARRIVAL_DAY = 10L
        const val PRE_BOOKING_DEPARTURE_DAY = 12L
        const val RESERVATION_ARRIVAL_DAY = 5L
        const val RESERVATION_DEPARTURE_DAY = 7L
        val DAILY_RATE = BigDecimal("300.00")
    }
}
