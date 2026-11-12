package com.zeny.desktop.seed.booking

import com.zeny.desktop.core.error.ApiError
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

@OptIn(ExperimentalCoroutinesApi::class)
internal class BookingRetryAtaqueTest : BookingAtaqueBase() {
    // ---- repetição de ROOM_TYPE_UNAVAILABLE / INVENTORY_DATE_OUT_OF_HORIZON ----

    @Test
    fun retriesBothCodesAsNewCallsWithGrowingWait() =
        runTest {
            val p = probe()
            p.createFailures += unavailable
            p.createFailures += horizon
            p.createFailures += unavailable
            BookingSeeder(p, report).seed(data, config)
            assertEquals(11, fake.reservations.size)
            assertEquals(14, p.createdSpecs.size)
            assertEquals(listOf("A1", "A1", "A1", "A1"), p.createdSpecs.take(4).map { it.slot })
            assertEquals(500L + 1000L + 2000L, currentTime)
        }

    @Test
    fun retryDoesNotCountCreatedTwice() =
        runTest {
            val p = probe()
            p.createFailures += unavailable
            BookingSeeder(p, report).seed(data, config)
            assertEquals(11, report.createdCount(SeedItem.RESERVATION))
            assertEquals(0, report.keptCount(SeedItem.RESERVATION))
        }

    @Test
    fun codesOutsideTheTwoAreNotRetried() {
        val others =
            listOf(
                ApiError.Problem(409, "ROOM_TYPE_UNAVAILABLE_FOO", null, emptyList()),
                ApiError.Problem(409, "room_type_unavailable", null, emptyList()),
                ApiError.Problem(409, null, null, emptyList()),
                ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList()),
                ApiError.Problem(409, "RATE_NOT_FOUND", null, emptyList()),
            )
        for (problem in others) {
            val f = FakeBookingGateway(config, date)
            runTest {
                val p = Probe(f) { currentTime }
                p.createFailures += problem
                val e =
                    assertFailsWith<ApiError.Problem>(
                        "${problem.code}",
                    ) { BookingSeeder(p, SeedReport()).seed(data, config) }
                assertEquals(problem.code, e.code)
                assertEquals(1, p.createdSpecs.size, "${problem.code} foi repetido")
                assertEquals(0, currentTime)
            }
        }
    }

    @Test
    fun nonProblemFailureInCreateIsNotRetried() =
        runTest {
            val p = probe()
            p.createFailures += Boom()
            assertFailsWith<Boom> { BookingSeeder(p, report).seed(data, config) }
            assertEquals(1, p.createdSpecs.size)
            assertEquals(0, currentTime)
        }

    @Test
    fun persistentUnavailableEndsInSeedFailureWithinTheLimit() =
        runTest {
            val p = probe()
            repeat(1000) { p.createFailures += unavailable }
            val failure = assertFailsWith<SeedFailure> { BookingSeeder(p, report).seed(data, config) }
            assertEquals(
                "O servidor não refez o inventário de UHs a tempo; rode o demo-seed de novo em alguns segundos.",
                failure.message,
            )
            assertTrue(currentTime >= 60_000)
            assertTrue(currentTime <= 65_000, "passou do limite: $currentTime")
            assertEquals(0, fake.reservations.size)
        }

    @Test
    fun retryUsesTheSameArgumentsEachTime() =
        runTest {
            val p = probe()
            p.createFailures += unavailable
            p.createFailures += horizon
            BookingSeeder(p, report).seed(data, config)
            assertEquals(3, p.createdSpecs.take(3).size)
            assertEquals(
                1,
                p.createdSpecs
                    .take(3)
                    .toSet()
                    .size,
            )
        }

    // ---- segunda execução ----

    @Test
    fun secondRunDoesNoWriteAndNoInventoryRead() =
        runTest {
            val p = probe()
            BookingSeeder(p, SeedReport()).seed(data, config)
            val before = p.events.size
            val at = currentTime
            BookingSeeder(p, SeedReport()).seed(data, config)
            val extra = p.events.drop(before)
            assertEquals(emptyList(), extra, "a segunda execução chamou escrita ou inventário")
            assertEquals(at, currentTime)
        }

    @Test
    fun secondRunOnPartiallyCheckedInDayWritesOnlyWhatIsMissing() =
        runTest {
            val p = probe()
            BookingSeeder(p, SeedReport()).seed(data, config)
            val before = p.events.size
            fake.reservations[spec("S1").externalId] = ref("S1").copy(status = "CONFIRMED")
            BookingSeeder(p, SeedReport()).seed(data, config)
            assertEquals(listOf("checkIn:${ref("S1").id}"), p.events.drop(before))
        }
}
