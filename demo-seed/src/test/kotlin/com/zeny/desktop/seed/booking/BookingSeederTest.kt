package com.zeny.desktop.seed.booking

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BookingSeederTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val data = DemoCatalog.build(date)
    private val config =
        SeededConfig(
            roomTypeIds = mapOf("STD" to "rt-std", "LUX" to "rt-lux", "SUI" to "rt-sui"),
            roomIds = data.rooms.associate { it.number to "room-${it.number}" },
            ratePlanId = "plan-1",
            activeRoomsByType = mapOf("STD" to 12, "LUX" to 6, "SUI" to 1),
            outOfOrderRoomsByType = mapOf("STD" to 0, "LUX" to 0, "SUI" to 1),
        )
    private val fake = FakeBookingGateway(config, date)
    private val report = SeedReport()

    private fun seeder(rep: SeedReport = report) = BookingSeeder(fake, rep)

    private fun spec(slot: String) = data.reservations.first { it.slot == slot }

    private fun ref(slot: String) = fake.reservations.getValue(spec(slot).externalId)

    private fun holderId(slot: String) =
        fake.guests.getValue(data.guests.first { it.key == spec(slot).holderGuestKey }.cpf)

    @Test
    fun firstRunCreatesAll() =
        runTest {
            seeder().seed(data, config)
            assertEquals(2, fake.companies.size)
            assertEquals(12, fake.guests.size)
            assertEquals(11, fake.reservations.size)
            assertEquals(9, fake.assignments)
            assertEquals(7, fake.checkIns.size)
            assertEquals(2, report.createdCount(SeedItem.COMPANY))
            assertEquals(12, report.createdCount(SeedItem.GUEST))
            assertEquals(11, report.createdCount(SeedItem.RESERVATION))
            assertEquals(9, report.createdCount(SeedItem.ROOM_ASSIGNMENT))
            assertEquals(7, report.createdCount(SeedItem.CHECK_IN))
            assertEquals(4, fake.reservations.values.count { it.status == "CONFIRMED" })
        }

    @Test
    fun secondRunCreatesNothing() =
        runTest {
            seeder().seed(data, config)
            val reads = fake.inventoryReads
            val creates = fake.createReservationCalls
            val second = SeedReport()
            seeder(second).seed(data, config)
            assertEquals(reads, fake.inventoryReads)
            assertEquals(creates, fake.createReservationCalls)
            assertEquals(9, fake.assignments)
            assertEquals(7, fake.checkIns.size)
            SeedItem.entries.forEach { assertEquals(0, second.createdCount(it), it.name) }
            assertEquals(11, second.keptCount(SeedItem.RESERVATION))
        }

    @Test
    fun waitsForInventory() =
        runTest {
            fake.inventoryStaleReads = 3
            seeder().seed(data, config)
            assertEquals(11, fake.reservations.size)
            assertTrue(currentTime >= 500 + 1000 + 2000, "esperou em tempo virtual: $currentTime")
        }

    @Test
    fun inventoryTimeoutFails() =
        runTest {
            fake.inventoryStaleReads = Int.MAX_VALUE
            val failure = assertFailsWith<SeedFailure> { seeder().seed(data, config) }
            assertEquals(
                "O servidor não refez o inventário de UHs a tempo; rode o demo-seed de novo em alguns segundos.",
                failure.message,
            )
            assertTrue(currentTime >= 60_000, "esperou o limite virtual: $currentTime")
            assertEquals(0, fake.reservations.size)
            assertEquals(0, fake.createReservationCalls)
        }

    @Test
    fun retriesUnavailableOnce() =
        runTest {
            fake.createFailures += ApiError.Problem(409, "ROOM_TYPE_UNAVAILABLE", null, emptyList())
            seeder().seed(data, config)
            assertEquals(11, fake.reservations.size)
            assertEquals(12, fake.createReservationCalls)
        }

    @Test
    fun confirmedWithoutCheckInIsCheckedIn() =
        runTest {
            seeder().seed(data, config)
            val key = spec("H1").externalId
            fake.reservations[key] = ref("H1").copy(status = "CONFIRMED")
            fake.checkIns.clear()
            seeder(SeedReport()).seed(data, config)
            assertEquals(listOf(ref("H1").id), fake.checkIns.map { it.reservationId })
            assertEquals("IN_HOUSE", ref("H1").status)
        }

    @Test
    fun confirmedWithoutRoomIsAssignedThenCheckedIn() =
        runTest {
            seeder().seed(data, config)
            val key = spec("H2").externalId
            fake.reservations[key] = ref("H2").copy(status = "CONFIRMED", roomId = null)
            val assignments = fake.assignments
            fake.checkIns.clear()
            seeder(SeedReport()).seed(data, config)
            assertEquals(assignments + 1, fake.assignments)
            assertEquals("room-104", ref("H2").roomId)
            assertEquals("IN_HOUSE", ref("H2").status)
            assertEquals(1, fake.checkIns.size)
        }

    @Test
    fun cancelledReservationIsKeptNotRecreated() =
        runTest {
            seeder().seed(data, config)
            val key = spec("A1").externalId
            fake.reservations[key] = ref("A1").copy(status = "CANCELLED", roomId = null)
            val creates = fake.createReservationCalls
            val assignments = fake.assignments
            val second = SeedReport()
            seeder(second).seed(data, config)
            assertEquals(creates, fake.createReservationCalls)
            assertEquals(assignments, fake.assignments)
            assertEquals("CANCELLED", ref("A1").status)
            assertEquals(0, second.createdCount(SeedItem.RESERVATION))
            assertEquals(11, second.keptCount(SeedItem.RESERVATION))
        }

    @Test
    fun checkInSendsHolderAsPrimary() =
        runTest {
            seeder().seed(data, config)
            val call = fake.checkIns.first { it.reservationId == ref("H3").id }
            assertEquals(listOf(holderId("H3")), call.guestIds)
            assertEquals("room-205", call.roomId)
        }

    @Test
    fun guestWithCompanyLinked() =
        runTest {
            seeder().seed(data, config)
            val guest = data.guests.first { it.key == "G1" }
            val company = fake.companies.getValue(guest.companyCnpj!!)
            assertEquals(company, fake.guestCompany[fake.guests.getValue(guest.cpf)])
            val free = data.guests.first { it.companyCnpj == null }
            assertEquals(null, fake.guestCompany[fake.guests.getValue(free.cpf)])
        }

    @Test
    fun otherProblemIsNotRetried() =
        runTest {
            fake.createFailures += ApiError.Problem(422, "VALIDATION_FAILED", null, emptyList())
            assertFailsWith<ApiError.Problem> { seeder().seed(data, config) }
            assertEquals(1, fake.createReservationCalls)
            assertEquals(0, currentTime)
        }

    @Test
    fun interruptedRunIsResumedWithoutDuplicates() =
        runTest {
            fake.createFailures += ApiError.Problem(422, "VALIDATION_FAILED", null, emptyList())
            fake.createFailures += ApiError.Problem(422, "VALIDATION_FAILED", null, emptyList())
            assertFailsWith<ApiError.Problem> { seeder().seed(data, config) }
            assertFailsWith<ApiError.Problem> { seeder().seed(data, config) }
            seeder().seed(data, config)
            assertEquals(2, fake.companies.size)
            assertEquals(12, fake.guests.size)
            assertEquals(11, fake.reservations.size)
            assertEquals(7, fake.checkIns.size)
        }

    @Test
    fun reportHasNoGuestData() =
        runTest {
            seeder().seed(data, config)
            val text = report.lines().joinToString("\n")
            data.guests.forEach {
                assertTrue(it.fullName !in text && it.cpf !in text)
            }
            data.companies.forEach {
                assertTrue(it.cnpj !in text && it.legalName !in text && it.tradeName !in text)
            }
        }
}
