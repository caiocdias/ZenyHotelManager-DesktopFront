package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reservations.FakeAvailabilitySource.Op
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Contrato do falso que as Tasks 3 a 7 usam. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeAvailabilitySourceAtaqueTest {
    private fun d(text: String) = BusinessDate.parse(text)

    @Test
    fun gateOnOneOperationDoesNotHoldTheOthers() =
        runTest {
            val fake = FakeAvailabilitySource()
            fake.hold(Op.QUOTE)
            fake.availability(d("2026-11-16"), d("2026-11-18"))
            fake.permissions()
            fake.businessDay()
            assertEquals(0, fake.quoteCalls.size)
        }

    @Test
    fun heldCallsReturnTheValueAtReleaseTimeAndGateHoldsWithoutConsuming() =
        runTest {
            val fake = FakeAvailabilitySource()
            fake.permissions = setOf("antes")
            val gate = fake.hold(Op.PERMISSIONS)
            val results = mutableListOf<Set<String>>()
            val first = launch { results += fake.permissions() }
            val second = launch { results += fake.permissions() }
            runCurrent()
            assertEquals(2, fake.permissionsCalls)
            assertTrue(results.isEmpty())

            fake.permissions = setOf("depois")
            gate.complete(Unit)
            first.join()
            second.join()

            assertEquals(listOf(setOf("depois"), setOf("depois")), results)
            assertEquals(setOf("depois"), fake.permissions())
        }

    @Test
    fun failureAppliesToEveryCallUntilRemovedAndIsRecordedBeforeThrowing() =
        runTest {
            val fake = FakeAvailabilitySource()
            fake.failures[Op.AVAILABILITY] = ApiError.Unexpected(500)
            repeat(2) { assertFailsWith<ApiError.Unexpected> { fake.availability(d("2026-11-16"), d("2026-11-17")) } }
            assertEquals(2, fake.availabilityCalls.size)

            fake.failures.remove(Op.AVAILABILITY)
            fake.availability(d("2026-11-16"), d("2026-11-17"))
            assertEquals(3, fake.availabilityCalls.size)
        }

    @Test
    fun failureOnAHeldOperationIsThrownOnlyAfterTheGateOpens() =
        runTest {
            val fake = FakeAvailabilitySource()
            val error = ApiError.Unexpected(503)
            fake.failures[Op.QUOTE] = error
            val gate = fake.hold(Op.QUOTE)
            var caught: Throwable? = null
            val job =
                launch {
                    try {
                        fake.quote(d("2026-11-20"), d("2026-11-22"), 1, emptyList())
                    } catch (e: ApiError.Unexpected) {
                        caught = e
                    }
                }
            runCurrent()
            assertNull(caught)

            gate.complete(Unit)
            job.join()
            assertSame(error, caught)
        }

    @Test
    fun quoteCallsKeepArgumentsOrderAndChildAges() =
        runTest {
            val fake = FakeAvailabilitySource()
            fake.quote(d("2026-11-20"), d("2026-11-22"), 2, listOf(3, 7))
            fake.quote(d("2026-12-01"), d("2026-12-03"), 1, emptyList())
            assertEquals(
                listOf(
                    QuoteCall(d("2026-11-20"), d("2026-11-22"), 2, listOf(3, 7)),
                    QuoteCall(d("2026-12-01"), d("2026-12-03"), 1, emptyList()),
                ),
                fake.quoteCalls,
            )
        }

    @Test
    fun repliesCanBeSwappedBetweenCalls() =
        runTest {
            val fake = FakeAvailabilitySource()
            val first = fake.quote(d("2026-11-20"), d("2026-11-22"), 2, emptyList())
            fake.quote = first.copy(nights = 9)
            assertEquals(9, fake.quote(d("2026-11-20"), d("2026-11-22"), 2, emptyList()).nights)
            assertEquals(2, first.nights)
            fake.availability = fake.availability.copy(to = "2026-12-31")
            assertEquals("2026-12-31", fake.availability(d("2026-11-16"), d("2026-11-17")).to)
        }

    @Test
    fun cancelledHeldCallStaysRecordedAndTheGateStillWorksForOthers() =
        runTest {
            val fake = FakeAvailabilitySource()
            val gate = fake.hold(Op.BUSINESS_DAY)
            val job = launch { fake.businessDay() }
            runCurrent()
            job.cancel()
            job.join()
            assertEquals(1, fake.businessDayCalls)
            gate.complete(Unit)
            fake.businessDay()
            assertEquals(2, fake.businessDayCalls)
        }
}
