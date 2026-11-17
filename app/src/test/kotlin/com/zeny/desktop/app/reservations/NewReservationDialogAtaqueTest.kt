package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.GuestPick
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.BookingResponse
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
import java.io.IOException
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val ddType = Uuid.parse("10000000-0000-0000-0000-0000000000d1")
private val ddPlan = Uuid.parse("20000000-0000-0000-0000-0000000000d1")
private const val DD_GUEST_ID = "70000000-0000-0000-0000-0000000000d1"
private val ddStay = QuotedStay(BusinessDate.parse("2026-11-13"), BusinessDate.parse("2026-11-16"), 2, emptyList())

private val ddOffer =
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
        ratePlanId = ddPlan,
        ratePlanName = "Flexível",
        roomTotal = "655.37",
        roomTypeCode = "STD",
        roomTypeId = ddType,
        roomTypeName = "Standard",
        total = "655.37",
    )

private fun ddResponse() =
    BookingResponse(
        channel = "DIRECT",
        code = "B-12",
        createdAt = "2026-11-12T10:00:00Z",
        holderGuestId = Uuid.parse(DD_GUEST_ID),
        id = Uuid.random(),
        origin = "FRONT_DESK",
        reservations =
            listOf(
                ReservationResponse(
                    additionalPackagesTotal = "0.00",
                    adults = 2,
                    arrival = "2026-11-13",
                    bookingId = Uuid.random(),
                    cancellationTerms =
                        CancellationTermsResponse(
                            deadlineHours = 24,
                            name = "Flex",
                            penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                            penaltyNights = Option.Some(1),
                            policyId = Uuid.random(),
                            version = 1,
                        ),
                    childAges = emptyList(),
                    code = "R-90",
                    createdAt = "2026-11-12T10:00:00Z",
                    dayUse = false,
                    departure = "2026-11-16",
                    folioId = Uuid.random(),
                    guaranteeTerms =
                        GuaranteeTermsResponse(name = "Sem", policyId = Uuid.random(), type = "NONE", version = 1),
                    guests = emptyList(),
                    id = Uuid.random(),
                    nightlyRates = emptyList(),
                    penaltiesCharged = "0.00",
                    ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                    ratePlanId = ddPlan,
                    roomTotal = "655.37",
                    roomTypeId = ddType,
                    segment = "LEISURE",
                    status = "CONFIRMED",
                    total = "655.37",
                    version = 1,
                ),
            ),
        restrictionAlerts = emptyList(),
        version = 1,
    )

/**
 * Ataques ao `NewReservationDialog` (M3 E03): o botão de fechar segue a mesma trava do Esc (sem escrita em curso, sem
 * cadastro do titular em curso). O Esc em si não é exercitável aqui: a conversão de Esc em "voltar" do Compose fica no
 * `ComposeSceneMediator` da janela real, fora do `runComposeUiTest` (ver o relatório do testador da E03).
 */
@OptIn(ExperimentalTestApi::class)
class NewReservationDialogAtaqueTest {
    private var closes = 0
    private val guests =
        FakeReceptionSource().apply { businessDay = BusinessDay(ddStay.arrival, ZoneId.of("America/Sao_Paulo")) }

    private fun ComposeUiTest.dialog(source: FakeNewReservationSource): NewReservationState {
        lateinit var state: NewReservationState
        setContent {
            val scope = rememberCoroutineScope()
            state =
                remember {
                    NewReservationState(
                        source,
                        guests,
                        scope,
                        ddOffer,
                        ddStay,
                        setOf("guests.profiles.read", "guests.profiles.manage"),
                        { it.run(null) },
                    ) {}
                }
            ZenyTheme { NewReservationDialog(state, onConfirmation = null) { closes++ } }
        }
        waitForIdle()
        state.picker.pick(GuestPick(DD_GUEST_ID, HolderSummary("Ana Teste", null)))
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.clickClose() {
        onNodeWithTag("new-reservation-close").performClick()
        waitForIdle()
    }

    @Test
    fun closeIsDisabledWhileCreatingAndCloseWorksAfterTheAnswer() =
        runComposeUiTest {
            val source =
                FakeNewReservationSource().apply {
                    response = ddResponse()
                    hold = true
                }
            val state = dialog(source)
            onNodeWithTag("new-reservation-submit").performClick()
            waitForIdle()
            assertTrue(state.busy)

            onNodeWithTag("new-reservation-close").assertIsNotEnabled()
            onNodeWithTag("new-reservation-submit").assertIsNotEnabled()
            clickClose()
            assertEquals(0, closes)

            source.release()
            waitForIdle()
            onNodeWithTag("new-reservation-close").assertIsEnabled()
            clickClose()
            assertEquals(1, closes)
            assertEquals(1, source.calls.size)
        }

    @Test
    fun unknownResultKeepsTheRetryButtonWithTheSameIntent() =
        runComposeUiTest {
            val source =
                FakeNewReservationSource().apply {
                    response = ddResponse()
                    outcomes += ApiError.Network(IOException("offline"))
                }
            val state = dialog(source)
            onNodeWithTag("new-reservation-submit").performClick()
            waitForIdle()
            assertTrue(state.unconfirmed)
            onNodeWithTag("new-reservation-submit").assertTextContains("Tentar de novo")

            onNodeWithTag("new-reservation-submit").performClick()
            waitForIdle()

            assertEquals(2, source.runs.size)
            assertEquals(source.runs[0].key, source.runs[1].key)
            onNodeWithTag("new-reservation-done").assertExists()
        }

    @Test
    fun closeAndSubmitAreDisabledWhileTheHolderIsBeingRegistered() =
        runComposeUiTest {
            guests.holdCreateGuest = true
            guests.createdGuest = GuestPick("70000000-0000-0000-0000-0000000000d2", HolderSummary("Caio", null))
            val source = FakeNewReservationSource().apply { response = ddResponse() }
            val state = dialog(source)
            val fields =
                state.picker.form.fields
                    .associateBy { it.name }
            fields.getValue("fullName").onChange("Caio Teste")
            fields.getValue("documentType").onChange("CPF")
            fields.getValue("documentNumber").onChange("12345678909")
            fields.getValue("issuingCountry").onChange("")
            fields.getValue("nationality").onChange("BR")
            fields.getValue("birthDate").onChange("10/05/1990")
            state.picker.create()
            waitForIdle()
            assertTrue(state.picker.creating)

            onNodeWithTag("new-reservation-close").assertIsNotEnabled()
            onNodeWithTag("new-reservation-submit").assertIsNotEnabled()
            clickClose()
            assertEquals(0, closes)
            assertTrue(source.calls.isEmpty())

            guests.heldCreateGuests.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("new-reservation-close").assertIsEnabled()
            onNodeWithTag("new-reservation-submit").assertIsEnabled()
        }
}
