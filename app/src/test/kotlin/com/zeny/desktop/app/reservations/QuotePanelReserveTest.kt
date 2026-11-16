package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

private val qrType = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val qrPlan = Uuid.parse("20000000-0000-0000-0000-000000000001")

private fun qrOffer(plan: String = "FLEX") =
    QuoteOfferResponse(
        accommodationTotal = "655.37",
        additionalPackagesTotal = "0.00",
        availableUnits = 2,
        cancellationPolicyId = qrPlan,
        category = QuoteOfferResponse.Category.NORMAL,
        guaranteePolicyId = qrPlan,
        includedPackagesTotal = "0.00",
        mealPlan = "BREAKFAST",
        nights = emptyList(),
        ratePlanCode = plan,
        ratePlanId = qrPlan,
        ratePlanName = plan,
        roomTotal = "655.37",
        roomTypeCode = "STD",
        roomTypeId = qrType,
        roomTypeName = "Standard",
        total = "655.37",
    )

private fun qrAnswer(vararg offers: QuoteOfferResponse) =
    QuoteResponse(
        adults = 2,
        arrival = "2026-11-16",
        channel = "DIRECT",
        childAges = listOf(5),
        departure = "2026-11-18",
        nights = 2,
        offers = offers.toList(),
        unavailable = Option.Some(emptyList()),
    )

@OptIn(ExperimentalTestApi::class)
class QuotePanelReserveTest {
    private val reserved = mutableListOf<Pair<QuoteOfferResponse, QuotedStay>>()

    private fun ComposeUiTest.panel(
        source: FakeAvailabilitySource,
        withReserve: Boolean = true,
    ): QuoteState {
        lateinit var state: QuoteState
        setContent {
            val scope = rememberCoroutineScope()
            state = remember { QuoteState(source, scope) }
            ZenyTheme {
                QuotePanel(
                    state,
                    emptyMap(),
                    onReserve = if (withReserve) { offer, stay -> reserved += offer to stay } else null,
                )
            }
        }
        state.arrivalText = "16/11/2026"
        state.departureText = "18/11/2026"
        state.childAgesText = "5"
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.submit() {
        onNodeWithTag("quote-submit").performClick()
        waitForIdle()
    }

    @Test
    fun reserveButtonOnlyWithCallback() =
        runComposeUiTest {
            val source = FakeAvailabilitySource().apply { quote = qrAnswer(qrOffer()) }
            panel(source, withReserve = false)
            submit()
            onNodeWithTag("quote-offer-0").assertExists()
            onNodeWithTag("quote-reserve-0").assertDoesNotExist()
        }

    @Test
    fun reserveSendsOfferAndQuotedStay() =
        runComposeUiTest {
            val source = FakeAvailabilitySource().apply { quote = qrAnswer(qrOffer("A"), qrOffer("B")) }
            val state = panel(source)
            submit()
            // Editar os campos depois da cotação não muda a estadia cotada.
            state.arrivalText = "20/11/2026"
            state.departureText = "25/11/2026"
            state.adultsText = "1"
            state.childAgesText = ""
            waitForIdle()
            onNodeWithTag("quote-reserve-1").performClick()
            val (offer, stay) = reserved.single()
            assertEquals("B", offer.ratePlanCode)
            assertEquals(
                QuotedStay(BusinessDate.parse("2026-11-16"), BusinessDate.parse("2026-11-18"), 2, listOf(5)),
                stay,
            )
        }

    @Test
    fun quotedStayClearsWithResult() =
        runComposeUiTest {
            val source = FakeAvailabilitySource().apply { quote = qrAnswer(qrOffer()) }
            val state = panel(source)
            assertNull(state.quotedStay)
            submit()
            assertEquals(2, state.quotedStay?.adults)
            state.prefill(BusinessDate.parse("2026-12-01"))
            waitForIdle()
            assertNull(state.quotedStay)
            onNodeWithTag("quote-reserve-0").assertDoesNotExist()
        }
}
