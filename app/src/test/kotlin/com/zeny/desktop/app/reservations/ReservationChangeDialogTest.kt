package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val cdRes = Uuid.parse("20000000-0000-0000-0000-0000000000d1")
private val cdType = Uuid.parse("10000000-0000-0000-0000-0000000000d1")
private val cdOtherType = Uuid.parse("10000000-0000-0000-0000-0000000000d2")
private val cdPlan = Uuid.parse("40000000-0000-0000-0000-0000000000d1")
private val cdOtherPlan = Uuid.parse("40000000-0000-0000-0000-0000000000d2")
private const val CD_STALE_NOTICE =
    "Alguém alterou esta reserva. Os dados foram recarregados; confira e tente de novo."

private fun cdVersion(
    status: String = "CONFIRMED",
    segment: String = "LEISURE",
    total: String = "720.00",
) = ReservationVersion(
    ReservationDetailResponse(
        bookingCode = "B-1",
        holderGuestId = Uuid.random(),
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = 2,
                arrival = "2026-11-20",
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
                code = "R-1",
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                departure = "2026-11-23",
                folioId = Uuid.random(),
                guaranteeTerms =
                    GuaranteeTermsResponse(name = "Sem garantia", policyId = Uuid.random(), type = "NONE", version = 1),
                guests = emptyList(),
                id = cdRes,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
                ratePlanId = cdPlan,
                roomTotal = total,
                roomTypeId = cdType,
                segment = segment,
                specialRequests = Option.Some("Andar alto"),
                status = status,
                total = total,
                version = 3,
            ),
        restrictionAlerts = emptyList(),
    ),
    "\"v1\"",
)

private fun cdOffer(
    type: Uuid,
    plan: Uuid,
    name: String,
    total: String,
) = QuoteOfferResponse(
    accommodationTotal = total,
    additionalPackagesTotal = "0.00",
    availableUnits = 2,
    cancellationPolicyId = plan,
    category = QuoteOfferResponse.Category.NORMAL,
    guaranteePolicyId = plan,
    includedPackagesTotal = "0.00",
    mealPlan = "BREAKFAST",
    nights = emptyList(),
    ratePlanCode = name,
    ratePlanId = plan,
    ratePlanName = "Plano $name",
    roomTotal = total,
    roomTypeCode = name,
    roomTypeId = type,
    roomTypeName = "Tipo $name",
    total = total,
)

@OptIn(ExperimentalTestApi::class)
class ReservationChangeDialogTest {
    private var changes = 0
    private var closes = 0
    private lateinit var state: ReservationChangeState

    private fun source(
        status: String = "CONFIRMED",
        segment: String = "LEISURE",
    ) = FakeReservationChangeSource().apply {
        versions[cdRes.toString()] = cdVersion(status, segment)
        changeResponse = cdVersion(total = "987.65").reservation
        quote =
            QuoteResponse(
                adults = 2,
                arrival = "2026-11-20",
                channel = "DIRECT",
                childAges = listOf(5),
                departure = "2026-11-23",
                nights = 3,
                offers =
                    listOf(
                        cdOffer(cdType, cdPlan, "Atual", "720.00"),
                        cdOffer(cdOtherType, cdPlan, "Suíte", "845.50"),
                        cdOffer(cdType, cdOtherPlan, "Promo", "610.00"),
                    ),
            )
    }

    private fun ComposeUiTest.dialog(
        source: FakeReservationChangeSource,
        canQuote: Boolean = true,
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            state =
                remember {
                    ReservationChangeState(source, scope, cdRes.toString(), canQuote, { it.run(null) }) { changes++ }
                }
            ZenyTheme { ReservationChangeDialog(state, onClose = { closes++ }) }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.editRequests() {
        onNodeWithTag("change-requests").performTextInput(" e vista")
        waitForIdle()
    }

    @Test
    fun fieldsShowReservation() =
        runComposeUiTest {
            dialog(source())

            onNodeWithText("Alterar reserva R-1").assertExists()
            onNodeWithTag("change-arrival").assertTextContains("20/11/2026")
            onNodeWithTag("change-departure").assertTextContains("23/11/2026")
            onNodeWithTag("change-adults").assertTextContains("2")
            onNodeWithTag("change-children").assertTextContains("5")
            onNodeWithTag("change-requests").assertTextContains("Andar alto")
            onNodeWithTag("change-segment-LEISURE").assertIsSelected()
            onNodeWithTag("change-segment-BUSINESS").assertIsNotSelected()
            onNodeWithTag("change-arrival").assertIsEnabled()
            onNodeWithText("Ver opções").assertExists()
            onNodeWithTag("change-submit").assertIsEnabled()
            onNodeWithTag("change-close").assertIsEnabled()
        }

    @Test
    fun inHouseLocksStayFields() =
        runComposeUiTest {
            dialog(source(status = "IN_HOUSE"))

            onNodeWithTag("change-arrival").assertIsNotEnabled()
            onNodeWithTag("change-adults").assertIsNotEnabled()
            onNodeWithTag("change-children").assertIsNotEnabled()
            onNodeWithTag("change-departure").assertIsEnabled()
            onNodeWithTag("change-requests").assertIsEnabled()
            onNodeWithTag("change-segment-BUSINESS").assertIsEnabled()
            onNodeWithText("Ver opções").assertDoesNotExist()
            onNodeWithText("Hóspede em casa: só a saída, o segmento e os pedidos podem mudar.").assertExists()
        }

    @Test
    fun optionsHiddenWithoutCanQuote() =
        runComposeUiTest {
            dialog(source(), canQuote = false)

            onNodeWithText("Ver opções").assertDoesNotExist()
            onNodeWithTag("change-options").assertDoesNotExist()
            onNodeWithText("Hóspede em casa", substring = true).assertDoesNotExist()
        }

    @Test
    fun optionsListShowsServerTotals() =
        runComposeUiTest {
            val server = source()
            dialog(server)

            onNodeWithTag("change-options").assertDoesNotExist()
            onNodeWithText("Ver opções").performClick()
            waitForIdle()

            onNodeWithText(
                "Cotação de hoje para a estadia digitada. O total final é o que o servidor devolver ao confirmar.",
            ).assertExists()
            onNodeWithTag("change-option-0")
                .assertTextContains("Tipo Suíte · Plano Suíte · Café da manhã", substring = true)
            onNodeWithTag("change-option-0").assertTextContains("R$ 845,50", substring = true)
            onNodeWithTag("change-option-1").assertTextContains("R$ 610,00", substring = true)
            onNodeWithTag("change-option-2").assertDoesNotExist()
            onNodeWithTag("change-option-keep").assertTextContains("Manter o tipo e o plano atuais")
            onNodeWithTag("change-option-keep").assertIsSelected()

            click("change-option-1")
            onNodeWithTag("change-option-1").assertIsSelected()
            onNodeWithTag("change-option-keep").assertIsNotSelected()
            assertEquals(cdOtherPlan, state.option?.ratePlanId)

            click("change-option-keep")
            onNodeWithTag("change-option-keep").assertIsSelected()
            assertNull(state.option)
        }

    @Test
    fun unknownSegmentMarksNoChip() =
        runComposeUiTest {
            dialog(source(segment = "WHATEVER"))

            BookingText.SEGMENTS.forEach { onNodeWithTag("change-segment-$it").assertIsNotSelected() }
        }

    @Test
    fun noticeIsShownAfterStaleVersion() =
        runComposeUiTest {
            val server = source()
            server.changeOutcomes += ApiError.PreconditionFailed(null)
            dialog(server)

            editRequests()
            click("change-submit")

            onNodeWithTag("change-notice").assertTextEquals(CD_STALE_NOTICE)
            onNodeWithTag("change-requests").assertTextContains("Andar alto")
            onNodeWithTag("change-submit").assertIsEnabled()
            assertEquals(2, server.versionCalls.size)
        }

    @Test
    fun fieldErrorNextToField() =
        runComposeUiTest {
            val server = source()
            server.changeOutcomes +=
                ApiError.Problem(
                    422,
                    "STAY_INVALID",
                    null,
                    listOf(FieldError("departure", "INVALID", "A saída cai fora do período vendido.")),
                )
            dialog(server)

            editRequests()
            click("change-submit")

            val message = "A saída cai fora do período vendido."
            val field = onNodeWithTag("change-departure").getBoundsInRoot()
            val shown = onNodeWithText(message).getBoundsInRoot()
            assertTrue(shown.left >= field.left && shown.right <= field.right, "mensagem fora da coluna do campo")
            assertTrue(shown.top >= field.top && shown.bottom <= field.bottom + 1.dp, "mensagem longe do campo")
            onAllNodesWithText(message).assertCountEquals(1)
            onNodeWithText("reservations[0]", substring = true).assertDoesNotExist()
            onNodeWithTag("change-arrival").assertTextContains("20/11/2026")
        }

    @Test
    fun arrivalFieldErrorShowsOnTheReadOnlyField() =
        runComposeUiTest {
            val server = source(status = "IN_HOUSE")
            server.changeOutcomes +=
                ApiError.Problem(
                    422,
                    "STAY_INVALID",
                    null,
                    listOf(FieldError("arrival", "INVALID", "A chegada não pode mudar com o hóspede em casa.")),
                )
            dialog(server)

            editRequests()
            click("change-submit")

            val message = "A chegada não pode mudar com o hóspede em casa."
            val field = onNodeWithTag("change-arrival").getBoundsInRoot()
            val shown = onNodeWithText(message).getBoundsInRoot()
            assertTrue(shown.left >= field.left && shown.right <= field.right, "mensagem fora da coluna do campo")
            assertTrue(shown.top >= field.top && shown.bottom <= field.bottom + 1.dp, "mensagem longe do campo")
            onAllNodesWithText(message).assertCountEquals(1)
        }

    @Test
    fun validationIsShown() =
        runComposeUiTest {
            val server = source()
            dialog(server)

            click("change-submit")

            onNodeWithTag("change-validation").assertTextEquals("Nada mudou.")
            assertEquals(0, server.changeCalls.size)
        }

    @Test
    fun errorIsShown() =
        runComposeUiTest {
            val server = source()
            server.changeOutcomes += ApiError.Problem(409, "IN_HOUSE_STAY_FIXED", null, emptyList())
            dialog(server)

            editRequests()
            click("change-submit")

            onNodeWithTag("change-error").assertExists()
            onNodeWithText("IN_HOUSE_STAY_FIXED", substring = true).assertDoesNotExist()
        }

    @Test
    fun doneShowsOnlyClose() =
        runComposeUiTest {
            dialog(source())

            editRequests()
            click("change-submit")

            onNodeWithTag("change-done").assertTextEquals("Reserva R-1 alterada. Novo total: R$ 987,65.")
            onNodeWithTag("change-submit").assertDoesNotExist()
            onNodeWithTag("change-arrival").assertDoesNotExist()
            onNodeWithTag("change-requests").assertDoesNotExist()
            onNodeWithText("Ver opções").assertDoesNotExist()
            click("change-close")
            assertEquals(1, closes)
            assertEquals(1, changes)
        }

    @Test
    fun submitDisabledWhileBusy() =
        runComposeUiTest {
            val server = source()
            server.holdChange = true
            dialog(server)

            editRequests()
            click("change-submit")

            onNodeWithTag("change-submit").assertIsNotEnabled()
            onNodeWithTag("change-close").assertIsNotEnabled()
            assertEquals(1, server.changeCalls.size)
            server.heldChanges.single().complete(Unit)
            waitForIdle()
            onNodeWithTag("change-done").assertExists()
        }
}
