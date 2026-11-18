package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
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

private val waStd = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val waLux = Uuid.parse("10000000-0000-0000-0000-000000000002")
private val waRoom101 = Uuid.parse("50000000-0000-0000-0000-000000000101")
private val waRoom103 = Uuid.parse("50000000-0000-0000-0000-000000000103")
private val waRoom201 = Uuid.parse("50000000-0000-0000-0000-000000000201")
private val waGuestUuid = Uuid.parse("70000000-0000-0000-0000-000000000001")

private const val WA_UNCONFIRMED =
    "Não foi possível confirmar o walk-in. Conclua de novo sem mudar nada; " +
        "se fechar, confira em Hospedados antes de refazer."
private const val WA_DETAIL = "detalhe do servidor"

private val waGuest = GuestPick(waGuestUuid.toString(), HolderSummary("Ana Teste", "CPF ***.***.***-01"))
private val waOtherGuest = GuestPick("70000000-0000-0000-0000-000000000002", HolderSummary("Bia Teste", null))
private val waCreatedGuest = GuestPick("70000000-0000-0000-0000-000000000003", HolderSummary("Caio Teste", null))

private fun waOffer(
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

private fun waQuote(vararg offers: QuoteOfferResponse) =
    QuoteResponse(
        adults = 1,
        arrival = "2026-11-13",
        channel = "DIRECT",
        childAges = emptyList(),
        departure = "2026-11-15",
        nights = 2,
        offers = offers.toList(),
    )

private fun waStatus(
    room: Uuid,
    number: String,
    type: Uuid = waStd,
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

@Suppress("LongParameterList") // a resposta do servidor, um parâmetro por campo que o teste varia
private fun waResponse(
    reservationCode: String = "R-90",
    bookingCode: String = "B-12",
    arrival: String = "2026-11-13",
    departure: String = "2026-11-15",
    dayUse: Boolean = false,
    roomNumber: String = "101",
    total: String = "600.00",
    reasons: List<String> = emptyList(),
) = WalkInResponse(
    bookingCode = bookingCode,
    bookingId = Uuid.random(),
    holderGuestId = waGuestUuid,
    reservation =
        StayReservationResponse(
            adults = 1,
            arrival = arrival,
            bookingId = Uuid.random(),
            children = 0,
            code = reservationCode,
            dayUse = dayUse,
            departure = departure,
            folioId = Uuid.random(),
            guests = emptyList(),
            id = Uuid.random(),
            roomTypeId = waStd,
            status = "CHECKED_IN",
            version = 2,
        ),
    restrictionAlerts =
        reasons.map { StayRestrictionAlertResponse(guestId = waGuestUuid, reason = it, since = "2026-11-01") },
    room =
        StayRoomResponse(
            housekeepingStatus = StayRoomResponse.HousekeepingStatus.CLEAN,
            inspectionPending = false,
            number = roomNumber,
            occupancy = StayRoomResponse.Occupancy.OCCUPIED,
            roomId = waRoom101,
            version = 3,
        ),
    total = total,
)

private fun waProblem(
    status: Int,
    code: String,
) = ApiError.Problem(status, code, WA_DETAIL, emptyList())

private fun waApproval() = ApiError.ApprovalRequired("WALK_IN", "x", "res", null, null)

/** Os quatro resultados desconhecidos da convenção da E02. */
private fun waUnknownOutcomes(): List<Pair<String, Throwable>> =
    listOf(
        "Network" to ApiError.Network(IOException("offline")),
        "IncompatibleResponse" to ApiError.IncompatibleResponse(IOException("formato")),
        "SessionEnded" to ApiError.SessionEnded(null),
        "KEY_IN_USE" to waProblem(409, "IDEMPOTENCY_KEY_IN_USE"),
    )

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela. */
private class WaApprover(
    var cancel: Boolean = false,
) {
    suspend fun approve(action: ApprovableAction<WalkInResponse>): ApiResponse<WalkInResponse>? =
        try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // o ataque da conclusão do walk-in: um teste por comportamento
class WalkInConfirmAtaqueTest {
    private val standard = waOffer(waStd, "Standard")
    private val standardAlt = waOffer(waStd, "Standard", "320.00")
    private val luxury = waOffer(waLux, "Luxo", "500.00")
    private val approver = WaApprover()
    private var changes = 0

    private fun source() =
        FakeReceptionSource().apply {
            stayQuote = waQuote(standard, standardAlt, luxury)
            roomStatusList +=
                listOf(
                    waStatus(waRoom103, "103"),
                    waStatus(waRoom101, "101"),
                    waStatus(waRoom201, "201", type = waLux),
                )
            walkInResponse = waResponse()
            createdGuest = waCreatedGuest
        }

    private fun TestScope.state(source: FakeReceptionSource) =
        WalkInState(
            source,
            backgroundScope,
            setOf("reservations.availability.read", "guests.profiles.read", "guests.profiles.manage"),
            approver::approve,
        ) { changes++ }

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
            it.picker.pick(waGuest)
        }

    private fun TestScope.confirmNow(state: WalkInState) {
        state.confirm()
        runCurrent()
    }

    private fun TestScope.requoteAndChoose(
        state: WalkInState,
        offer: QuoteOfferResponse = standard,
        number: String = "101",
    ) {
        state.quote()
        runCurrent()
        state.chooseOffer(offer)
        runCurrent()
        state.chooseRoom(state.rooms.first { it.number == number })
    }

    private fun keys(source: FakeReceptionSource) = source.walkInRuns.filter { it.token == null }.map { it.key }

    private fun fillNewGuest(state: WalkInState) {
        val byName =
            state.picker.form.fields
                .associateBy { it.name }
        byName.getValue("fullName").onChange("Caio Teste")
        byName.getValue("documentType").onChange("CPF")
        byName.getValue("documentNumber").onChange("12345678909")
        byName.getValue("issuingCountry").onChange("")
        byName.getValue("nationality").onChange("BR")
        byName.getValue("birthDate").onChange("10/05/1990")
    }

    // ---- a regra de renovação, campo a campo da tupla ----

    @Test
    fun everyTupleFieldChangeRenewsKey() =
        runTest {
            val mutations: List<Pair<String, TestScope.(WalkInState) -> Unit>> =
                listOf(
                    "departure" to { s ->
                        s.departureText = "16/11/2026"
                        requoteAndChoose(s)
                    },
                    "adults" to { s ->
                        s.adults = 2
                        requoteAndChoose(s)
                    },
                    "childAges" to { s ->
                        s.childAges = listOf(4)
                        requoteAndChoose(s)
                    },
                    "segment" to { s -> s.segment = "EVENT" },
                    "ratePlanId" to { s -> requoteAndChoose(s, standardAlt) },
                    "roomTypeId" to { s -> requoteAndChoose(s, luxury, "201") },
                    "roomId" to { s -> s.chooseRoom(s.rooms.first { it.number == "103" }) },
                    "guestId" to { s -> s.picker.pick(waOtherGuest) },
                )
            for ((field, mutate) in mutations) {
                val source = source().apply { walkInRequiresApproval = waApproval() }
                approver.cancel = true
                val state = ready(source)

                confirmNow(state)
                mutate(state)
                confirmNow(state)

                val calls = source.walkInCalls
                assertEquals(2, calls.size, field)
                assertNotSame(calls[0].intent, calls[1].intent, field)
                val sent = keys(source)
                assertEquals(2, sent.size, field)
                assertNotEquals(sent[0], sent[1], field)
            }
        }

    @Test
    fun unchangedTupleKeepsIntentAcrossCancelledApproval() =
        runTest {
            val source = source().apply { walkInRequiresApproval = waApproval() }
            approver.cancel = true
            val state = ready(source)

            confirmNow(state)
            state.segment = "LEISURE"
            state.adults = 1
            state.chooseRoom(state.rooms.first { it.number == "101" })
            state.picker.pick(waGuest)
            confirmNow(state)

            assertEquals(2, source.walkInCalls.size)
            assertSame(source.walkInCalls[0].intent, source.walkInCalls[1].intent)
            assertEquals(source.walkInCalls[0].input, source.walkInCalls[1].input)
        }

    @Test
    fun definitiveRefusalRenewsKeyEvenWithSameTuple() =
        runTest {
            val codes =
                listOf(
                    "GUEST_DATA_INCOMPLETE" to false,
                    "GUEST_ANONYMIZED" to false,
                    "DEPOSIT_INSUFFICIENT" to false,
                    "ROOM_NOT_READY" to true,
                    "ROOM_ALREADY_ASSIGNED" to true,
                )
            for ((code, rePickRoom) in codes) {
                val source = source().apply { walkInOutcomes += waProblem(422, code) }
                val state = ready(source)

                confirmNow(state)
                if (rePickRoom) state.chooseRoom(state.rooms.first { it.number == "101" })
                confirmNow(state)

                val sent = keys(source)
                assertEquals(2, sent.size, code)
                assertNotEquals(sent[0], sent[1], code)
                assertEquals(source.walkInCalls[0].input, source.walkInCalls[1].input, code)
                assertNotNull(state.done, code)
            }
        }

    // ---- a trava depois de resultado desconhecido ----

    @Test
    fun everyUnknownOutcomeLocksEveryInputAndRetriesSameKeyAndBody() =
        runTest {
            for ((name, outcome) in waUnknownOutcomes()) {
                val source = source().apply { walkInOutcomes += outcome }
                val state = ready(source)

                confirmNow(state)
                assertTrue(state.unconfirmed, name)
                assertEquals(WA_UNCONFIRMED, state.error, name)
                assertFalse(state.busy, name)

                state.departureText = "16/11/2026"
                state.adults = 2
                state.childAges = listOf(3)
                state.segment = "OTHER"
                state.quote()
                state.chooseOffer(luxury)
                state.chooseOffer(standardAlt)
                state.chooseRoom(state.rooms.first { it.number == "103" })
                state.picker.pick(waOtherGuest)
                runCurrent()

                assertEquals("15/11/2026", state.departureText, name)
                assertEquals(1, state.adults, name)
                assertEquals(emptyList(), state.childAges, name)
                assertEquals("LEISURE", state.segment, name)
                assertEquals(standard, state.offer, name)
                assertEquals("101", state.room?.number, name)
                assertEquals(waGuest, state.primary, name)
                assertEquals(1, source.quoteStayCalls.size, name)
                assertEquals(1, source.roomStatusesCalls, name)
                assertTrue(state.canConfirm, name)

                confirmNow(state)

                assertEquals(2, source.walkInCalls.size, name)
                assertSame(source.walkInCalls[0].intent, source.walkInCalls[1].intent, name)
                assertEquals(source.walkInCalls[0].input, source.walkInCalls[1].input, name)
                assertEquals(1, keys(source).toSet().size, name)
                assertNotNull(state.done, name)
                assertFalse(state.unconfirmed, name)
                assertEquals(1, changes.also { changes = 0 }, name)
            }
        }

    private fun attemptPickerCalls(state: WalkInState) {
        fillNewGuest(state)
        state.picker.create()
        state.picker.query = "Maria"
        state.picker.searchByName()
        state.picker.documentNumber = "12345678909"
        state.picker.searchByDocument()
    }

    private fun assertNoPickerCall(source: FakeReceptionSource) {
        assertEquals(0, source.createGuestCalls)
        assertEquals(emptyList(), source.searchCalls)
        assertEquals(emptyList(), source.documentSearchCalls)
    }

    @Test
    fun pickerCreateAndSearchDuringUnknownLockDoNotCallServerNorChangePrimary() =
        runTest {
            val source = source().apply { walkInOutcomes += ApiError.Network(IOException("offline")) }
            val state = ready(source)
            confirmNow(state)
            assertTrue(state.unconfirmed)

            attemptPickerCalls(state)
            runCurrent()

            assertNoPickerCall(source)
            assertEquals(waGuest, state.primary)
            confirmNow(state)
            assertEquals(
                waGuest.id,
                source.walkInCalls
                    .last()
                    .input.guests
                    .single()
                    .guestId,
            )
        }

    @Test
    fun pickerCreateAndSearchDuringBusyDoNotCallServerNorChangePrimary() =
        runTest {
            val source = source().apply { holdWalkIn = true }
            val state = ready(source)
            confirmNow(state)
            assertTrue(state.busy)

            attemptPickerCalls(state)
            runCurrent()
            assertNoPickerCall(source)

            source.heldWalkIns.forEach { it.complete(Unit) }
            runCurrent()
            assertNoPickerCall(source)
            assertEquals(waGuest, state.primary)
            assertNotNull(state.done)
        }

    @Test
    fun cancelledApprovalOnRetryKeepsLockMessageAndKey() =
        runTest {
            val source = source().apply { walkInOutcomes += ApiError.Network(IOException("offline")) }
            val state = ready(source)
            confirmNow(state)
            assertTrue(state.unconfirmed)

            source.walkInRequiresApproval = waApproval()
            approver.cancel = true
            confirmNow(state)

            assertFalse(state.unconfirmed)
            assertEquals("Aprovação cancelada. O walk-in não foi concluído.", state.error)
            assertNull(state.done)
            assertFalse(state.busy)
            assertTrue(state.canConfirm, "formulário destravado: dá para concluir de novo")

            approver.cancel = false
            confirmNow(state)
            assertNotNull(state.done)
            assertEquals(3, source.walkInCalls.size)
            assertSame(source.walkInCalls[0].intent, source.walkInCalls[2].intent)
            assertEquals(1, changes)
        }

    @Test
    fun networkNetworkSuccessCreatesOneWalkInWithOneKey() =
        runTest {
            val source =
                source().apply {
                    walkInOutcomes += ApiError.Network(IOException("offline"))
                    walkInOutcomes += ApiError.IncompatibleResponse(IOException("formato"))
                }
            val state = ready(source)

            confirmNow(state)
            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertNull(state.done)
            confirmNow(state)

            val sent = keys(source)
            assertEquals(3, sent.size)
            assertEquals(1, sent.toSet().size)
            assertEquals(
                1,
                source.walkInCalls
                    .map { it.intent }
                    .toSet()
                    .size,
            )
            assertNotNull(state.done)
            assertFalse(state.unconfirmed)
            assertEquals(1, changes)
            // Com a conclusão feita, mais um clique não cria outro walk-in.
            confirmNow(state)
            assertEquals(3, source.walkInRuns.size)
        }

    @Test
    fun networkThenRuleThenSuccessRenewsKeyOnlyAfterTheRule() =
        runTest {
            val source =
                source().apply {
                    walkInOutcomes += ApiError.Network(IOException("offline"))
                    walkInOutcomes += waProblem(422, "GUEST_DATA_INCOMPLETE")
                }
            val state = ready(source)

            confirmNow(state)
            confirmNow(state)
            assertFalse(state.unconfirmed)
            assertEquals(ActionText.walkInError(waProblem(422, "GUEST_DATA_INCOMPLETE")), state.error)
            confirmNow(state)

            val sent = keys(source)
            assertEquals(3, sent.size)
            assertEquals(sent[0], sent[1])
            assertNotEquals(sent[1], sent[2])
            assertNotNull(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun definitiveResponsesUnlockTheInputs() =
        runTest {
            val definitive: List<Throwable> =
                listOf(
                    waProblem(403, "FORBIDDEN"),
                    waProblem(404, "UNKNOWN_ROOM"),
                    waProblem(422, "UNKNOWN_GUEST"),
                    waProblem(409, "SOME_NEW_CODE"),
                    ApiError.PreconditionFailed(null),
                )
            for (outcome in definitive) {
                val source =
                    source().apply {
                        walkInOutcomes += ApiError.Network(IOException("offline"))
                        walkInOutcomes += outcome
                    }
                val state = ready(source)
                val label = outcome::class.simpleName + (outcome as? ApiError.Problem)?.code

                confirmNow(state)
                assertTrue(state.unconfirmed, label)
                confirmNow(state)

                assertFalse(state.unconfirmed, label)
                assertNull(state.done, label)
                state.segment = "EVENT"
                assertEquals("EVENT", state.segment, label)
                state.picker.pick(waOtherGuest)
                assertEquals(waOtherGuest, state.primary, label)
            }
        }

    @Test
    fun offerRefusalAfterUnknownResultUnlocksAndRequotes() =
        runTest {
            val source =
                source().apply {
                    walkInOutcomes += ApiError.Network(IOException("offline"))
                    walkInOutcomes += waProblem(409, "ROOM_TYPE_UNAVAILABLE")
                }
            val state = ready(source)

            confirmNow(state)
            confirmNow(state)

            assertFalse(state.unconfirmed)
            assertNull(state.offer)
            assertEquals(2, source.quoteStayCalls.size)
            assertEquals(source.quoteStayCalls[0], source.quoteStayCalls[1])
            assertEquals(waGuest, state.primary)
            assertEquals("LEISURE", state.segment)
        }

    @Test
    fun roomRefusalAfterUnknownResultUnlocksAndRereadsRooms() =
        runTest {
            val source =
                source().apply {
                    walkInOutcomes += ApiError.Network(IOException("offline"))
                    walkInOutcomes += waProblem(409, "ROOM_ALREADY_ASSIGNED")
                }
            val state = ready(source)
            confirmNow(state)
            source.assignedRooms = setOf(waRoom101.toString())

            confirmNow(state)

            assertFalse(state.unconfirmed)
            assertNull(state.room)
            assertEquals(listOf("103"), state.rooms.map { it.number })
            assertEquals(2, source.assignedRoomsCalls.size)
            assertEquals(source.assignedRoomsCalls[0], source.assignedRoomsCalls[1])
            state.chooseRoom(state.rooms.single())
            confirmNow(state)
            assertNotNull(state.done)
            val sent = keys(source)
            assertEquals(3, sent.size)
            assertNotEquals(sent[1], sent[2])
        }

    @Test
    fun rawNonApiExceptionCountsAsUnknownResult() =
        runTest {
            val source = source().apply { walkInOutcomes += IOException("socket fechou") }
            val state = ready(source)

            confirmNow(state)

            assertTrue(state.unconfirmed)
            assertEquals(WA_UNCONFIRMED, state.error)
            confirmNow(state)
            val sent = keys(source)
            assertEquals(2, sent.size)
            assertEquals(sent[0], sent[1])
        }

    @Test
    fun unknownResultAfterApprovedRunKeepsIntentAndAsksApprovalAgain() =
        runTest {
            val source =
                source().apply {
                    walkInRequiresApproval = waApproval()
                    walkInOutcomes += ApiError.Network(IOException("offline"))
                }
            val state = ready(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertEquals(listOf(null, "tok"), source.walkInRuns.map { it.token })
            confirmNow(state)

            assertEquals(listOf(null, "tok", null, "tok"), source.walkInRuns.map { it.token })
            assertSame(source.walkInCalls[0].intent, source.walkInCalls[1].intent)
            assertEquals(source.walkInCalls[0].input, source.walkInCalls[1].input)
            assertNotNull(state.done)
            assertEquals(1, changes)
        }

    // ---- desfechos de erro do brief ----

    @Test
    fun errorsNeverNotifyOrFinish() =
        runTest {
            val outcomes: List<Throwable> =
                waUnknownOutcomes().map { it.second } +
                    listOf(
                        waProblem(422, "GUEST_ANONYMIZED"),
                        waProblem(409, "ROOM_NOT_READY"),
                        waProblem(409, "ROOM_TYPE_UNAVAILABLE"),
                        waProblem(422, "DEPOSIT_INSUFFICIENT"),
                    )
            for (outcome in outcomes) {
                val source = source().apply { walkInOutcomes += outcome }
                val state = ready(source)

                confirmNow(state)

                assertNull(state.done, outcome.message)
                assertFalse(state.busy, outcome.message)
                assertNotNull(state.error, outcome.message)
            }
            assertEquals(0, changes)
        }

    @Test
    fun roomRefusalKeepsOfferOffersAndGuestAndDoesNotRequote() =
        runTest {
            val source = source().apply { walkInOutcomes += waProblem(409, "ROOM_OUT_OF_ORDER") }
            val state = ready(source)

            confirmNow(state)

            assertEquals(standard, state.offer)
            assertEquals(listOf(standard, standardAlt, luxury), state.offers)
            assertEquals(waGuest, state.primary)
            assertEquals(1, source.quoteStayCalls.size)
            assertEquals(2, source.roomStatusesCalls)
            assertEquals(listOf("101", "103"), state.rooms.map { it.number })
        }

    @Test
    fun roomRefusalBlocksConfirmWhileRoomsAreReread() =
        runTest {
            val source = source().apply { walkInOutcomes += waProblem(409, "ROOM_ALREADY_ASSIGNED") }
            val state = ready(source)
            source.holdAssignedRooms = true

            confirmNow(state)
            assertTrue(state.loadingRooms)
            assertFalse(state.canConfirm)
            state.confirm()
            runCurrent()
            assertEquals(1, source.walkInRuns.size)

            source.heldAssignedRooms.forEach { it.complete(Unit) }
            runCurrent()
            assertFalse(state.loadingRooms)
            assertNull(state.room)
            assertFalse(state.canConfirm)
            assertEquals(ActionText.walkInError(waProblem(409, "ROOM_ALREADY_ASSIGNED")), state.error)
        }

    @Test
    fun problemWithoutCodeDoesNotRereadOrRequote() =
        runTest {
            val source = source().apply { walkInOutcomes += ApiError.Problem(500, null, null, emptyList()) }
            val state = ready(source)

            confirmNow(state)

            assertEquals(1, source.roomStatusesCalls)
            assertEquals(1, source.quoteStayCalls.size)
            assertEquals(standard, state.offer)
            assertEquals("101", state.room?.number)
            assertNotNull(state.error)
        }

    @Test
    fun offerRefusalRequotesWithTheSameStayAndKeepsGuestAndSegment() =
        runTest {
            for (code in listOf("ROOM_TYPE_UNAVAILABLE", "UNKNOWN_RATE_PLAN", "UNKNOWN_ROOM_TYPE")) {
                val source = source().apply { walkInOutcomes += waProblem(409, code) }
                val state = state(source)
                state.start()
                runCurrent()
                state.departureText = "15/11/2026"
                state.adults = 2
                state.childAges = listOf(6)
                state.segment = "BUSINESS"
                requoteAndChoose(state)
                state.picker.pick(waGuest)

                confirmNow(state)

                assertEquals(2, source.quoteStayCalls.size, code)
                assertEquals(source.quoteStayCalls[0], source.quoteStayCalls[1], code)
                assertEquals(1, source.roomStatusesCalls, code)
                assertEquals("BUSINESS", state.segment, code)
                assertEquals(waGuest, state.primary, code)
                assertEquals(listOf(standard, standardAlt, luxury), state.offers, code)
                assertFalse(state.quoting, code)

                state.chooseOffer(standard)
                runCurrent()
                state.chooseRoom(state.rooms.first { it.number == "101" })
                confirmNow(state)
                val sent = keys(source)
                assertEquals(2, sent.size, code)
                assertNotEquals(sent[0], sent[1], code)
                assertNotNull(state.done, code)
            }
        }

    @Test
    fun offerRefusalWithEmptyRequoteShowsNoOffersAndNoStaleOffers() =
        runTest {
            val source = source().apply { walkInOutcomes += waProblem(409, "ROOM_TYPE_UNAVAILABLE") }
            val state = ready(source)
            source.stayQuote = waQuote()

            confirmNow(state)

            assertEquals(emptyList(), state.offers)
            assertTrue(state.noOffers)
            assertNull(state.offer)
            assertFalse(state.canConfirm)
            assertEquals(ActionText.walkInError(waProblem(409, "ROOM_TYPE_UNAVAILABLE")), state.error)
        }

    @Test
    fun depositRefusalKeepsOfferAndRenewsKeyOnNextOffer() =
        runTest {
            val source = source().apply { walkInOutcomes += waProblem(422, "DEPOSIT_INSUFFICIENT") }
            val state = ready(source)

            confirmNow(state)
            assertEquals(standard, state.offer)
            state.chooseOffer(luxury)
            runCurrent()
            state.chooseRoom(state.rooms.single())
            confirmNow(state)

            assertEquals(luxury.ratePlanId.toString(), source.walkInCalls[1].input.ratePlanId)
            assertEquals(waRoom201.toString(), source.walkInCalls[1].input.roomId)
            val sent = keys(source)
            assertNotEquals(sent[0], sent[1])
            assertNotNull(state.done)
        }

    // ---- aprovação, cliques e canConfirm ----

    @Test
    fun cancelledApprovalDoesNotRenewKeyOrSetError() =
        runTest {
            val source = source().apply { walkInRequiresApproval = waApproval() }
            approver.cancel = true
            val state = ready(source)

            confirmNow(state)
            confirmNow(state)
            confirmNow(state)

            assertEquals(3, source.walkInCalls.size)
            assertEquals(
                1,
                source.walkInCalls
                    .map { it.intent }
                    .toSet()
                    .size,
            )
            assertEquals(1, keys(source).toSet().size)
            assertTrue(source.walkInRuns.all { it.token == null })
            assertNull(state.error)
            assertFalse(state.unconfirmed)
            assertEquals(0, changes)
        }

    @Test
    fun cancelledApprovalKeepsAnExistingRuleErrorOnScreen() =
        runTest {
            val refusal = waProblem(422, "GUEST_DATA_INCOMPLETE")
            val source = source().apply { walkInOutcomes += refusal }
            val state = ready(source)
            confirmNow(state)
            val shown = state.error
            assertEquals(ActionText.walkInError(refusal), shown)

            source.walkInRequiresApproval = waApproval()
            approver.cancel = true
            confirmNow(state)

            assertEquals(shown, state.error)
            assertNull(state.done)
            assertTrue(state.canConfirm)
        }

    @Test
    fun doubleConfirmWhilePendingApprovalSendsOnce() =
        runTest {
            val source =
                source().apply {
                    walkInRequiresApproval = waApproval()
                    holdWalkIn = true
                }
            val state = ready(source)

            state.confirm()
            runCurrent()
            state.confirm()
            state.confirm()
            runCurrent()

            assertEquals(1, source.walkInCalls.size)
            assertEquals(1, source.walkInRuns.size)
            assertTrue(state.busy)
        }

    @Test
    fun doubleConfirmAfterUnknownResultSendsOnePerClickBurst() =
        runTest {
            val source = source().apply { walkInOutcomes += ApiError.Network(IOException("offline")) }
            val state = ready(source)
            confirmNow(state)

            state.confirm()
            state.confirm()
            state.confirm()
            runCurrent()

            assertEquals(2, source.walkInRuns.size)
            assertNotNull(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun confirmAfterDoneSendsNothing() =
        runTest {
            val source = source()
            val state = ready(source)
            confirmNow(state)
            assertNotNull(state.done)

            assertFalse(state.canConfirm)
            confirmNow(state)

            assertEquals(1, source.walkInRuns.size)
            assertEquals(1, changes)
        }

    @Test
    fun confirmWithoutGuestOrRoomOrOfferSendsNothing() =
        runTest {
            val source = source()
            val noGuest = state(source)
            noGuest.start()
            runCurrent()
            noGuest.departureText = "15/11/2026"
            requoteAndChoose(noGuest)
            assertFalse(noGuest.canConfirm)
            confirmNow(noGuest)

            val noRoom = state(source)
            noRoom.start()
            runCurrent()
            noRoom.departureText = "15/11/2026"
            noRoom.quote()
            runCurrent()
            noRoom.chooseOffer(standard)
            runCurrent()
            noRoom.picker.pick(waGuest)
            assertFalse(noRoom.canConfirm)
            confirmNow(noRoom)

            val noOffer = state(source)
            noOffer.start()
            runCurrent()
            noOffer.picker.pick(waGuest)
            assertFalse(noOffer.canConfirm)
            confirmNow(noOffer)

            assertTrue(source.walkInRuns.isEmpty())
        }

    @Test
    fun canConfirmIsFalseWhileGuestIsBeingRegistered() =
        runTest {
            val source = source().apply { holdCreateGuest = true }
            val state = ready(source)
            assertTrue(state.canConfirm)

            fillNewGuest(state)
            state.picker.create()
            runCurrent()
            assertTrue(state.picker.creating)
            assertFalse(state.canConfirm)
            confirmNow(state)
            assertTrue(source.walkInRuns.isEmpty())

            source.heldCreateGuests.forEach { it.complete(Unit) }
            runCurrent()
            assertFalse(state.picker.creating)
            assertEquals(waCreatedGuest, state.primary)
            assertTrue(state.canConfirm)
            confirmNow(state)
            assertEquals(
                waCreatedGuest.id,
                source.walkInCalls
                    .single()
                    .input.guests
                    .single()
                    .guestId,
            )
        }

    @Test
    fun canConfirmIsFalseWhileOfferRoomsAreLoading() =
        runTest {
            val source = source()
            val state = ready(source)
            source.holdAssignedRooms = true

            state.chooseOffer(luxury)
            runCurrent()

            assertTrue(state.loadingRooms)
            assertFalse(state.canConfirm)
            confirmNow(state)
            assertTrue(source.walkInRuns.isEmpty())
        }

    @Test
    fun guestCountIsCappedAtFortyAndChildrenAreForwarded() =
        runTest {
            val source = source()
            val state = state(source)
            state.start()
            runCurrent()
            state.departureText = "15/11/2026"
            state.adults = 40
            state.childAges = listOf(0, 17, 9)
            requoteAndChoose(state)
            state.picker.pick(waGuest)

            confirmNow(state)

            val input = source.walkInCalls.single().input
            assertEquals(40, input.guestCount)
            assertEquals(40, input.adults)
            assertEquals(listOf(0, 17, 9), input.childAges)
            assertEquals(1, input.guests.size)
            assertTrue(input.guests.single().primary)
        }

    // ---- a conclusão ----

    @Test
    fun doneComesFromTheResponseNotFromTheInput() =
        runTest {
            val source =
                source().apply {
                    walkInResponse =
                        waResponse(
                            reservationCode = "R-777",
                            bookingCode = "B-5",
                            arrival = "2026-11-14",
                            departure = "2026-11-17",
                            roomNumber = "205",
                            total = "1234.50",
                            reasons = listOf("Primeira", "Segunda"),
                        )
                }
            val state = ready(source)

            confirmNow(state)

            assertEquals(
                WalkInDone(
                    reservationCode = "R-777",
                    roomNumber = "205",
                    period = ReceptionText.period("2026-11-14", "2026-11-17", false),
                    total = ReceptionText.money("1234.50"),
                    alerts = listOf("Hóspede com restrição: Primeira", "Hóspede com restrição: Segunda"),
                ),
                state.done,
            )
            assertNotEquals("B-5", state.done?.reservationCode)
        }

    @Test
    fun doneUsesTheDayUseFlagOfTheResponse() =
        runTest {
            val source = source().apply { walkInResponse = waResponse(dayUse = true) }
            val state = ready(source)

            confirmNow(state)

            assertEquals(ReceptionText.period("2026-11-13", "2026-11-15", true), state.done?.period)
        }

    @Test
    fun successAfterUnknownResultClearsTheLockText() =
        runTest {
            val source = source().apply { walkInOutcomes += ApiError.Network(IOException("offline")) }
            val state = ready(source)
            confirmNow(state)
            assertEquals(WA_UNCONFIRMED, state.error)

            confirmNow(state)

            assertNull(state.error)
            assertFalse(state.unconfirmed)
            assertFalse(state.busy)
        }

    // ---- close e cancelamento ----

    @Test
    fun closeDuringConfirmIsNotAnErrorNorUnconfirmedNorNotified() =
        runTest {
            val source = source().apply { holdWalkIn = true }
            val state = ready(source)
            confirmNow(state)
            assertTrue(state.busy)

            state.close()
            source.heldWalkIns.forEach { it.complete(Unit) }
            runCurrent()

            assertFalse(state.busy)
            assertFalse(state.unconfirmed)
            assertNull(state.error)
            assertNull(state.done)
            assertEquals(0, changes)
        }

    @Test
    fun confirmAndInputsAfterCloseDoNothing() =
        runTest {
            val source = source()
            val state = ready(source)

            state.close()
            state.confirm()
            state.chooseRoom(state.rooms.first { it.number == "103" })
            state.picker.pick(waOtherGuest)
            runCurrent()

            assertTrue(source.walkInRuns.isEmpty())
            assertEquals("101", state.room?.number)
            assertEquals(0, changes)
        }

    @Test
    fun closeDuringRoomRefusalRereadIsNotAnErrorOverride() =
        runTest {
            val source = source().apply { walkInOutcomes += waProblem(409, "ROOM_ALREADY_ASSIGNED") }
            val state = ready(source)
            source.holdAssignedRooms = true
            confirmNow(state)

            state.close()
            source.heldAssignedRooms.forEach { it.complete(Unit) }
            runCurrent()

            assertEquals(ActionText.walkInError(waProblem(409, "ROOM_ALREADY_ASSIGNED")), state.error)
        }

    // ---- dados pessoais e textos ----

    @Test
    fun stateToStringHasNoPersonalData() =
        runTest {
            val source = source().apply { walkInResponse = waResponse(reasons = listOf("Segredo")) }
            val state = ready(source)
            state.picker.query = "Ana Teste"
            state.picker.documentNumber = "12345678901"
            confirmNow(state)

            val texts = listOf(state.toString(), state.picker.toString(), state.done.toString())

            for (text in texts) {
                assertFalse("Ana" in text, text)
                assertFalse("12345678901" in text, text)
                assertFalse("CPF" in text, text)
                assertFalse("Segredo" in text, text)
                assertFalse(waGuestUuid.toString() in text, text)
            }
        }

    @Test
    fun errorTextsNeverShowCodeOrServerDetail() =
        runTest {
            val codes =
                listOf(
                    "ROOM_ALREADY_ASSIGNED",
                    "ROOM_NOT_READY",
                    "ROOM_OUT_OF_ORDER",
                    "ROOM_TYPE_MISMATCH",
                    "ROOM_INACTIVE",
                    "ROOM_TYPE_UNAVAILABLE",
                    "UNKNOWN_RATE_PLAN",
                    "UNKNOWN_ROOM_TYPE",
                    "DEPOSIT_INSUFFICIENT",
                    "GUEST_ANONYMIZED",
                    "GUEST_DATA_INCOMPLETE",
                    "IDEMPOTENCY_KEY_IN_USE",
                    "A_CODE_FROM_THE_FUTURE",
                )
            for (code in codes) {
                val source = source().apply { walkInOutcomes += waProblem(409, code) }
                val state = ready(source)

                confirmNow(state)
                runCurrent()

                val text = state.error
                assertNotNull(text, code)
                assertFalse(code in text, code)
                assertFalse('{' in text, code)
                // Um código novo mostra o detalhe se ele for texto de pessoa (ErrorCatalog); os conhecidos, nunca.
                if (code != "A_CODE_FROM_THE_FUTURE") assertFalse(WA_DETAIL in text, code)
            }
        }

    // ---- pendências do handoff da E01 ----

    @Test
    fun cancelledApprovalAfterUnknownResultAcceptsAnEditThatKeepsTheQuote() =
        runTest {
            val source = source().apply { walkInOutcomes += ApiError.Network(IOException("offline")) }
            val state = ready(source)
            confirmNow(state)
            assertTrue(state.unconfirmed)
            state.segment = "EVENT"
            assertEquals("LEISURE", state.segment, "o resultado desconhecido deixou mudar a entrada")

            source.walkInRequiresApproval = waApproval()
            approver.cancel = true
            confirmNow(state)

            // A prova do destravamento é a edição valer, não só o `canConfirm`.
            state.segment = "EVENT"
            assertEquals("EVENT", state.segment, "o formulário ficou travado depois da aprovação cancelada")
            assertTrue(state.canConfirm, "a edição do segmento derrubou a cotação")
            approver.cancel = false
            source.walkInRequiresApproval = null
            confirmNow(state)

            assertNotNull(state.done)
            assertNotEquals(keys(source).first(), keys(source).last(), "a tupla mudou e a chave ficou")
            assertEquals(
                "EVENT",
                source.walkInCalls
                    .last()
                    .input.segment,
            )
        }

    @Test
    fun keyInUseWith500IsUnknownNotStored() =
        runTest {
            val source = source().apply { walkInOutcomes += waProblem(500, "IDEMPOTENCY_KEY_IN_USE") }
            val state = ready(source)

            confirmNow(state)

            assertTrue(state.unconfirmed)
            assertFalse(state.closeOnly, "o IDEMPOTENCY_KEY_IN_USE 500 virou resposta guardada")
            assertEquals(WA_UNCONFIRMED, state.error)
            assertTrue(state.canConfirm)
            confirmNow(state)

            assertNotNull(state.done)
            val sent = keys(source)
            assertEquals(2, sent.size)
            assertEquals(sent[0], sent[1])
            assertEquals(1, changes)
        }

    @Test
    fun lostApprovedRunWithStored5xxIsCloseOnly() =
        runTest {
            val source =
                source().apply {
                    walkInRequiresApproval = waApproval()
                    walkInOutcomes += waProblem(500, "INTERNAL_ERROR")
                }
            val state = ready(source)

            confirmNow(state)

            assertEquals(listOf(null, "tok"), source.walkInRuns.map { it.token })
            assertTrue(state.closeOnly, "a aprovada perdida com 5xx guardado ainda oferece repetir")
            assertFalse(state.canConfirm)
            confirmNow(state)
            assertEquals(2, source.walkInRuns.size, "repetiu a resposta guardada")
            assertNull(state.done)
            assertEquals(0, changes)
        }
}
