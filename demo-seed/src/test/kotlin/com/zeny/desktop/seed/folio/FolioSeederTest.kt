package com.zeny.desktop.seed.folio

import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FolioSeederTest {
    private val date = LocalDate.of(2026, 11, 19)
    private val data = DemoCatalog.build(date)
    private val seeded =
        SeededConfig(
            roomTypeIds = emptyMap(),
            roomIds = data.rooms.associate { it.number to "room-${it.number}" },
            ratePlanId = "plan-1",
            activeRoomsByType = emptyMap(),
            outOfOrderRoomsByType = emptyMap(),
        )
    private val gateway = FakeFolioGateway()
    private val report = SeedReport()
    private val inHouse = data.reservations.filter { it.checkIn }

    private fun plantInHouse() = inHouse.forEach { gateway.plant(it.externalId) }

    private suspend fun seed(into: SeedReport = report) = FolioSeeder(gateway, into).seed(data, seeded)

    @Test
    fun postsTwoConsumptionsOnEachInHouseFolio() =
        runTest {
            plantInHouse()
            seed()
            assertEquals(2, data.consumptions.size)
            assertTrue(data.consumptions.all { Regex("""\d+\.\d{2}""").matches(it.unitPrice) })
            assertEquals(inHouse.size * 2, gateway.postings.size)
            for (spec in inHouse) {
                val own = gateway.postings.filter { it.folioId == "folio-${spec.externalId}" }
                assertEquals(data.consumptions.map { "Seed M4: ${it.item}" }, own.map { it.description })
                assertEquals(data.consumptions.map { it.unitPrice }, own.map { it.unitPrice })
                assertTrue(own.all { it.codeId == "code-ab" })
            }
            assertEquals(DemoCatalog.CHARGEABLE_CODE_NAMES, gateway.acceptedNames)
        }

    @Test
    fun secondRunKeepsTheConsumptions() =
        runTest {
            plantInHouse()
            seed()
            val again = SeedReport()
            seed(again)
            assertEquals(inHouse.size * 2, gateway.postings.size)
            assertEquals(0, again.consumptionsCreated)
            assertEquals(inHouse.size * 2, again.consumptionsKept)
        }

    @Test
    fun noChargeableCodeWarnsAndGoesOn() =
        runTest {
            plantInHouse()
            gateway.codeId = null
            seed()
            assertTrue(gateway.postings.isEmpty())
            assertEquals(1, report.warnings().size)
            assertTrue(report.warnings().single().contains("taxa de serviço"), report.warnings().toString())
        }

    @Test
    fun reservationWithoutFolioIsSkippedWithAWarning() =
        runTest {
            plantInHouse()
            val skipped = inHouse.first()
            gateway.folios.remove("res-${skipped.externalId}")
            seed()
            assertEquals((inHouse.size - 1) * 2, gateway.postings.size)
            assertTrue(gateway.postings.none { it.folioId == "folio-${skipped.externalId}" })
            assertEquals(1, report.warnings().size)
            assertTrue(report.warnings().single().contains(skipped.slot), report.warnings().toString())
        }

    @Test
    fun reportCountsCreatedAndKept() =
        runTest {
            plantInHouse()
            val first = inHouse.first()
            val planted = "Seed M4: ${data.consumptions[0].item}"
            gateway.postings += PostedConsumption("folio-${first.externalId}", "code-ab", "1.00", planted)
            seed()
            val created = inHouse.size * 2 - 1
            assertEquals(created, report.consumptionsCreated)
            assertEquals(1, report.consumptionsKept)
            assertTrue(report.lines().contains("consumos: $created criados, 1 já existiam"), report.lines().toString())
        }
}
