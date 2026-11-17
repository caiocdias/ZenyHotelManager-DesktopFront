package com.zeny.desktop.seed.pending

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoCatalog
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingSeederTest {
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
    private val holderCpf = data.guests.first { it.key == "G12" }.cpf
    private val gateway =
        FakePendingGateway().apply {
            cancellationPolicies[data.cancellationPolicy.name] = "cp-1"
            guests[holderCpf] = "guest-12"
        }

    private suspend fun seed(config: SeededConfig = this.config) = PendingSeeder(gateway).seed(data, config, date)

    private fun plantR1(
        status: String,
        roomId: String?,
    ) {
        gateway.reservations["DEMO-PEND-R1"] = PendingReservationRef("res-r1", status, roomId)
    }

    @Test
    fun firstRunCreatesFourItemsInOrder() =
        runTest {
            val result = seed()

            assertEquals(PendingSeedResult(created = 4, existing = 0), result)
            assertEquals(
                listOf(
                    "createCardGuaranteePolicy",
                    "createWebsiteRatePlan",
                    "writePrices",
                    "createReservation DEMO-PEND-P1",
                    "createReservation DEMO-PEND-R1",
                    "assignRoom",
                    "openOutOfOrder",
                ),
                gateway.calls,
            )
            val plan = gateway.plans.single()
            assertEquals("GARANTIDA", plan.code)
            assertEquals("Tarifa garantida", plan.name)
            assertEquals(gateway.guaranteePolicies.getValue("Garantia por cartão (demo)"), plan.guaranteePolicyId)
            assertEquals("cp-1", plan.cancellationPolicyId)
            assertEquals("rt-std", plan.roomTypeId)
            val (priceplan, prices) = gateway.priceWrites.single()
            assertEquals(gateway.ratePlans.getValue("GARANTIDA"), priceplan)
            assertEquals("rt-std", prices.roomTypeId)
            assertEquals(BigDecimal("300.00"), prices.oneAdult)
            assertEquals(BigDecimal("300.00"), prices.twoAdults)
            assertEquals(date, prices.from)
            assertEquals(date.plusDays(59), prices.to)

            val (p1, r1) = gateway.reservationSpecs
            assertEquals("guest-12", p1.holderGuestId)
            assertEquals("rt-std", p1.roomTypeId)
            assertEquals(gateway.ratePlans.getValue("GARANTIDA"), p1.ratePlanId)
            assertEquals("WEBSITE", p1.origin)
            assertEquals(date.plusDays(10), p1.arrival)
            assertEquals(date.plusDays(12), p1.departure)
            assertEquals(2, p1.adults)
            assertEquals("guest-12", r1.holderGuestId)
            assertEquals("rt-lux", r1.roomTypeId)
            assertEquals("plan-balcao", r1.ratePlanId)
            assertEquals("FRONT_DESK", r1.origin)
            assertEquals(date.plusDays(5), r1.arrival)
            assertEquals(date.plusDays(7), r1.departure)
            assertEquals(2, r1.adults)

            assertEquals(listOf(gateway.reservations.getValue("DEMO-PEND-R1").id to "room-208"), gateway.assignments)
            assertEquals(
                listOf(OutOfOrderCall("room-208", date.plusDays(5), date.plusDays(7), "Manutenção (demo)")),
                gateway.outOfOrders,
            )
        }

    @Test
    fun secondRunCreatesNothingButRewritesPrices() =
        runTest {
            seed()
            gateway.calls.clear()

            val result = seed()

            assertEquals(PendingSeedResult(created = 0, existing = 4), result)
            assertEquals(listOf("writePrices", "openOutOfOrder"), gateway.calls)
            assertEquals(2, gateway.priceWrites.size)
            assertEquals(1, gateway.plans.size)
            assertEquals(1, gateway.outOfOrders.size)
        }

    @Test
    fun interruptedRunResumes() =
        runTest {
            plantR1("CONFIRMED", null)

            seed()

            assertEquals(listOf("res-r1" to "room-208"), gateway.assignments)
            assertEquals(1, gateway.outOfOrders.size)
            assertFalse(gateway.calls.contains("createReservation DEMO-PEND-R1"))
        }

    @Test
    fun interruptedRunAfterAssignmentOnlyOpensOutOfOrder() =
        runTest {
            plantR1("CONFIRMED", "room-208")

            seed()

            assertTrue(gateway.assignments.isEmpty())
            assertEquals(1, gateway.outOfOrders.size)
        }

    @Test
    fun rerunAfterCancelOrMoveDoesNothing() =
        runTest {
            for ((status, roomId) in listOf("CANCELLED" to null, "IN_HOUSE" to "room-208", "CONFIRMED" to "room-109")) {
                gateway.reservations.clear()
                gateway.assignments.clear()
                gateway.outOfOrders.clear()
                plantR1(status, roomId)

                val result = seed()

                assertTrue(gateway.assignments.isEmpty(), status)
                assertTrue(gateway.outOfOrders.isEmpty(), status)
                assertEquals(status, gateway.reservations.getValue("DEMO-PEND-R1").status)
                assertTrue(result.existing >= 1, status)
            }
        }

    @Test
    fun existingPreBookingCountsWhateverItsStatus() =
        runTest {
            gateway.reservations["DEMO-PEND-P1"] = PendingReservationRef("res-p1", "CANCELLED", null)

            val result = seed()

            assertEquals(PendingSeedResult(created = 3, existing = 1), result)
            assertFalse(gateway.calls.contains("createReservation DEMO-PEND-P1"))
        }

    @Test
    fun outOfOrderOverlapCountsAsDone() =
        runTest {
            gateway.outOfOrders += OutOfOrderCall("room-208", date.plusDays(5), date.plusDays(7), "Outro motivo")

            val result = seed()

            assertEquals(PendingSeedResult(created = 4, existing = 0), result)
            assertEquals(1, gateway.outOfOrders.size)
        }

    @Test
    fun missingCancellationPolicyFailsClearly() =
        runTest {
            gateway.cancellationPolicies.clear()

            val failure = assertFailsWith<SeedFailure> { seed() }

            assertTrue(failure.message.orEmpty().contains("Flexível (demo)"), failure.message)
            assertTrue(gateway.calls.isEmpty())
        }

    @Test
    fun missingGuestFailsClearly() =
        runTest {
            gateway.guests.clear()

            val failure = assertFailsWith<SeedFailure> { seed() }

            val message = failure.message.orEmpty()
            assertTrue(message.contains("Hóspede", ignoreCase = true), message)
            assertFalse(message.contains(holderCpf), message)
            assertFalse(message.contains("Doze"), message)
            assertTrue(gateway.calls.isEmpty())
        }

    @Test
    fun missingRoomFailsClearly() =
        runTest {
            val failure = assertFailsWith<SeedFailure> { seed(config.copy(roomIds = config.roomIds - "208")) }

            assertTrue(failure.message.orEmpty().contains("208"), failure.message)
            assertTrue(gateway.calls.isEmpty())
        }

    @Test
    fun externalIdsStayOutOfDemoSeedPrefix() =
        runTest {
            seed()

            val ids = gateway.reservationSpecs.map { it.externalId }
            assertEquals(listOf("DEMO-PEND-P1", "DEMO-PEND-R1"), ids)
            assertTrue(ids.none { it.startsWith("DEMO-SEED-") })
        }
}
