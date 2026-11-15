package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CheckInGuest
import com.zeny.desktop.core.facade.CheckInInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.RestrictionAlertResponse
import com.zeny.desktop.sdk.model.StayReservationResponse
import com.zeny.desktop.sdk.model.StayRoomResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val resId = Uuid.parse("60000000-0000-0000-0000-000000000001")
private val guestUuid = Uuid.parse("70000000-0000-0000-0000-000000000001")
private val roomUuid = Uuid.parse("80000000-0000-0000-0000-000000000001")

private fun fakeDetail() =
    ReservationDetailResponse(
        bookingCode = "B-1",
        holderGuestId = guestUuid,
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
                childAges = emptyList(),
                code = "R-1",
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guaranteeTerms =
                    GuaranteeTermsResponse(name = "x", policyId = Uuid.random(), type = "NONE", version = 1),
                guests = emptyList(),
                id = resId,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
                ratePlanId = Uuid.random(),
                roomTotal = "1.00",
                roomTypeId = Uuid.random(),
                segment = "LEISURE",
                status = "CONFIRMED",
                total = "1.00",
                version = 1,
            ),
        restrictionAlerts = emptyList<RestrictionAlertResponse>(),
    )

private fun fakeCheckInResponse() =
    CheckInResponse(
        reservation =
            StayReservationResponse(
                adults = 2,
                arrival = "2026-11-13",
                bookingId = Uuid.random(),
                children = 0,
                code = "R-1",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guests = emptyList(),
                id = resId,
                roomTypeId = Uuid.random(),
                status = "CHECKED_IN",
                version = 2,
            ),
        restrictionAlerts = emptyList(),
        room =
            StayRoomResponse(
                housekeepingStatus = StayRoomResponse.HousekeepingStatus.INSPECTED,
                inspectionPending = false,
                number = "101",
                occupancy = StayRoomResponse.Occupancy.OCCUPIED,
                roomId = roomUuid,
                version = 3,
            ),
    )

private fun input() = CheckInInput(listOf(CheckInGuest(guestUuid.toString(), true)), 1, roomUuid.toString(), null)

private fun approval() = ApiError.ApprovalRequired("CHECK_IN", "frontdesk.stays.checkin", "res", null, null)

private fun pick(n: Int) = GuestPick("g$n", HolderSummary("Hóspede $n", "CPF ***$n"))

/** Ataques ao falso compartilhado das ações da recepção (base das Tasks 6 a 12) e a `DayPanelState.permissions`. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeReceptionActionsAtaqueTest {
    private fun fake() = FakeReceptionSource().apply { checkInResponse = fakeCheckInResponse() }

    @Test
    fun everyCheckInRunRecordsTheIntentKeyInUseAndTheToken() =
        runTest {
            val source = fake()
            val intent = Intent()
            val action = source.checkIn(resId.toString(), input(), intent)

            action.run(null)
            action.run(null)
            action.run("tok")

            assertEquals(listOf(intent.key, intent.key, intent.key), source.checkInRuns.map { it.key })
            assertEquals(listOf(null, null, "tok"), source.checkInRuns.map { it.token })
            assertEquals(1, source.checkInCalls.size)
            assertSame(intent, source.checkInCalls.single().intent)
            assertEquals(resId.toString(), source.checkInCalls.single().id)
        }

    @Test
    fun creatingTheActionRecordsNoRunUntilItIsExecuted() {
        val source = fake()

        source.checkIn(resId.toString(), input(), Intent())

        assertEquals(1, source.checkInCalls.size)
        assertEquals(emptyList(), source.checkInRuns)
    }

    @Test
    fun approvalScriptFailsTheFirstTokenlessRunAndPassesWithToken() =
        runTest {
            val source = fake().apply { checkInRequiresApproval = approval() }
            val action = source.checkIn(resId.toString(), input(), Intent())

            val thrown = assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
            val response = action.run("tok")

            assertSame(source.checkInRequiresApproval, thrown)
            assertEquals(200, response.status)
            assertEquals(listOf(null, "tok"), source.checkInRuns.map { it.token })
        }

    @Test
    fun approvalRunDoesNotConsumeTheScriptedOutcomes() =
        runTest {
            val source =
                fake().apply {
                    checkInRequiresApproval = approval()
                    checkInOutcomes += ApiError.Problem(422, "ROOM_NOT_READY", null, emptyList())
                }
            val action = source.checkIn(resId.toString(), input(), Intent())

            assertFailsWith<ApiError.ApprovalRequired> { action.run(null) }
            val failure = assertFailsWith<ApiError.Problem> { action.run("tok") }
            assertEquals("ROOM_NOT_READY", failure.code)
            assertEquals(200, action.run("tok").status)
        }

    @Test
    fun outcomesApplyPerRunInOrderAndThenSucceed() =
        runTest {
            val source =
                fake().apply {
                    checkInOutcomes += ApiError.Network(IOException("off"))
                    checkInOutcomes += null
                    checkInOutcomes += ApiError.PreconditionFailed(null)
                }
            val action = source.checkIn(resId.toString(), input(), Intent())

            assertFailsWith<ApiError.Network> { action.run(null) }
            assertEquals(200, action.run(null).status)
            assertFailsWith<ApiError.PreconditionFailed> { action.run(null) }
            assertEquals(200, action.run(null).status)
            assertEquals(4, source.checkInRuns.size)
        }

    @Test
    fun runWithoutAScriptedResponseFailsLoudInsteadOfInventingOne() =
        runTest {
            val source = FakeReceptionSource()

            assertFailsWith<IllegalStateException> { source.checkIn(resId.toString(), input(), Intent()).run(null) }
            assertEquals(1, source.checkInRuns.size)
        }

    @Test
    fun heldCheckInRunIsRecordedBeforeItBlocksAndReleasesWhenTheGateOpens() =
        runTest {
            val source = fake().apply { holdCheckIn = true }
            val result = async { source.checkIn(resId.toString(), input(), Intent()).run(null) }
            runCurrent()

            assertEquals(1, source.checkInRuns.size)
            assertEquals(1, source.heldCheckIns.size)
            assertFalse(result.isCompleted)

            source.heldCheckIns.single().complete(Unit)
            advanceUntilIdle()
            assertEquals(200, result.await().status)
        }

    @Test
    fun heldCheckInRunsReleaseIndependentlyAndOutOfOrder() =
        runTest {
            val source = fake().apply { holdCheckIn = true }
            val a = async { source.checkIn("a", input(), Intent()).run(null) }
            val b = async { source.checkIn("b", input(), Intent()).run(null) }
            runCurrent()

            source.heldCheckIns[1].complete(Unit)
            runCurrent()

            assertFalse(a.isCompleted)
            assertTrue(b.isCompleted)
            source.heldCheckIns[0].complete(Unit)
            runCurrent()
            assertTrue(a.isCompleted)
        }

    @Test
    fun assignUnassignAndDoNotMoveRecordCallsAndReturnTheStoredReservation() =
        runTest {
            val stored = fakeDetail()
            val source = fake().apply { reservations[resId.toString()] = stored }

            assertSame(stored, source.assignRoom(resId.toString(), roomUuid.toString(), null))
            assertSame(stored, source.assignRoom(resId.toString(), roomUuid.toString(), true))
            assertSame(stored, source.unassignRoom(resId.toString()))
            assertSame(stored, source.setDoNotMove(resId.toString(), false))

            assertEquals(
                listOf(
                    AssignCall(resId.toString(), roomUuid.toString(), null),
                    AssignCall(resId.toString(), roomUuid.toString(), true),
                ),
                source.assignCalls,
            )
            assertEquals(listOf(resId.toString()), source.unassignCalls)
            assertEquals(listOf(resId.toString() to false), source.doNotMoveCalls)
        }

    @Test
    fun errorsAreThrownPerOperationAndTheCallIsStillRecorded() =
        runTest {
            val source =
                fake().apply {
                    reservations[resId.toString()] = fakeDetail()
                    assignError = ApiError.Problem(409, "ROOM_ALREADY_ASSIGNED", null, emptyList())
                    unassignError = ApiError.Network(IOException("off"))
                    doNotMoveError = ApiError.PreconditionFailed(null)
                }

            assertFailsWith<ApiError.Problem> { source.assignRoom(resId.toString(), "r", null) }
            assertFailsWith<ApiError.Network> { source.unassignRoom(resId.toString()) }
            assertFailsWith<ApiError.PreconditionFailed> { source.setDoNotMove(resId.toString(), true) }
            // o erro de uma operação não vaza para as outras
            source.assignError = null
            source.assignRoom(resId.toString(), "r", null)

            assertEquals(2, source.assignCalls.size)
            assertEquals(1, source.unassignCalls.size)
            assertEquals(1, source.doNotMoveCalls.size)
        }

    @Test
    fun heldAssignKeepsTheErrorThatWasSetWhenTheCallStarted() =
        runTest {
            val source =
                fake().apply {
                    reservations[resId.toString()] = fakeDetail()
                    holdAssign = true
                    assignError = ApiError.Problem(409, "ROOM_ALREADY_ASSIGNED", null, emptyList())
                }
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { source.assignRoom(resId.toString(), "r", null) }
                }
            source.assignError = null
            source.heldAssigns.single().complete(Unit)
            advanceUntilIdle()

            assertTrue(result.await().exceptionOrNull() is ApiError.Problem)
        }

    @Test
    fun assignOfAReservationOutsideTheFakeFailsLoud() =
        runTest {
            assertFailsWith<IllegalStateException> { FakeReceptionSource().assignRoom("x", "y", null) }
        }

    @Test
    fun suggestionsQuoteAndStatusesRecordCallsAndHonourErrorsAndHold() =
        runTest {
            val source = fake()

            assertEquals(emptyList(), source.roomSuggestions("a"))
            assertEquals(emptyList(), source.roomStatuses())
            source.suggestionsError = ApiError.Network(IOException("off"))
            source.roomStatusesError = ApiError.Network(IOException("off"))
            source.quoteError = ApiError.Network(IOException("off"))
            assertFailsWith<ApiError.Network> { source.roomSuggestions("b") }
            assertFailsWith<ApiError.Network> { source.roomStatuses() }
            assertFailsWith<ApiError.Network> { source.earlyLateQuote("c") }

            assertEquals(listOf("a", "b"), source.suggestionCalls)
            assertEquals(2, source.roomStatusesCalls)
            assertEquals(listOf("c"), source.quoteCalls)

            source.suggestionsError = null
            source.holdSuggestions = true
            val held = async { source.roomSuggestions("d") }
            runCurrent()
            assertFalse(held.isCompleted)
            source.heldSuggestions.single().complete(Unit)
            runCurrent()
            assertTrue(held.isCompleted)
        }

    @Test
    fun searchRecordsNamesAndNeverTheDocumentNumber() =
        runTest {
            val source =
                fake().apply {
                    guestsByName["Ana"] = listOf(pick(1), pick(2))
                    guestsByDocument["52998224725"] = listOf(pick(3))
                }

            assertEquals(listOf(pick(1), pick(2)), source.searchGuests("Ana"))
            assertEquals(emptyList(), source.searchGuests("Zé"))
            assertEquals(listOf(pick(3)), source.searchGuestsByDocument("CPF", "52998224725", "BR"))
            assertEquals(emptyList(), source.searchGuestsByDocument("CPF", "00000000000", null))

            assertEquals(listOf("Ana", "Zé"), source.searchCalls)
            assertEquals(listOf("CPF" to "BR", "CPF" to null), source.documentSearchCalls)
            assertFalse(source.documentSearchCalls.toString().contains("52998224725"))
        }

    @Test
    fun heldSearchReleasesAndErrorsAreThrown() =
        runTest {
            val source = fake().apply { guestsByName["Ana"] = listOf(pick(1)) }
            source.holdSearch = true
            val held = async { source.searchGuests("Ana") }
            runCurrent()
            assertFalse(held.isCompleted)
            source.heldSearches.single().complete(Unit)
            runCurrent()
            assertEquals(listOf(pick(1)), held.await())

            source.holdSearch = false
            source.searchError = ApiError.Problem(422, "VALIDATION_ERROR", null, emptyList())
            assertFailsWith<ApiError.Problem> { source.searchGuests("Ana") }
            assertFailsWith<ApiError.Problem> { source.searchGuestsByDocument("CPF", "1", null) }
        }

    @Test
    fun createGuestCountsReturnsTheScriptedPickAndHonoursErrorAndHold() =
        runTest {
            val source = fake().apply { createdGuest = pick(9) }
            val newGuest =
                com.zeny.desktop.core.facade.NewGuest(
                    "Fulana",
                    "CPF",
                    "52998224725",
                    null,
                    "BR",
                    com.zeny.desktop.core.types.BusinessDate
                        .parse("1990-01-01"),
                )

            assertEquals(pick(9), source.createGuest(newGuest))
            assertEquals(1, source.createGuestCalls)

            source.holdCreateGuest = true
            val held = async { source.createGuest(newGuest) }
            runCurrent()
            assertFalse(held.isCompleted)
            source.heldCreateGuests.single().complete(Unit)
            runCurrent()
            assertEquals(pick(9), held.await())

            source.holdCreateGuest = false
            source.createGuestError = ApiError.Problem(422, "GUEST_DATA_INCOMPLETE", null, emptyList())
            assertFailsWith<ApiError.Problem> { source.createGuest(newGuest) }
            assertEquals(3, source.createGuestCalls)
            source.createdGuest = null
            source.createGuestError = null
            assertFailsWith<IllegalStateException> { source.createGuest(newGuest) }
        }

    @Test
    fun heldCallsAreCancelledWithTheirScopeAndLeaveNothingRunning() =
        runTest {
            val source = fake().apply { holdCheckIn = true }
            val job = async { source.checkIn(resId.toString(), input(), Intent()).run(null) }
            runCurrent()
            job.cancel()
            runCurrent()

            assertTrue(job.isCancelled)
            assertEquals(1, source.checkInRuns.size)
            val gate: CompletableDeferred<Unit> = source.heldCheckIns.single()
            assertFalse(gate.isCompleted)
        }

    @Test
    fun theE01BehaviourOfTheFakeIsIntact() =
        runTest {
            val source = FakeReceptionSource()

            assertTrue(source.permissions().isNotEmpty())
            assertEquals(1, source.permissionsCalls)
            source.permissionsError = ApiError.Network(IOException("off"))
            assertFailsWith<ApiError.Network> { source.permissions() }
            assertEquals(2, source.permissionsCalls)
            assertEquals(0, source.checkInRuns.size)
            assertEquals(0, source.roomTypesCalls)
        }

    @Test
    fun panelPermissionsStayEmptyWhenPermissionsFails() =
        runTest {
            val source =
                FakeReceptionSource().apply { permissionsError = ApiError.Network(IOException("off")) }
            val panel = DayPanelState(source, backgroundScope)

            assertEquals(emptySet(), panel.permissions)
            runCurrent()

            assertEquals(emptySet(), panel.permissions)
            assertEquals(1, source.permissionsCalls)
            assertEquals(0, source.subscriptions)
        }

    @Test
    fun panelPermissionsStayEmptyWhileTheReadIsPendingAndNeverCallPermissionsTwice() =
        runTest {
            val source = FakeReceptionSource().apply { permissions = setOf("guests.profiles.read") }
            val panel = DayPanelState(source, backgroundScope)
            runCurrent()
            panel.refresh()
            runCurrent()
            panel.refresh()
            advanceUntilIdle()

            assertEquals(setOf("guests.profiles.read"), panel.permissions)
            assertEquals(1, source.permissionsCalls)
        }

    @Test
    fun panelPermissionsAreTheExactSetEvenWhenItHasNoReadTopics() =
        runTest {
            val source = FakeReceptionSource().apply { permissions = setOf("frontdesk.stays.checkin") }
            val panel = DayPanelState(source, backgroundScope)
            runCurrent()

            assertEquals(setOf("frontdesk.stays.checkin"), panel.permissions)
        }
}
