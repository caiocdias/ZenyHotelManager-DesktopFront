package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.facade.RoomMoveInput
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val mvRes = Uuid.parse("20000000-0000-0000-0000-0000000000a1")
private val mvHolder = Uuid.parse("30000000-0000-0000-0000-0000000000a1")
private val mvType = Uuid.parse("10000000-0000-0000-0000-0000000000a1")
private val mvOtherType = Uuid.parse("10000000-0000-0000-0000-0000000000a2")
private val mvR101 = Uuid.parse("50000000-0000-0000-0000-000000000101")
private val mvR102 = Uuid.parse("50000000-0000-0000-0000-000000000102")
private val mvR103 = Uuid.parse("50000000-0000-0000-0000-000000000103")
private val mvR110 = Uuid.parse("50000000-0000-0000-0000-000000000110")
private val mvR201 = Uuid.parse("50000000-0000-0000-0000-000000000201")
private const val MV_ETAG = "\"v3\""

private fun mvAssignment(
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
    reservationId = mvRes,
    roomId = room,
    roomNumber = Option.Some(number),
    startsOn = arrival,
)

@Suppress("LongParameterList") // fixture: cada campo da reserva que os testes variam, com padrão
private fun mvVersion(
    etag: String = MV_ETAG,
    status: String = "IN_HOUSE",
    arrival: String = "2026-11-12",
    departure: String = "2026-11-15",
    dayUse: Boolean = false,
    room: Uuid = mvR101,
    number: String = "101",
) = ReservationVersion(
    ReservationDetailResponse(
        bookingCode = "B-1",
        holderGuestId = mvHolder,
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
                id = mvRes,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
                ratePlanId = Uuid.random(),
                roomAssignment = Option.Some(mvAssignment(arrival, departure, dayUse, room, number)),
                roomTotal = "500.00",
                roomTypeId = mvType,
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
private fun mvStatus(
    room: Uuid,
    number: String,
    type: Uuid = mvType,
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

private fun mvMoved(): RoomMoveResponse {
    fun room(
        id: Uuid,
        number: String,
    ) = """{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"$number","occupancy":"VACANT",
        "roomId":"$id","version":5}"""
    val any = Uuid.random()
    val json =
        """{"difference":"0.00","remainingAfter":"1000.00","remainingBefore":"1000.00","tariff":"KEEP",
        "fromRoom":${room(mvR101, "101")},"toRoom":${room(mvR102, "102")},
        "reservation":{"adults":2,"arrival":"2026-11-12","bookingId":"$any","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$any","guests":[],"id":"$mvRes",
        "roomTypeId":"$mvType","status":"IN_HOUSE","version":4}}"""
    return Json { ignoreUnknownKeys = true }.decodeFromString(RoomMoveResponse.serializer(), json)
}

private fun mvProblem(
    status: Int,
    code: String?,
) = ApiError.Problem(status, code, "detalhe do servidor", emptyList())

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class MvApprover(
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
class RoomMoveStateTest {
    private val id = mvRes.toString()
    private var changes = 0
    private val approver = MvApprover()

    private fun source() =
        FakeReceptionSource().apply {
            businessDay =
                com.zeny.desktop.core.facade
                    .BusinessDay(BusinessDate.parse("2026-11-13"), null)
            versions[id] = mvVersion()
            roomStatusList +=
                listOf(
                    mvStatus(mvR101, "101", occupancy = RoomStatusResponse.Occupancy.OCCUPIED),
                    mvStatus(mvR110, "110"),
                    mvStatus(mvR102, "102"),
                    mvStatus(mvR103, "103"),
                    mvStatus(mvR201, "201", type = mvOtherType),
                )
            moveResponse = mvMoved()
        }

    private fun TestScope.state(source: FakeReceptionSource) =
        RoomMoveState(source, backgroundScope, id, approver::approve) { changes++ }

    private fun TestScope.started(source: FakeReceptionSource): RoomMoveState =
        state(source).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.ready(source: FakeReceptionSource): RoomMoveState =
        started(source).also { state ->
            state.room = state.rooms.first { it.number == "102" }
        }

    private fun TestScope.confirmNow(state: RoomMoveState) {
        state.confirm()
        runCurrent()
    }

    @Test
    fun startReadsVersionThenRooms() =
        runTest {
            val source = source()
            val state = started(source)

            assertEquals(listOf(id), source.versionCalls)
            assertEquals(1, source.businessDayCalls)
            assertEquals(
                listOf(BusinessDate.parse("2026-11-13") to BusinessDate.parse("2026-11-14")),
                source.assignedRoomsCalls,
            )
            assertEquals("101", state.currentRoomNumber)
            assertFalse(state.loading)
            assertNull(state.error)
            assertEquals("GUEST_REQUEST", state.reason)
            assertEquals("KEEP", state.tariff)
        }

    @Test
    fun roomsOfSameTypeReadyAndFree() =
        runTest {
            val source =
                source().apply {
                    assignedRooms = setOf(mvR103.toString())
                    roomStatusList += mvStatus(Uuid.random(), "104", ready = false)
                    roomStatusList += mvStatus(Uuid.random(), "105", active = false)
                    roomStatusList +=
                        mvStatus(Uuid.random(), "106", occupancy = RoomStatusResponse.Occupancy.OCCUPIED)
                }
            val state = started(source)

            assertEquals(listOf("102", "110"), state.rooms.map { it.number })
        }

    @Test
    fun departureTodayShowsStayEnded() =
        runTest {
            val source = source().apply { versions[id] = mvVersion(departure = "2026-11-13") }
            val state = started(source)

            assertTrue(state.rooms.isEmpty())
            assertEquals(
                "A estadia desta reserva não tem mais noites; a UH não é mais trocada.",
                state.error,
            )
            assertTrue(source.assignedRoomsCalls.isEmpty())
            assertFalse(state.canConfirm)
        }

    @Test
    fun dayUsePeriodIsToday() =
        runTest {
            val source =
                source().apply {
                    versions[id] = mvVersion(arrival = "2026-11-13", departure = "2026-11-13", dayUse = true)
                }
            val state = started(source)

            val today = BusinessDate.parse("2026-11-13")
            assertEquals(listOf(today to today), source.assignedRoomsCalls)
            assertEquals(listOf("102", "103", "110"), state.rooms.map { it.number })
        }

    @Test
    fun dayUseOfAnotherDayHasNoNights() =
        runTest {
            val source =
                source().apply {
                    versions[id] = mvVersion(arrival = "2026-11-12", departure = "2026-11-12", dayUse = true)
                }
            val state = started(source)

            assertTrue(state.rooms.isEmpty())
            assertTrue(source.assignedRoomsCalls.isEmpty())
            assertNotEquals(null, state.error)
        }

    @Test
    fun assignedRoomsFailureShowsNoRooms() =
        runTest {
            val failure = mvProblem(500, "INTERNAL")
            val source = source().apply { assignedRoomsError = failure }
            val state = started(source)

            assertTrue(state.rooms.isEmpty())
            assertEquals(ActionText.error(failure), state.error)
            assertFalse(state.loading)
        }

    @Test
    fun versionFailureShowsNoRooms() =
        runTest {
            val failure = mvProblem(403, "FORBIDDEN")
            val source = source().apply { versionError = failure }
            val state = started(source)

            assertTrue(state.rooms.isEmpty())
            assertEquals(ActionText.error(failure), state.error)
            assertFalse(state.canConfirm)
        }

    @Test
    fun notInHouseCannotConfirm() =
        runTest {
            val source = source().apply { versions[id] = mvVersion(status = "CHECKED_OUT") }
            val state = started(source)

            assertFalse(state.canConfirm)
            assertEquals(
                ActionText.moveError(mvProblem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")),
                state.error,
            )
            state.confirm()
            runCurrent()
            assertTrue(source.moveCalls.isEmpty())
        }

    @Test
    fun confirmSendsMoveWithEtag() =
        runTest {
            val source = source()
            val state = ready(source)
            state.reason = "ROOM_ISSUE"
            state.tariff = "RECALCULATE"
            confirmNow(state)

            val call = source.moveCalls.single()
            assertEquals(id, call.id)
            assertEquals(RoomMoveInput(mvR102.toString(), "ROOM_ISSUE", "RECALCULATE"), call.input)
            assertEquals(MV_ETAG, call.etag)
            assertEquals(1, approver.calls)
        }

    @Test
    fun retryAfterNetworkErrorReusesKey() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.Network(RuntimeException("rede"))
            source.moveOutcomes += mvProblem(409, "IDEMPOTENCY_KEY_IN_USE")
            confirmNow(state)
            assertNull(state.done)
            confirmNow(state)
            confirmNow(state)

            val keys = source.moveRuns.map { it.key }
            assertEquals(3, keys.size)
            assertEquals(1, keys.toSet().size)
            assertNotNull(state.done)
        }

    @Test
    fun ruleErrorRenewsKey() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += mvProblem(422, "ROOM_MOVE_INVALID")
            confirmNow(state)
            confirmNow(state)

            val keys = source.moveRuns.map { it.key }
            assertEquals(2, keys.size)
            assertNotEquals(keys[0], keys[1])
        }

    @Test
    fun changingInputsRenewsKey() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.Network(RuntimeException("rede"))
            source.moveOutcomes += ApiError.Network(RuntimeException("rede"))
            source.moveOutcomes += ApiError.Network(RuntimeException("rede"))
            source.moveOutcomes += ApiError.Network(RuntimeException("rede"))
            confirmNow(state)
            state.reason = "OPERATIONAL"
            confirmNow(state)
            state.tariff = "RECALCULATE"
            confirmNow(state)
            state.room = state.rooms.first { it.number == "103" }
            confirmNow(state)

            val keys = source.moveRuns.map { it.key }
            assertEquals(4, keys.toSet().size)
        }

    @Test
    fun preconditionFailedReloadsCurrentRoomAndRooms() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.PreconditionFailed(null)
            source.versions[id] = mvVersion(etag = "\"v4\"", room = mvR110, number = "110")
            source.assignedRooms = setOf(mvR102.toString())
            confirmNow(state)

            assertEquals("110", state.currentRoomNumber)
            assertEquals(listOf("103"), state.rooms.map { it.number })
            assertNull(state.room)
            assertEquals(ActionText.moveError(ApiError.PreconditionFailed(null)), state.error)
            assertFalse(state.loading)
            assertEquals(2, source.versionCalls.size)

            state.room = state.rooms.first { it.number == "103" }
            confirmNow(state)
            val runs = source.moveRuns
            assertEquals(2, runs.size)
            assertNotEquals(runs[0].key, runs[1].key)
            assertEquals("\"v4\"", runs[1].etag)
        }

    @Test
    fun preconditionFailedKeepsRoomStillListed() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.PreconditionFailed(null)
            source.versions[id] = mvVersion(etag = "\"v4\"")
            confirmNow(state)

            assertEquals("102", state.room?.number)
        }

    @Test
    fun reloadFailureAfterPreconditionShowsReloadError() =
        runTest {
            val source = source()
            val state = ready(source)
            val failure = mvProblem(500, "INTERNAL")
            source.moveOutcomes += ApiError.PreconditionFailed(null)
            source.versionError = failure
            confirmNow(state)

            assertEquals(ActionText.error(failure), state.error)
            assertTrue(state.rooms.isEmpty())
            assertFalse(state.canConfirm)
        }

    @Test
    fun roomTakenRereadsRooms() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += mvProblem(409, "ROOM_ALREADY_ASSIGNED")
            source.assignedRooms = setOf(mvR102.toString())
            confirmNow(state)

            assertNull(state.room)
            assertEquals(listOf("103", "110"), state.rooms.map { it.number })
            assertEquals(ActionText.moveError(mvProblem(409, "ROOM_ALREADY_ASSIGNED")), state.error)
            assertTrue(state.canConfirm.not())
        }

    @Test
    fun transitionNotAllowedNotifies() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += mvProblem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
            confirmNow(state)

            assertEquals(1, changes)
            assertEquals(
                ActionText.moveError(mvProblem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")),
                state.error,
            )
            assertNull(state.done)
        }

    @Test
    fun otherErrorShowsMoveError() =
        runTest {
            val source = source()
            val state = ready(source)
            val failure = mvProblem(422, "ROOM_BLOCK_RESERVATION_STAY_FIXED")
            source.moveOutcomes += failure
            confirmNow(state)

            assertEquals(ActionText.moveError(failure), state.error)
            assertEquals("102", state.room?.number)
            assertTrue(state.canConfirm)
        }

    @Test
    fun foreignCancellationBecomesError() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += CancellationException("alheia")
            confirmNow(state)

            assertNotEquals(null, state.error)
            assertFalse(state.busy)
        }

    @Test
    fun approvalCancelledLeavesState() =
        runTest {
            val source = source().apply { moveRequiresApproval = approvalRequired() }
            approver.cancel = true
            val state = ready(source)
            confirmNow(state)

            assertNull(state.done)
            assertNull(state.error)
            assertFalse(state.busy)
            assertEquals(0, changes)
            assertEquals("102", state.room?.number)
            assertTrue(state.canConfirm)
        }

    @Test
    fun approvedRetryCarriesTokenViaApprover() =
        runTest {
            val source = source().apply { moveRequiresApproval = approvalRequired() }
            val state = ready(source)
            confirmNow(state)

            assertEquals(listOf(null, "tok"), source.moveRuns.map { it.token })
            assertNotNull(state.done)
        }

    @Test
    fun doubleConfirmSendsOnce() =
        runTest {
            val source = source().apply { holdMove = true }
            val state = ready(source)
            state.confirm()
            state.confirm()
            runCurrent()
            state.confirm()
            runCurrent()

            assertEquals(1, source.moveCalls.size)
            assertEquals(1, source.moveRuns.size)
            assertTrue(state.busy)
        }

    @Test
    fun inputsIgnoredWhileBusyOrDone() =
        runTest {
            val source = source().apply { holdMove = true }
            val state = ready(source)
            state.confirm()
            runCurrent()
            state.reason = "OPERATIONAL"
            state.tariff = "RECALCULATE"
            state.room = state.rooms.first { it.number == "103" }
            assertEquals("GUEST_REQUEST", state.reason)
            assertEquals("KEEP", state.tariff)
            assertEquals("102", state.room?.number)

            source.heldMoves.single().complete(Unit)
            runCurrent()
            assertNotNull(state.done)
            state.reason = "OPERATIONAL"
            state.room = null
            assertEquals("GUEST_REQUEST", state.reason)
            assertEquals("102", state.room?.number)
        }

    @Test
    fun onlyKnownReasonsAndTariffsAccepted() =
        runTest {
            val state = started(source())
            state.reason = "INITIAL"
            state.tariff = "OTHER"
            state.room =
                com.zeny.desktop.app.reception
                    .WalkInRoom("x", "999")

            assertEquals("GUEST_REQUEST", state.reason)
            assertEquals("KEEP", state.tariff)
            assertNull(state.room)
        }

    @Test
    fun successNotifiesAndShowsSummary() =
        runTest {
            val source = source()
            val state = ready(source)
            confirmNow(state)

            assertEquals(ActionText.moveSummary(mvMoved()), state.done)
            assertEquals(1, changes)
            assertNull(state.error)
            assertFalse(state.busy)
            assertFalse(state.canConfirm)
        }

    @Test
    fun confirmIgnoredWhileReloading() =
        runTest {
            val source = source().apply { holdVersion = true }
            val state = state(source)
            state.start()
            runCurrent()
            assertTrue(state.loading)
            state.confirm()
            runCurrent()

            assertTrue(source.moveCalls.isEmpty())
            source.heldVersions.single().complete(Unit)
            runCurrent()
            assertFalse(state.loading)
        }

    @Test
    fun closeCancels() =
        runTest {
            val source = source().apply { holdMove = true }
            val state = ready(source)
            state.confirm()
            runCurrent()
            state.close()
            runCurrent()

            assertNull(state.done)
            assertNull(state.error)
            assertEquals(0, changes)
            state.confirm()
            runCurrent()
            assertEquals(1, source.moveCalls.size)
        }

    private fun approvalRequired() =
        ApiError.ApprovalRequired("frontdesk.stays.move", "frontdesk.stays.move", id, null, null)
}
