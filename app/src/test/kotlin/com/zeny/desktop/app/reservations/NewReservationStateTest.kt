package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.GuestPick
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BookingInput
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.NightlyRateInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.BookingResponse
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.RestrictionAlertResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.ZoneId
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

private val nrType = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val nrPlan = Uuid.parse("20000000-0000-0000-0000-000000000001")
private const val NR_GUEST_ID = "70000000-0000-0000-0000-000000000001"
private val nrGuest = GuestPick(NR_GUEST_ID, HolderSummary("Ana Teste", "CPF ***.***.***-01"))
private val nrOtherGuest = GuestPick("70000000-0000-0000-0000-000000000002", HolderSummary("Bia Teste", null))
private val nrZone = ZoneId.of("America/Sao_Paulo")

private const val NR_UNCONFIRMED_TEXT =
    "Não foi possível confirmar a reserva. Conclua de novo sem mudar nada; " +
        "se fechar, confira na Busca antes de refazer."

private const val NR_SERVER_UNCONFIRMED_TEXT =
    "O servidor não confirmou a reserva. Feche e confira na Busca antes de refazer."

private const val NR_RATE_TEXT = "Informe um valor válido para a diária."

private val nrOffer =
    QuoteOfferResponse(
        accommodationTotal = "300.00",
        additionalPackagesTotal = "0.00",
        availableUnits = 2,
        cancellationPolicyId = Uuid.random(),
        category = QuoteOfferResponse.Category.NORMAL,
        guaranteePolicyId = Uuid.random(),
        includedPackagesTotal = "0.00",
        mealPlan = "BREAKFAST",
        nights = emptyList(),
        ratePlanCode = "BAR",
        ratePlanId = nrPlan,
        ratePlanName = "Balcão",
        roomTotal = "300.00",
        roomTypeCode = "STD",
        roomTypeId = nrType,
        roomTypeName = "Standard",
        total = "300.00",
    )

private val nrStay =
    QuotedStay(BusinessDate.parse("2026-11-13"), BusinessDate.parse("2026-11-16"), 2, listOf(5))

private fun nrGuarantee(type: String) =
    GuaranteeTermsResponse(
        depositPercent = if (type == "DEPOSIT") Option.Some("30.00") else Option.Undefined,
        dueDaysBefore = if (type == "DEPOSIT") Option.Some(7) else Option.Undefined,
        name = "Política",
        policyId = Uuid.random(),
        type = type,
        version = 1,
    )

private fun nrCancellation() =
    CancellationTermsResponse(
        deadlineHours = 24,
        name = "Flex",
        penaltyAmount = Option.Undefined,
        penaltyNights = Option.Some(1),
        penaltyPercent = Option.Undefined,
        penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
        policyId = Uuid.random(),
        version = 1,
    )

private fun nrResponse(
    status: String = "CONFIRMED",
    total: String = "655.37",
    provisionalUntil: String? = null,
    guaranteeType: String = "NONE",
    reasons: List<String> = emptyList(),
): BookingResponse {
    val reservation =
        ReservationResponse(
            additionalPackagesTotal = "0.00",
            adults = 2,
            arrival = "2026-11-13",
            bookingId = Uuid.random(),
            cancellationTerms = nrCancellation(),
            childAges = listOf(5),
            code = "R-90",
            createdAt = "2026-11-12T10:00:00Z",
            dayUse = false,
            departure = "2026-11-16",
            folioId = Uuid.random(),
            guaranteeTerms = nrGuarantee(guaranteeType),
            guests = emptyList(),
            id = Uuid.parse("30000000-0000-0000-0000-000000000009"),
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            provisionalUntil = provisionalUntil?.let { Option.Some(it) } ?: Option.Undefined,
            ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
            ratePlanId = nrPlan,
            roomTotal = "1.00",
            roomTypeId = nrType,
            segment = "LEISURE",
            status = status,
            total = total,
            version = 1,
        )
    return BookingResponse(
        channel = "DIRECT",
        code = "B-12",
        createdAt = "2026-11-12T10:00:00Z",
        holderGuestId = Uuid.parse(NR_GUEST_ID),
        id = Uuid.random(),
        origin = "FRONT_DESK",
        reservations = listOf(reservation),
        restrictionAlerts =
            reasons.map {
                RestrictionAlertResponse(guestId = Uuid.parse(NR_GUEST_ID), reason = it, since = "2026-11-01")
            },
        version = 1,
    )
}

private fun nrProblem(
    status: Int,
    code: String,
    fields: List<FieldError> = emptyList(),
) = ApiError.Problem(status, code, "detalhe do servidor", fields)

private fun nrApproval() = ApiError.ApprovalRequired("DISCOUNT", "rates.discounts.apply", "res", null, null)

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class BookingApprover(
    var cancel: Boolean = false,
) {
    var calls = 0
    val received = mutableListOf<ApprovableAction<BookingResponse>>()

    suspend fun approve(action: ApprovableAction<BookingResponse>): ApiResponse<BookingResponse>? {
        calls++
        received += action
        return try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NewReservationStateTest {
    private val approver = BookingApprover()
    private var changes = 0
    private val guests = FakeReceptionSource().apply { businessDay = BusinessDay(nrStay.arrival, nrZone) }

    private fun source() = FakeNewReservationSource().apply { response = nrResponse() }

    private fun TestScope.state(
        source: FakeNewReservationSource,
        permissions: Set<String> = setOf("guests.profiles.read", "guests.profiles.manage"),
    ) = NewReservationState(source, guests, backgroundScope, nrOffer, nrStay, permissions, approver::approve) {
        changes++
    }

    private fun TestScope.ready(source: FakeNewReservationSource): NewReservationState =
        state(source).also { it.picker.pick(nrGuest) }

    private fun TestScope.confirmNow(state: NewReservationState) {
        state.confirm()
        runCurrent()
    }

    private fun tokenlessKeys(source: FakeNewReservationSource) = source.runs.filter { it.token == null }.map { it.key }

    @Test
    fun confirmSendsOfferStayAndHolder() =
        runTest {
            val source = source()
            val state = ready(source)
            state.origin = "PHONE"
            state.segment = "BUSINESS"
            state.specialRequests = "Andar alto"

            confirmNow(state)

            assertEquals(
                BookingInput(
                    holderGuestId = NR_GUEST_ID,
                    origin = "PHONE",
                    arrival = nrStay.arrival,
                    departure = nrStay.departure,
                    adults = 2,
                    childAges = listOf(5),
                    roomTypeId = nrType.toString(),
                    ratePlanId = nrPlan.toString(),
                    segment = "BUSINESS",
                    specialRequests = "Andar alto",
                    nightlyRates = emptyList(),
                ),
                source.calls.single().input,
            )
            assertEquals(1, approver.calls)
        }

    @Test
    fun defaultsAreFrontDeskLeisureAndNoRequests() =
        runTest {
            val source = source()
            val state = ready(source)

            assertEquals("FRONT_DESK", state.origin)
            assertEquals("LEISURE", state.segment)
            confirmNow(state)

            val input = source.calls.single().input
            assertEquals("FRONT_DESK", input.origin)
            assertEquals("LEISURE", input.segment)
            assertNull(input.specialRequests)
        }

    @Test
    fun pickerPermissionsFollowGuestsPermissions() =
        runTest {
            val full = state(source())
            assertTrue(full.picker.canSearch)
            assertTrue(full.picker.canCreate)

            val none = state(source(), permissions = emptySet())
            assertFalse(none.picker.canSearch)
            assertFalse(none.picker.canCreate)
        }

    @Test
    fun originAndSegmentAcceptOnlyOfferedValues() =
        runTest {
            val state = ready(source())

            state.origin = "WEBSITE"
            state.segment = "GROUP"
            assertEquals("FRONT_DESK", state.origin)
            assertEquals("LEISURE", state.segment)

            state.origin = "WHATSAPP"
            state.segment = "EVENT"
            assertEquals("WHATSAPP", state.origin)
            assertEquals("EVENT", state.segment)
        }

    @Test
    fun specialRequestsAreLimitedTo1000Characters() =
        runTest {
            val state = ready(source())

            state.specialRequests = "a".repeat(1000)
            assertEquals(1000, state.specialRequests.length)
            state.specialRequests = "a".repeat(1001)
            assertEquals(1000, state.specialRequests.length)
        }

    @Test
    fun negotiatedRateGoesToEveryNightUnchanged() =
        runTest {
            val source = source()
            val state = ready(source)
            state.nightlyRateText = "150,00"

            confirmNow(state)

            val sent = source.calls.single().input
            val rate = Money.parse("150.00")
            assertEquals(
                listOf(
                    NightlyRateInput(BusinessDate.parse("2026-11-13"), rate),
                    NightlyRateInput(BusinessDate.parse("2026-11-14"), rate),
                    NightlyRateInput(BusinessDate.parse("2026-11-15"), rate),
                ),
                sent.nightlyRates,
            )
            assertNull(state.rateError)
        }

    @Test
    fun invalidRateDoesNotHitServer() =
        runTest {
            val source = source()
            val state = ready(source)

            for (text in listOf("abc", "0", "0,00", "-10,00", "10,555")) {
                state.nightlyRateText = text
                confirmNow(state)
                assertEquals(NR_RATE_TEXT, state.rateError, text)
                assertFalse(state.busy)
            }

            assertTrue(source.calls.isEmpty())
            assertEquals(0, approver.calls)
            assertNull(state.done)

            state.nightlyRateText = "150"
            assertNull(state.rateError)
            confirmNow(state)
            assertEquals(1, source.calls.size)
        }

    @Test
    fun cannotConfirmWithoutHolder() =
        runTest {
            val source = source()
            val state = state(source)

            assertFalse(state.canConfirm)
            confirmNow(state)
            assertTrue(source.calls.isEmpty())
            assertEquals(0, approver.calls)

            state.picker.pick(nrGuest)
            assertTrue(state.canConfirm)
        }

    @Test
    fun doneShowsServerTotal() =
        runTest {
            val source = source().apply { response = nrResponse(total = "655.37") }
            val state = ready(source)

            confirmNow(state)

            val done = assertNotNull(state.done)
            assertEquals(ReceptionText.money("655.37"), done.total)
            assertEquals("30000000-0000-0000-0000-000000000009", done.reservationId)
            assertEquals("R-90", done.code)
            assertEquals("CONFIRMED", done.status)
            assertEquals(ReceptionText.period("2026-11-13", "2026-11-16", false), done.period)
            assertEquals(1, changes)
            assertNull(state.error)
        }

    @Test
    fun provisionalShowsDeadlineAndGuarantee() =
        runTest {
            val deadline = "2026-11-14T15:00:00Z"
            val source =
                source().apply {
                    response = nrResponse("PROVISIONAL", provisionalUntil = deadline, guaranteeType = "CARD")
                }
            val state = ready(source)

            confirmNow(state)

            val done = assertNotNull(state.done)
            assertEquals("PROVISIONAL", done.status)
            assertEquals(ReceptionText.instant(deadline, nrZone), done.provisionalUntil)
            assertNotNull(done.provisionalUntil)
            assertEquals("Garantia exigida: Cartão", done.guarantee)
        }

    @Test
    fun provisionalWithoutZoneHidesDeadline() =
        runTest {
            guests.businessDay = BusinessDay(nrStay.arrival, null)
            val source =
                source().apply {
                    response =
                        nrResponse("PROVISIONAL", provisionalUntil = "2026-11-14T15:00:00Z", guaranteeType = "CARD")
                }
            val state = ready(source)

            confirmNow(state)

            val done = assertNotNull(state.done)
            assertNull(done.provisionalUntil)
            assertEquals("Garantia exigida: Cartão", done.guarantee)
        }

    @Test
    fun provisionalSurvivesFailureToReadBusinessDay() =
        runTest {
            guests.businessDayError = IOException("offline")
            val source =
                source().apply {
                    response =
                        nrResponse("PROVISIONAL", provisionalUntil = "2026-11-14T15:00:00Z", guaranteeType = "CARD")
                }
            val state = ready(source)

            confirmNow(state)

            assertNull(assertNotNull(state.done).provisionalUntil)
            assertFalse(state.unconfirmed)
            assertEquals(1, changes)
        }

    @Test
    fun confirmedHidesThem() =
        runTest {
            val source = source().apply { response = nrResponse("CONFIRMED", guaranteeType = "NONE") }
            val state = ready(source)

            confirmNow(state)

            val done = assertNotNull(state.done)
            assertNull(done.provisionalUntil)
            assertNull(done.guarantee)
            assertEquals(0, guests.businessDayCalls)
        }

    @Test
    fun doneShowsCancellationTerms() =
        runTest {
            val state = ready(source())

            confirmNow(state)

            assertEquals(
                "Cancelamento sem multa até 24 h antes do check-in; depois, multa de 1 diária(s).",
                assertNotNull(state.done).cancellation,
            )
        }

    @Test
    fun doneShowsRestrictionAlerts() =
        runTest {
            val source = source().apply { response = nrResponse(reasons = listOf("Fumou no quarto")) }
            val state = ready(source)

            confirmNow(state)

            assertEquals(listOf("Hóspede com restrição: Fumou no quarto"), assertNotNull(state.done).alerts)
        }

    @Test
    fun approvalRequiredGoesThroughApprove() =
        runTest {
            val source = source().apply { requiresApproval = nrApproval() }
            val state = ready(source)

            confirmNow(state)

            assertSame(source.actions.single(), approver.received.single())
            assertEquals(listOf(null, "tok"), source.runs.map { it.token })
            assertNotNull(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun approvalCancelledKeepsIntent() =
        runTest {
            val source = source().apply { requiresApproval = nrApproval() }
            approver.cancel = true
            val state = ready(source)

            confirmNow(state)
            assertNull(state.done)
            assertFalse(state.busy)
            assertFalse(state.unconfirmed)
            assertEquals(0, changes)
            approver.cancel = false
            confirmNow(state)

            assertSame(source.calls[0].intent, source.calls[1].intent)
            assertEquals(2, tokenlessKeys(source).size)
            assertEquals(tokenlessKeys(source)[0], tokenlessKeys(source)[1])
            assertNotNull(state.done)
        }

    @Test
    fun cancelledApprovalAfterUnknownResultUnlocksTheForm() =
        runTest {
            val reference = source().apply { requiresApproval = nrApproval() }
            approver.cancel = true
            val referenceState = ready(reference)
            confirmNow(referenceState)
            approver.cancel = false
            confirmNow(referenceState)

            val source = source().apply { outcomes += ApiError.Network(IOException("offline")) }
            val state = ready(source)
            confirmNow(state)
            assertTrue(state.unconfirmed)
            source.requiresApproval = nrApproval()
            approver.cancel = true
            confirmNow(state)

            assertFalse(state.unconfirmed)
            assertNull(state.error)
            assertNull(state.done)
            assertFalse(state.busy)
            assertTrue(state.canConfirm)
            approver.cancel = false
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(3, keys.size)
            assertEquals(1, keys.toSet().size)
            assertSame(source.calls[0].intent, source.calls[2].intent)
            assertEquals(1, tokenlessKeys(reference).toSet().size)
            assertEquals(tokenlessKeys(reference).size, keys.size - 1)
            assertNotNull(state.done)
        }

    @Test
    fun inputsAcceptEditsAfterCancelledApprovalFollowingUnknownResult() =
        runTest {
            val source = source().apply { outcomes += ApiError.Network(IOException("offline")) }
            val state = ready(source)
            confirmNow(state)
            source.requiresApproval = nrApproval()
            approver.cancel = true
            confirmNow(state)

            state.specialRequests = "Andar alto"

            assertEquals("Andar alto", state.specialRequests)
        }

    @Test
    fun unknownResultKeepsIntent() =
        runTest {
            val source = source().apply { outcomes += ApiError.Network(IOException("offline")) }
            val state = ready(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertNull(state.done)
            assertEquals(NR_UNCONFIRMED_TEXT, state.error)
            assertEquals(0, changes)
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
            assertSame(source.calls[0].intent, source.calls[1].intent)
            assertNotNull(state.done)
            assertFalse(state.unconfirmed)
            assertNull(state.error)
        }

    @Test
    fun keyInUseKeepsIntent() =
        runTest {
            val source = source().apply { outcomes += nrProblem(409, "IDEMPOTENCY_KEY_IN_USE") }
            val state = ready(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertEquals(NR_UNCONFIRMED_TEXT, state.error)
            confirmNow(state)

            assertSame(source.calls[0].intent, source.calls[1].intent)
            assertNotNull(state.done)
        }

    @Test
    fun serverErrorShowsServerUnconfirmedText() =
        runTest {
            val source = source().apply { outcomes += nrProblem(500, "INTERNAL_ERROR") }
            val state = ready(source)

            confirmNow(state)

            assertTrue(state.unconfirmed)
            assertEquals(NR_SERVER_UNCONFIRMED_TEXT, state.error)
            confirmNow(state)
            assertSame(source.calls[0].intent, source.calls[1].intent)
        }

    @Test
    fun gatewayErrorShowsUnknownResultText() =
        runTest {
            val source = source().apply { outcomes += ApiError.Unexpected(502) }
            val state = ready(source)

            confirmNow(state)

            assertTrue(state.unconfirmed)
            assertEquals(NR_UNCONFIRMED_TEXT, state.error)
        }

    @Test
    fun changedInputTakesNewIntent() =
        runTest {
            val source = source().apply { requiresApproval = nrApproval() }
            approver.cancel = true
            val state = ready(source)

            confirmNow(state)
            state.picker.pick(nrOtherGuest)
            confirmNow(state)
            state.origin = "EMAIL"
            confirmNow(state)
            state.segment = "EVENT"
            confirmNow(state)
            state.specialRequests = "Berço"
            confirmNow(state)
            state.nightlyRateText = "100,00"
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(6, keys.size)
            assertEquals(6, keys.toSet().size)
        }

    @Test
    fun definitiveRefusalTakesNewIntent() =
        runTest {
            val refusal = nrProblem(409, "ROOM_TYPE_UNAVAILABLE")
            val source = source().apply { outcomes += refusal }
            val state = ready(source)

            confirmNow(state)
            assertFalse(state.unconfirmed)
            assertEquals(refusal.userMessage, state.error)
            assertNull(state.done)
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(2, keys.size)
            assertNotEquals(keys[0], keys[1])
            assertNotSame(source.calls[0].intent, source.calls[1].intent)
            assertEquals(1, changes)
        }

    @Test
    fun fieldErrorMessagesWithoutKeys() =
        runTest {
            val refusal =
                nrProblem(
                    400,
                    "STAY_INVALID",
                    listOf(
                        FieldError("reservations[0].departure", "STAY_INVALID", "A saída precisa vir depois."),
                        FieldError("reservations[0].adults", "OUT_OF_RANGE", "Adultos fora do limite."),
                    ),
                )
            val source = source().apply { outcomes += refusal }
            val state = ready(source)

            confirmNow(state)

            assertEquals(listOf("A saída precisa vir depois.", "Adultos fora do limite."), state.fieldErrors)
            assertEquals(refusal.userMessage, state.error)
            assertFalse(state.error.orEmpty().contains("reservations["))
            assertFalse(state.unconfirmed)

            confirmNow(state)
            assertEquals(emptyList(), state.fieldErrors)
            assertNull(state.error)
        }

    @Test
    fun lockedWhileBusyAndAfterDone() =
        runTest {
            val source = source().apply { hold = true }
            val state = ready(source)

            state.confirm()
            runCurrent()
            assertTrue(state.busy)
            assertFalse(state.canConfirm)
            state.origin = "PHONE"
            state.segment = "EVENT"
            state.specialRequests = "x"
            state.nightlyRateText = "10"
            state.picker.pick(nrOtherGuest)
            state.confirm()
            runCurrent()
            assertEquals("FRONT_DESK", state.origin)
            assertEquals("LEISURE", state.segment)
            assertEquals("", state.specialRequests)
            assertEquals("", state.nightlyRateText)
            assertEquals(nrGuest, state.holder)
            assertEquals(1, source.calls.size)

            source.release()
            runCurrent()
            assertNotNull(state.done)
            assertFalse(state.busy)

            state.origin = "PHONE"
            state.segment = "EVENT"
            state.specialRequests = "x"
            state.nightlyRateText = "10"
            state.picker.pick(nrOtherGuest)
            state.confirm()
            runCurrent()
            assertEquals("FRONT_DESK", state.origin)
            assertEquals("LEISURE", state.segment)
            assertEquals("", state.specialRequests)
            assertEquals("", state.nightlyRateText)
            assertEquals(nrGuest, state.holder)
            assertFalse(state.canConfirm)
            assertEquals(1, source.calls.size)
            assertEquals(1, changes)
        }

    @Test
    fun lockedWhileUnconfirmed() =
        runTest {
            val source = source().apply { outcomes += ApiError.Network(IOException("offline")) }
            val state = ready(source)
            state.specialRequests = "Andar alto"

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertTrue(state.canConfirm)

            state.origin = "PHONE"
            state.segment = "EVENT"
            state.specialRequests = "outro"
            state.nightlyRateText = "10"
            state.picker.pick(nrOtherGuest)
            runCurrent()

            assertEquals("FRONT_DESK", state.origin)
            assertEquals("LEISURE", state.segment)
            assertEquals("Andar alto", state.specialRequests)
            assertEquals("", state.nightlyRateText)
            assertEquals(nrGuest, state.holder)
            assertEquals(NR_UNCONFIRMED_TEXT, state.error)

            confirmNow(state)
            val keys = tokenlessKeys(source)
            assertEquals(keys[0], keys[1])
            assertEquals(source.calls[0].input, source.calls[1].input)
            assertNotNull(state.done)
        }

    @Test
    fun closeDropsLateAnswer() =
        runTest {
            val source = source().apply { hold = true }
            val state = ready(source)

            state.confirm()
            runCurrent()
            state.close()
            source.release()
            runCurrent()

            assertNull(state.done)
            assertEquals(0, changes)
            state.confirm()
            runCurrent()
            assertEquals(1, source.calls.size)
        }

    @Test
    fun onChangedOnlyOnSuccess() =
        runTest {
            val source =
                source().apply {
                    outcomes += ApiError.Network(IOException("offline"))
                    outcomes += nrProblem(422, "GUEST_ANONYMIZED")
                }
            val state = ready(source)

            confirmNow(state)
            assertEquals(0, changes)
            confirmNow(state)
            assertEquals(0, changes)
            confirmNow(state)
            assertEquals(1, changes)
            confirmNow(state)
            assertEquals(1, changes)
        }

    @Test
    fun toStringHidesHolderAndRequests() =
        runTest {
            val state = ready(source())
            state.specialRequests = "alergia a amendoim"

            val text = state.toString()

            assertFalse(text.contains("Ana"))
            assertFalse(text.contains(NR_GUEST_ID))
            assertFalse(text.contains("amendoim"))
        }

    @Test
    fun doneToStringHidesAlerts() =
        runTest {
            val source = source().apply { response = nrResponse(reasons = listOf("Fumou no quarto")) }
            val state = ready(source)

            confirmNow(state)

            val text = assertNotNull(state.done).toString()
            assertFalse(text.contains("Fumou"))
            assertTrue(text.contains("R-90"))
        }
}
