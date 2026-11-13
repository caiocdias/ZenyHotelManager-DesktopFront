package com.zeny.desktop.seed.config

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.HousekeepingTarget
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigSeederTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val data = DemoCatalog.build(date)
    private val fake = FakeConfigGateway()

    private fun seeder(report: SeedReport = SeedReport()) = ConfigSeeder(fake, report)

    private fun target(number: String): HousekeepingTarget = data.rooms.first { it.number == number }.target

    private fun levelOf(room: RoomState) =
        when {
            room.housekeeping == "CLEAN" && room.inspectionPending -> HousekeepingTarget.CLEAN_PENDING_INSPECTION
            room.housekeeping == "CLEAN" -> HousekeepingTarget.CLEAN
            room.housekeeping == "INSPECTED" -> HousekeepingTarget.INSPECTED
            else -> HousekeepingTarget.DIRTY
        }

    @Test
    fun firstRunCreatesWholeCatalog() =
        runTest {
            seeder().seed(data, date)
            assertEquals(DemoCatalog.HOTEL_TRADE_NAME, fake.hotelName)
            assertEquals(3, fake.roomTypes.size)
            assertEquals(3, fake.areas.size)
            assertEquals(20, fake.roomStates.size)
            assertEquals(2, fake.seasons.size)
            assertEquals(1, fake.ratePlans.size)
            assertEquals(6, fake.priceWrites.size)
            assertEquals(listOf("210"), fake.outOfOrderOpened)
            fake.roomStates.values.forEach { assertEquals(target(it.number), levelOf(it), it.number) }
        }

    @Test
    fun secondRunCreatesNothing() =
        runTest {
            seeder().seed(data, date)
            val writes = fake.writeCalls
            val report = SeedReport()
            seeder(report).seed(data, date)
            assertEquals(writes, fake.writeCalls)
            assertEquals(20, fake.roomStates.size)
            assertEquals(12, fake.priceWrites.size)
            SeedItem.entries.forEach { assertEquals(0, report.createdCount(it), it.name) }
            assertEquals(1, report.keptCount(SeedItem.HOTEL))
            assertEquals(2, report.keptCount(SeedItem.POLICY))
            assertEquals(3, report.keptCount(SeedItem.ROOM_TYPE))
            assertEquals(20, report.keptCount(SeedItem.ROOM))
            assertEquals(6, report.keptCount(SeedItem.PRICES))
        }

    @Test
    fun resumesPartialRun() =
        runTest {
            seeder().seed(data, date)
            fake.ratePlans.clear()
            data.rooms.drop(10).forEach { fake.roomStates.remove(it.number) }
            val report = SeedReport()
            val seeded = seeder(report).seed(data, date)
            assertEquals(20, fake.roomStates.size)
            assertEquals(10, report.createdCount(SeedItem.ROOM))
            assertEquals(10, report.keptCount(SeedItem.ROOM))
            assertEquals(1, report.createdCount(SeedItem.RATE_PLAN))
            assertEquals(1, fake.ratePlans.size)
            assertEquals(fake.ratePlans.values.single(), seeded.ratePlanId)
            fake.roomStates.values.forEach { assertEquals(target(it.number), levelOf(it), it.number) }
        }

    @Test
    fun configuredDemoHotelIsNotOverwritten() =
        runTest {
            fake.hotelName = DemoCatalog.HOTEL_TRADE_NAME
            val report = SeedReport()
            seeder(report).seed(data, date)
            assertEquals(1, report.keptCount(SeedItem.HOTEL))
            assertEquals(0, report.createdCount(SeedItem.HOTEL))
        }

    @Test
    fun foreignHotelStopsBeforeAnyWrite() =
        runTest {
            fake.hotelName = "Hotel de Verdade"
            val failure = assertFailsWith<SeedFailure> { seeder().seed(data, date) }
            assertEquals(
                "Este servidor já tem outro hotel configurado; o demo-seed só roda num servidor da demo.",
                failure.message,
            )
            assertEquals(0, fake.writeCalls)
        }

    @Test
    fun occupiedRoomStatusUntouched() =
        runTest {
            seeder().seed(data, date)
            val room = fake.roomStates.getValue("101")
            fake.roomStates["101"] = room.copy(occupied = true, housekeeping = "DIRTY")
            val writes = fake.writeCalls
            seeder().seed(data, date)
            assertEquals("DIRTY", fake.roomStates.getValue("101").housekeeping)
            assertEquals(writes, fake.writeCalls)
        }

    @Test
    fun outOfOrderNotReopened() =
        runTest {
            seeder().seed(data, date)
            seeder().seed(data, date)
            assertEquals(listOf("210"), fake.outOfOrderOpened)
        }

    @Test
    fun returnsIdsByNaturalKey() =
        runTest {
            val seeded = seeder().seed(data, date)
            assertEquals(data.rooms.map { it.number }.toSet(), seeded.roomIds.keys)
            assertEquals(setOf("STD", "LUX", "SUI"), seeded.roomTypeIds.keys)
            assertEquals(fake.ratePlans.values.single(), seeded.ratePlanId)
            assertEquals(mapOf("STD" to 12, "LUX" to 6, "SUI" to 1), seeded.activeRoomsByType)
            assertEquals(mapOf("STD" to 0, "LUX" to 0, "SUI" to 1), seeded.outOfOrderRoomsByType)
            assertTrue(seeded.roomIds.values.all { it.startsWith("room-") })
        }
}
