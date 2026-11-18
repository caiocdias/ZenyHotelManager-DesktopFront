package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.CheckInGuest
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val ciaRes = Uuid.parse("20000000-0000-0000-0000-000000000181")
private val ciaHolder = Uuid.parse("30000000-0000-0000-0000-000000000181")
private val ciaRoomA = Uuid.parse("50000000-0000-0000-0000-0000000001a1")
private val ciaRoomB = Uuid.parse("50000000-0000-0000-0000-0000000001b1")
private val ciaUntil: Instant = Instant.parse("2026-11-13T15:00:00Z")
private val ciaBefore: Instant = Instant.parse("2026-11-13T13:00:00Z")
private val ciaOther =
    GuestPick("30000000-0000-0000-0000-000000000199", HolderSummary("Bruno Fictício", "CPF ***.888-**"))
private val ciaHolderSummary = HolderSummary("Clara Titular Fictícia", "CPF ***.333.444-**")
private const val CIA_WINDOW_ENDED = "A janela do early check-in terminou. Confira e confirme de novo."
private const val CIA_FEE_CHANGED_TEXT =
    "O valor do early check-in mudou. Confira a nova cotação e confirme de novo."
private val ciaPermissions =
    setOf(
        "frontdesk.stays.checkin",
        "reservations.assignments.manage",
        "guests.profiles.read",
        "guests.profiles.manage",
    )

private fun ciaProblem(
    code: String?,
    status: Int = 422,
) = ApiError.Problem(status, code, null, emptyList())

private fun ciaNetwork() = ApiError.Network(IOException("offline"))

private fun ciaApproval() = ApiError.ApprovalRequired("CHECK_IN", "x", "res", null, null)

private fun ciaQuote(
    fee: String = "80.00",
    untilText: String = ciaUntil.toString(),
) = EarlyLateQuoteResponse(
    arrival = "2026-11-13",
    checkInTime = "14:00",
    checkOutTime = "12:00",
    departure = "2026-11-15",
    earlyCheckIn =
        EarlyLateOfferResponse(
            offered = true,
            fee = Option.Some(fee),
            limitTime = Option.Some("10:00"),
            from = Option.Some("2026-11-13T13:00:00Z"),
            until = Option.Some(untilText),
        ),
    lateCheckOut = EarlyLateOfferResponse(offered = false),
    reservationId = ciaRes,
    timezone = "America/Sao_Paulo",
)

private fun ciaResponse(
    number: String,
    reasons: List<String>,
) = CheckInResponse(
    reservation =
        StayReservationResponse(
            adults = 2,
            arrival = "2026-11-13",
            bookingId = Uuid.random(),
            children = 0,
            code = "R-181",
            dayUse = false,
            departure = "2026-11-15",
            folioId = Uuid.random(),
            guests = emptyList(),
            id = ciaRes,
            roomTypeId = Uuid.random(),
            status = "CHECKED_IN",
            version = 2,
        ),
    restrictionAlerts =
        reasons.map { StayRestrictionAlertResponse(guestId = ciaHolder, reason = it, since = "2026-11-01") },
    room =
        StayRoomResponse(
            housekeepingStatus = StayRoomResponse.HousekeepingStatus.INSPECTED,
            inspectionPending = false,
            number = number,
            occupancy = StayRoomResponse.Occupancy.OCCUPIED,
            roomId = ciaRoomA,
            version = 3,
        ),
)

private fun ciaAssignment(
    room: Uuid,
    number: String,
    doNotMove: Boolean,
) = RoomAssignmentResponse(
    active = true,
    assignedAt = "2026-11-13T15:00:00Z",
    dayUse = false,
    doNotMove = doNotMove,
    endsOn = "2026-11-15",
    id = Uuid.random(),
    reason = "INITIAL",
    reservationId = ciaRes,
    roomId = room,
    roomNumber = Option.Some(number),
    startsOn = "2026-11-13",
)

private fun ciaDetail(
    room: Uuid?,
    number: String = "101",
    doNotMove: Boolean = false,
) = ReservationDetailResponse(
    bookingCode = "B-181",
    holderGuestId = ciaHolder,
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
            code = "R-181",
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
            id = ciaRes,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
            ratePlanId = Uuid.random(),
            roomAssignment = room?.let { Option.Some(ciaAssignment(it, number, doNotMove)) } ?: Option.Undefined,
            roomTotal = "500.00",
            roomTypeId = Uuid.random(),
            segment = "LEISURE",
            status = "CONFIRMED",
            total = "500.00",
            version = 1,
        ),
    restrictionAlerts = emptyList(),
)

private fun ciaSuggestion(
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

/**
 * Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, espera a [decision] (o token, ou `null`
 * = cancelou) e repete uma vez com o token.
 */
private class CoordinatorLike(
    var decision: suspend () -> String? = { "tok" },
) {
    var calls = 0

    suspend fun approve(action: ApprovableAction<CheckInResponse>): ApiResponse<CheckInResponse>? {
        calls++
        val required =
            try {
                return action.run(null)
            } catch (e: ApiError.ApprovalRequired) {
                e
            }
        check(required.action == "CHECK_IN")
        return decision()?.let { action.run(it) }
    }
}

/**
 * Ataque ao `CheckInState`: a regra de renovação da `Idempotency-Key` em cada tipo de resposta e em sequências, a
 * aprovação imitando o coordenador, cliques repetidos, o corpo do pedido nas bordas, a janela do early, o
 * `close()` com o check-in em voo e os dados pessoais fora do `toString`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // um teste por comportamento atacado: chave, aprovação, corpo, janela, close e cargas
class CheckInStateAtaqueTest {
    private val id = ciaRes.toString()
    private val holderId = ciaHolder.toString()
    private var clock = ciaBefore
    private var changes = 0
    private val coordinator = CoordinatorLike()

    private fun seed(
        adults: Int = 2,
        children: Int = 0,
        roomId: String? = ciaRoomA.toString(),
    ) = CheckInSeed(
        reservationId = id,
        holderGuestId = holderId,
        status = "CONFIRMED",
        adults = adults,
        children = children,
        roomId = roomId,
        roomNumber = roomId?.let { "101" },
        doNotMove = false,
    )

    private fun source() =
        FakeReceptionSource().apply {
            holders[holderId] = ciaHolderSummary
            quotes[id] = ciaQuote()
            checkInResponse = ciaResponse("101", emptyList())
            reservations[id] = ciaDetail(ciaRoomB, "102")
            suggestions[id] = listOf(ciaSuggestion(ciaRoomA, "101"), ciaSuggestion(ciaRoomB, "102"))
        }

    private fun TestScope.started(
        source: FakeReceptionSource,
        seed: CheckInSeed = seed(),
        approve: suspend (ApprovableAction<CheckInResponse>) -> ApiResponse<CheckInResponse>? = coordinator::approve,
    ) = CheckInState(source, backgroundScope, seed, ciaPermissions, approve, { clock }) { changes++ }.also {
        it.start()
        runCurrent()
    }

    private fun TestScope.confirmNow(state: CheckInState) {
        state.confirm()
        runCurrent()
    }

    private fun FakeReceptionSource.keys() = checkInRuns.map { it.key }

    // --- Regra de renovação, resposta a resposta ---

    @Test
    fun unknownOutcomesKeepTheSameIntentObject() =
        runTest {
            val unknown =
                listOf(
                    ciaNetwork(),
                    ApiError.IncompatibleResponse(IllegalStateException("x")),
                    ApiError.SessionEnded(null),
                    ciaProblem("IDEMPOTENCY_KEY_IN_USE", 409),
                )
            for (failure in unknown) {
                val source = source().apply { checkInOutcomes += failure }
                val state = started(source)
                confirmNow(state)
                assertEquals(ActionText.error(failure), state.error)
                assertEquals(0, changes, "onChanged depois de ${failure::class.simpleName}")
                confirmNow(state)
                assertSame(source.checkInCalls[0].intent, source.checkInCalls[1].intent, "Intent trocada")
                assertEquals(source.keys()[0], source.keys()[1])
                changes = 0
            }
        }

    @Test
    fun everyDefinitiveRefusalRenewsKeyAndOnlyTransitionNotifies() =
        runTest {
            val definitive =
                listOf(
                    ciaProblem("RESERVATION_NOT_FOUND", 404),
                    ciaProblem("RESERVATION_TRANSITION_NOT_ALLOWED", 409),
                    ciaProblem("CHECK_IN_BEFORE_ARRIVAL"),
                    ciaProblem("FOLIO_CLOSED", 409),
                    ciaProblem("UNKNOWN_GUEST"),
                    ciaProblem("GUEST_ANONYMIZED"),
                    ciaProblem("GUEST_DATA_INCOMPLETE"),
                    ciaProblem("ROOM_NOT_ASSIGNED"),
                    ciaProblem("ROOM_NOT_READY"),
                    ciaProblem("MINOR_AUTHORIZATION_REQUIRED"),
                    ciaProblem("EARLY_LATE_FEE_CHANGED", 409),
                    ciaProblem("EARLY_LATE_NOT_OFFERED"),
                    ciaProblem("PERMISSION_DENIED", 403),
                    ciaProblem("FUTURE_CODE_UNKNOWN_TO_CLIENT", 409),
                    ApiError.PreconditionFailed(null),
                )
            for (failure in definitive) {
                changes = 0
                val source = source().apply { checkInOutcomes += failure }
                val state = started(source)
                confirmNow(state)
                val name = (failure as? ApiError.Problem)?.code ?: failure::class.simpleName
                assertEquals(ActionText.error(failure), state.error, "texto de $name")
                assertNull(state.done, "done depois de $name")
                assertFalse(state.busy)
                val transition = name == "RESERVATION_TRANSITION_NOT_ALLOWED"
                assertEquals(if (transition) 1 else 0, changes, "onChanged depois de $name")

                confirmNow(state)
                assertNotSame(source.checkInCalls[0].intent, source.checkInCalls[1].intent, "Intent mantida: $name")
                assertNotEquals(source.keys()[0], source.keys()[1], "chave mantida depois de $name")
            }
        }

    @Test
    fun serverFailuresKeepKeyAndIntentAndTextDependsOnWhoAnswered() =
        runTest {
            val stored = "O servidor não confirmou o check-in. Feche e confira a reserva antes de refazer."
            val cases =
                listOf(
                    Triple("Problem 500 sem código", ciaProblem(null, 500), stored),
                    Triple("Problem 503 com código", ciaProblem("INTERNAL", 503), stored),
                    Triple("Unexpected 502", ApiError.Unexpected(502), ActionText.error(ApiError.Unexpected(502))),
                    Triple("Unexpected 504", ApiError.Unexpected(504), ActionText.error(ApiError.Unexpected(504))),
                )
            for ((name, failure, text) in cases) {
                changes = 0
                val source = source().apply { checkInOutcomes += failure }
                val state = started(source)
                confirmNow(state)
                assertEquals(text, state.error, "texto de $name")
                assertNull(state.done, "done depois de $name")
                assertFalse(state.busy, name)
                assertEquals(0, changes, "onChanged depois de $name")

                confirmNow(state)
                assertSame(source.checkInCalls[0].intent, source.checkInCalls[1].intent, "Intent trocada: $name")
                assertEquals(source.keys()[0], source.keys()[1], "chave renovada depois de $name")
            }
        }

    @Test
    fun networkRuleNetworkSuccessUsesOneKeyPerIntent() =
        runTest {
            val source =
                source().apply {
                    checkInOutcomes += ciaNetwork()
                    checkInOutcomes += ciaProblem("ROOM_NOT_READY")
                    checkInOutcomes += ciaNetwork()
                    checkInOutcomes += null
                }
            val state = started(source)

            repeat(4) { confirmNow(state) }

            val keys = source.keys()
            assertEquals(4, keys.size)
            assertEquals(keys[0], keys[1])
            assertNotEquals(keys[1], keys[2])
            assertEquals(keys[2], keys[3])
            assertNotNull(state.done)
            assertNull(state.error)
            assertEquals(1, changes)
            assertFalse(state.canConfirm)

            confirmNow(state)
            assertEquals(4, source.checkInRuns.size)
        }

    @Test
    fun keyInUseTwiceThenSuccessKeepsKeyThroughout() =
        runTest {
            val inUse = ciaProblem("IDEMPOTENCY_KEY_IN_USE", 409)
            val source =
                source().apply {
                    checkInOutcomes += inUse
                    checkInOutcomes += ciaNetwork()
                    checkInOutcomes += inUse
                }
            val state = started(source)

            repeat(4) { confirmNow(state) }

            assertEquals(1, source.keys().toSet().size)
            assertEquals(4, source.keys().size)
            assertNotNull(state.done)
        }

    // --- Aprovação (imitando o coordenador; chaves comparadas só nas execuções sem token) ---

    @Test
    fun cancelledApprovalTwiceKeepsTheSameIntentAndShowsNothing() =
        runTest {
            val source = source().apply { checkInRequiresApproval = ciaApproval() }
            coordinator.decision = { null }
            val state = started(source)

            confirmNow(state)
            confirmNow(state)

            assertNull(state.error)
            assertNull(state.done)
            assertFalse(state.busy)
            assertEquals(0, changes)
            assertTrue(state.canConfirm)
            assertEquals(2, coordinator.calls)
            assertSame(source.checkInCalls[0].intent, source.checkInCalls[1].intent)
            assertEquals(listOf<String?>(null, null), source.checkInRuns.map { it.token })
            assertEquals(source.keys()[0], source.keys()[1])
        }

    @Test
    fun approvedRunWithUnknownOutcomeKeepsIntent() =
        runTest {
            val source =
                source().apply {
                    checkInRequiresApproval = ciaApproval()
                    checkInOutcomes += ciaNetwork()
                }
            val state = started(source)

            confirmNow(state)
            assertEquals(ciaNetwork().userMessage, state.error)
            assertNull(state.done)
            confirmNow(state)

            assertEquals(listOf(null, "tok", null, "tok"), source.checkInRuns.map { it.token })
            assertSame(source.checkInCalls[0].intent, source.checkInCalls[1].intent)
            assertNotNull(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun approvedRunWithRuleRefusalRenewsIntent() =
        runTest {
            val source =
                source().apply {
                    checkInRequiresApproval = ciaApproval()
                    checkInOutcomes += ciaProblem("ROOM_NOT_READY")
                }
            val state = started(source)

            confirmNow(state)
            assertEquals(ActionText.error(ciaProblem("ROOM_NOT_READY")), state.error)
            confirmNow(state)

            assertNotSame(source.checkInCalls[0].intent, source.checkInCalls[1].intent)
            val tokenless = source.checkInRuns.filter { it.token == null }.map { it.key }
            assertEquals(2, tokenless.size)
            assertNotEquals(tokenless[0], tokenless[1])
            assertNotNull(state.done)
        }

    @Test
    fun clicksWhileApprovalIsPendingDoNothing() =
        runTest {
            val source = source().apply { checkInRequiresApproval = ciaApproval() }
            val gate = CompletableDeferred<String?>()
            coordinator.decision = { gate.await() }
            val state = started(source)

            confirmNow(state)
            confirmNow(state)
            state.confirm()
            runCurrent()

            assertTrue(state.busy)
            assertFalse(state.canConfirm)
            assertEquals(1, coordinator.calls)
            assertEquals(1, source.checkInCalls.size)

            gate.complete(null)
            runCurrent()
            assertFalse(state.busy)
            assertNull(state.error)
            assertTrue(state.canConfirm)
            assertEquals(1, source.checkInRuns.size)
        }

    // --- Troca das entradas ---

    @Test
    fun changingChargeWhileInFlightRenewsKeyEvenAfterUnknownOutcome() =
        runTest {
            val source =
                source().apply {
                    holdCheckIn = true
                    checkInOutcomes += ciaNetwork()
                }
            val state = started(source)

            state.confirm()
            runCurrent()
            state.chargeEarly = true
            source.heldCheckIns.single().complete(Unit)
            runCurrent()
            assertEquals(ciaNetwork().userMessage, state.error)

            source.holdCheckIn = false
            confirmNow(state)
            assertNotEquals(source.keys()[0], source.keys()[1])
            assertNull(source.checkInCalls[0].input.earlyCheckInFee)
            assertEquals(Money.parse("80.00"), source.checkInCalls[1].input.earlyCheckInFee)
        }

    @Test
    fun pickingAnotherGuestWhileInFlightRenewsKeyEvenAfterUnknownOutcome() =
        runTest {
            val source =
                source().apply {
                    holdCheckIn = true
                    checkInOutcomes += ApiError.IncompatibleResponse(IllegalStateException("x"))
                }
            val state = started(source)

            state.confirm()
            runCurrent()
            state.picker.pick(ciaOther)
            source.heldCheckIns.single().complete(Unit)
            runCurrent()

            source.holdCheckIn = false
            confirmNow(state)
            assertNotEquals(source.keys()[0], source.keys()[1])
            assertEquals(
                ciaOther.id,
                source.checkInCalls[1]
                    .input.guests
                    .single()
                    .guestId,
            )
        }

    @Test
    fun repickingTheSameGuestKeepsKey() =
        runTest {
            val source = source().apply { checkInOutcomes += ciaNetwork() }
            val state = started(source)

            confirmNow(state)
            state.picker.pick(GuestPick(holderId, HolderSummary("Clara T. Fictícia", null)))
            confirmNow(state)

            assertEquals(source.keys()[0], source.keys()[1])
        }

    @Test
    fun settingChargeToItsCurrentValueKeepsKey() =
        runTest {
            val source =
                source().apply {
                    checkInOutcomes += ciaNetwork()
                    checkInOutcomes += ciaNetwork()
                }
            val state = started(source)
            state.chargeEarly = true

            confirmNow(state)
            state.chargeEarly = true
            confirmNow(state)
            state.picker.pick(GuestPick(holderId, ciaHolderSummary))
            confirmNow(state)

            assertEquals(1, source.keys().toSet().size)
            assertEquals(3, source.keys().size)
        }

    @Test
    fun doNotMoveOrFailedAssignKeepKeyButNotifyOnlyOnChange() =
        runTest {
            val source =
                source().apply {
                    checkInOutcomes += ciaNetwork()
                    checkInOutcomes += ciaNetwork()
                    reservations[id] = ciaDetail(ciaRoomA, "101", doNotMove = true)
                }
            val state = started(source)

            confirmNow(state)
            state.allocation.toggleDoNotMove()
            runCurrent()
            assertTrue(state.allocation.doNotMove)
            assertEquals(ciaRoomA.toString(), state.allocation.currentRoomId)
            assertEquals(1, changes)
            confirmNow(state)

            source.assignError = ciaProblem("ROOM_ALREADY_ASSIGNED", 409)
            state.allocation.assign(ciaRoomB.toString())
            runCurrent()
            assertEquals(ciaRoomA.toString(), state.allocation.currentRoomId)
            confirmNow(state)

            assertEquals(1, source.keys().toSet().size)
            assertEquals(3, source.keys().size)
        }

    @Test
    fun releasingTheRoomBlocksConfirm() =
        runTest {
            val source = source().apply { reservations[id] = ciaDetail(null) }
            val state = started(source)

            state.allocation.unassign()
            runCurrent()

            assertNull(state.allocation.currentRoomId)
            assertFalse(state.canConfirm)
            confirmNow(state)
            assertEquals(emptyList(), source.checkInCalls)
            assertEquals(0, coordinator.calls)
        }

    // --- Corpo do pedido ---

    @Test
    fun guestCountIsClampedAtEveryBoundary() =
        runTest {
            val cases =
                listOf(
                    (0 to 0) to 1,
                    (1 to 0) to 1,
                    (0 to 2) to 2,
                    (2 to 3) to 5,
                    (40 to 0) to 40,
                    (39 to 1) to 40,
                    (41 to 0) to 40,
                    (40 to 1) to 40,
                    (30 to 30) to 40,
                )
            for ((people, expected) in cases) {
                val source = source()
                confirmNow(started(source, seed(adults = people.first, children = people.second)))
                assertEquals(
                    expected,
                    source.checkInCalls
                        .single()
                        .input.guestCount,
                    "para $people",
                )
            }
        }

    @Test
    fun bodyHasOnePrimaryGuestAndNoRoomEvenAfterReassign() =
        runTest {
            val source = source()
            val state = started(source)

            state.allocation.assign(ciaRoomB.toString())
            runCurrent()
            assertEquals(ciaRoomB.toString(), state.allocation.currentRoomId)
            confirmNow(state)

            val input = source.checkInCalls.single().input
            assertEquals(listOf(CheckInGuest(holderId, true)), input.guests)
            assertNull(input.roomId)
            assertEquals(id, source.checkInCalls.single().id)
        }

    @Test
    fun feeGoesAsQuotedWithItsScale() =
        runTest {
            for (fee in listOf("80.00", "0.50", "1234.5", "75")) {
                val source = source().apply { quotes[id] = ciaQuote(fee = fee) }
                val state = started(source)
                state.chargeEarly = true
                confirmNow(state)
                assertEquals(
                    fee,
                    source.checkInCalls
                        .single()
                        .input.earlyCheckInFee
                        ?.toApi(),
                )
            }
        }

    @Test
    fun noPrimaryGuestCallsNothing() =
        runTest {
            val source = source().apply { holderErrors[holderId] = ciaProblem("PERMISSION_DENIED", 403) }
            val state = started(source)

            confirmNow(state)
            confirmNow(state)

            assertEquals(emptyList(), source.checkInCalls)
            assertEquals(0, coordinator.calls)
            assertNull(state.error)
            assertFalse(state.busy)
        }

    // --- Janela do early ---

    @Test
    fun confirmAtExactlyUntilEndsTheWindow() =
        runTest {
            val source = source()
            val state = started(source)
            state.chargeEarly = true
            clock = ciaUntil

            confirmNow(state)

            assertEquals(emptyList(), source.checkInCalls)
            assertEquals(CIA_WINDOW_ENDED, state.error)
            assertFalse(state.chargeEarly)
            assertNull(state.earlyText)
            state.chargeEarly = true
            assertFalse(state.chargeEarly)
        }

    @Test
    fun confirmJustBeforeUntilSendsTheFee() =
        runTest {
            val source = source()
            val state = started(source)
            state.chargeEarly = true
            clock = ciaUntil.minusMillis(1)

            confirmNow(state)

            assertEquals(
                Money.parse("80.00"),
                source.checkInCalls
                    .single()
                    .input.earlyCheckInFee,
            )
            assertNull(state.error)
        }

    @Test
    fun windowEndingAfterNetworkFailureRenewsKeyAndDropsFee() =
        runTest {
            val source = source().apply { checkInOutcomes += ciaNetwork() }
            val state = started(source)
            state.chargeEarly = true

            confirmNow(state)
            clock = ciaUntil.plusSeconds(1)
            confirmNow(state)
            assertEquals(CIA_WINDOW_ENDED, state.error)
            assertEquals(1, source.checkInRuns.size)
            confirmNow(state)

            assertEquals(2, source.checkInRuns.size)
            assertNotEquals(source.keys()[0], source.keys()[1])
            assertNull(source.checkInCalls[1].input.earlyCheckInFee)
            assertNotNull(state.done)
        }

    @Test
    fun untilWithOffsetIsComparedAsAnInstant() =
        runTest {
            // 12:00 em -03:00 é 15:00Z; um relógio em 14:59:59Z está dentro da janela, 15:00:00Z já fora.
            val offsetQuote = ciaQuote(untilText = "2026-11-13T12:00:00-03:00")
            clock = Instant.parse("2026-11-13T14:59:59Z")
            assertNotNull(started(source().apply { quotes[id] = offsetQuote }).earlyText)
            clock = Instant.parse("2026-11-13T15:00:00Z")
            assertNull(started(source().apply { quotes[id] = offsetQuote }).earlyText)
        }

    @Test
    fun windowNotRecheckedWithoutCharge() =
        runTest {
            val source = source()
            val state = started(source)
            clock = ciaUntil.plusSeconds(3600)

            confirmNow(state)

            assertNull(
                source.checkInCalls
                    .single()
                    .input.earlyCheckInFee,
            )
            assertNotNull(state.done)
        }

    // --- Tarifa mudada ---

    @Test
    fun feeChangedKeepsBusyUntilTheFreshQuoteArrives() =
        runTest {
            val source =
                source().apply {
                    checkInOutcomes += ciaProblem("EARLY_LATE_FEE_CHANGED", 409)
                }
            val state = started(source)
            state.chargeEarly = true
            source.holdQuote = true
            source.quotes[id] = ciaQuote(fee = "95.00")

            confirmNow(state)

            assertTrue(state.busy)
            assertFalse(state.canConfirm)
            assertFalse(state.chargeEarly)
            assertEquals(CIA_FEE_CHANGED_TEXT, state.error)
            assertEquals(0, changes)

            source.heldQuotes.single().complete(Unit)
            runCurrent()
            assertFalse(state.busy)
            assertEquals(ciaQuote(fee = "95.00"), state.quote)
            assertEquals(CIA_FEE_CHANGED_TEXT, state.error)
            assertFalse(state.chargeEarly)
            assertEquals(0, changes)
        }

    @Test
    fun feeChangedWithFailedRequoteLeavesNoEarly() =
        runTest {
            val source = source().apply { checkInOutcomes += ciaProblem("EARLY_LATE_FEE_CHANGED", 409) }
            val state = started(source)
            state.chargeEarly = true
            source.quoteError = ciaNetwork()

            confirmNow(state)

            assertEquals(CIA_FEE_CHANGED_TEXT, state.error)
            assertNull(state.quote)
            assertNull(state.earlyText)
            state.chargeEarly = true
            assertFalse(state.chargeEarly)
            assertFalse(state.busy)
        }

    // --- Sucesso ---

    @Test
    fun successUsesServerRoomAndKeepsAlertsInOrder() =
        runTest {
            val source =
                source().apply {
                    checkInOutcomes += ciaNetwork()
                    checkInResponse = ciaResponse("205", listOf("Pendência financeira", "Restrição de acesso"))
                }
            val state = started(source)

            confirmNow(state)
            assertEquals(0, changes)
            confirmNow(state)

            assertEquals(
                CheckInDone(
                    "205",
                    listOf("Hóspede com restrição: Pendência financeira", "Hóspede com restrição: Restrição de acesso"),
                ),
                state.done,
            )
            assertEquals(1, changes)
            assertNull(state.error)
            assertFalse(state.canConfirm)
        }

    @Test
    fun toStringsHidePersonalData() =
        runTest {
            val source = source().apply { checkInResponse = ciaResponse("101", listOf("Pendência financeira")) }
            val state = started(source)
            confirmNow(state)

            val texts = listOf(state.toString(), state.done.toString(), state.primary.toString())
            for (text in texts) {
                assertFalse("Clara" in text, text)
                assertFalse("333" in text, text)
                assertFalse("Pendência" in text, text)
                assertFalse("restrição" in text, text)
            }
        }

    // --- close() e cancelamento próprio ---

    @Test
    fun closeWhileCheckInInFlightChangesNothingAfterwards() =
        runTest {
            val source = source().apply { holdCheckIn = true }
            val state = started(source)

            state.confirm()
            runCurrent()
            state.close()
            source.heldCheckIns.single().complete(Unit)
            runCurrent()

            assertNull(state.done)
            assertNull(state.error)
            assertEquals(0, changes)
            assertEquals(holderId, state.primary?.id)
        }

    @Test
    fun closeWhileApprovalIsPendingIsNotAnError() =
        runTest {
            val source = source().apply { checkInRequiresApproval = ciaApproval() }
            coordinator.decision = { awaitCancellation() }
            val state = started(source)

            confirmNow(state)
            assertTrue(state.busy)
            state.close()
            runCurrent()

            assertNull(state.error)
            assertNull(state.done)
            assertEquals(0, changes)
            assertEquals(1, source.checkInRuns.size)
        }

    @Test
    fun closeWhileRequotingKeepsTheFeeMessage() =
        runTest {
            val source = source().apply { checkInOutcomes += ciaProblem("EARLY_LATE_FEE_CHANGED", 409) }
            val state = started(source)
            state.chargeEarly = true
            source.holdQuote = true
            source.quotes[id] = ciaQuote(fee = "95.00")

            confirmNow(state)
            state.close()
            source.heldQuotes.single().complete(Unit)
            runCurrent()

            assertEquals(CIA_FEE_CHANGED_TEXT, state.error)
            assertEquals(ciaQuote(), state.quote)
            assertEquals(0, changes)
        }

    @Test
    fun closeAlsoClosesPickerAndAllocation() =
        runTest {
            val source = source()
            val state = started(source)
            state.picker.documentNumber = "52998224725"

            state.close()
            state.allocation.assign(ciaRoomB.toString())
            state.picker.searchByName()
            runCurrent()

            assertEquals("", state.picker.documentNumber)
            assertEquals(emptyList(), source.assignCalls)
            assertEquals(emptyList(), source.searchCalls)
        }

    @Test
    fun startAndConfirmAfterCloseDoNothing() =
        runTest {
            val source = source()
            val state = started(source)
            state.close()

            state.start()
            confirmNow(state)

            assertEquals(1, source.holderCalls.size)
            assertEquals(1, source.quoteCalls.size)
            assertEquals(emptyList(), source.checkInCalls)
            assertEquals(0, coordinator.calls)
        }

    // --- Cargas em paralelo ---

    @Test
    fun startLoadsHolderQuoteAndRoomsInParallel() =
        runTest {
            val source =
                source().apply {
                    holdHolder = true
                    holdSuggestions = true
                }
            val state = started(source)

            assertEquals(listOf(holderId), source.holderCalls)
            assertEquals(listOf(id), source.suggestionCalls)
            assertNotNull(state.earlyText)
            assertNull(state.primary)

            val other =
                source().apply {
                    holdQuote = true
                    holdSuggestions = true
                }
            val second = started(other)
            assertEquals(holderId, second.primary?.id)
            assertEquals(listOf(id), other.quoteCalls)
            assertNull(second.earlyText)
        }
}
