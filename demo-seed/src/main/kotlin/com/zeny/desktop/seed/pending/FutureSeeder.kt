package com.zeny.desktop.seed.pending

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoData
import java.time.LocalDate

/** O que uma execução fez: reservas criadas e reservas que já existiam. */
data class FutureSeedResult(
    val created: Int,
    val existing: Int,
)

/**
 * Semeia duas reservas futuras confirmadas e uma cancelada, todas do hóspede de exemplo das pendências. Confere
 * antes de criar, pelo `externalId`: uma reserva achada nunca é tocada, qualquer que seja a situação, e só a
 * cancelada criada nesta execução é cancelada (o seed não distingue uma execução interrompida de uma reativação
 * feita na demo sem ler o histórico).
 */
class FutureSeeder(
    private val gateway: FutureGateway,
) {
    private class Plan(
        val externalId: String,
        val roomType: String,
        val arrivalDay: Long,
        val departureDay: Long,
        val origin: String,
        val cancelAfterCreate: Boolean,
    )

    suspend fun seed(
        data: DemoData,
        config: SeededConfig,
        businessDate: LocalDate,
    ): FutureSeedResult {
        val holderCpf = data.guests.first { it.key == HOLDER_KEY }.cpf
        val holderId =
            gateway.guestId(holderCpf)
                ?: throw SeedFailure(
                    "Hóspede de exemplo das reservas futuras não encontrado; o demo-seed não as cria sem ele.",
                )
        val typeIds = PLANS.associate { it.roomType to (config.roomTypeIds[it.roomType] ?: missing(it.roomType)) }
        var created = 0
        var existing = 0
        for (plan in PLANS) {
            if (gateway.findReservation(plan.externalId) != null) {
                existing++
                continue
            }
            val ref =
                gateway.createReservation(
                    PendingReservationSpec(
                        externalId = plan.externalId,
                        holderGuestId = holderId,
                        roomTypeId = typeIds.getValue(plan.roomType),
                        ratePlanId = config.ratePlanId,
                        origin = plan.origin,
                        arrival = businessDate.plusDays(plan.arrivalDay),
                        departure = businessDate.plusDays(plan.departureDay),
                        adults = ADULTS,
                    ),
                )
            if (plan.cancelAfterCreate) gateway.cancelReservation(ref.id, CANCEL_REASON)
            created++
        }
        return FutureSeedResult(created, existing)
    }

    private fun missing(roomType: String): Nothing =
        throw SeedFailure("Tipo de UH $roomType não encontrado; o demo-seed não cria reservas futuras sem ele.")

    private companion object {
        const val HOLDER_KEY = "G12"
        const val ADULTS = 2
        const val CANCEL_REASON = "GUEST_REQUEST"
        val PLANS =
            listOf(
                Plan("DEMO-FUT-F1", "STD", 14, 17, "FRONT_DESK", cancelAfterCreate = false),
                Plan("DEMO-FUT-F2", "LUX", 21, 23, "PHONE", cancelAfterCreate = false),
                Plan("DEMO-FUT-C1", "STD", 28, 30, "FRONT_DESK", cancelAfterCreate = true),
            )
    }
}
