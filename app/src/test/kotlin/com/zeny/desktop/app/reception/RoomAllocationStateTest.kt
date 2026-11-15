package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomSuggestionResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val resUuid = Uuid.parse("20000000-0000-0000-0000-000000000001")
private val holderUuid = Uuid.parse("30000000-0000-0000-0000-000000000001")
private val roomA = Uuid.parse("50000000-0000-0000-0000-00000000000a")
private val roomB = Uuid.parse("50000000-0000-0000-0000-00000000000b")
private val roomC = Uuid.parse("50000000-0000-0000-0000-00000000000c")

private fun assignment(
    room: Uuid,
    number: String,
    doNotMove: Boolean = false,
) = RoomAssignmentResponse(
    active = true,
    assignedAt = "2026-11-13T15:00:00Z",
    dayUse = false,
    doNotMove = doNotMove,
    endsOn = "2026-11-15",
    id = Uuid.random(),
    reason = "INITIAL",
    reservationId = resUuid,
    roomId = room,
    roomNumber = Option.Some(number),
    startsOn = "2026-11-13",
)

private fun detail(assignment: RoomAssignmentResponse? = null) =
    ReservationDetailResponse(
        bookingCode = "B-1",
        holderGuestId = holderUuid,
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = 2,
                arrival = "2026-11-13",
                bookingId = Uuid.random(),
                cancellationTerms =
                    CancellationTermsResponse(
                        24,
                        "Flexível",
                        penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                        policyId = Uuid.random(),
                        version = 1,
                    ),
                childAges = listOf(5, 7),
                code = "R-1",
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guaranteeTerms =
                    GuaranteeTermsResponse(
                        name = "Sem garantia",
                        policyId = Uuid.random(),
                        type = "NONE",
                        version = 1,
                    ),
                guests = emptyList(),
                id = resUuid,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
                ratePlanId = Uuid.random(),
                roomAssignment = assignment?.let { Option.Some(it) } ?: Option.Undefined,
                roomTotal = "500.00",
                roomTypeId = Uuid.random(),
                segment = "LEISURE",
                status = "CONFIRMED",
                total = "500.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

private fun suggestion(
    room: Uuid,
    number: String,
    current: Boolean = false,
    outOfService: Boolean = false,
) = RoomSuggestionResponse(
    current = current,
    featureIds = emptyList(),
    floor = Option.Some("2"),
    matchedFeatureIds = emptyList(),
    matchesFloorPreference = true,
    number = number,
    outOfService = outOfService,
    roomId = room,
)

private fun status(
    room: Uuid,
    number: String,
    ready: Boolean = false,
    housekeeping: RoomStatusResponse.HousekeepingStatus = RoomStatusResponse.HousekeepingStatus.CLEAN,
    occupancy: RoomStatusResponse.Occupancy = RoomStatusResponse.Occupancy.VACANT,
) = RoomStatusResponse(
    active = true,
    changedAt = "2026-11-12T10:00:00Z",
    housekeepingStatus = housekeeping,
    inspectionPending = false,
    number = number,
    occupancy = occupancy,
    readyForCheckIn = ready,
    roomId = room,
    roomTypeId = Uuid.random(),
    serviceFlag = RoomStatusResponse.ServiceFlag.NONE,
    version = 1,
)

private fun problem(code: String) = ApiError.Problem(422, code, null, emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class RoomAllocationStateTest {
    private val id = resUuid.toString()

    private fun source(current: RoomAssignmentResponse? = null) =
        FakeReceptionSource().apply {
            reservations[id] = detail(current)
            suggestions[id] =
                listOf(suggestion(roomA, "101"), suggestion(roomB, "102"), suggestion(roomC, "103"))
            roomStatusList +=
                listOf(
                    status(roomA, "101", ready = true),
                    status(roomB, "102", housekeeping = RoomStatusResponse.HousekeepingStatus.DIRTY),
                    status(roomC, "103", occupancy = RoomStatusResponse.Occupancy.OCCUPIED),
                )
        }

    private fun TestScope.state(
        source: FakeReceptionSource,
        seed: CheckInSeed = detail().toCheckInSeed(),
        canManage: Boolean = true,
        onChanged: () -> Unit = {},
    ) = RoomAllocationState(source, backgroundScope, seed, canManage, onChanged)

    @Test
    fun seedFromReservation() {
        val none = detail().toCheckInSeed()
        assertEquals(id, none.reservationId)
        assertEquals(holderUuid.toString(), none.holderGuestId)
        assertEquals("CONFIRMED", none.status)
        assertEquals(2, none.adults)
        assertEquals(2, none.children)
        assertNull(none.roomId)
        assertNull(none.roomNumber)
        assertFalse(none.doNotMove)

        val with = detail(assignment(roomA, "101")).toCheckInSeed()
        assertEquals(roomA.toString(), with.roomId)
        assertEquals("101", with.roomNumber)
        assertFalse(with.doNotMove)

        assertTrue(detail(assignment(roomA, "101", doNotMove = true)).toCheckInSeed().doNotMove)
    }

    @Test
    fun currentOutsideSuggestionsStillShown() =
        runTest {
            val seed = detail(assignment(roomC, "999")).toCheckInSeed()
            val source = source().apply { suggestions[id] = listOf(suggestion(roomA, "101")) }
            val state = state(source, seed)
            state.load()
            runCurrent()

            assertEquals("999", state.currentRoomNumber)
            assertEquals(roomC.toString(), state.currentRoomId)
            assertEquals(listOf("101"), state.options.map { it.number })
        }

    @Test
    fun initialDoNotMoveFromReservation() =
        runTest {
            val locked = detail(assignment(roomA, "101", doNotMove = true))
            val source = source(null).apply { reservations[id] = detail(assignment(roomA, "101")) }
            val state = state(source, locked.toCheckInSeed())
            assertTrue(state.doNotMove)

            state.toggleDoNotMove()
            runCurrent()

            assertEquals(listOf(id to false), source.doNotMoveCalls)
            assertFalse(state.doNotMove)
        }

    @Test
    fun loadsSuggestionsWithReadiness() =
        runTest {
            val source = source()
            val state = state(source)
            state.load()
            runCurrent()

            val byNumber = state.options.associateBy { it.number }
            assertEquals("Pronta", byNumber.getValue("101").readiness.label)
            assertTrue(byNumber.getValue("101").ready)
            assertFalse(byNumber.getValue("102").ready)
            assertEquals("Suja", byNumber.getValue("102").readiness.label)
            assertFalse(byNumber.getValue("103").ready)
            assertEquals("Ocupada", byNumber.getValue("103").readiness.label)
            assertEquals("2", byNumber.getValue("101").floor)
            assertFalse(state.loading)
            assertNull(state.error)
        }

    @Test
    fun statusesForbiddenStillLists() =
        runTest {
            val source = source().apply { roomStatusesError = ApiError.Problem(403, "FORBIDDEN", null, emptyList()) }
            val state = state(source)
            state.load()
            runCurrent()

            assertEquals(3, state.options.size)
            assertTrue(state.options.all { it.readiness.label == "Status desconhecido" && !it.ready })
            assertNull(state.error)
        }

    @Test
    fun withoutPermissionNoCalls() =
        runTest {
            val seed = detail(assignment(roomA, "101")).toCheckInSeed()
            val source = source()
            val state = state(source, seed, canManage = false)
            state.load()
            state.assign(roomB.toString())
            state.unassign()
            state.toggleDoNotMove()
            runCurrent()

            assertFalse(state.canManage)
            assertEquals("101", state.currentRoomNumber)
            assertTrue(source.suggestionCalls.isEmpty())
            assertEquals(0, source.roomStatusesCalls)
            assertTrue(source.assignCalls.isEmpty())
            assertTrue(source.unassignCalls.isEmpty())
            assertTrue(source.doNotMoveCalls.isEmpty())
        }

    @Test
    fun assignUpdatesCurrentAndNotifies() =
        runTest {
            val source = source().apply { reservations[id] = detail(assignment(roomB, "102")) }
            var changed = 0
            val state = state(source, onChanged = { changed++ })
            state.load()
            runCurrent()

            state.assign(roomB.toString())
            runCurrent()

            assertEquals(listOf(AssignCall(id, roomB.toString(), null)), source.assignCalls)
            assertEquals("102", state.currentRoomNumber)
            assertEquals(roomB.toString(), state.currentRoomId)
            assertEquals(1, changed)
            assertEquals(2, source.suggestionCalls.size)
            assertFalse(state.busy)
            assertNull(state.error)
        }

    @Test
    fun unassignClearsCurrent() =
        runTest {
            val seed = detail(assignment(roomA, "101", doNotMove = true)).toCheckInSeed()
            val source = source()
            var changed = 0
            val state = state(source, seed, onChanged = { changed++ })

            state.unassign()
            runCurrent()

            assertEquals(listOf(id), source.unassignCalls)
            assertNull(state.currentRoomId)
            assertNull(state.currentRoomNumber)
            assertFalse(state.doNotMove)
            assertEquals(1, changed)
        }

    @Test
    fun lockedShowsNextStep() =
        runTest {
            val seed = detail(assignment(roomA, "101", doNotMove = true)).toCheckInSeed()
            val source = source().apply { assignError = problem("ROOM_ASSIGNMENT_LOCKED") }
            var changed = 0
            val state = state(source, seed, onChanged = { changed++ })

            state.assign(roomB.toString())
            runCurrent()

            assertEquals(ActionText.error(problem("ROOM_ASSIGNMENT_LOCKED")), state.error)
            assertEquals("101", state.currentRoomNumber)
            assertEquals(0, changed)
            assertFalse(state.busy)
        }

    @Test
    fun doubleAssignSendsOnce() =
        runTest {
            val source = source().apply { holdAssign = true }
            val state = state(source)

            state.assign(roomA.toString())
            runCurrent()
            state.assign(roomB.toString())
            runCurrent()
            assertTrue(state.busy)
            assertEquals(1, source.assignCalls.size)

            source.reservations[id] = detail(assignment(roomA, "101"))
            source.heldAssigns.forEach { it.complete(Unit) }
            runCurrent()

            assertEquals(1, source.assignCalls.size)
            assertFalse(state.busy)
            assertEquals("101", state.currentRoomNumber)
        }

    @Test
    fun notAllowedShowsMessage() =
        runTest {
            val source = source().apply { assignError = problem("ROOM_ALREADY_ASSIGNED") }
            val state = state(source)

            state.assign(roomA.toString())
            runCurrent()

            assertEquals(ActionText.error(problem("ROOM_ALREADY_ASSIGNED")), state.error)
            assertNull(state.currentRoomId)
            assertFalse(state.busy)
        }

    @Test
    fun closeCancels() =
        runTest {
            val source = source().apply { holdSuggestions = true }
            val state = state(source)
            state.load()
            runCurrent()
            assertTrue(state.loading)

            state.close()
            source.heldSuggestions.forEach { it.complete(Unit) }
            runCurrent()
            state.load()
            state.assign(roomA.toString())
            runCurrent()

            assertTrue(state.options.isEmpty())
            assertEquals(1, source.suggestionCalls.size)
            assertTrue(source.assignCalls.isEmpty())
        }
}
