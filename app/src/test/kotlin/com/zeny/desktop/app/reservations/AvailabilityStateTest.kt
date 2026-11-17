package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.AvailabilityDayResponse
import com.zeny.desktop.sdk.model.AvailabilityResponse
import com.zeny.desktop.sdk.model.RoomTypeAvailabilityResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private fun date(text: String) = BusinessDate.parse(text)

private fun day(
    date: String,
    available: Int = 3,
) = AvailabilityDayResponse(
    available = available,
    blocked = 0,
    date = date,
    outOfOrder = 0,
    overbooked = false,
    overbookingLimit = 0,
    sold = 1,
    total = 4,
)

private val standardId = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val suiteId = Uuid.parse("10000000-0000-0000-0000-000000000002")

private fun roomType(
    id: Uuid,
    name: String,
    days: List<AvailabilityDayResponse>,
) = RoomTypeAvailabilityResponse(
    active = true,
    code = name.take(3).uppercase(),
    days = days,
    name = name,
    overbookedDates = emptyList(),
    roomTypeId = id,
)

private fun response(
    from: String,
    vararg types: RoomTypeAvailabilityResponse,
) = AvailabilityResponse(from = from, roomTypes = types.toList(), to = from)

@OptIn(ExperimentalCoroutinesApi::class)
class AvailabilityStateTest {
    private val today = date("2026-11-16")

    private fun source() =
        FakeAvailabilitySource().apply {
            businessDay = BusinessDay(today, null)
            availability = response("2026-11-16", roomType(standardId, "Standard", listOf(day("2026-11-16"))))
        }

    private fun TestScope.started(source: FakeAvailabilitySource): AvailabilityState =
        AvailabilityState(source, backgroundScope).also {
            it.start()
            runCurrent()
        }

    @Test
    fun startLoadsThirtyDaysFromBusinessDay() =
        runTest {
            val fake = source()
            val state = started(fake)
            assertEquals(today, state.businessDay)
            assertEquals(today, state.from)
            assertEquals(listOf(AvailabilityCall(today, date("2026-12-15"))), fake.availabilityCalls)
            assertEquals(listOf("Standard"), state.rows.map { it.name })
            assertEquals(standardId.toString(), state.rows.single().roomTypeId)
            assertFalse(state.loading)
            assertNull(state.error)
        }

    @Test
    fun startTwiceDoesNothing() =
        runTest {
            val fake = source()
            val state = started(fake)
            state.start()
            runCurrent()
            assertEquals(1, fake.businessDayCalls)
            assertEquals(1, fake.availabilityCalls.size)
        }

    @Test
    fun columnsFollowWindowNotDays() =
        runTest {
            val fake = source()
            fake.availability =
                response(
                    "2026-11-16",
                    roomType(standardId, "Standard", listOf(day("2026-11-16"), day("2026-11-17"))),
                )
            val state = started(fake)
            assertEquals(30, state.dates.size)
            assertEquals(today, state.dates.first())
            assertEquals(date("2026-12-15"), state.dates.last())
            assertEquals(
                2,
                state.rows
                    .single()
                    .cells.size,
            )
        }

    @Test
    fun typeWithoutDaysKeepsRow() =
        runTest {
            val fake = source()
            fake.availability = response("2026-11-16", roomType(suiteId, "Suíte", emptyList()))
            val state = started(fake)
            assertEquals(listOf("Suíte"), state.rows.map { it.name })
            assertTrue(
                state.rows
                    .single()
                    .cells
                    .isEmpty(),
            )
        }

    @Test
    fun previousStopsAtBusinessDay() =
        runTest {
            val fake = source()
            val state = started(fake)
            assertFalse(state.canGoBack)
            state.previous()
            runCurrent()
            assertEquals(1, fake.availabilityCalls.size)
            state.next()
            runCurrent()
            assertTrue(state.canGoBack)
            state.previous()
            runCurrent()
            assertEquals(today, state.from)
            assertFalse(state.canGoBack)
        }

    @Test
    fun nextMovesThirtyDays() =
        runTest {
            val fake = source()
            val state = started(fake)
            state.next()
            runCurrent()
            assertEquals(date("2026-12-16"), state.from)
            assertEquals(date("2026-12-16"), state.dates.first())
            assertEquals(AvailabilityCall(date("2026-12-16"), date("2027-01-14")), fake.availabilityCalls.last())
        }

    @Test
    fun reloadShowsNewCounts() =
        runTest {
            val fake = source()
            val state = started(fake)
            assertEquals(
                3,
                state.rows
                    .single()
                    .cells
                    .getValue(today)
                    .available,
            )
            fake.availability =
                response("2026-11-16", roomType(standardId, "Standard", listOf(day("2026-11-16", available = 1))))
            state.reload()
            runCurrent()
            assertEquals(
                1,
                state.rows
                    .single()
                    .cells
                    .getValue(today)
                    .available,
            )
            assertEquals(2, fake.availabilityCalls.size)
        }

    @Test
    fun reloadAfterFailedStartRetries() =
        runTest {
            val fake = source()
            fake.failures[FakeAvailabilitySource.Op.BUSINESS_DAY] = ApiError.Network(RuntimeException("x"))
            val state = started(fake)
            assertNull(state.from)
            assertNotNull(state.error)
            assertTrue(state.dates.isEmpty())
            fake.failures.clear()
            state.reload()
            runCurrent()
            assertEquals(today, state.from)
            assertNull(state.error)
            assertEquals(listOf("Standard"), state.rows.map { it.name })
        }

    @Test
    fun newerCalendarWins() =
        runTest {
            val fake = source()
            val state = started(fake)
            val staleGate = fake.hold(FakeAvailabilitySource.Op.AVAILABILITY)
            state.next()
            runCurrent()
            val newerGate = fake.hold(FakeAvailabilitySource.Op.AVAILABILITY)
            state.previous()
            runCurrent()
            // A segunda janela responde primeiro; a primeira chega tarde, com outra resposta.
            fake.availability = response("2026-11-16", roomType(standardId, "Segunda", listOf(day("2026-11-16"))))
            newerGate.complete(Unit)
            runCurrent()
            fake.availability = response("2026-12-16", roomType(suiteId, "Primeira", listOf(day("2026-12-16"))))
            staleGate.complete(Unit)
            runCurrent()
            assertEquals(listOf("Segunda"), state.rows.map { it.name })
            assertEquals(today, state.from)
            assertFalse(state.loading)
        }

    @Test
    fun closeDropsLateResponse() =
        runTest {
            val fake = source()
            val gate = fake.hold(FakeAvailabilitySource.Op.AVAILABILITY)
            val state = started(fake)
            assertTrue(state.loading)
            state.close()
            gate.complete(Unit)
            runCurrent()
            assertTrue(state.rows.isEmpty())
            assertNull(state.error)
            assertFalse(state.loading)
        }

    @Test
    fun errorShowsCatalogText() =
        runTest {
            val fake = source()
            fake.failures[FakeAvailabilitySource.Op.AVAILABILITY] =
                ApiError.Problem(403, "ACCESS_DENIED", null, emptyList())
            val state = started(fake)
            assertEquals("Você não tem permissão para esta ação.", state.error)
            assertFalse(state.loading)
        }

    @Test
    fun selectionSurvivesReload() =
        runTest {
            val fake = source()
            val state = started(fake)
            state.select(standardId.toString(), today)
            assertEquals(3, state.selectedDay?.available)
            fake.availability =
                response("2026-11-16", roomType(standardId, "Standard", listOf(day("2026-11-16", available = 2))))
            state.reload()
            runCurrent()
            assertEquals(standardId.toString() to today, state.selected)
            assertEquals(2, state.selectedDay?.available)
            fake.availability = response("2026-11-16", roomType(suiteId, "Suíte", listOf(day("2026-11-16"))))
            state.reload()
            runCurrent()
            assertNull(state.selected)
            assertNull(state.selectedDay)
        }

    @Test
    fun selectionOutsideWindowDrops() =
        runTest {
            val state = started(source())
            state.select(standardId.toString(), today)
            state.next()
            runCurrent()
            assertNull(state.selected)
        }
}
