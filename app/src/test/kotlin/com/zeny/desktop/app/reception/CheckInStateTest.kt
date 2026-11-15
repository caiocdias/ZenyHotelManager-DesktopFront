package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.CheckInGuest
import com.zeny.desktop.core.facade.CheckInInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.EarlyLateOfferResponse
import com.zeny.desktop.sdk.model.EarlyLateQuoteResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomSuggestionResponse
import com.zeny.desktop.sdk.model.StayReservationResponse
import com.zeny.desktop.sdk.model.StayRestrictionAlertResponse
import com.zeny.desktop.sdk.model.StayRoomResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val checkInResUuid = Uuid.parse("20000000-0000-0000-0000-000000000081")
private val checkInHolderUuid = Uuid.parse("30000000-0000-0000-0000-000000000081")
private val checkInRoomA = Uuid.parse("50000000-0000-0000-0000-0000000000a1")
private val checkInRoomB = Uuid.parse("50000000-0000-0000-0000-0000000000b1")
private val until: Instant = Instant.parse("2026-11-13T15:00:00Z")
private val beforeUntil: Instant = Instant.parse("2026-11-13T13:00:00Z")
private val other = GuestPick("30000000-0000-0000-0000-000000000099", HolderSummary("Outra Pessoa", "CPF ***.999-**"))
private val holderSummary = HolderSummary("Ana Titular", "CPF ***.111.222-**")

/** CPF fictício com dígitos verificadores válidos, a partir de nove dígitos-base. */
private fun ciCpfOf(base: String): String {
    fun digit(
        digits: String,
        weight: Int,
    ): Char {
        val rest = digits.mapIndexed { i, c -> (c - '0') * (weight - i) }.sum() % 11
        return if (rest < 2) '0' else ('0' + (11 - rest))
    }
    val first = digit(base, 10)
    return base + first + digit(base + first, 11)
}

private const val WINDOW_ENDED = "A janela do early check-in terminou. Confira e confirme de novo."
private const val ROOM_NOT_READY_TEXT =
    "A UH ainda não está pronta. Escolha uma UH pronta ou peça a liberação à governança."
private const val TRANSITION_TEXT =
    "A reserva não está mais confirmada; o check-in pode já ter sido feito. Feche e confira o detalhe."
private const val FEE_CHANGED_TEXT = "O valor do early check-in mudou. Confira a nova cotação e confirme de novo."
private val allPermissions =
    setOf(
        "frontdesk.stays.checkin",
        "reservations.assignments.manage",
        "guests.profiles.read",
        "guests.profiles.manage",
    )

private fun ciProblem(
    code: String,
    status: Int = 422,
) = ApiError.Problem(status, code, null, emptyList())

private fun ciNetwork() = ApiError.Network(IOException("offline"))

private fun ciQuote(
    fee: String = "80.00",
    offered: Boolean = true,
    untilText: String? = until.toString(),
) = EarlyLateQuoteResponse(
    arrival = "2026-11-13",
    checkInTime = "14:00",
    checkOutTime = "12:00",
    departure = "2026-11-15",
    earlyCheckIn =
        EarlyLateOfferResponse(
            offered = offered,
            fee = Option.Some(fee),
            limitTime = Option.Some("10:00"),
            from = Option.Some("2026-11-13T13:00:00Z"),
            until = untilText?.let { Option.Some(it) } ?: Option.Undefined,
        ),
    lateCheckOut = EarlyLateOfferResponse(offered = false),
    reservationId = checkInResUuid,
    timezone = "America/Sao_Paulo",
)

private fun ciResponse(
    number: String = "101",
    reasons: List<String> = emptyList(),
) = CheckInResponse(
    reservation =
        StayReservationResponse(
            adults = 2,
            arrival = "2026-11-13",
            bookingId = Uuid.random(),
            children = 1,
            code = "R-81",
            dayUse = false,
            departure = "2026-11-15",
            folioId = Uuid.random(),
            guests = emptyList(),
            id = checkInResUuid,
            roomTypeId = Uuid.random(),
            status = "CHECKED_IN",
            version = 2,
        ),
    restrictionAlerts =
        reasons.map { StayRestrictionAlertResponse(guestId = checkInHolderUuid, reason = it, since = "2026-11-01") },
    room =
        StayRoomResponse(
            housekeepingStatus = StayRoomResponse.HousekeepingStatus.INSPECTED,
            inspectionPending = false,
            number = number,
            occupancy = StayRoomResponse.Occupancy.OCCUPIED,
            roomId = checkInRoomA,
            version = 3,
        ),
)

private fun ciAssignment(
    room: Uuid,
    number: String,
) = RoomAssignmentResponse(
    active = true,
    assignedAt = "2026-11-13T15:00:00Z",
    dayUse = false,
    doNotMove = false,
    endsOn = "2026-11-15",
    id = Uuid.random(),
    reason = "INITIAL",
    reservationId = checkInResUuid,
    roomId = room,
    roomNumber = Option.Some(number),
    startsOn = "2026-11-13",
)

private fun ciDetail(
    room: Uuid,
    number: String,
) = ReservationDetailResponse(
    bookingCode = "B-81",
    holderGuestId = checkInHolderUuid,
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
            childAges = listOf(5),
            code = "R-81",
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
            id = checkInResUuid,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
            ratePlanId = Uuid.random(),
            roomAssignment = Option.Some(ciAssignment(room, number)),
            roomTotal = "500.00",
            roomTypeId = Uuid.random(),
            segment = "LEISURE",
            status = "CONFIRMED",
            total = "500.00",
            version = 1,
        ),
    restrictionAlerts = emptyList(),
)

private fun ciSuggestion(
    room: Uuid,
    number: String,
) = RoomSuggestionResponse(
    current = false,
    featureIds = emptyList(),
    floor = Option.Some("1"),
    matchedFeatureIds = emptyList(),
    matchesFloorPreference = false,
    number = number,
    outOfService = false,
    roomId = room,
)

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class FakeApprover(
    var cancel: Boolean = false,
) {
    var calls = 0

    suspend fun approve(action: ApprovableAction<CheckInResponse>): ApiResponse<CheckInResponse>? {
        calls++
        return try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CheckInStateTest {
    private val id = checkInResUuid.toString()
    private val holderId = checkInHolderUuid.toString()
    private var clock = beforeUntil
    private var changes = 0
    private val approver = FakeApprover()

    private fun seed(
        adults: Int = 2,
        children: Int = 1,
    ) = CheckInSeed(
        reservationId = id,
        holderGuestId = holderId,
        status = "CONFIRMED",
        adults = adults,
        children = children,
        roomId = checkInRoomA.toString(),
        roomNumber = "101",
        doNotMove = false,
    )

    private fun source() =
        FakeReceptionSource().apply {
            holders[holderId] = holderSummary
            quotes[id] = ciQuote()
            checkInResponse = ciResponse()
            reservations[id] = ciDetail(checkInRoomB, "102")
            suggestions[id] = listOf(ciSuggestion(checkInRoomA, "101"), ciSuggestion(checkInRoomB, "102"))
        }

    private fun TestScope.state(
        source: FakeReceptionSource,
        seed: CheckInSeed = seed(),
        permissions: Set<String> = allPermissions,
    ) = CheckInState(source, backgroundScope, seed, permissions, approver::approve, { clock }) { changes++ }

    private fun TestScope.started(
        source: FakeReceptionSource,
        seed: CheckInSeed = seed(),
    ) = state(source, seed).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.confirmNow(state: CheckInState) {
        state.confirm()
        runCurrent()
    }

    @Test
    fun startsWithHolderAndQuote() =
        runTest {
            val source = source()
            val state = started(source)

            assertEquals(holderId, state.primary?.id)
            assertEquals(holderSummary, state.primary?.summary)
            assertEquals(listOf(holderId), source.holderCalls)
            assertEquals(ciQuote(), state.quote)
            assertEquals(ActionText.earlyOffer(ciQuote()), state.earlyText)
            assertNotNull(state.earlyText)
            assertEquals(listOf(id), source.quoteCalls)
            assertEquals(listOf(id), source.suggestionCalls)
            assertEquals(listOf("101", "102"), state.allocation.options.map { it.number })
            assertFalse(state.chargeEarly)
            assertNull(state.error)
            assertNull(state.done)
            assertFalse(state.busy)
            assertTrue(state.canConfirm)
        }

    @Test
    fun quoteFailureMeansNoEarlyAndNoError() =
        runTest {
            val source = source().apply { quoteError = ciNetwork() }
            val state = started(source)

            assertNull(state.quote)
            assertNull(state.earlyText)
            assertNull(state.error)
            state.chargeEarly = true
            assertFalse(state.chargeEarly)
            assertTrue(state.canConfirm)
        }

    @Test
    fun earlyOfferedOnlyBeforeUntil() =
        runTest {
            val before = started(source())
            assertNotNull(before.earlyText)
            before.chargeEarly = true
            assertTrue(before.chargeEarly)

            clock = until
            val atUntil = started(source())
            assertNull(atUntil.earlyText)
            atUntil.chargeEarly = true
            assertFalse(atUntil.chargeEarly)

            clock = until.plusSeconds(60)
            assertNull(started(source()).earlyText)

            clock = beforeUntil
            assertNull(started(source().apply { quotes[id] = ciQuote(offered = false) }).earlyText)
            assertNull(started(source().apply { quotes[id] = ciQuote(untilText = null) }).earlyText)
        }

    @Test
    fun earlyWindowRecheckedOnConfirm() =
        runTest {
            val source = source()
            val state = started(source)
            state.chargeEarly = true
            clock = until.plusSeconds(1)

            confirmNow(state)

            assertEquals(emptyList(), source.checkInCalls)
            assertEquals(0, approver.calls)
            assertFalse(state.chargeEarly)
            assertNull(state.earlyText)
            assertEquals(WINDOW_ENDED, state.error)
            assertFalse(state.busy)
            assertNull(state.done)

            confirmNow(state)
            assertNull(
                source.checkInCalls
                    .single()
                    .input.earlyCheckInFee,
            )
        }

    @Test
    fun confirmSendsPrimaryCountAndFee() =
        runTest {
            val source = source().apply { quotes[id] = ciQuote(fee = "80.5") }
            val state = started(source)
            state.chargeEarly = true

            confirmNow(state)

            val call = source.checkInCalls.single()
            assertEquals(id, call.id)
            assertEquals(
                CheckInInput(listOf(CheckInGuest(holderId, true)), 3, null, Money.parse("80.5")),
                call.input,
            )
            assertEquals("80.5", call.input.earlyCheckInFee?.toApi())

            val plain = source()
            confirmNow(started(plain))
            assertEquals(
                CheckInInput(listOf(CheckInGuest(holderId, true)), 3, null, null),
                plain.checkInCalls.single().input,
            )
        }

    @Test
    fun guestCountClampedToContract() =
        runTest {
            val none = source()
            confirmNow(started(none, seed(adults = 0, children = 0)))
            assertEquals(
                1,
                none.checkInCalls
                    .single()
                    .input.guestCount,
            )

            val many = source()
            confirmNow(started(many, seed(adults = 35, children = 10)))
            assertEquals(
                40,
                many.checkInCalls
                    .single()
                    .input.guestCount,
            )
        }

    @Test
    fun retryAfterNetworkErrorReusesKey() =
        runTest {
            val source = source().apply { checkInOutcomes += ciNetwork() }
            val state = started(source)

            confirmNow(state)
            assertEquals(ciNetwork().userMessage, state.error)
            assertNull(state.done)
            assertFalse(state.busy)

            confirmNow(state)

            val keys = source.checkInRuns.map { it.key }
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
            assertNotNull(state.done)
            assertNull(state.error)
        }

    @Test
    fun unknownOutcomesKeepKey() =
        runTest {
            val unknown =
                listOf(
                    ApiError.IncompatibleResponse(IllegalStateException("x")),
                    ApiError.SessionEnded(null),
                    ciProblem("IDEMPOTENCY_KEY_IN_USE", 409),
                )
            for (failure in unknown) {
                val source = source().apply { checkInOutcomes += failure }
                val state = started(source)
                confirmNow(state)
                confirmNow(state)
                val keys = source.checkInRuns.map { it.key }
                assertEquals(keys[0], keys[1], "chave renovada depois de ${failure::class.simpleName}")
                assertNotNull(state.done)
            }
        }

    @Test
    fun ruleErrorRenewsKey() =
        runTest {
            val source = source().apply { checkInOutcomes += ciProblem("ROOM_NOT_READY") }
            val state = started(source)

            confirmNow(state)
            confirmNow(state)

            val keys = source.checkInRuns.map { it.key }
            assertEquals(2, keys.size)
            assertNotEquals(keys[0], keys[1])
            assertNotNull(state.done)
        }

    @Test
    fun definitiveFailuresRenewKey() =
        runTest {
            val definitive =
                listOf(
                    ApiError.PreconditionFailed(null),
                    ApiError.Unexpected(500),
                    ciProblem("GUEST_DATA_INCOMPLETE"),
                    ciProblem("IDEMPOTENCY_KEY_REUSED", 422),
                )
            for (failure in definitive) {
                val source = source().apply { checkInOutcomes += failure }
                val state = started(source)
                confirmNow(state)
                assertEquals(ActionText.error(failure), state.error)
                confirmNow(state)
                val keys = source.checkInRuns.map { it.key }
                assertNotEquals(keys[0], keys[1], "chave mantida depois de ${failure::class.simpleName}")
            }
        }

    @Test
    fun keyInUseKeepsKey() =
        runTest {
            val inUse = ciProblem("IDEMPOTENCY_KEY_IN_USE", 409)
            val source = source().apply { checkInOutcomes += inUse }
            val state = started(source)

            confirmNow(state)
            assertEquals(ActionText.error(inUse), state.error)
            confirmNow(state)

            val keys = source.checkInRuns.map { it.key }
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
        }

    @Test
    fun changingInputsRenewsKey() =
        runTest {
            // A UH: depois de uma falha de rede (que manteria a chave), alocar outra UH troca a chave.
            val byRoom = source().apply { checkInOutcomes += ciNetwork() }
            val roomState = started(byRoom)
            confirmNow(roomState)
            roomState.allocation.assign(checkInRoomB.toString())
            runCurrent()
            assertEquals(checkInRoomB.toString(), roomState.allocation.currentRoomId)
            confirmNow(roomState)
            assertNotEquals(byRoom.checkInRuns[0].key, byRoom.checkInRuns[1].key)

            // O hóspede principal.
            val byGuest = source().apply { checkInOutcomes += ciNetwork() }
            val guestState = started(byGuest)
            confirmNow(guestState)
            guestState.picker.pick(other)
            assertEquals(other, guestState.primary)
            confirmNow(guestState)
            assertNotEquals(byGuest.checkInRuns[0].key, byGuest.checkInRuns[1].key)
            assertEquals(
                other.id,
                byGuest.checkInCalls[1]
                    .input.guests
                    .single()
                    .guestId,
            )

            // A cobrança do early.
            val byEarly = source().apply { checkInOutcomes += ciNetwork() }
            val earlyState = started(byEarly)
            confirmNow(earlyState)
            earlyState.chargeEarly = true
            confirmNow(earlyState)
            assertNotEquals(byEarly.checkInRuns[0].key, byEarly.checkInRuns[1].key)
        }

    @Test
    fun doubleConfirmSendsOnce() =
        runTest {
            val source = source().apply { holdCheckIn = true }
            val state = started(source)

            state.confirm()
            state.confirm()
            runCurrent()

            assertTrue(state.busy)
            assertFalse(state.canConfirm)
            assertEquals(1, source.checkInCalls.size)
            assertEquals(1, source.checkInRuns.size)
            assertEquals(1, approver.calls)

            source.heldCheckIns.single().complete(Unit)
            runCurrent()

            assertFalse(state.busy)
            assertNotNull(state.done)
            assertEquals(1, source.checkInRuns.size)
        }

    @Test
    fun roomNotReadyShowsNextStep() =
        runTest {
            val source = source().apply { checkInOutcomes += ciProblem("ROOM_NOT_READY") }
            val state = started(source)

            confirmNow(state)

            assertEquals(ROOM_NOT_READY_TEXT, state.error)
            assertNull(state.done)
            assertEquals(0, changes)
            assertFalse(state.busy)
            assertTrue(state.canConfirm)
        }

    @Test
    fun transitionNotAllowedRefreshes() =
        runTest {
            val source = source().apply { checkInOutcomes += ciProblem("RESERVATION_TRANSITION_NOT_ALLOWED", 409) }
            val state = started(source)

            confirmNow(state)

            assertEquals(TRANSITION_TEXT, state.error)
            assertNull(state.done)
            assertEquals(1, changes)
            assertFalse(state.busy)
        }

    @Test
    fun approvalCancelledLeavesDialog() =
        runTest {
            val source =
                source().apply {
                    checkInRequiresApproval = ApiError.ApprovalRequired("CHECK_IN", "x", "res", null, null)
                }
            approver.cancel = true
            val state = started(source)

            confirmNow(state)

            assertNull(state.error)
            assertNull(state.done)
            assertEquals(0, changes)
            assertFalse(state.busy)
            assertTrue(state.canConfirm)

            // A mesma intenção: a próxima tentativa sem token leva a mesma chave.
            approver.cancel = false
            confirmNow(state)
            val tokenless = source.checkInRuns.filter { it.token == null }.map { it.key }
            assertEquals(2, tokenless.size)
            assertEquals(tokenless[0], tokenless[1])
            assertNotNull(state.done)
        }

    @Test
    fun approvedRetrySucceeds() =
        runTest {
            val source =
                source().apply {
                    checkInRequiresApproval = ApiError.ApprovalRequired("CHECK_IN", "x", "res", null, null)
                }
            val state = started(source)

            confirmNow(state)

            assertEquals(listOf(null, "tok"), source.checkInRuns.map { it.token })
            assertEquals(CheckInDone("101", emptyList()), state.done)
            assertEquals(1, changes)
            assertNull(state.error)
            assertFalse(state.busy)
        }

    @Test
    fun sessionEndedShowsMessage() =
        runTest {
            val source = source().apply { checkInOutcomes += ApiError.SessionEnded(null) }
            val state = started(source)

            confirmNow(state)

            assertEquals("Sua sessão foi encerrada. Entre novamente.", state.error)
            assertNull(state.done)
            assertEquals(0, changes)
            assertFalse(state.busy)
        }

    @Test
    fun feeChangedRequotes() =
        runTest {
            val source = source().apply { checkInOutcomes += ciProblem("EARLY_LATE_FEE_CHANGED") }
            val state = started(source)
            state.chargeEarly = true
            source.quotes[id] = ciQuote(fee = "95.00")

            confirmNow(state)

            assertEquals(FEE_CHANGED_TEXT, state.error)
            assertEquals(2, source.quoteCalls.size)
            assertEquals(ciQuote(fee = "95.00"), state.quote)
            assertEquals(ActionText.earlyOffer(ciQuote(fee = "95.00")), state.earlyText)
            assertFalse(state.chargeEarly)
            assertNull(state.done)
            assertFalse(state.busy)

            state.chargeEarly = true
            confirmNow(state)
            assertEquals(Money.parse("95.00"), source.checkInCalls[1].input.earlyCheckInFee)
            assertNotEquals(source.checkInRuns[0].key, source.checkInRuns[1].key)
            assertNotNull(state.done)
        }

    @Test
    fun successNotifiesAndShowsAlerts() =
        runTest {
            val source = source().apply { checkInResponse = ciResponse("101", listOf("Pendência financeira")) }
            val state = started(source)

            confirmNow(state)

            assertEquals(CheckInDone("101", listOf("Hóspede com restrição: Pendência financeira")), state.done)
            assertEquals(1, changes)
            assertNull(state.error)
            assertFalse(state.busy)
            assertFalse(state.canConfirm)

            confirmNow(state)
            assertEquals(1, source.checkInRuns.size)
        }

    @Test
    fun holderForbiddenNeedsPick() =
        runTest {
            for (failure in listOf(ciProblem("PERMISSION_DENIED", 403), ciProblem("NOT_FOUND", 404))) {
                val source = source().apply { holderErrors[holderId] = failure }
                val state = started(source)

                assertNull(state.primary)
                assertNull(state.error)
                assertFalse(state.canConfirm)

                state.picker.pick(other)
                assertEquals(other, state.primary)
                assertTrue(state.canConfirm)
                confirmNow(state)
                assertEquals(
                    listOf(CheckInGuest(other.id, true)),
                    source.checkInCalls
                        .single()
                        .input.guests,
                )
            }
        }

    @Test
    fun pickBeforeHolderArrivesWins() =
        runTest {
            val source = source().apply { holdHolder = true }
            val state = started(source)

            state.picker.pick(other)
            source.heldHolders
                .single()
                .second
                .complete(Unit)
            runCurrent()

            assertEquals(other, state.primary)
        }

    @Test
    fun noRoomOrNoPrimaryCannotConfirm() =
        runTest {
            val source = source()
            val noRoom = started(source, seed().copy(roomId = null, roomNumber = null))
            assertFalse(noRoom.canConfirm)
            noRoom.confirm()
            runCurrent()
            assertEquals(emptyList(), source.checkInCalls)
        }

    @Test
    fun permissionsReachChildren() =
        runTest {
            val full = state(source())
            assertTrue(full.allocation.canManage)
            assertTrue(full.picker.canSearch)
            assertTrue(full.picker.canCreate)

            val none = state(source(), permissions = setOf("frontdesk.stays.checkin"))
            assertFalse(none.allocation.canManage)
            assertFalse(none.picker.canSearch)
            assertFalse(none.picker.canCreate)

            val onlyRead = state(source(), permissions = setOf("guests.profiles.read"))
            assertTrue(onlyRead.picker.canSearch)
            assertFalse(onlyRead.picker.canCreate)
            assertFalse(onlyRead.allocation.canManage)

            // O `onChanged` chega à alocação: alocar avisa quem abriu o diálogo.
            full.allocation.assign(checkInRoomB.toString())
            runCurrent()
            assertEquals(1, changes)
        }

    @Test
    fun closeCancelsLoads() =
        runTest {
            val source = source().apply { holdHolder = true }
            val state = started(source)

            state.close()
            source.heldHolders
                .single()
                .second
                .complete(Unit)
            runCurrent()

            assertNull(state.primary)
            assertNull(state.error)
        }

    @Test
    fun confirmWaitsForAssignInFlight() =
        runTest {
            val source = source().apply { holdAssign = true }
            val state = started(source)

            state.allocation.assign(checkInRoomB.toString())
            runCurrent()
            assertTrue(state.allocation.busy)
            assertFalse(state.canConfirm)
            confirmNow(state)
            assertEquals(emptyList(), source.checkInRuns)
            assertEquals(0, approver.calls)

            source.heldAssigns.single().complete(Unit)
            runCurrent()
            assertTrue(state.canConfirm)
            confirmNow(state)
            assertEquals(1, source.checkInRuns.size)
        }

    @Test
    fun confirmWaitsForGuestCreationInFlight() =
        runTest {
            val source =
                source().apply {
                    holdCreateGuest = true
                    createdGuest = other
                }
            val state = started(source)
            val fields =
                state.picker.form.fields
                    .associateBy { it.name }
            fields.getValue("fullName").onChange("Outra Pessoa")
            fields.getValue("documentNumber").onChange(ciCpfOf("987654321"))
            fields.getValue("birthDate").onChange("10/05/1990")

            state.picker.create()
            runCurrent()
            assertTrue(state.picker.creating)
            assertFalse(state.canConfirm)
            confirmNow(state)
            assertEquals(emptyList(), source.checkInRuns)
            assertEquals(0, approver.calls)

            source.heldCreateGuests.single().complete(Unit)
            runCurrent()
            assertEquals(other, state.primary)
            assertTrue(state.canConfirm)
            confirmNow(state)
            assertEquals(
                other.id,
                source.checkInCalls
                    .single()
                    .input.guests
                    .single()
                    .guestId,
            )
        }

    @Test
    fun holderErrorClearedWhenGuestChosen() =
        runTest {
            val source = source().apply { holderErrors[holderId] = ciNetwork() }
            val state = started(source)
            assertNull(state.primary)
            assertEquals(ciNetwork().userMessage, state.error)

            state.picker.pick(other)

            assertEquals(other, state.primary)
            assertNull(state.error)
        }

    @Test
    fun doneToStringHidesAlerts() {
        val done = CheckInDone("101", listOf("Hóspede com restrição: Pendência financeira"))

        assertFalse("Pendência" in done.toString())
        assertFalse("restrição" in done.toString())
        assertTrue("101" in done.toString())
    }
}
