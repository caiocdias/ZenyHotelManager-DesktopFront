package com.zeny.desktop.seed.pending

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoCatalog
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Ataque ao `PendingSeeder` da M3 E04: a `R1` que a demo levou a qualquer situação fora de `CONFIRMED` não é tocada e a
 * contagem fica exata; uma recusa do fora de ordem que não é a sobreposição sobe; a segunda execução não escreve nada
 * além dos preços.
 */
class PendingSeederAtaqueTest {
    private val date = LocalDate.of(2026, 11, 16)
    private val data = DemoCatalog.build(date)
    private val config =
        SeededConfig(
            roomTypeIds = mapOf("STD" to "rt-std", "LUX" to "rt-lux", "SUI" to "rt-sui"),
            roomIds = data.rooms.associate { it.number to "room-${it.number}" },
            ratePlanId = "plan-balcao",
            activeRoomsByType = emptyMap(),
            outOfOrderRoomsByType = emptyMap(),
        )

    private fun gateway() =
        FakePendingGateway().apply {
            cancellationPolicies[data.cancellationPolicy.name] = "cp-1"
            guests[data.guests.first { it.key == "G12" }.cpf] = "guest-12"
        }

    @Test
    fun r1LeftInAnyOtherSituationIsNotTouchedAndTheCountIsExact() =
        runTest {
            for (status in listOf("PROVISIONAL", "IN_HOUSE", "CHECKED_OUT", "CANCELLED", "NO_SHOW", "OUTRA")) {
                val gateway = gateway()
                PendingSeeder(gateway).seed(data, config, date)
                gateway.reservations["DEMO-PEND-R1"] = PendingReservationRef("res-r1", status, null)
                gateway.calls.clear()

                val result = PendingSeeder(gateway).seed(data, config, date)

                assertEquals(PendingSeedResult(created = 0, existing = 4), result, status)
                assertEquals(listOf("writePrices"), gateway.calls, "só os preços são regravados com a R1 $status")
            }
        }

    @Test
    fun secondRunWritesOnlyThePricesAndTheSamePrices() =
        runTest {
            val gateway = gateway()
            PendingSeeder(gateway).seed(data, config, date)
            val firstPrices = gateway.priceWrites.single()
            gateway.calls.clear()

            val result = PendingSeeder(gateway).seed(data, config, date)

            assertEquals(PendingSeedResult(created = 0, existing = 4), result)
            // A R1 confirmada na 208 repete o fora de ordem, que o servidor responde como sobreposição.
            assertEquals(listOf("writePrices", "openOutOfOrder"), gateway.calls)
            assertEquals(firstPrices, gateway.priceWrites.last())
            assertEquals(1, gateway.outOfOrders.size)
            assertEquals(1, gateway.assignments.size)
            assertEquals(2, gateway.reservationSpecs.size)
        }

    @Test
    fun outOfOrderRefusalOtherThanOverlapPropagates() =
        runTest {
            val refusal = ApiError.Problem(409, "ROOM_NOT_AVAILABLE", "UH indisponível", emptyList())
            val inner = gateway()
            val gateway =
                object : PendingGateway by inner {
                    override suspend fun openOutOfOrder(
                        roomId: String,
                        from: LocalDate,
                        to: LocalDate,
                        reason: String,
                    ): Boolean = throw refusal
                }

            val failure = assertFailsWith<ApiError.Problem> { PendingSeeder(gateway).seed(data, config, date) }

            assertSame(refusal, failure)
            assertTrue(inner.calls.contains("assignRoom"))
        }

    @Test
    fun preBookingIsAWebsiteBookingOnTheGuaranteedPlanWithPricesBeforeIt() =
        runTest {
            val gateway = gateway()

            PendingSeeder(gateway).seed(data, config, date)

            val plan = gateway.plans.single()
            val planId = gateway.ratePlans.getValue(plan.code)
            val (pricedPlan, prices) = gateway.priceWrites.single()
            assertEquals(planId, pricedPlan)
            assertEquals("rt-std", prices.roomTypeId)
            assertEquals(date, prices.from)
            assertTrue(prices.to >= date.plusDays(12), "o preço cobre a estadia da P1")
            val p1 = gateway.reservationSpecs.first { it.externalId == "DEMO-PEND-P1" }
            assertEquals(planId, p1.ratePlanId)
            assertEquals("WEBSITE", p1.origin)
            assertEquals("rt-std", p1.roomTypeId)
            val r1 = gateway.reservationSpecs.first { it.externalId == "DEMO-PEND-R1" }
            assertEquals("plan-balcao", r1.ratePlanId)
            assertEquals("rt-lux", r1.roomTypeId)
            val ooo = gateway.outOfOrders.single()
            assertEquals("room-208", ooo.roomId)
            assertEquals(r1.arrival, ooo.from, "o fora de ordem cobre a estadia da R1")
            assertEquals(r1.departure, ooo.to)
            assertTrue(
                gateway.calls.indexOf("writePrices") < gateway.calls.indexOf("createReservation DEMO-PEND-P1"),
                "os preços vêm antes da pré-reserva",
            )
        }
}
