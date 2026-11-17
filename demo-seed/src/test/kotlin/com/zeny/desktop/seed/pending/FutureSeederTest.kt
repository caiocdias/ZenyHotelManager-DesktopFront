package com.zeny.desktop.seed.pending

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoCatalog
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FutureSeederTest {
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
    private val gateway = FakeFutureGateway().apply { guests[holderCpf] = "guest-12" }

    private suspend fun seed() = FutureSeeder(gateway).seed(data, config, date)

    @Test
    fun firstRunCreatesThreeAndCancelsC1() =
        runTest {
            val result = seed()

            assertEquals(FutureSeedResult(created = 3, existing = 0), result)
            assertEquals(
                listOf(
                    "createReservation DEMO-FUT-F1",
                    "createReservation DEMO-FUT-F2",
                    "createReservation DEMO-FUT-C1",
                    "cancelReservation ${gateway.reservations.getValue("DEMO-FUT-C1").id}",
                ),
                gateway.calls,
            )
            val c1Id = gateway.reservations.getValue("DEMO-FUT-C1").id
            assertEquals(listOf(c1Id to "GUEST_REQUEST"), gateway.cancellations)
            assertEquals("CANCELLED", gateway.reservations.getValue("DEMO-FUT-C1").status)
            assertEquals("CONFIRMED", gateway.reservations.getValue("DEMO-FUT-F1").status)
            assertEquals("CONFIRMED", gateway.reservations.getValue("DEMO-FUT-F2").status)

            val (f1, f2, c1) = gateway.reservationSpecs
            assertTrue(gateway.reservationSpecs.all { it.holderGuestId == "guest-12" })
            assertTrue(gateway.reservationSpecs.all { it.ratePlanId == "plan-balcao" })
            assertTrue(gateway.reservationSpecs.all { it.adults == 2 })
            assertEquals("rt-std", f1.roomTypeId)
            assertEquals("FRONT_DESK", f1.origin)
            assertEquals(date.plusDays(14), f1.arrival)
            assertEquals(date.plusDays(17), f1.departure)
            assertEquals("rt-lux", f2.roomTypeId)
            assertEquals("PHONE", f2.origin)
            assertEquals(date.plusDays(21), f2.arrival)
            assertEquals(date.plusDays(23), f2.departure)
            assertEquals("rt-std", c1.roomTypeId)
            assertEquals("FRONT_DESK", c1.origin)
            assertEquals(date.plusDays(28), c1.arrival)
            assertEquals(date.plusDays(30), c1.departure)
        }

    @Test
    fun secondRunCreatesNothing() =
        runTest {
            seed()
            gateway.calls.clear()

            val result = seed()

            assertEquals(FutureSeedResult(created = 0, existing = 3), result)
            assertTrue(gateway.calls.isEmpty(), gateway.calls.toString())
            assertEquals(3, gateway.reservationSpecs.size)
            assertEquals(1, gateway.cancellations.size)
        }

    @Test
    fun foundReservationsAreNeverTouched() =
        runTest {
            gateway.reservations["DEMO-FUT-C1"] = PendingReservationRef("res-c1", "CONFIRMED", null)
            gateway.reservations["DEMO-FUT-F2"] = PendingReservationRef("res-f2", "CONFIRMED", "room-101")
            gateway.reservations["DEMO-FUT-F1"] = PendingReservationRef("res-f1", "CANCELLED", null)

            val result = seed()

            assertEquals(FutureSeedResult(created = 0, existing = 3), result)
            assertTrue(gateway.calls.isEmpty(), gateway.calls.toString())

            gateway.reservations["DEMO-FUT-C1"] = PendingReservationRef("res-c1", "CANCELLED", null)

            assertEquals(FutureSeedResult(created = 0, existing = 3), seed())
            assertTrue(gateway.calls.isEmpty(), gateway.calls.toString())
            assertEquals("CANCELLED", gateway.reservations.getValue("DEMO-FUT-C1").status)
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
    fun externalIdsUseFutPrefix() =
        runTest {
            seed()

            val ids = gateway.reservationSpecs.map { it.externalId }
            assertEquals(listOf("DEMO-FUT-F1", "DEMO-FUT-F2", "DEMO-FUT-C1"), ids)
            assertTrue(ids.none { it.startsWith("DEMO-SEED-") })
        }
}
