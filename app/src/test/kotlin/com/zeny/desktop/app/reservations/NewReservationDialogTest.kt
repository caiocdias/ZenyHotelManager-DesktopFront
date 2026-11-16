package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.GuestPick
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.BookingResponse
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.RestrictionAlertResponse
import de.quati.kotlin.util.Option
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private val ndType = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val ndPlan = Uuid.parse("20000000-0000-0000-0000-000000000001")
private const val ND_GUEST_ID = "70000000-0000-0000-0000-000000000001"
private val ndGuest = GuestPick(ND_GUEST_ID, HolderSummary("Ana Teste", "CPF ***.***.***-01"))
private const val ND_RESERVATION_ID = "30000000-0000-0000-0000-000000000009"
private val ndUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private val ndStay = QuotedStay(BusinessDate.parse("2026-11-13"), BusinessDate.parse("2026-11-16"), 2, emptyList())

private val ndOffer =
    QuoteOfferResponse(
        accommodationTotal = "655.37",
        additionalPackagesTotal = "0.00",
        availableUnits = 2,
        cancellationPolicyId = Uuid.random(),
        category = QuoteOfferResponse.Category.NORMAL,
        guaranteePolicyId = Uuid.random(),
        includedPackagesTotal = "0.00",
        mealPlan = "BREAKFAST",
        nights = emptyList(),
        ratePlanCode = "FLEX",
        ratePlanId = ndPlan,
        ratePlanName = "Flexível",
        roomTotal = "655.37",
        roomTypeCode = "STD",
        roomTypeId = ndType,
        roomTypeName = "Standard",
        total = "655.37",
    )

private fun ndCancellation() =
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

private fun ndGuarantee(type: String) =
    GuaranteeTermsResponse(
        depositPercent = Option.Undefined,
        dueDaysBefore = Option.Undefined,
        name = "Política",
        policyId = Uuid.random(),
        type = type,
        version = 1,
    )

private fun ndResponse(
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
            cancellationTerms = ndCancellation(),
            childAges = emptyList(),
            code = "R-90",
            createdAt = "2026-11-12T10:00:00Z",
            dayUse = false,
            departure = "2026-11-16",
            folioId = Uuid.random(),
            guaranteeTerms = ndGuarantee(guaranteeType),
            guests = emptyList(),
            id = Uuid.parse(ND_RESERVATION_ID),
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            provisionalUntil = provisionalUntil?.let { Option.Some(it) } ?: Option.Undefined,
            ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
            ratePlanId = ndPlan,
            roomTotal = "1.00",
            roomTypeId = ndType,
            segment = "LEISURE",
            status = status,
            total = total,
            version = 1,
        )
    return BookingResponse(
        channel = "DIRECT",
        code = "B-12",
        createdAt = "2026-11-12T10:00:00Z",
        holderGuestId = Uuid.parse(ND_GUEST_ID),
        id = Uuid.random(),
        origin = "FRONT_DESK",
        reservations = listOf(reservation),
        restrictionAlerts =
            reasons.map {
                RestrictionAlertResponse(guestId = Uuid.parse(ND_GUEST_ID), reason = it, since = "2026-11-01")
            },
        version = 1,
    )
}

@OptIn(ExperimentalTestApi::class)
class NewReservationDialogTest {
    private var changes = 0
    private var closes = 0
    private val confirmations = mutableListOf<Pair<String, String>>()
    private val guests =
        FakeReceptionSource().apply {
            businessDay = BusinessDay(ndStay.arrival, ZoneId.of("America/Sao_Paulo"))
        }

    private fun source() = FakeNewReservationSource().apply { response = ndResponse() }

    private fun ComposeUiTest.dialog(
        source: FakeNewReservationSource,
        withConfirmation: Boolean = true,
    ): NewReservationState {
        lateinit var state: NewReservationState
        setContent {
            val scope = rememberCoroutineScope()
            state =
                remember {
                    NewReservationState(
                        source,
                        guests,
                        scope,
                        ndOffer,
                        ndStay,
                        setOf("guests.profiles.read", "guests.profiles.manage"),
                        { ApiResponse(it.run(null).value, null, 201) },
                    ) { changes++ }
                }
            ZenyTheme {
                NewReservationDialog(
                    state,
                    if (withConfirmation) { id, code -> confirmations += id to code } else null,
                ) { closes++ }
            }
        }
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.choose(state: NewReservationState) {
        state.picker.pick(ndGuest)
        waitForIdle()
    }

    private fun ComposeUiTest.submit() {
        onNodeWithTag("new-reservation-submit").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.countOf(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun showsOfferSummaryWithServerTotal() =
        runComposeUiTest {
            dialog(source())
            val summary = onNodeWithTag("new-reservation-summary")
            summary.assertTextContains("Standard · Flexível · Café da manhã", substring = true)
            summary.assertTextContains(ReceptionText.period("2026-11-13", "2026-11-16", false), substring = true)
            summary.assertTextContains("Total ${ReceptionText.money("655.37")}", substring = true)
        }

    @Test
    fun submitDisabledWithoutHolder() =
        runComposeUiTest {
            val state = dialog(source())
            onNodeWithTag("new-reservation-submit").assertIsNotEnabled()
            onNodeWithText("Escolha o titular").assertExists()
            choose(state)
            onNodeWithTag("new-reservation-submit").assertIsEnabled()
            onNodeWithTag("new-reservation-holder").assertTextContains("Ana Teste", substring = true)
            onNodeWithTag("new-reservation-holder").assertTextContains("CPF ***.***.***-01", substring = true)
        }

    @Test
    fun originAndSegmentChipsUseBookingTextLabels() =
        runComposeUiTest {
            val state = dialog(source())
            BookingText.ORIGINS.forEach {
                onNodeWithTag("new-reservation-origin-$it").assertTextContains(BookingText.origin(it))
            }
            BookingText.SEGMENTS.forEach {
                onNodeWithTag("new-reservation-segment-$it").assertTextContains(BookingText.segment(it))
            }
            onNodeWithTag("new-reservation-origin-PHONE").performScrollTo().performClick()
            onNodeWithTag("new-reservation-segment-BUSINESS").performScrollTo().performClick()
            waitForIdle()
            assertEquals("PHONE", state.origin)
            assertEquals("BUSINESS", state.segment)
        }

    @Test
    fun specialRequestsAndRateGoToTheServer() =
        runComposeUiTest {
            val source = source()
            val state = dialog(source)
            choose(state)
            onNodeWithTag("new-reservation-requests").performScrollTo().performTextInput("Andar alto")
            onNodeWithTag("new-reservation-rate").performScrollTo().performTextInput("250,00")
            submit()
            val input = source.calls.single().input
            assertEquals("Andar alto", input.specialRequests)
            assertEquals(3, input.nightlyRates.size)
        }

    @Test
    fun rateErrorIsShown() =
        runComposeUiTest {
            val source = source()
            val state = dialog(source)
            choose(state)
            onNodeWithTag("new-reservation-rate").performScrollTo().performTextInput("abc")
            submit()
            onNodeWithTag("new-reservation-rate-error").assertTextContains("Informe um valor válido para a diária.")
            assertEquals(0, source.calls.size)
        }

    @Test
    fun doneShowsCodeStatusTotalAndCancellationTerms() =
        runComposeUiTest {
            val state = dialog(source().apply { response = ndResponse(reasons = listOf("Fumante")) })
            choose(state)
            submit()
            val done = onNodeWithTag("new-reservation-done")
            done.assertTextContains("R-90", substring = true)
            done.assertTextContains(ReceptionText.status("CONFIRMED").label, substring = true)
            done.assertTextContains(ReceptionText.period("2026-11-13", "2026-11-16", false), substring = true)
            done.assertTextContains(ReceptionText.money("655.37"), substring = true)
            done.assertTextContains("Cancelamento sem multa até 24 h antes do check-in", substring = true)
            onNodeWithText("Fumante", substring = true).assertExists()
            onNodeWithTag("new-reservation-submit").assertDoesNotExist()
            assertEquals(1, changes)
            onNodeWithTag("new-reservation-close").performClick()
            assertEquals(1, closes)
        }

    @Test
    fun provisionalDoneShowsDeadlineAndGuarantee() =
        runComposeUiTest {
            val response = ndResponse("PROVISIONAL", provisionalUntil = "2026-11-14T15:00:00Z", guaranteeType = "CARD")
            val state = dialog(source().apply { this.response = response })
            choose(state)
            submit()
            val done = onNodeWithTag("new-reservation-done")
            done.assertTextContains(ReceptionText.status("PROVISIONAL").label, substring = true)
            done.assertTextContains("Garantia exigida: Cartão", substring = true)
            done.assertTextContains("Prazo", substring = true)
        }

    @Test
    fun confirmationButtonOnlyWithCallback() =
        runComposeUiTest {
            val state = dialog(source(), withConfirmation = false)
            choose(state)
            submit()
            onNodeWithTag("new-reservation-confirmation").assertDoesNotExist()
        }

    @Test
    fun confirmationButtonReportsReservationIdAndCode() =
        runComposeUiTest {
            val state = dialog(source())
            choose(state)
            submit()
            onNodeWithTag("new-reservation-confirmation").performClick()
            assertEquals(listOf(ND_RESERVATION_ID to "R-90"), confirmations)
        }

    @Test
    fun errorShowsCatalogTextWithoutCode() =
        runComposeUiTest {
            val source =
                source().apply {
                    outcomes += ApiError.Problem(409, "ROOM_TYPE_UNAVAILABLE", "detalhe", emptyList())
                }
            val state = dialog(source)
            choose(state)
            submit()
            onNodeWithTag("new-reservation-error").assertTextContains(state.error!!)
            assertEquals(0, countOf("ROOM_TYPE_UNAVAILABLE"))
            assertEquals(0, onAllNodesWithText(ndUuid.pattern).fetchSemanticsNodes().size)
        }

    @Test
    fun fieldErrorMessagesAreListed() =
        runComposeUiTest {
            val source =
                source().apply {
                    outcomes +=
                        ApiError.Problem(
                            400,
                            "STAY_INVALID",
                            null,
                            listOf(
                                FieldError("reservations[0].departure", "X", "A saída é inválida."),
                                FieldError("reservations[0].adults", "X", "Adultos demais."),
                            ),
                        )
                }
            val state = dialog(source)
            choose(state)
            submit()
            onNodeWithText("A saída é inválida.").assertExists()
            onNodeWithText("Adultos demais.").assertExists()
            assertEquals(0, countOf("reservations[0]"))
        }

    @Test
    fun unconfirmedOffersRetry() =
        runComposeUiTest {
            val source = source().apply { outcomes += java.io.IOException("rede") }
            val state = dialog(source)
            choose(state)
            onNodeWithTag("new-reservation-submit").assertTextContains("Criar reserva")
            submit()
            onNodeWithTag("new-reservation-submit").assertTextContains("Tentar de novo")
            onNodeWithTag("new-reservation-submit").assertIsEnabled()
            submit()
            onNodeWithTag("new-reservation-done").assertExists()
            assertEquals(2, source.runs.size)
            assertEquals(source.runs[0].key, source.runs[1].key)
        }

    @Test
    fun cancelCloses() =
        runComposeUiTest {
            dialog(source())
            onNodeWithTag("new-reservation-close").assertTextContains("Cancelar")
            onNodeWithTag("new-reservation-close").performClick()
            assertEquals(1, closes)
        }
}
