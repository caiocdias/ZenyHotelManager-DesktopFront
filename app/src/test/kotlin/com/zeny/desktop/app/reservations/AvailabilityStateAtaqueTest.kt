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

private val stdId = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val suiId = Uuid.parse("10000000-0000-0000-0000-000000000002")

private fun type(
    id: Uuid,
    name: String,
    vararg days: AvailabilityDayResponse,
) = RoomTypeAvailabilityResponse(
    active = true,
    code = name.take(3).uppercase(),
    days = days.toList(),
    name = name,
    overbookedDates = emptyList(),
    roomTypeId = id,
)

private fun resp(vararg types: RoomTypeAvailabilityResponse) =
    AvailabilityResponse(from = "2026-11-16", roomTypes = types.toList(), to = "2026-11-16")

private val AVAIL = FakeAvailabilitySource.Op.AVAILABILITY
private val BDAY = FakeAvailabilitySource.Op.BUSINESS_DAY

@OptIn(ExperimentalCoroutinesApi::class)
class AvailabilityStateAtaqueTest {
    private val today = date("2026-11-16")

    private fun source(businessDay: BusinessDate = today) =
        FakeAvailabilitySource().apply {
            this.businessDay = BusinessDay(businessDay, null)
            availability = resp(type(stdId, "Standard", day(businessDay.value.toString())))
        }

    private fun TestScope.started(
        source: FakeAvailabilitySource,
        days: Int = 30,
    ) = AvailabilityState(source, backgroundScope, days).also {
        it.start()
        runCurrent()
    }

    private fun names(state: AvailabilityState) = state.rows.map { it.name }

    @Test
    fun nextNextPreviousShowsOnlyLastWindowWhateverTheReleaseOrder() =
        runTest {
            val fake = source()
            val state = started(fake)
            val g1 = fake.hold(AVAIL)
            state.next()
            runCurrent()
            val g2 = fake.hold(AVAIL)
            state.next()
            runCurrent()
            val g3 = fake.hold(AVAIL)
            state.previous()
            runCurrent()
            assertEquals(date("2026-12-16"), state.from)
            fake.availability = resp(type(suiId, "Terceira"))
            g3.complete(Unit)
            runCurrent()
            fake.availability = resp(type(suiId, "Primeira"))
            g1.complete(Unit)
            fake.availability = resp(type(suiId, "Segunda"))
            g2.complete(Unit)
            runCurrent()
            assertEquals(listOf("Terceira"), names(state))
            assertFalse(state.loading)
            assertNull(state.error)
        }

    @Test
    fun staleFailureDoesNotSetErrorOverNewerSuccess() =
        runTest {
            val fake = source()
            val state = started(fake)
            val stale = fake.hold(AVAIL)
            state.next()
            runCurrent()
            val newer = fake.hold(AVAIL)
            state.previous()
            runCurrent()
            fake.availability = resp(type(stdId, "Nova"))
            newer.complete(Unit)
            runCurrent()
            fake.failures[AVAIL] = ApiError.Problem(403, "ACCESS_DENIED", null, emptyList())
            stale.complete(Unit)
            runCurrent()
            assertNull(state.error)
            assertEquals(listOf("Nova"), names(state))
        }

    @Test
    fun reloadDuringLoadKeepsOnlyTheReloadResponse() =
        runTest {
            val fake = source()
            val first = fake.hold(AVAIL)
            val state = AvailabilityState(fake, backgroundScope)
            state.start()
            runCurrent()
            assertTrue(state.loading)
            fake.availability = resp(type(stdId, "Velha"))
            val second = fake.hold(AVAIL)
            state.reload()
            runCurrent()
            fake.availability = resp(type(stdId, "Nova"))
            second.complete(Unit)
            runCurrent()
            first.complete(Unit)
            runCurrent()
            assertEquals(listOf("Nova"), names(state))
            assertFalse(state.loading)
        }

    @Test
    fun reloadDuringLoadWhenStaleArrivesFirstStillShowsReload() =
        runTest {
            val fake = source()
            val first = fake.hold(AVAIL)
            val state = AvailabilityState(fake, backgroundScope)
            state.start()
            runCurrent()
            val second = fake.hold(AVAIL)
            state.reload()
            runCurrent()
            fake.availability = resp(type(stdId, "Velha"))
            first.complete(Unit)
            runCurrent()
            assertTrue(state.rows.isEmpty())
            assertTrue(state.loading)
            fake.availability = resp(type(stdId, "Nova"))
            second.complete(Unit)
            runCurrent()
            assertEquals(listOf("Nova"), names(state))
            assertFalse(state.loading)
        }

    @Test
    fun closeDuringBusinessDayLoadAdoptsNothing() =
        runTest {
            val fake = source()
            val gate = fake.hold(BDAY)
            val state = AvailabilityState(fake, backgroundScope)
            state.start()
            runCurrent()
            state.close()
            gate.complete(Unit)
            runCurrent()
            assertNull(state.from)
            assertNull(state.businessDay)
            assertTrue(fake.availabilityCalls.isEmpty())
            assertFalse(state.loading)
        }

    @Test
    fun closeDuringFailingLoadDoesNotSetError() =
        runTest {
            val fake = source()
            val gate = fake.hold(AVAIL)
            fake.failures[AVAIL] = ApiError.Network(RuntimeException("x"))
            val state = AvailabilityState(fake, backgroundScope)
            state.start()
            runCurrent()
            state.close()
            gate.complete(Unit)
            runCurrent()
            assertNull(state.error)
            assertFalse(state.loading)
        }

    @Test
    fun callsAfterCloseDoNothing() =
        runTest {
            val fake = source()
            val state = started(fake)
            state.close()
            val calls = fake.availabilityCalls.size
            val from = state.from
            state.reload()
            state.next()
            state.previous()
            state.start()
            runCurrent()
            assertEquals(calls, fake.availabilityCalls.size)
            assertEquals(from, state.from)
            assertFalse(state.loading)
            assertEquals(1, fake.businessDayCalls)
        }

    @Test
    fun closeThenLateNextResponseIsDropped() =
        runTest {
            val fake = source()
            val state = started(fake)
            val gate = fake.hold(AVAIL)
            state.next()
            runCurrent()
            state.close()
            fake.availability = resp(type(suiId, "Tardia"))
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("Standard"), names(state))
            assertFalse(state.loading)
        }

    @Test
    fun startAfterCloseNeverCallsServer() =
        runTest {
            val fake = source()
            val state = AvailabilityState(fake, backgroundScope)
            state.close()
            state.start()
            runCurrent()
            assertEquals(0, fake.businessDayCalls)
            assertTrue(fake.availabilityCalls.isEmpty())
            assertFalse(state.loading)
        }

    @Test
    fun startTwiceWhileLoadingDoesNotRestartLoad() =
        runTest {
            val fake = source()
            val gate = fake.hold(AVAIL)
            val state = AvailabilityState(fake, backgroundScope)
            state.start()
            runCurrent()
            state.start()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, fake.businessDayCalls)
            assertEquals(1, fake.availabilityCalls.size)
            assertEquals(listOf("Standard"), names(state))
        }

    @Test
    fun failedStartThenStartAgainStaysInert() =
        runTest {
            val fake = source()
            fake.failures[BDAY] = ApiError.Network(RuntimeException("x"))
            val state = started(fake)
            fake.failures.clear()
            state.start()
            runCurrent()
            assertEquals(1, fake.businessDayCalls)
            assertNull(state.from)
        }

    @Test
    fun availabilityFailureThenReloadDoesNotRereadBusinessDay() =
        runTest {
            val fake = source()
            fake.failures[AVAIL] = ApiError.Network(RuntimeException("x"))
            val state = started(fake)
            assertEquals(today, state.from)
            assertNotNull(state.error)
            assertTrue(state.rows.isEmpty())
            assertFalse(state.loading)
            fake.failures.clear()
            state.reload()
            runCurrent()
            assertEquals(1, fake.businessDayCalls)
            assertEquals(listOf("Standard"), names(state))
            assertNull(state.error)
            assertEquals(AvailabilityCall(today, date("2026-12-15")), fake.availabilityCalls.last())
        }

    @Test
    fun businessDayFailureShowsCatalogTextAndLoadingFalse() =
        runTest {
            val fake = source()
            fake.failures[BDAY] = ApiError.Problem(403, "ACCESS_DENIED", null, emptyList())
            val state = started(fake)
            assertEquals("Você não tem permissão para esta ação.", state.error)
            assertFalse(state.loading)
            assertFalse(state.canGoBack)
            state.next()
            state.previous()
            runCurrent()
            assertTrue(fake.availabilityCalls.isEmpty())
        }

    @Test
    fun errorClearsOnNextSuccessfulNavigation() =
        runTest {
            val fake = source()
            val state = started(fake)
            fake.failures[AVAIL] = ApiError.Problem(403, "ACCESS_DENIED", null, emptyList())
            state.next()
            runCurrent()
            assertEquals("Você não tem permissão para esta ação.", state.error)
            fake.failures.clear()
            state.next()
            runCurrent()
            assertNull(state.error)
            assertFalse(state.loading)
            assertEquals(date("2027-01-15"), state.from)
        }

    @Test
    fun errorIsClearedAtTheStartOfTheNextLoad() =
        runTest {
            val fake = source()
            fake.failures[AVAIL] = ApiError.Network(RuntimeException("x"))
            val state = started(fake)
            assertNotNull(state.error)
            fake.failures.clear()
            val gate = fake.hold(AVAIL)
            state.reload()
            runCurrent()
            assertNull(state.error)
            assertTrue(state.loading)
            gate.complete(Unit)
            runCurrent()
        }

    @Test
    fun previousFromBusinessDayDoesNotCallServer() =
        runTest {
            val fake = source()
            val state = started(fake)
            state.previous()
            state.previous()
            runCurrent()
            assertEquals(1, fake.availabilityCalls.size)
            assertEquals(today, state.from)
        }

    @Test
    fun nextThenPreviousReturnsToExactBusinessDayWindow() =
        runTest {
            val fake = source()
            val state = started(fake)
            state.next()
            runCurrent()
            state.next()
            runCurrent()
            assertEquals(date("2027-01-15"), state.from)
            state.previous()
            runCurrent()
            state.previous()
            runCurrent()
            assertEquals(today, state.from)
            assertFalse(state.canGoBack)
            assertEquals(AvailabilityCall(today, date("2026-12-15")), fake.availabilityCalls.last())
        }

    @Test
    fun datesAlwaysHaveDaysEntriesAcrossNavigationAndConsecutive() =
        runTest {
            val fake = source()
            val state = started(fake, days = 7)
            repeat(3) {
                assertEquals(7, state.dates.size)
                state.dates.zipWithNext().forEach { (a, b) -> assertEquals(a.value.plusDays(1), b.value) }
                state.next()
                runCurrent()
            }
            assertEquals(date("2026-12-07"), state.from)
            assertEquals(7, state.dates.size)
        }

    @Test
    fun windowAcrossYearEndHasContinuousDates() =
        runTest {
            val fake = source(date("2026-12-20"))
            val state = started(fake)
            assertEquals(30, state.dates.size)
            assertEquals(date("2026-12-31"), state.dates[11])
            assertEquals(date("2027-01-01"), state.dates[12])
            assertEquals(date("2027-01-18"), state.dates.last())
            assertEquals(AvailabilityCall(date("2026-12-20"), date("2027-01-18")), fake.availabilityCalls.single())
        }

    @Test
    fun malformedServerDateDoesNotCrashAndIsReportedAsError() =
        runTest {
            val fake = source()
            fake.availability = resp(type(stdId, "Standard", day("2026-11-16"), day("não-é-data")))
            val state = started(fake)
            assertFalse(state.loading)
            assertNotNull(state.error)
            fake.availability = resp(type(stdId, "Standard", day("2026-11-16")))
            state.reload()
            runCurrent()
            assertNull(state.error)
            assertEquals(listOf("Standard"), names(state))
        }

    @Test
    fun duplicateTypesDoNotCrashAndSelectionResolves() =
        runTest {
            val fake = source()
            fake.availability =
                resp(
                    type(stdId, "Standard", day("2026-11-16", available = 3)),
                    type(stdId, "Standard", day("2026-11-16", available = 1)),
                )
            val state = started(fake)
            assertNull(state.error)
            assertFalse(state.loading)
            state.select(stdId.toString(), today)
            assertNotNull(state.selectedDay)
        }

    @Test
    fun duplicateDaysOfSameTypeDoNotCrash() =
        runTest {
            val fake = source()
            fake.availability = resp(type(stdId, "Standard", day("2026-11-16", 3), day("2026-11-16", 1)))
            val state = started(fake)
            assertNull(state.error)
            assertEquals(
                1,
                state.rows
                    .single()
                    .cells.size,
            )
        }

    @Test
    fun selectUnknownTypeHasNoSelectedDay() =
        runTest {
            val state = started(source())
            state.select(suiId.toString(), today)
            assertNull(state.selectedDay)
            state.select("lixo", today)
            assertNull(state.selectedDay)
        }

    @Test
    fun selectBeforeStartDoesNotCrashAndDropsAfterLoadWhenTypeAbsent() =
        runTest {
            val fake = source()
            val state = AvailabilityState(fake, backgroundScope)
            state.select(suiId.toString(), today)
            assertNull(state.selectedDay)
            state.start()
            runCurrent()
            assertNull(state.selected)
        }

    @Test
    fun selectionDroppedWhenWindowMovesAndNotRestoredOnReturn() =
        runTest {
            val fake = source()
            val state = started(fake)
            state.select(stdId.toString(), today)
            state.next()
            runCurrent()
            state.previous()
            runCurrent()
            assertNull(state.selected)
        }

    @Test
    fun selectionSurvivesReloadWhenCellDisappearsButTypeAndDateStay() =
        runTest {
            val fake = source()
            val state = started(fake)
            state.select(stdId.toString(), today)
            fake.availability = resp(type(stdId, "Standard"))
            state.reload()
            runCurrent()
            assertEquals(stdId.toString() to today, state.selected)
            assertNull(state.selectedDay)
        }

    @Test
    fun selectionDroppedWhenNavigationFailsOutOfWindow() =
        runTest {
            val fake = source()
            val state = started(fake)
            state.select(stdId.toString(), today)
            fake.failures[AVAIL] = ApiError.Network(RuntimeException("x"))
            state.next()
            runCurrent()
            assertNull(state.selected)
            assertEquals(date("2026-12-16"), state.from)
        }

    @Test
    fun duringNavigationLoadingIsTrueWhileFromAndDatesAlreadyMoved() =
        runTest {
            val fake = source()
            val state = started(fake)
            val gate = fake.hold(AVAIL)
            state.next()
            runCurrent()
            assertTrue(state.loading)
            assertEquals(date("2026-12-16"), state.dates.first())
            assertEquals(listOf("Standard"), names(state))
            gate.complete(Unit)
            runCurrent()
            assertFalse(state.loading)
        }

    @Test
    fun dayOutsideWindowDoesNotAppearInCells() =
        runTest {
            val fake = source()
            fake.availability =
                resp(type(stdId, "Standard", day("2026-11-16"), day("2025-01-01"), day("2027-06-01")))
            val state = started(fake)
            val cells = state.rows.single().cells
            assertTrue(
                state.dates.containsAll(cells.keys),
                "células fora da janela: ${cells.keys - state.dates.toSet()}",
            )
        }
}
