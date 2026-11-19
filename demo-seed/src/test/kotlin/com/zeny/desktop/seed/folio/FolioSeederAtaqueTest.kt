package com.zeny.desktop.seed.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Ataque ao `FolioSeeder` (M4 E05): repetição, retomada após falha, sem código, sem conta e recusa do servidor. */
class FolioSeederAtaqueTest {
    private val data = DemoCatalog.build(LocalDate.of(2026, 11, 19))
    private val seeded =
        SeededConfig(
            roomTypeIds = emptyMap(),
            roomIds = emptyMap(),
            ratePlanId = "plan-1",
            activeRoomsByType = emptyMap(),
            outOfOrderRoomsByType = emptyMap(),
        )
    private val gateway = FakeFolioGateway()
    private val inHouse = data.reservations.filter { it.checkIn }
    private val perFolio = data.consumptions.size

    private fun plantInHouse() = inHouse.forEach { gateway.plant(it.externalId) }

    private suspend fun seed(report: SeedReport = SeedReport()): SeedReport {
        FolioSeeder(gateway, report).seed(data, seeded)
        return report
    }

    @Test
    fun theCatalogHasInHouseReservationsAndConsumptions() {
        assertTrue(inHouse.isNotEmpty(), "sem reservas hospedadas o seeder não testa nada")
        assertTrue(perFolio > 0)
    }

    @Test
    fun runningThreeTimesStillLeavesExactlyOneOfEachConsumptionPerFolio() =
        runTest {
            plantInHouse()
            seed()
            seed()
            val third = seed()
            for (spec in inHouse) {
                val own = gateway.postings.filter { it.folioId == "folio-${spec.externalId}" }.map { it.description }
                assertEquals(data.consumptions.map { "Seed M4: ${it.item}" }, own)
            }
            assertEquals(0, third.consumptionsCreated)
            assertEquals(inHouse.size * perFolio, third.consumptionsKept)
        }

    @Test
    fun aRefusedPostingSurfacesTheErrorAndIsNotCountedAsCreated() =
        runTest {
            plantInHouse()
            gateway.failures += ApiError.Problem(422, "ALGUM_CODIGO", null, emptyList())
            val report = SeedReport()
            val error = assertFailsWith<ApiError.Problem> { seed(report) }
            assertEquals(422, error.status)
            assertTrue(gateway.postings.isEmpty())
            assertEquals(0, report.consumptionsCreated)
        }

    @Test
    fun rerunAfterARefusalCompletesWithoutDuplicatingWhatWasPosted() =
        runTest {
            plantInHouse()
            // a segunda tentativa de lançamento falha: o primeiro consumo da primeira conta já está lançado
            gateway.failures += ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList())
            val already = "Seed M4: ${data.consumptions[0].item}"
            gateway.postings += PostedConsumption("folio-${inHouse.first().externalId}", "code-ab", "1.00", already)
            assertFailsWith<ApiError.Problem> { seed() }
            val report = seed()
            for (spec in inHouse) {
                val own = gateway.postings.filter { it.folioId == "folio-${spec.externalId}" }.map { it.description }
                assertEquals(data.consumptions.map { "Seed M4: ${it.item}" }.sorted(), own.sorted(), spec.slot)
            }
            assertEquals(inHouse.size * perFolio, gateway.postings.size)
            assertEquals(inHouse.size * perFolio - 1, report.consumptionsCreated + 0)
            assertEquals(1, report.consumptionsKept)
        }

    @Test
    fun withoutAFeeCodeNothingIsPostedEvenOnRepeatAndEachRunWarnsOnce() =
        runTest {
            plantInHouse()
            gateway.codeId = null
            val first = seed()
            val second = seed()
            assertTrue(gateway.postings.isEmpty())
            assertEquals(1, first.warnings().size)
            assertEquals(1, second.warnings().size)
            assertEquals(0, first.consumptionsCreated + first.consumptionsKept)
            assertTrue(first.lines().none { it.startsWith("consumos:") }, first.lines().toString())
        }

    @Test
    fun withoutAFeeCodeAndWithoutReservationsItStillOnlyWarns() =
        runTest {
            gateway.codeId = null
            val report = seed()
            assertEquals(1, report.warnings().size)
            assertTrue(gateway.postings.isEmpty())
        }

    @Test
    fun reservationsThatAreNotOnTheServerWarnOnePerReservationAndPostNothing() =
        runTest {
            val report = seed()
            assertTrue(gateway.postings.isEmpty())
            assertEquals(inHouse.size, report.warnings().size)
            inHouse.forEach { spec ->
                assertTrue(report.warnings().any { spec.slot in it }, "${spec.slot}: ${report.warnings()}")
            }
        }

    @Test
    fun reservationsWithoutAnyFolioWarnAndTheRunDoesNotThrow() =
        runTest {
            inHouse.forEach { gateway.plant(it.externalId, withFolio = false) }
            val report = seed()
            assertTrue(gateway.postings.isEmpty())
            assertEquals(inHouse.size, report.warnings().size)
            assertTrue(report.lines().none { it.startsWith("consumos:") }, report.lines().toString())
        }

    @Test
    fun reservationsThatAreNotInHouseGetNoConsumption() =
        runTest {
            data.reservations.forEach { gateway.plant(it.externalId) }
            seed()
            val inHouseFolios = inHouse.map { "folio-${it.externalId}" }.toSet()
            assertTrue(gateway.postings.all { it.folioId in inHouseFolios }, gateway.postings.toString())
            assertEquals(inHouse.size * perFolio, gateway.postings.size)
        }
}
