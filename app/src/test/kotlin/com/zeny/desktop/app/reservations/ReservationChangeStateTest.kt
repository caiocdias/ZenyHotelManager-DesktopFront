package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.ReservationChangeInput
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
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

private val rcRes = Uuid.parse("20000000-0000-0000-0000-0000000000b1")
private val rcType = Uuid.parse("10000000-0000-0000-0000-0000000000b1")
private val rcOtherType = Uuid.parse("10000000-0000-0000-0000-0000000000b2")
private val rcPlan = Uuid.parse("40000000-0000-0000-0000-0000000000b1")
private val rcOtherPlan = Uuid.parse("40000000-0000-0000-0000-0000000000b2")
private const val RC_ETAG = "\"v1\""
private const val RC_NEW_ETAG = "\"v2\""
private const val RC_STALE_NOTICE = "Alguém alterou esta reserva. Os dados foram recarregados; confira e tente de novo."
private const val RC_NOTHING = "Nada mudou."

@Suppress("LongParameterList") // fixture: cada campo da reserva que os testes variam, com padrão
private fun rcVersion(
    etag: String = RC_ETAG,
    status: String = "CONFIRMED",
    arrival: String = "2026-11-20",
    departure: String = "2026-11-23",
    adults: Int = 2,
    childAges: List<Int> = listOf(5),
    segment: String = "LEISURE",
    specialRequests: String? = "Andar alto",
    total: String = "720.00",
) = ReservationVersion(
    ReservationDetailResponse(
        bookingCode = "B-1",
        holderGuestId = Uuid.random(),
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = adults,
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
                childAges = childAges,
                code = "R-1",
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
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
                id = rcRes,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
                ratePlanId = rcPlan,
                roomTotal = total,
                roomTypeId = rcType,
                segment = segment,
                specialRequests = Option.Some(specialRequests),
                status = status,
                total = total,
                version = 3,
            ),
        restrictionAlerts = emptyList(),
    ),
    etag,
)

private fun rcOffer(
    type: Uuid,
    plan: Uuid,
    name: String,
) = QuoteOfferResponse(
    accommodationTotal = "300.00",
    additionalPackagesTotal = "0.00",
    availableUnits = 2,
    cancellationPolicyId = plan,
    category = QuoteOfferResponse.Category.NORMAL,
    guaranteePolicyId = plan,
    includedPackagesTotal = "0.00",
    mealPlan = "BB",
    nights = emptyList(),
    ratePlanCode = name,
    ratePlanId = plan,
    ratePlanName = name,
    roomTotal = "300.00",
    roomTypeCode = name,
    roomTypeId = type,
    roomTypeName = name,
    total = "300.00",
)

private fun rcQuote(vararg offers: QuoteOfferResponse) =
    QuoteResponse(
        adults = 2,
        arrival = "2026-11-20",
        channel = "DIRECT",
        childAges = listOf(5),
        departure = "2026-11-23",
        nights = 3,
        offers = offers.toList(),
    )

private fun rcProblem(
    status: Int,
    code: String?,
    fieldErrors: List<FieldError> = emptyList(),
) = ApiError.Problem(status, code, "detalhe do servidor", fieldErrors)

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class RcApprover(
    var cancel: Boolean = false,
) {
    var calls = 0

    suspend fun approve(action: ApprovableAction<ReservationDetailResponse>): ApiResponse<ReservationDetailResponse>? {
        calls++
        return try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions") // um teste por regra do brief
class ReservationChangeStateTest {
    private val id = rcRes.toString()
    private var changes = 0
    private val approver = RcApprover()

    private fun source() =
        FakeReservationChangeSource().apply {
            versions[id] = rcVersion()
            changeResponse = rcVersion(total = "987.65").reservation
        }

    private fun TestScope.state(
        source: FakeReservationChangeSource,
        canQuote: Boolean = true,
    ) = ReservationChangeState(source, backgroundScope, id, canQuote, approver::approve) { changes++ }

    private fun TestScope.started(
        source: FakeReservationChangeSource,
        canQuote: Boolean = true,
    ): ReservationChangeState =
        state(source, canQuote).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.confirmNow(state: ReservationChangeState) {
        state.confirm()
        runCurrent()
    }

    private fun TestScope.loadOptionsNow(state: ReservationChangeState) {
        state.loadOptions()
        runCurrent()
    }

    @Suppress("LongParameterList") // fixture: cada campo da alteração que os testes variam, com padrão
    private fun input(
        arrival: String = "2026-11-20",
        departure: String = "2026-11-23",
        adults: Int = 2,
        childAges: List<Int> = listOf(5),
        type: Uuid = rcType,
        plan: Uuid = rcPlan,
        segment: String = "LEISURE",
        specialRequests: String? = "Andar alto",
    ) = ReservationChangeInput(
        BusinessDate.parse(arrival),
        BusinessDate.parse(departure),
        adults,
        childAges,
        type.toString(),
        plan.toString(),
        segment,
        specialRequests,
        emptyList(),
    )

    @Test
    fun startFillsFieldsFromReservation() =
        runTest {
            val source = source()
            val state = started(source)

            assertEquals(listOf(id), source.versionCalls)
            assertEquals(1, source.businessDayCalls)
            assertEquals("R-1", state.code)
            assertEquals("20/11/2026", state.arrivalText)
            assertEquals("23/11/2026", state.departureText)
            assertEquals("2", state.adultsText)
            assertEquals("5", state.childAgesText)
            assertEquals("LEISURE", state.segment)
            assertEquals("Andar alto", state.specialRequests)
            assertFalse(state.inHouse)
            assertFalse(state.loading)
            assertTrue(state.canConfirm)
            assertNull(state.error)
            assertNull(state.notice)
            assertTrue(state.options.isEmpty())
            assertNull(state.option)
        }

    @Test
    fun startFailureShowsErrorAndBlocksConfirm() =
        runTest {
            val failure = rcProblem(404, "RESERVATION_NOT_FOUND")
            val source = source().apply { versionError = failure }
            val state = started(source)

            assertEquals(failure.userMessage, state.error)
            assertFalse(state.loading)
            assertFalse(state.canConfirm)
        }

    @Test
    fun changeSendsWholeStayWithEtag() =
        runTest {
            val source = source()
            val state = started(source)
            state.departureText = "24/11/2026"
            confirmNow(state)

            assertEquals(listOf(ChangeCall(id, input(departure = "2026-11-24"), RC_ETAG)), source.changeCalls)
            assertNotNull(state.done)
        }

    @Test
    fun chosenOptionReplacesTypeAndPlan() =
        runTest {
            val other = rcOffer(rcOtherType, rcOtherPlan, "Suíte")
            val source = source().apply { quote = rcQuote(other) }
            val state = started(source)
            loadOptionsNow(state)
            state.choose(other)
            confirmNow(state)

            assertEquals(other, state.option)
            assertEquals(listOf(input(type = rcOtherType, plan = rcOtherPlan)), source.changeCalls.map { it.input })
        }

    @Test
    fun noOptionKeepsCurrentTypeAndPlan() =
        runTest {
            val other = rcOffer(rcOtherType, rcOtherPlan, "Suíte")
            val source = source().apply { quote = rcQuote(other) }
            val state = started(source)
            loadOptionsNow(state)
            state.departureText = "24/11/2026"
            loadOptionsNow(state)
            state.choose(other)
            state.choose(null)
            confirmNow(state)

            assertEquals(listOf(input(departure = "2026-11-24")), source.changeCalls.map { it.input })
        }

    @Test
    fun stayEditDropsOptions() =
        runTest {
            val other = rcOffer(rcOtherType, rcOtherPlan, "Suíte")
            val source = source().apply { quote = rcQuote(other) }
            val state = started(source)
            val edits: List<(ReservationChangeState) -> Unit> =
                listOf(
                    { it.arrivalText = "19/11/2026" },
                    { it.departureText = "25/11/2026" },
                    { it.adultsText = "3" },
                    { it.childAgesText = "5, 8" },
                )
            for (edit in edits) {
                loadOptionsNow(state)
                state.choose(other)
                assertEquals(other, state.option)
                edit(state)
                assertTrue(state.options.isEmpty())
                assertNull(state.option)
            }
        }

    @Test
    fun segmentAndRequestsEditsKeepOptions() =
        runTest {
            val other = rcOffer(rcOtherType, rcOtherPlan, "Suíte")
            val source = source().apply { quote = rcQuote(other) }
            val state = started(source)
            loadOptionsNow(state)
            state.choose(other)
            state.segment = "BUSINESS"
            state.specialRequests = "Berço"

            assertEquals(listOf(other), state.options)
            assertEquals(other, state.option)
        }

    @Test
    fun editDuringOptionsLoadDiscardsTheLateAnswer() =
        runTest {
            val source = source().apply { holdQuote = true }
            val state = started(source)
            state.loadOptions()
            runCurrent()
            assertTrue(state.loadingOptions)
            state.adultsText = "3"
            assertFalse(state.loadingOptions)
            source.heldQuotes.forEach { it.complete(Unit) }
            runCurrent()

            assertTrue(state.options.isEmpty())
        }

    @Test
    fun inHouseSendsOnlyNewDeparture() =
        runTest {
            val source =
                source().apply {
                    versions[id] = rcVersion(status = "IN_HOUSE", arrival = "2026-11-12", departure = "2026-11-15")
                }
            val state = started(source)
            state.arrivalText = "11/11/2026"
            state.adultsText = "3"
            state.childAgesText = ""
            state.departureText = "16/11/2026"
            state.loadOptions()
            runCurrent()
            confirmNow(state)

            assertTrue(state.inHouse)
            assertEquals("12/11/2026", state.arrivalText)
            assertEquals("2", state.adultsText)
            assertTrue(source.quoteCalls.isEmpty())
            assertTrue(state.options.isEmpty())
            assertEquals(
                listOf(input(arrival = "2026-11-12", departure = "2026-11-16")),
                source.changeCalls.map { it.input },
            )
        }

    @Test
    fun inHouseDepartureMustBeAfterBusinessDate() =
        runTest {
            val source =
                source().apply {
                    versions[id] = rcVersion(status = "IN_HOUSE", arrival = "2026-11-12", departure = "2026-11-15")
                }
            val state = started(source)
            state.departureText = "13/11/2026"
            confirmNow(state)

            assertEquals("A saída precisa ser depois da data operacional (13/11/2026).", state.validation)
            assertTrue(source.changeCalls.isEmpty())
        }

    @Test
    fun inHouseUnchangedDepartureOnBusinessDatePassesWithOtherChanges() =
        runTest {
            val source =
                source().apply {
                    businessDay = BusinessDay(BusinessDate.parse("2026-11-15"), null)
                    versions[id] = rcVersion(status = "IN_HOUSE", arrival = "2026-11-12", departure = "2026-11-15")
                }
            val state = started(source)
            state.specialRequests = "Sem glúten"
            confirmNow(state)

            assertNull(state.validation)
            assertEquals(
                listOf(input(arrival = "2026-11-12", departure = "2026-11-15", specialRequests = "Sem glúten")),
                source.changeCalls.map { it.input },
            )
        }

    @Test
    fun optionsNeedCanQuote() =
        runTest {
            val source = source().apply { quote = rcQuote(rcOffer(rcOtherType, rcOtherPlan, "Suíte")) }
            val state = started(source, canQuote = false)
            loadOptionsNow(state)

            assertFalse(state.canQuote)
            assertTrue(source.quoteCalls.isEmpty())
            assertTrue(state.options.isEmpty())
        }

    @Test
    fun optionsQuoteTheTypedStay() =
        runTest {
            val source = source()
            val state = started(source)
            state.departureText = "25/11/2026"
            state.adultsText = "3"
            state.childAgesText = "5, 8"
            loadOptionsNow(state)

            assertEquals(
                listOf(
                    QuoteCall(
                        BusinessDate.parse("2026-11-20"),
                        BusinessDate.parse("2026-11-25"),
                        3,
                        listOf(5, 8),
                    ),
                ),
                source.quoteCalls,
            )
        }

    @Test
    fun optionsOmitCurrentTypeAndPlan() =
        runTest {
            val current = rcOffer(rcType, rcPlan, "Atual")
            val samePlanOtherType = rcOffer(rcOtherType, rcPlan, "Suíte")
            val sameTypeOtherPlan = rcOffer(rcType, rcOtherPlan, "Flex")
            val source = source().apply { quote = rcQuote(current, samePlanOtherType, sameTypeOtherPlan) }
            val state = started(source)
            loadOptionsNow(state)

            assertEquals(listOf(samePlanOtherType, sameTypeOtherPlan), state.options)
            assertFalse(state.loadingOptions)
        }

    @Test
    fun optionsFailureShowsMessage() =
        runTest {
            val failure = rcProblem(400, "STAY_INVALID", listOf(FieldError("departure", "X", "Saída inválida")))
            val source = source().apply { quoteError = failure }
            val state = started(source)
            loadOptionsNow(state)

            assertEquals(failure.userMessage, state.error)
            assertEquals(mapOf("departure" to "Saída inválida"), state.fieldErrors)
            assertFalse(state.loadingOptions)
        }

    @Test
    fun chooseIgnoresAnOfferThatIsNotListed() =
        runTest {
            val source = source().apply { quote = rcQuote(rcOffer(rcOtherType, rcOtherPlan, "Suíte")) }
            val state = started(source)
            loadOptionsNow(state)
            state.choose(rcOffer(rcOtherType, rcPlan, "Fora da lista"))

            assertNull(state.option)
        }

    @Test
    fun unknownSegmentAsksForChoice() =
        runTest {
            val source = source().apply { versions[id] = rcVersion(segment = "WEDDING") }
            val state = started(source)

            assertNull(state.segment)
            confirmNow(state)
            assertEquals("Escolha o segmento.", state.validation)
            assertTrue(source.changeCalls.isEmpty())

            state.segment = "EVENT"
            confirmNow(state)
            assertEquals(listOf(input(segment = "EVENT")), source.changeCalls.map { it.input })
        }

    @Test
    fun segmentOutsideTheRequestEnumIsIgnored() =
        runTest {
            val state = started(source())
            state.segment = "WEDDING"

            assertEquals("LEISURE", state.segment)
        }

    @Test
    fun unchangedStayDoesNotHitServer() =
        runTest {
            val source = source()
            val state = started(source)
            confirmNow(state)

            assertEquals(RC_NOTHING, state.validation)
            assertTrue(source.changeCalls.isEmpty())
            assertEquals(0, approver.calls)
            assertEquals(0, changes)
            assertFalse(state.busy)
        }

    @Test
    fun blankRequestsEqualAbsentRequests() =
        runTest {
            val source = source().apply { versions[id] = rcVersion(specialRequests = null) }
            val state = started(source)
            state.specialRequests = "  "
            confirmNow(state)

            assertEquals(RC_NOTHING, state.validation)
            assertTrue(source.changeCalls.isEmpty())
        }

    @Test
    fun invalidStayDoesNotHitServer() =
        runTest {
            val cases: List<Pair<(ReservationChangeState) -> Unit, String>> =
                listOf(
                    ({ s: ReservationChangeState -> s.arrivalText = "xx" }) to
                        "Informe a chegada e a saída no formato dd/mm/aaaa.",
                    ({ s: ReservationChangeState -> s.departureText = "" }) to
                        "Informe a chegada e a saída no formato dd/mm/aaaa.",
                    ({ s: ReservationChangeState -> s.departureText = "20/11/2026" }) to
                        "A saída precisa ser depois da chegada.",
                    ({ s: ReservationChangeState -> s.departureText = "19/11/2026" }) to
                        "A saída precisa ser depois da chegada.",
                    ({ s: ReservationChangeState -> s.departureText = "21/11/2027" }) to
                        "A estadia vai até 365 noites.",
                    ({ s: ReservationChangeState -> s.adultsText = "0" }) to "Informe de 1 a 40 adultos.",
                    ({ s: ReservationChangeState -> s.adultsText = "41" }) to "Informe de 1 a 40 adultos.",
                    ({ s: ReservationChangeState -> s.adultsText = "dois" }) to "Informe de 1 a 40 adultos.",
                    ({ s: ReservationChangeState -> s.childAgesText = "a" }) to
                        "Informe as idades das crianças em números, separadas por vírgula.",
                    ({ s: ReservationChangeState -> s.childAgesText = "-1" }) to
                        "Informe as idades das crianças em números, separadas por vírgula.",
                    ({ s: ReservationChangeState -> s.childAgesText = List(21) { "3" }.joinToString(",") }) to
                        "Informe até 20 crianças.",
                )
            for ((edit, message) in cases) {
                val source = source()
                val state = started(source)
                edit(state)
                confirmNow(state)

                assertEquals(message, state.validation)
                assertTrue(source.changeCalls.isEmpty())
                assertFalse(state.busy)
            }
        }

    @Test
    fun invalidStayDoesNotQuoteEither() =
        runTest {
            val source = source()
            val state = started(source)
            state.adultsText = "0"
            loadOptionsNow(state)

            assertEquals("Informe de 1 a 40 adultos.", state.validation)
            assertTrue(source.quoteCalls.isEmpty())
        }

    @Test
    fun staleVersionReloadsAndWarns() =
        runTest {
            val other = rcOffer(rcOtherType, rcOtherPlan, "Suíte")
            val source =
                source().apply {
                    quote = rcQuote(other)
                    changeOutcomes += ApiError.PreconditionFailed(null)
                }
            val state = started(source)
            state.arrivalText = "21/11/2026"
            state.departureText = "28/11/2026"
            state.adultsText = "4"
            state.childAgesText = "1, 2"
            loadOptionsNow(state)
            state.choose(other)
            assertEquals(other, state.option)
            state.segment = "BUSINESS"
            state.specialRequests = "Outro pedido"
            source.versions[id] =
                rcVersion(
                    etag = RC_NEW_ETAG,
                    departure = "2026-11-25",
                    adults = 3,
                    childAges = emptyList(),
                    segment = "EVENT",
                    specialRequests = "Do outro usuário",
                )
            confirmNow(state)

            assertEquals(listOf(id, id), source.versionCalls)
            assertEquals(RC_STALE_NOTICE, state.notice)
            assertNull(state.done)
            assertEquals(0, changes)
            assertEquals("20/11/2026", state.arrivalText)
            assertEquals("25/11/2026", state.departureText)
            assertEquals("3", state.adultsText)
            assertEquals("", state.childAgesText)
            assertEquals("EVENT", state.segment)
            assertEquals("Do outro usuário", state.specialRequests)
            assertNull(state.option)
            assertTrue(state.options.isEmpty())
            assertNull(state.validation)
            assertFalse(state.loading)
            assertFalse(state.busy)
            assertTrue(state.canConfirm)
        }

    @Test
    fun confirmRightAfterStaleSaysNothingChanged() =
        runTest {
            val source = source().apply { changeOutcomes += ApiError.PreconditionFailed(null) }
            val state = started(source)
            state.departureText = "24/11/2026"
            confirmNow(state)
            assertEquals(RC_STALE_NOTICE, state.notice)
            confirmNow(state)

            assertEquals(RC_NOTHING, state.validation)
            assertEquals(1, source.changeCalls.size)
            assertNull(state.done)
        }

    @Test
    fun retryAfterReloadUsesNewEtag() =
        runTest {
            val source = source().apply { changeOutcomes += ApiError.PreconditionFailed(null) }
            val state = started(source)
            state.departureText = "24/11/2026"
            source.versions[id] = rcVersion(etag = RC_NEW_ETAG)
            confirmNow(state)
            state.departureText = "24/11/2026"
            confirmNow(state)

            assertEquals(listOf(RC_ETAG, RC_NEW_ETAG), source.changeCalls.map { it.etag })
            assertNotNull(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun staleReloadFailureShowsTheFailure() =
        runTest {
            val source = source().apply { changeOutcomes += ApiError.PreconditionFailed(null) }
            val state = started(source)
            state.departureText = "24/11/2026"
            val failure = rcProblem(500, "INTERNAL")
            source.versionError = failure
            confirmNow(state)

            assertEquals(failure.userMessage, state.error)
            assertNull(state.notice)
            assertFalse(state.canConfirm)
        }

    @Test
    fun changedTotalComesFromServer() =
        runTest {
            val source = source()
            val state = started(source)
            state.departureText = "24/11/2026"
            confirmNow(state)

            assertEquals("Reserva R-1 alterada. Novo total: ${ReceptionText.money("987.65")}.", state.done)
            assertEquals(1, changes)
            assertNull(state.error)
        }

    @Test
    fun approvalRequiredGoesThroughApprove() =
        runTest {
            val source =
                source().apply {
                    changeRequiresApproval =
                        ApiError.ApprovalRequired("rates.discounts.apply", "rates.discounts.apply", id, null, null)
                }
            val state = started(source)
            state.departureText = "24/11/2026"
            confirmNow(state)

            assertEquals(1, approver.calls)
            assertEquals(listOf(null, "tok"), source.changeRuns.map { it.token })
            assertEquals(listOf(RC_ETAG, RC_ETAG), source.changeRuns.map { it.etag })
            assertNotNull(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun approvalCancelledLeavesDialogOpen() =
        runTest {
            val source =
                source().apply {
                    changeRequiresApproval =
                        ApiError.ApprovalRequired("rates.discounts.apply", "rates.discounts.apply", id, null, null)
                }
            approver.cancel = true
            val state = started(source)
            state.departureText = "24/11/2026"
            confirmNow(state)

            assertEquals(listOf<String?>(null), source.changeRuns.map { it.token })
            assertNull(state.done)
            assertNull(state.error)
            assertFalse(state.busy)
            assertTrue(state.canConfirm)
            assertEquals("24/11/2026", state.departureText)
            assertEquals(0, changes)
        }

    @Test
    fun refusalShowsCatalogText() =
        runTest {
            val refusals =
                listOf(
                    rcProblem(409, "IN_HOUSE_STAY_FIXED"),
                    rcProblem(
                        422,
                        "IN_HOUSE_DEPARTURE_INVALID",
                        listOf(FieldError("departure", "X", "Saída inválida")),
                    ),
                )
            for (refusal in refusals) {
                val source = source().apply { changeOutcomes += refusal }
                val state = started(source)
                state.departureText = "24/11/2026"
                confirmNow(state)

                assertEquals(refusal.userMessage, state.error)
                assertFalse(state.error.orEmpty().contains(refusal.code.orEmpty()))
                assertEquals(
                    refusal.fieldErrors.associate { it.field to it.message },
                    state.fieldErrors,
                )
                assertNull(state.done)
                assertNull(state.notice)
                assertEquals(0, changes)
                assertTrue(state.canConfirm)
            }
        }

    @Test
    fun networkFailureShowsMessageAndKeepsEdit() =
        runTest {
            val failure = ApiError.Network(RuntimeException("boom"))
            val source = source().apply { changeOutcomes += failure }
            val state = started(source)
            state.departureText = "24/11/2026"
            confirmNow(state)

            assertEquals(failure.userMessage, state.error)
            assertEquals("24/11/2026", state.departureText)
            assertEquals(1, source.versionCalls.size)
            assertTrue(state.canConfirm)
        }

    @Test
    fun errorClearsOnTheNextAttempt() =
        runTest {
            val source =
                source().apply {
                    changeOutcomes += rcProblem(409, "IN_HOUSE_STAY_FIXED")
                }
            val state = started(source)
            state.departureText = "24/11/2026"
            confirmNow(state)
            assertNotNull(state.error)
            confirmNow(state)

            assertNull(state.error)
            assertTrue(state.fieldErrors.isEmpty())
            assertNotNull(state.done)
        }

    @Test
    fun clearingRequestsSendsNull() =
        runTest {
            val source = source()
            val state = started(source)
            state.specialRequests = "   "
            confirmNow(state)

            assertEquals(listOf(input(specialRequests = null)), source.changeCalls.map { it.input })
        }

    @Test
    fun doneLocksTheDialog() =
        runTest {
            val source = source()
            val state = started(source)
            state.departureText = "24/11/2026"
            confirmNow(state)
            state.departureText = "26/11/2026"
            confirmNow(state)

            assertEquals("24/11/2026", state.departureText)
            assertEquals(1, source.changeCalls.size)
            assertFalse(state.canConfirm)
        }

    @Test
    fun closeDropsLateLoadAndAnswer() =
        runTest {
            val loading = source().apply { holdVersion = true }
            val whileLoading = state(loading)
            whileLoading.start()
            runCurrent()
            whileLoading.close()
            loading.heldVersions.forEach { it.complete(Unit) }
            runCurrent()
            assertNull(whileLoading.code)
            assertFalse(whileLoading.canConfirm)

            val saving = source().apply { holdChange = true }
            val whileSaving = started(saving)
            whileSaving.departureText = "24/11/2026"
            whileSaving.confirm()
            runCurrent()
            whileSaving.close()
            saving.heldChanges.forEach { it.complete(Unit) }
            runCurrent()

            assertNull(whileSaving.done)
            assertNull(whileSaving.error)
            assertEquals(0, changes)
            whileSaving.confirm()
            runCurrent()
            assertEquals(1, saving.changeCalls.size)
        }

    @Test
    fun closeDropsLateOptions() =
        runTest {
            val source = source().apply { holdQuote = true }
            val state = started(source)
            state.loadOptions()
            runCurrent()
            state.close()
            source.heldQuotes.forEach { it.complete(Unit) }
            runCurrent()

            assertTrue(state.options.isEmpty())
        }

    @Test
    fun onChangedOnlyOnSuccess() =
        runTest {
            val source =
                source().apply {
                    changeOutcomes += rcProblem(409, "IN_HOUSE_STAY_FIXED")
                    changeOutcomes += ApiError.PreconditionFailed(null)
                }
            val state = started(source)
            state.departureText = "24/11/2026"
            confirmNow(state)
            assertEquals(0, changes)
            confirmNow(state)
            assertEquals(0, changes)
            state.departureText = "24/11/2026"
            confirmNow(state)
            assertEquals(1, changes)
        }

    @Test
    fun secondConfirmWhileBusyIsIgnored() =
        runTest {
            val source = source().apply { holdChange = true }
            val state = started(source)
            state.departureText = "24/11/2026"
            state.confirm()
            runCurrent()
            assertTrue(state.busy)
            state.confirm()
            state.departureText = "26/11/2026"
            runCurrent()

            assertEquals(1, source.changeCalls.size)
            assertEquals("24/11/2026", state.departureText)
        }
}
