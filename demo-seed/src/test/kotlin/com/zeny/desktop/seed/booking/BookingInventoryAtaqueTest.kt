package com.zeny.desktop.seed.booking

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class BookingInventoryAtaqueTest : BookingAtaqueBase() {
    // ---- espera do inventário ----

    @Test
    fun inventoryReadyOnNthReadWaitsExactlyTheBackoff() {
        val expected = mapOf(1 to 0L, 2 to 500L, 3 to 1500L, 4 to 3500L, 5 to 7500L, 6 to 12500L)
        for ((ready, millis) in expected) {
            val f = FakeBookingGateway(config, date)
            runTest {
                val p = Probe(f) { currentTime }
                p.transform = { type, n, rows -> if (type == "rt-std" && n < ready) emptyList() else rows }
                BookingSeeder(p, SeedReport()).seed(data, config)
                assertEquals(millis, currentTime, "pronto na leitura $ready")
                assertEquals(ready, p.inventoryReads.count { it.typeId == "rt-std" })
                assertEquals(11, f.reservations.size)
            }
        }
    }

    @Test
    fun noReservationIsCreatedBeforeInventoryIsReady() =
        runTest {
            val p = probe()
            p.transform = { type, n, rows -> if (type == "rt-std" && n < 4) emptyList() else rows }
            BookingSeeder(p, report).seed(data, config)
            val firstCreate = p.events.indexOfFirst { it.startsWith("createReservation:") }
            assertEquals(4, p.events.take(firstCreate).count { it == "inventory:rt-std" })
        }

    @Test
    fun inventoryIsReadForEachTypeBeforeItsFirstCreation() =
        runTest {
            val p = probe()
            BookingSeeder(p, report).seed(data, config)
            for (type in listOf("rt-std", "rt-lux", "rt-sui")) {
                val firstRead = p.events.indexOf("inventory:$type")
                val firstCreate = p.events.indexOfFirst { it == "createReservation:$type" }
                assertTrue(firstRead in 0 until firstCreate, "$type: leitura $firstRead, criação $firstCreate")
            }
        }

    @Test
    fun inventoryRangeCoversArrivalToLastNightPerType() =
        runTest {
            val p = probe()
            BookingSeeder(p, report).seed(data, config)
            val byType = p.inventoryReads.associateBy { it.typeId }
            assertEquals(date, byType.getValue("rt-std").from)
            assertEquals(date.plusDays(2), byType.getValue("rt-std").to)
            assertEquals(date.plusDays(3), byType.getValue("rt-lux").to)
            assertEquals(date.plusDays(1), byType.getValue("rt-sui").to)
            assertTrue(p.inventoryReads.all { it.from == date })
        }

    @Test
    fun waitsWhenLastNightIsMissing() =
        runTest {
            val p = probe()
            p.transform = { type, n, rows -> if (type == "rt-lux" && n < 3) rows.dropLast(1) else rows }
            BookingSeeder(p, report).seed(data, config)
            assertEquals(3, p.inventoryReads.count { it.typeId == "rt-lux" })
            assertEquals(1500, currentTime)
        }

    @Test
    fun waitsWhenFirstOrMiddleDateIsMissing() =
        runTest {
            val p = probe()
            p.transform = { type, n, rows ->
                when {
                    type != "rt-lux" || n >= 4 -> rows
                    n == 1 -> rows.drop(1)
                    n == 2 -> rows.filterIndexed { i, _ -> i != 1 }
                    else -> emptyList()
                }
            }
            BookingSeeder(p, report).seed(data, config)
            assertEquals(4, p.inventoryReads.count { it.typeId == "rt-lux" })
            assertEquals(3500, currentTime)
        }

    @Test
    fun waitsWhenTotalIsBelowActiveRooms() =
        runTest {
            val p = probe()
            p.transform = { type, n, rows ->
                if (type == "rt-std" && n < 3) rows.map { it.copy(total = 11) } else rows
            }
            BookingSeeder(p, report).seed(data, config)
            assertEquals(3, p.inventoryReads.count { it.typeId == "rt-std" })
            assertEquals(11, fake.reservations.size)
        }

    @Test
    fun waitsWhenTotalIsBelowActiveOnlyOnTheLastDate() =
        runTest {
            val p = probe()
            p.transform = { type, n, rows ->
                if (type == "rt-std" && n < 2) rows.dropLast(1) + rows.last().copy(total = 0) else rows
            }
            BookingSeeder(p, report).seed(data, config)
            assertEquals(2, p.inventoryReads.count { it.typeId == "rt-std" })
        }

    @Test
    fun waitsWhenOutOfOrderIsNotYetReflected() =
        runTest {
            val p = probe()
            p.transform = { type, n, rows ->
                if (type == "rt-sui" && n < 3) rows.map { it.copy(outOfOrder = 0) } else rows
            }
            BookingSeeder(p, report).seed(data, config)
            assertEquals(3, p.inventoryReads.count { it.typeId == "rt-sui" })
            assertEquals(1500, currentTime)
            assertEquals(11, fake.reservations.size)
        }

    @Test
    fun readyWithExtraRowsOutsideRange() =
        runTest {
            val p = probe()
            p.transform = { _, _, rows ->
                listOf(InventoryDay(date.minusDays(1), 0, 0)) + rows + InventoryDay(date.plusDays(30), 0, 0)
            }
            BookingSeeder(p, report).seed(data, config)
            assertEquals(0, currentTime)
            assertEquals(3, p.inventoryReads.size)
        }

    @Test
    fun timeoutFailsWithExactMessageAfterSixtySecondsAndNoPersonalData() =
        runTest {
            val p = probe()
            p.transform = { _, _, _ -> emptyList() }
            val failure = assertFailsWith<SeedFailure> { BookingSeeder(p, report).seed(data, config) }
            assertEquals(
                "O servidor não refez o inventário de UHs a tempo; rode o demo-seed de novo em alguns segundos.",
                failure.message,
            )
            assertTrue(currentTime >= 60_000, "falhou cedo demais: $currentTime")
            assertTrue(currentTime <= 60_000 + 5_000, "passou do limite mais de uma espera máxima: $currentTime")
            assertEquals(0, p.createdSpecs.size)
            assertEquals(0, fake.reservations.size)
            assertEquals(0, report.createdCount(SeedItem.RESERVATION))
        }

    @Test
    fun timeoutEqualToBackoffSumFailsAtExactlyThatTimeWithFourReads() =
        runTest {
            val p = probe()
            p.transform = { _, _, _ -> emptyList() }
            assertFailsWith<SeedFailure> { BookingSeeder(p, report, 3500.milliseconds).seed(data, config) }
            assertEquals(3500, currentTime)
            assertEquals(4, p.inventoryReads.size)
        }

    @Test
    fun inventoryReadyOnTheReadRightAtTheLimitSucceeds() =
        runTest {
            val p = probe()
            p.transform = { type, n, rows -> if (type == "rt-std" && n < 4) emptyList() else rows }
            BookingSeeder(p, report, 3500.milliseconds).seed(data, config)
            assertEquals(11, fake.reservations.size)
            assertEquals(3500, currentTime)
        }

    @Test
    fun timeoutIsASeedFailureNotAnApiError() =
        runTest {
            val p = probe()
            p.transform = { _, _, _ -> emptyList() }
            val e = assertFailsWith<Throwable> { BookingSeeder(p, report, 1.milliseconds).seed(data, config) }
            assertTrue(e is SeedFailure, "veio ${e::class}")
        }

    @Test
    fun backoffStepsDoubleFromHalfASecondAndNeverExceedFiveSeconds() =
        runTest {
            val p = probe()
            p.transform = { type, n, rows -> if (type == "rt-std" && n < 12) emptyList() else rows }
            BookingSeeder(p, report).seed(data, config)
            val times = p.inventoryReads.filter { it.typeId == "rt-std" }.map { it.at }
            val steps = times.zipWithNext { a, b -> b - a }
            assertEquals(listOf(500L, 1000L, 2000L), steps.take(3))
            assertTrue(steps.all { it in 500L..5000L }, "$steps")
            assertEquals(steps.sorted(), steps, "espera não pode diminuir: $steps")
            assertEquals(5000L, steps.last())
        }

    @Test
    fun longWaitDoesNotBlockRealTime() {
        val started = System.nanoTime()
        runTest {
            val p = probe()
            p.transform = { _, _, _ -> emptyList() }
            assertFailsWith<SeedFailure> { BookingSeeder(p, report).seed(data, config) }
            assertTrue(currentTime >= 60_000)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 20_000, "60 s virtuais levaram $elapsedMs ms reais: Thread.sleep?")
    }

    @Test
    fun onlyPendingTypesAreAwaitedAndOnlyOverTheirRange() =
        runTest {
            val p = probe()
            BookingSeeder(p, SeedReport()).seed(data, config)
            fake.reservations.remove(spec("H5").externalId)
            p.inventoryReads.clear()
            BookingSeeder(p, SeedReport()).seed(data, config)
            assertEquals(listOf("rt-sui"), p.inventoryReads.map { it.typeId })
            assertEquals(date, p.inventoryReads.single().from)
            assertEquals(date.plusDays(1), p.inventoryReads.single().to)
        }

    @Test
    fun dayUseOnlyPendingReadsJustTheArrivalDate() =
        runTest {
            val p = probe()
            BookingSeeder(p, SeedReport()).seed(data, config)
            fake.reservations.remove(spec("S2").externalId)
            p.inventoryReads.clear()
            BookingSeeder(p, SeedReport()).seed(data, config)
            val read = p.inventoryReads.single()
            assertEquals("rt-lux", read.typeId)
            assertEquals(date, read.from)
            assertEquals(date, read.to)
        }
}
