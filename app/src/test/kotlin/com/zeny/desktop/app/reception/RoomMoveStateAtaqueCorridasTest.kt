package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomMoveResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val atRes = Uuid.parse("20000000-0000-0000-0000-0000000000a1")
private val atHolder = Uuid.parse("30000000-0000-0000-0000-0000000000a1")
private val atType = Uuid.parse("10000000-0000-0000-0000-0000000000a1")
private val atOtherType = Uuid.parse("10000000-0000-0000-0000-0000000000a2")
private val atR101 = Uuid.parse("50000000-0000-0000-0000-000000000101")
private val atR102 = Uuid.parse("50000000-0000-0000-0000-000000000102")
private val atR103 = Uuid.parse("50000000-0000-0000-0000-000000000103")
private val atR110 = Uuid.parse("50000000-0000-0000-0000-000000000110")
private val atR201 = Uuid.parse("50000000-0000-0000-0000-000000000201")
private const val AT_ETAG = "\"v3\""

private fun atAssignment(
    arrival: String,
    departure: String,
    dayUse: Boolean,
    room: Uuid,
    number: String,
) = RoomAssignmentResponse(
    active = true,
    assignedAt = "2026-11-12T15:00:00Z",
    dayUse = dayUse,
    doNotMove = false,
    endsOn = departure,
    id = Uuid.random(),
    reason = "INITIAL",
    reservationId = atRes,
    roomId = room,
    roomNumber = Option.Some(number),
    startsOn = arrival,
)

@Suppress("LongParameterList") // fixture: cada campo da reserva que os testes variam, com padrão
private fun atVersion(
    etag: String = AT_ETAG,
    status: String = "IN_HOUSE",
    arrival: String = "2026-11-12",
    departure: String = "2026-11-15",
    dayUse: Boolean = false,
    room: Uuid = atR101,
    number: String = "101",
    type: Uuid = atType,
) = ReservationVersion(
    ReservationDetailResponse(
        bookingCode = "B-1",
        holderGuestId = atHolder,
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = 2,
                arrival = arrival,
                bookingId = Uuid.random(),
                cancellationTerms =
                    CancellationTermsResponse(
                        24,
                        "Flexível",
                        penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                        policyId = Uuid.random(),
                        version = 1,
                    ),
                childAges = emptyList(),
                code = "R-1",
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = dayUse,
                departure = departure,
                folioId = Uuid.random(),
                guaranteeTerms =
                    GuaranteeTermsResponse(
                        name = "Sem garantia",
                        policyId = Uuid.random(),
                        type = "NONE",
                        version = 1,
                    ),
                guests = emptyList(),
                id = atRes,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
                ratePlanId = Uuid.random(),
                roomAssignment = Option.Some(atAssignment(arrival, departure, dayUse, room, number)),
                roomTotal = "500.00",
                roomTypeId = type,
                segment = "LEISURE",
                status = status,
                total = "500.00",
                version = 3,
            ),
        restrictionAlerts = emptyList(),
    ),
    etag,
)

@Suppress("LongParameterList") // fixture: cada campo do status que os testes variam, com padrão
private fun atStatus(
    room: Uuid,
    number: String,
    type: Uuid = atType,
    ready: Boolean = true,
    active: Boolean = true,
    occupancy: RoomStatusResponse.Occupancy = RoomStatusResponse.Occupancy.VACANT,
) = RoomStatusResponse(
    active = active,
    changedAt = "2026-11-12T10:00:00Z",
    housekeepingStatus = RoomStatusResponse.HousekeepingStatus.CLEAN,
    inspectionPending = false,
    number = number,
    occupancy = occupancy,
    readyForCheckIn = ready,
    roomId = room,
    roomTypeId = type,
    serviceFlag = RoomStatusResponse.ServiceFlag.NONE,
    version = 1,
)

private fun atMoved(): RoomMoveResponse {
    fun room(
        id: Uuid,
        number: String,
    ) = """{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"$number","occupancy":"VACANT",
        "roomId":"$id","version":5}"""
    val any = Uuid.random()
    val json =
        """{"difference":"0.00","remainingAfter":"1000.00","remainingBefore":"1000.00","tariff":"KEEP",
        "fromRoom":${room(atR101, "101")},"toRoom":${room(atR102, "102")},
        "reservation":{"adults":2,"arrival":"2026-11-12","bookingId":"$any","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$any","guests":[],"id":"$atRes",
        "roomTypeId":"$atType","status":"IN_HOUSE","version":4}}"""
    return Json { ignoreUnknownKeys = true }.decodeFromString(RoomMoveResponse.serializer(), json)
}

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class AtRacesApprover(
    var cancel: Boolean = false,
) {
    var calls = 0

    suspend fun approve(action: ApprovableAction<RoomMoveResponse>): ApiResponse<RoomMoveResponse>? {
        calls++
        return try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoomMoveStateAtaqueCorridasTest {
    private val id = atRes.toString()
    private var changes = 0
    private val approver = AtRacesApprover()

    private fun source(today: String = "2026-11-13") =
        FakeReceptionSource().apply {
            businessDay =
                com.zeny.desktop.core.facade
                    .BusinessDay(BusinessDate.parse(today), null)
            versions[id] = atVersion()
            roomStatusList +=
                listOf(
                    atStatus(atR101, "101", occupancy = RoomStatusResponse.Occupancy.OCCUPIED),
                    atStatus(atR110, "110"),
                    atStatus(atR102, "102"),
                    atStatus(atR103, "103"),
                    atStatus(atR201, "201", type = atOtherType),
                )
            moveResponse = atMoved()
        }

    private fun TestScope.state(source: FakeReceptionSource) =
        RoomMoveState(source, backgroundScope, id, approver::approve) { changes++ }

    private fun TestScope.started(source: FakeReceptionSource): RoomMoveState =
        state(source).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.ready(source: FakeReceptionSource): RoomMoveState =
        started(source).also { state -> state.room = state.rooms.first { it.number == "102" } }

    private fun TestScope.confirmNow(state: RoomMoveState) {
        state.confirm()
        runCurrent()
    }

    // ---- corridas ----

    @Test
    fun startWhileConfirmInFlightLeavesConsistentStateAfterBoth() =
        runTest {
            val source = source().apply { holdMove = true }
            val state = ready(source)
            state.confirm()
            runCurrent()
            assertTrue(state.busy)
            source.holdVersion = true
            state.start()
            runCurrent()
            assertTrue(state.busy)
            assertTrue(state.loading)
            source.heldVersions.single().complete(Unit)
            source.heldMoves.single().complete(Unit)
            runCurrent()
            assertFalse(state.busy)
            assertFalse(state.loading)
            assertNotNull(state.done)
            assertEquals(1, changes)
            assertFalse(state.canConfirm)
        }

    @Test
    fun staleLoadResultIsDiscarded() =
        runTest {
            val source = source().apply { holdVersion = true }
            val state = state(source)
            state.start()
            runCurrent()
            source.versions[id] = atVersion(etag = "\"v-new\"", room = atR110, number = "110")
            state.start()
            runCurrent()
            assertEquals(2, source.heldVersions.size)
            source.heldVersions[1].complete(Unit)
            runCurrent()
            source.heldVersions[0].complete(Unit)
            runCurrent()
            assertEquals("110", state.currentRoomNumber)
            assertFalse(state.loading)
            state.room = state.rooms.first()
            source.holdVersion = false
            confirmNow(state)
            assertEquals("\"v-new\"", source.moveRuns.single().etag)
        }

    @Test
    fun olderLoadCompletingLastDoesNotOverwriteNewer() =
        runTest {
            val source = source().apply { holdAssignedRooms = true }
            val state = state(source)
            state.start()
            runCurrent()
            source.assignedRooms = setOf(atR102.toString())
            state.start()
            runCurrent()
            source.heldAssignedRooms.last().complete(Unit)
            runCurrent()
            source.heldAssignedRooms.first().complete(Unit)
            runCurrent()
            assertEquals(listOf("103", "110"), state.rooms.map { it.number })
            assertFalse(state.loading)
        }

    @Test
    fun inputsAreNoopWhileLoading() =
        runTest {
            val source = source()
            val state = ready(source)
            source.holdVersion = true
            state.start()
            runCurrent()
            assertTrue(state.loading)
            state.reason = "OPERATIONAL"
            state.tariff = "RECALCULATE"
            state.room = null
            assertEquals("GUEST_REQUEST", state.reason)
            assertEquals("KEEP", state.tariff)
            assertEquals("102", state.room?.number)
            source.heldVersions.single().complete(Unit)
            runCurrent()
        }

    @Test
    fun confirmDuringPreconditionReloadIsNoop() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.PreconditionFailed(null)
            source.holdAssignedRooms = true
            state.confirm()
            runCurrent()
            assertTrue(state.loading)
            state.confirm()
            runCurrent()
            assertEquals(1, source.moveRuns.size)
            source.heldAssignedRooms.single().complete(Unit)
            runCurrent()
            assertFalse(state.loading)
            assertEquals("Alguém alterou esta reserva. Confira e confirme de novo.", state.error)
        }

    @Test
    fun closeDuringLoadLeavesNothingBehind() =
        runTest {
            val source = source().apply { holdAssignedRooms = true }
            val state = state(source)
            state.start()
            runCurrent()
            state.close()
            runCurrent()
            assertTrue(state.rooms.isEmpty())
            assertNull(state.error)
            assertFalse(state.canConfirm)
            state.start()
            runCurrent()
            assertEquals(1, source.versionCalls.size)
        }

    @Test
    fun closeDuringMoveSuppressesResult() =
        runTest {
            val source = source().apply { holdMove = true }
            val state = ready(source)
            state.confirm()
            runCurrent()
            state.close()
            source.heldMoves.forEach { it.complete(Unit) }
            runCurrent()
            assertNull(state.done)
            assertNull(state.error)
            assertEquals(0, changes)
            assertFalse(state.busy)
        }

    @Test
    fun closeDuringPreconditionReloadStopsTheReload() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.PreconditionFailed(null)
            source.holdAssignedRooms = true
            state.confirm()
            runCurrent()
            state.close()
            runCurrent()
            assertFalse(state.loading)
            confirmNow(state)
            assertEquals(1, source.moveRuns.size)
            assertEquals(0, changes)
        }

    @Test
    fun settersAreNoopAfterClose() =
        runTest {
            val state = started(source())
            state.close()
            state.reason = "OPERATIONAL"
            state.tariff = "RECALCULATE"
            state.room = state.rooms.first()
            assertEquals("GUEST_REQUEST", state.reason)
            assertEquals("KEEP", state.tariff)
            assertNull(state.room)
            state.close()
        }

    @Test
    fun foreignCancellationDuringLoadBecomesErrorAndOwnDoesNot() =
        runTest {
            val source = source().apply { versionError = CancellationException("alheia") }
            val state = started(source)
            assertNotNull(state.error)
            assertFalse(state.loading)
            assertFalse(state.canConfirm)
        }

    @Test
    fun foreignCancellationFromApproverBecomesErrorAndKeepsKeyUnknown() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += CancellationException("alheia")
            confirmNow(state)
            assertNotNull(state.error)
            assertFalse(state.busy)
            confirmNow(state)
            val keys = source.moveRuns.map { it.key }
            assertEquals(2, keys.size)
            assertEquals(1, keys.toSet().size)
        }

    @Test
    fun runtimeExceptionFromApproverBecomesErrorAndReleasesBusy() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += IllegalStateException("bug")
            confirmNow(state)
            assertNotNull(state.error)
            assertFalse(state.busy)
            assertTrue(state.canConfirm)
        }

    @Test
    fun confirmWithoutRoomIsNoopAndSendsNothing() =
        runTest {
            val source = source()
            val state = started(source)
            confirmNow(state)
            assertTrue(source.moveCalls.isEmpty())
            assertEquals(0, approver.calls)
        }

    @Test
    fun roomOutsideTheListIsRejected() =
        runTest {
            val state = started(source())
            state.room = WalkInRoom(atR201.toString(), "201")
            assertNull(state.room)
            state.room = WalkInRoom(atR101.toString(), "101")
            assertNull(state.room)
        }
}
