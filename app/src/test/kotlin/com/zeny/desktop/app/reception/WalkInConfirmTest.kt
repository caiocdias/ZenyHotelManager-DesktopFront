package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.CheckInGuest
import com.zeny.desktop.core.facade.WalkInInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.StayReservationResponse
import com.zeny.desktop.sdk.model.StayRestrictionAlertResponse
import com.zeny.desktop.sdk.model.StayRoomResponse
import com.zeny.desktop.sdk.model.WalkInResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val wcTypeStd = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val wcTypeLux = Uuid.parse("10000000-0000-0000-0000-000000000002")
private val wcRoom101 = Uuid.parse("50000000-0000-0000-0000-000000000101")
private val wcRoom103 = Uuid.parse("50000000-0000-0000-0000-000000000103")
private val wcRoom201 = Uuid.parse("50000000-0000-0000-0000-000000000201")
private val wcGuestUuid = Uuid.parse("70000000-0000-0000-0000-000000000001")

private const val UNCONFIRMED_TEXT =
    "Não foi possível confirmar o walk-in. Conclua de novo sem mudar nada; " +
        "se fechar, confira em Hospedados antes de refazer."

private val wcGuest = GuestPick(wcGuestUuid.toString(), HolderSummary("Ana Teste", "CPF ***.***.***-01"))
private val wcOtherGuest = GuestPick("70000000-0000-0000-0000-000000000002", HolderSummary("Bia Teste", null))

private fun wcOffer(
    type: Uuid,
    name: String,
    total: String = "300.00",
) = QuoteOfferResponse(
    accommodationTotal = total,
    additionalPackagesTotal = "0.00",
    availableUnits = 2,
    cancellationPolicyId = Uuid.random(),
    category = QuoteOfferResponse.Category.NORMAL,
    guaranteePolicyId = Uuid.random(),
    includedPackagesTotal = "0.00",
    mealPlan = "BREAKFAST",
    nights = emptyList(),
    ratePlanCode = "BAR",
    ratePlanId = Uuid.random(),
    ratePlanName = "Balcão",
    roomTotal = total,
    roomTypeCode = "STD",
    roomTypeId = type,
    roomTypeName = name,
    total = total,
)

private fun wcQuote(vararg offers: QuoteOfferResponse) =
    QuoteResponse(
        adults = 1,
        arrival = "2026-11-13",
        channel = "DIRECT",
        childAges = emptyList(),
        departure = "2026-11-15",
        nights = 2,
        offers = offers.toList(),
    )

private fun wcStatus(
    room: Uuid,
    number: String,
    type: Uuid = wcTypeStd,
) = RoomStatusResponse(
    active = true,
    changedAt = "2026-11-12T10:00:00Z",
    housekeepingStatus = RoomStatusResponse.HousekeepingStatus.CLEAN,
    inspectionPending = false,
    number = number,
    occupancy = RoomStatusResponse.Occupancy.VACANT,
    readyForCheckIn = true,
    roomId = room,
    roomTypeId = type,
    serviceFlag = RoomStatusResponse.ServiceFlag.NONE,
    version = 1,
)

private fun wcResponse(reasons: List<String> = emptyList()) =
    WalkInResponse(
        bookingCode = "B-12",
        bookingId = Uuid.random(),
        holderGuestId = wcGuestUuid,
        reservation =
            StayReservationResponse(
                adults = 1,
                arrival = "2026-11-13",
                bookingId = Uuid.random(),
                children = 0,
                code = "R-90",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guests = emptyList(),
                id = Uuid.random(),
                roomTypeId = wcTypeStd,
                status = "CHECKED_IN",
                version = 2,
            ),
        restrictionAlerts =
            reasons.map { StayRestrictionAlertResponse(guestId = wcGuestUuid, reason = it, since = "2026-11-01") },
        room =
            StayRoomResponse(
                housekeepingStatus = StayRoomResponse.HousekeepingStatus.CLEAN,
                inspectionPending = false,
                number = "101",
                occupancy = StayRoomResponse.Occupancy.OCCUPIED,
                roomId = wcRoom101,
                version = 3,
            ),
        total = "600.00",
    )

private fun wcProblem(
    status: Int,
    code: String,
) = ApiError.Problem(status, code, "detalhe do servidor", emptyList())

private fun wcApproval() = ApiError.ApprovalRequired("WALK_IN", "x", "res", null, null)

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class WalkInApprover(
    var cancel: Boolean = false,
) {
    var calls = 0

    suspend fun approve(action: ApprovableAction<WalkInResponse>): ApiResponse<WalkInResponse>? {
        calls++
        return try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WalkInConfirmTest {
    private val standard = wcOffer(wcTypeStd, "Standard")
    private val luxury = wcOffer(wcTypeLux, "Luxo", "500.00")
    private val approver = WalkInApprover()
    private var changes = 0

    private fun source() =
        FakeReceptionSource().apply {
            stayQuote = wcQuote(standard, luxury)
            roomStatusList +=
                listOf(
                    wcStatus(wcRoom103, "103"),
                    wcStatus(wcRoom101, "101"),
                    wcStatus(wcRoom201, "201", type = wcTypeLux),
                )
            walkInResponse = wcResponse()
        }

    private fun TestScope.state(source: FakeReceptionSource) =
        WalkInState(
            source,
            backgroundScope,
            setOf("reservations.availability.read", "guests.profiles.read", "guests.profiles.manage"),
            approver::approve,
        ) { changes++ }

    /** Estadia de 13 a 15/11, oferta Standard, UH 101 e a hóspede principal escolhidas. */
    private fun TestScope.ready(source: FakeReceptionSource): WalkInState =
        state(source).also {
            it.start()
            runCurrent()
            it.departureText = "15/11/2026"
            it.quote()
            runCurrent()
            it.chooseOffer(standard)
            runCurrent()
            it.chooseRoom(it.rooms.first { room -> room.number == "101" })
            it.picker.pick(wcGuest)
        }

    private fun TestScope.confirmNow(state: WalkInState) {
        state.confirm()
        runCurrent()
    }

    private fun tokenlessKeys(source: FakeReceptionSource) =
        source.walkInRuns.filter { it.token == null }.map { it.key }

    @Test
    fun confirmSendsWalkIn() =
        runTest {
            val source = source()
            val state = state(source)
            state.start()
            runCurrent()
            state.departureText = "15/11/2026"
            state.adults = 2
            state.childAges = listOf(5)
            state.segment = "BUSINESS"
            state.quote()
            runCurrent()
            state.chooseOffer(standard)
            runCurrent()
            state.chooseRoom(state.rooms.first { it.number == "101" })
            assertFalse(state.canConfirm)
            state.picker.pick(wcGuest)
            assertTrue(state.picker.canSearch)
            assertTrue(state.picker.canCreate)
            assertEquals(wcGuest, state.primary)
            assertTrue(state.canConfirm)

            confirmNow(state)

            assertEquals(
                WalkInInput(
                    departure = BusinessDate.parse("2026-11-15"),
                    adults = 2,
                    childAges = listOf(5),
                    ratePlanId = standard.ratePlanId.toString(),
                    roomTypeId = wcTypeStd.toString(),
                    roomId = wcRoom101.toString(),
                    segment = "BUSINESS",
                    guests = listOf(CheckInGuest(wcGuest.id, true)),
                    guestCount = 3,
                ),
                source.walkInCalls.single().input,
            )
            assertEquals(1, source.walkInRuns.size)
            assertEquals(1, approver.calls)
        }

    @Test
    fun retryAfterNetworkErrorReusesKey() =
        runTest {
            val source = source().apply { walkInOutcomes += ApiError.Network(IOException("offline")) }
            val state = ready(source)

            confirmNow(state)
            assertNull(state.done)
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
            assertSame(source.walkInCalls[0].intent, source.walkInCalls[1].intent)
            assertNotNull(state.done)
            assertFalse(state.unconfirmed)
        }

    @Test
    fun unknownResultLocksInputs() =
        runTest {
            val source = source().apply { walkInOutcomes += ApiError.Network(IOException("offline")) }
            val state = ready(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertTrue(state.canConfirm)

            state.departureText = "16/11/2026"
            state.adults = 3
            state.childAges = listOf(1)
            state.segment = "EVENT"
            state.quote()
            state.chooseOffer(luxury)
            state.chooseRoom(state.rooms.first { it.number == "103" })
            state.picker.pick(wcOtherGuest)
            runCurrent()

            assertEquals("15/11/2026", state.departureText)
            assertEquals(1, state.adults)
            assertEquals(emptyList(), state.childAges)
            assertEquals("LEISURE", state.segment)
            assertEquals(standard, state.offer)
            assertEquals("101", state.room?.number)
            assertEquals(wcGuest, state.primary)
            assertEquals(1, source.quoteStayCalls.size)
            assertEquals(1, source.assignedRoomsCalls.size)
            assertTrue(state.unconfirmed)

            confirmNow(state)
            assertFalse(state.unconfirmed)
            assertNotNull(state.done)
            assertEquals(
                wcRoom101.toString(),
                source.walkInCalls
                    .last()
                    .input.roomId,
            )
        }

    @Test
    fun unknownResultMessageSuggestsCheckingInHouse() =
        runTest {
            val source = source().apply { walkInOutcomes += ApiError.IncompatibleResponse(IOException("x")) }
            val state = ready(source)

            confirmNow(state)

            assertEquals(UNCONFIRMED_TEXT, state.error)
            assertTrue(state.unconfirmed)
            assertNull(state.done)
            assertEquals(0, changes)
            assertFalse(state.busy)
        }

    @Test
    fun keyInUseKeepsKey() =
        runTest {
            val source = source().apply { walkInOutcomes += wcProblem(409, "IDEMPOTENCY_KEY_IN_USE") }
            val state = ready(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertEquals(UNCONFIRMED_TEXT, state.error)
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
            assertNotNull(state.done)
        }

    @Test
    fun ruleErrorRenewsKey() =
        runTest {
            val refusal = wcProblem(422, "GUEST_DATA_INCOMPLETE")
            val source = source().apply { walkInOutcomes += refusal }
            val state = ready(source)

            confirmNow(state)
            assertEquals(ActionText.walkInError(refusal), state.error)
            assertFalse(state.unconfirmed)
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(2, keys.size)
            assertNotEquals(keys[0], keys[1])
            assertNotSame(source.walkInCalls[0].intent, source.walkInCalls[1].intent)
        }

    @Test
    fun ruleErrorAfterUnknownResultUnlocks() =
        runTest {
            val source =
                source().apply {
                    walkInOutcomes += ApiError.Network(IOException("offline"))
                    walkInOutcomes += wcProblem(422, "GUEST_DATA_INCOMPLETE")
                }
            val state = ready(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            confirmNow(state)

            assertFalse(state.unconfirmed)
            state.picker.pick(wcOtherGuest)
            assertEquals(wcOtherGuest, state.primary)
        }

    @Test
    fun changingRoomOrGuestRenewsKey() =
        runTest {
            val source = source().apply { walkInRequiresApproval = wcApproval() }
            approver.cancel = true
            val state = ready(source)

            confirmNow(state)
            state.chooseRoom(state.rooms.first { it.number == "103" })
            confirmNow(state)
            state.picker.pick(wcOtherGuest)
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(3, keys.size)
            assertEquals(3, keys.toSet().size)
            assertEquals(wcRoom103.toString(), source.walkInCalls[1].input.roomId)
            assertEquals(
                wcOtherGuest.id,
                source.walkInCalls[2]
                    .input.guests
                    .single()
                    .guestId,
            )
        }

    @Test
    fun changingStayRenewsKey() =
        runTest {
            val source = source().apply { walkInRequiresApproval = wcApproval() }
            approver.cancel = true
            val state = ready(source)

            confirmNow(state)
            state.segment = "EVENT"
            confirmNow(state)

            state.departureText = "16/11/2026"
            state.quote()
            runCurrent()
            state.chooseOffer(standard)
            runCurrent()
            state.chooseRoom(state.rooms.first { it.number == "101" })
            confirmNow(state)

            state.adults = 2
            state.quote()
            runCurrent()
            state.chooseOffer(standard)
            runCurrent()
            state.chooseRoom(state.rooms.first { it.number == "101" })
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(4, keys.size)
            assertEquals(4, keys.toSet().size)
            assertEquals(BusinessDate.parse("2026-11-16"), source.walkInCalls[2].input.departure)
            assertEquals(2, source.walkInCalls[3].input.adults)
        }

    @Test
    fun inputsIgnoredWhileBusy() =
        runTest {
            val source = source().apply { holdWalkIn = true }
            val state = ready(source)

            confirmNow(state)
            assertTrue(state.busy)
            assertFalse(state.canConfirm)

            state.departureText = "16/11/2026"
            state.adults = 3
            state.childAges = listOf(1)
            state.segment = "EVENT"
            state.quote()
            state.chooseOffer(luxury)
            state.chooseRoom(state.rooms.first { it.number == "103" })
            state.picker.pick(wcOtherGuest)
            runCurrent()

            assertEquals("15/11/2026", state.departureText)
            assertEquals(1, state.adults)
            assertEquals(emptyList(), state.childAges)
            assertEquals("LEISURE", state.segment)
            assertEquals(standard, state.offer)
            assertEquals("101", state.room?.number)
            assertEquals(wcGuest, state.primary)
            assertEquals(1, source.quoteStayCalls.size)

            source.heldWalkIns.forEach { it.complete(Unit) }
            runCurrent()
            assertFalse(state.busy)
            assertNotNull(state.done)
        }

    @Test
    fun sessionEndedShowsMessage() =
        runTest {
            val source = source().apply { walkInOutcomes += ApiError.SessionEnded(null) }
            val state = ready(source)

            confirmNow(state)

            assertEquals(UNCONFIRMED_TEXT, state.error)
            assertTrue(state.unconfirmed)
            assertNull(state.done)
            assertEquals(0, changes)
            assertFalse(state.busy)

            confirmNow(state)
            val keys = tokenlessKeys(source)
            assertEquals(keys[0], keys[1])
        }

    @Test
    fun approvedRetrySucceeds() =
        runTest {
            val source = source().apply { walkInRequiresApproval = wcApproval() }
            val state = ready(source)

            confirmNow(state)

            assertEquals(listOf(null, "tok"), source.walkInRuns.map { it.token })
            assertEquals(1, source.walkInCalls.size)
            assertNotNull(state.done)
            assertEquals(1, changes)
            assertNull(state.error)
            assertFalse(state.busy)
        }

    @Test
    fun roomTakenRereadsRoomsAndRenewsKey() =
        runTest {
            for (code in listOf(
                "ROOM_ALREADY_ASSIGNED",
                "ROOM_NOT_READY",
                "ROOM_OUT_OF_ORDER",
                "ROOM_TYPE_MISMATCH",
                "ROOM_INACTIVE",
            )) {
                val refusal = wcProblem(409, code)
                val source = source().apply { walkInOutcomes += refusal }
                val state = ready(source)
                source.assignedRooms = setOf(wcRoom101.toString())

                confirmNow(state)

                assertEquals(ActionText.walkInError(refusal), state.error, code)
                assertNull(state.room, code)
                assertEquals(standard, state.offer, code)
                assertEquals(listOf("103"), state.rooms.map { it.number }, code)
                assertEquals(2, source.roomStatusesCalls, code)
                assertFalse(state.unconfirmed, code)

                state.chooseRoom(state.rooms.single())
                confirmNow(state)
                val keys = tokenlessKeys(source)
                assertEquals(2, keys.size, code)
                assertNotEquals(keys[0], keys[1], code)
                assertNotNull(state.done, code)
            }
        }

    @Test
    fun typeUnavailableDropsOfferAndRequotes() =
        runTest {
            for (code in listOf("ROOM_TYPE_UNAVAILABLE", "UNKNOWN_RATE_PLAN", "UNKNOWN_ROOM_TYPE")) {
                val refusal = wcProblem(409, code)
                val source = source().apply { walkInOutcomes += refusal }
                val state = ready(source)
                source.holdQuoteStay = true
                source.stayQuote = wcQuote(luxury)

                confirmNow(state)

                assertEquals(ActionText.walkInError(refusal), state.error, code)
                assertNull(state.offer, code)
                assertNull(state.room, code)
                assertEquals(emptyList(), state.rooms, code)
                assertEquals(emptyList(), state.offers, code)
                assertTrue(state.quoting, code)
                assertEquals(2, source.quoteStayCalls.size, code)
                assertFalse(state.canConfirm, code)

                source.heldQuoteStays.forEach { it.complete(Unit) }
                runCurrent()
                assertEquals(listOf(luxury), state.offers, code)
                assertEquals(ActionText.walkInError(refusal), state.error, code)
                assertEquals(wcGuest, state.primary, code)
            }
        }

    @Test
    fun depositPlanShowsNextStep() =
        runTest {
            val source = source().apply { walkInOutcomes += wcProblem(422, "DEPOSIT_INSUFFICIENT") }
            val state = ready(source)

            confirmNow(state)

            assertEquals(
                "Este plano exige depósito antecipado e não serve ao walk-in. Escolha outra oferta.",
                state.error,
            )
            assertEquals(standard, state.offer)
            assertEquals(listOf(standard, luxury), state.offers)
            assertEquals(1, source.quoteStayCalls.size)
            assertFalse(state.unconfirmed)

            state.chooseOffer(luxury)
            runCurrent()
            assertEquals(luxury, state.offer)
        }

    @Test
    fun otherErrorsUseWalkInText() =
        runTest {
            val refusal = wcProblem(422, "GUEST_ANONYMIZED")
            val source = source().apply { walkInOutcomes += refusal }
            val state = ready(source)

            confirmNow(state)

            assertEquals(ActionText.walkInError(refusal), state.error)
            assertEquals(standard, state.offer)
            assertEquals("101", state.room?.number)
            assertNull(state.done)
            assertEquals(0, changes)
        }

    @Test
    fun approvalCancelledLeavesState() =
        runTest {
            val source = source().apply { walkInRequiresApproval = wcApproval() }
            approver.cancel = true
            val state = ready(source)

            confirmNow(state)

            assertNull(state.error)
            assertNull(state.done)
            assertEquals(0, changes)
            assertFalse(state.busy)
            assertFalse(state.unconfirmed)
            assertEquals(standard, state.offer)
            assertEquals("101", state.room?.number)
            assertEquals(wcGuest, state.primary)
            assertTrue(state.canConfirm)

            approver.cancel = false
            confirmNow(state)
            val keys = tokenlessKeys(source)
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
            assertNotNull(state.done)
        }

    @Test
    fun doubleConfirmSendsOnce() =
        runTest {
            val source = source()
            val state = ready(source)

            state.confirm()
            state.confirm()
            runCurrent()
            state.confirm()
            runCurrent()

            assertEquals(1, source.walkInCalls.size)
            assertEquals(1, source.walkInRuns.size)
        }

    @Test
    fun successNotifiesAndShowsDone() =
        runTest {
            val source = source().apply { walkInResponse = wcResponse(listOf("Pendência no cadastro")) }
            val state = ready(source)

            confirmNow(state)

            assertEquals(
                WalkInDone(
                    reservationCode = "R-90",
                    roomNumber = "101",
                    period = ReceptionText.period("2026-11-13", "2026-11-15", false),
                    total = ReceptionText.money("600.00"),
                    alerts = listOf("Hóspede com restrição: Pendência no cadastro"),
                ),
                state.done,
            )
            assertEquals(1, changes)
            assertNull(state.error)
            assertFalse(state.busy)
            assertFalse(state.unconfirmed)
            assertFalse(state.canConfirm)
        }

    @Test
    fun doneToStringHidesAlerts() {
        val done = WalkInDone("R-90", "101", "13/11 a 15/11", "R$ 600,00", listOf("Hóspede com restrição: Segredo"))

        val text = done.toString()

        assertFalse("Segredo" in text)
        assertTrue("R-90" in text)
        assertTrue("101" in text)
    }

    @Test
    fun closeClosesPicker() =
        runTest {
            val source = source()
            val state = ready(source)
            state.picker.documentNumber = "12345678901"
            state.picker.query = "Ana"

            state.close()
            state.picker.searchByName()
            runCurrent()

            assertEquals("", state.picker.documentNumber)
            assertTrue(source.searchCalls.isEmpty())
        }
}
