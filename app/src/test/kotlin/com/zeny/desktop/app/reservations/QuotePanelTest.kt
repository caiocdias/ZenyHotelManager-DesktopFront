package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.AvailabilityDayResponse
import com.zeny.desktop.sdk.model.AvailabilityResponse
import com.zeny.desktop.sdk.model.QuoteNightResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteRefusalResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.QuoteUnavailableResponse
import com.zeny.desktop.sdk.model.RoomTypeAvailabilityResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private val stdId = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val planA = Uuid.parse("20000000-0000-0000-0000-000000000001")
private val planB = Uuid.parse("20000000-0000-0000-0000-000000000002")

private fun night(
    date: String,
    total: String,
) = QuoteNightResponse(
    accommodationAmount = total,
    additionalPackagesAmount = "0.00",
    baseRate = total,
    children = emptyList(),
    date = date,
    extraAdults = 0,
    extraAdultsAmount = "0.00",
    includedPackagesAmount = "0.00",
    packages = emptyList(),
    roomAmount = total,
    total = total,
)

private fun offer(
    total: String = "655.37",
    nights: List<QuoteNightResponse> = emptyList(),
) = QuoteOfferResponse(
    accommodationTotal = total,
    additionalPackagesTotal = "0.00",
    availableUnits = 2,
    cancellationPolicyId = planA,
    category = QuoteOfferResponse.Category.NORMAL,
    guaranteePolicyId = planA,
    includedPackagesTotal = "0.00",
    mealPlan = "BREAKFAST",
    nights = nights,
    ratePlanCode = "FLEX",
    ratePlanId = planA,
    ratePlanName = "Flexível",
    roomTotal = total,
    roomTypeCode = "STD",
    roomTypeId = stdId,
    roomTypeName = "Standard",
    total = total,
)

private fun refused(
    plan: Uuid,
    planCode: String,
    code: String,
    message: String,
) = QuoteUnavailableResponse(
    ratePlanCode = planCode,
    ratePlanId = plan,
    reasons = listOf(QuoteRefusalResponse(code = code, dates = emptyList(), message = message)),
    roomTypeCode = "STD",
    roomTypeId = stdId,
)

private fun answer(
    offers: List<QuoteOfferResponse> = emptyList(),
    unavailable: List<QuoteUnavailableResponse> = emptyList(),
) = QuoteResponse(
    adults = 2,
    arrival = "2026-11-16",
    channel = "DIRECT",
    childAges = emptyList(),
    departure = "2026-11-18",
    nights = 2,
    offers = offers,
    unavailable = Option.Some(unavailable),
)

private fun stayInvalid(vararg errors: FieldError) = ApiError.Problem(400, "STAY_INVALID", null, errors.toList())

@OptIn(ExperimentalTestApi::class)
class QuotePanelTest {
    private fun ComposeUiTest.panel(
        source: FakeAvailabilitySource,
        names: Map<String, String> = emptyMap(),
    ): QuoteState {
        lateinit var state: QuoteState
        setContent {
            val scope = rememberCoroutineScope()
            state = remember { QuoteState(source, scope) }
            ZenyTheme { QuotePanel(state, names) }
        }
        state.arrivalText = "16/11/2026"
        state.departureText = "18/11/2026"
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.submit() {
        onNodeWithTag("quote-submit").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.countOf(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun offerShowsServerTotalsAndNights() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote =
                answer(listOf(offer(nights = listOf(night("2026-11-16", "320.00"), night("2026-11-17", "320.00")))))
            panel(source)
            submit()
            val node = onNodeWithTag("quote-offer-0")
            node.assertTextContains("Standard · Flexível · Café da manhã", substring = true)
            node.assertTextContains("2 UHs disponíveis", substring = true)
            node.assertTextContains("Total R$ 655,37", substring = true)
            node.assertTextContains("16/11 R$ 320,00", substring = true)
            node.assertTextContains("17/11 R$ 320,00", substring = true)
        }

    @Test
    fun malformedAmountShowsDash() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote = answer(listOf(offer(total = "abc"), offer(total = "100.00")))
            panel(source)
            submit()
            onNodeWithTag("quote-offer-0").assertTextContains("Total —", substring = true)
            onNodeWithTag("quote-offer-1").assertTextContains("Total R$ 100,00", substring = true)
        }

    @Test
    fun unavailableShowsReasonsInPortuguese() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote =
                answer(
                    unavailable =
                        listOf(
                            refused(planA, "FLEX", "MIN_STAY_NOT_MET", "A estadia é curta demais."),
                            refused(planB, "PROMO", "CLOSED_TO_ARRIVAL", "CLOSED_TO_ARRIVAL"),
                        ),
                )
            panel(source, mapOf(stdId.toString() to "Standard"))
            submit()
            onNodeWithText("Indisponíveis").assertExists()
            onNodeWithTag("quote-unavailable-0").assertTextContains("A estadia é curta demais.", substring = true)
            onNodeWithTag("quote-unavailable-1")
                .assertTextContains("A data está fechada para chegada.", substring = true)
            assertEquals(0, onAllNodesWithText("MIN_STAY_NOT_MET", substring = true).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithText("CLOSED_TO_ARRIVAL", substring = true).fetchSemanticsNodes().size)
        }

    @Test
    fun unavailableUsesPlanNameWhenOffered() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote =
                answer(
                    offers = listOf(offer()),
                    unavailable =
                        listOf(
                            refused(planA, "FLEX", "RATE_NOT_PRICED", "Sem preço."),
                            refused(planB, "PROMO", "RATE_NOT_PRICED", "Sem preço."),
                        ),
                )
            panel(source)
            submit()
            onNodeWithTag("quote-unavailable-0").assertTextContains("STD · Flexível", substring = true)
            onNodeWithTag("quote-unavailable-1").assertTextContains("STD · PROMO", substring = true)
        }

    @Test
    fun unavailableUsesRoomTypeNameWhenKnown() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote = answer(unavailable = listOf(refused(planB, "PROMO", "RATE_NOT_PRICED", "Sem preço.")))
            panel(source, mapOf(stdId.toString() to "Standard"))
            submit()
            onNodeWithTag("quote-unavailable-0").assertTextContains("Standard · PROMO", substring = true)
        }

    @Test
    fun noCombinationMessage() =
        runComposeUiTest {
            panel(FakeAvailabilitySource().apply { quote = answer() })
            submit()
            onNodeWithText("Nenhuma combinação para essa estadia.").assertExists()
        }

    @Test
    fun enterSubmitsQuote() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            panel(source)
            onNodeWithTag("quote-adults").performTextInput("1")
            onNodeWithTag("quote-adults").performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals(1, source.quoteCalls.size)
        }

    @Test
    fun fieldErrorAppearsUnderChildren() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.failures[FakeAvailabilitySource.Op.QUOTE] =
                stayInvalid(FieldError("childAges[0]", "X", "Idade fora do limite."))
            val state = panel(source)
            state.childAgesText = "99"
            submit()
            onNodeWithText("Idade fora do limite.").assertExists()
        }

    @Test
    fun genericErrorIsHiddenWhenFieldErrorsExist() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.failures[FakeAvailabilitySource.Op.QUOTE] = stayInvalid(FieldError("adults", "X", "Adultos demais."))
            val state = panel(source)
            submit()
            onNodeWithText("Adultos demais.").assertExists()
            assertEquals(0, countOf(state.error!!))
        }

    @Test
    fun unknownFieldErrorIsShownAbove() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.failures[FakeAvailabilitySource.Op.QUOTE] =
                stayInvalid(FieldError("channel", "X", "Canal inválido."))
            panel(source)
            submit()
            onNodeWithText("Canal inválido.").assertExists()
        }

    @Test
    fun serverErrorWithoutFieldsShowsGenericError() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.failures[FakeAvailabilitySource.Op.QUOTE] = ApiError.Problem(403, "FORBIDDEN", null, emptyList())
            val state = panel(source)
            submit()
            onNodeWithText(state.error!!).assertExists()
        }

    @Test
    fun validationMessageIsShown() =
        runComposeUiTest {
            val state = panel(FakeAvailabilitySource())
            state.departureText = "15/11/2026"
            submit()
            onNodeWithText("A saída precisa ser depois da chegada.").assertExists()
        }

    @Test
    fun submitIsDisabledWhileLoading() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.hold(FakeAvailabilitySource.Op.QUOTE)
            panel(source)
            submit()
            onNodeWithTag("quote-submit").assertIsNotEnabled()
        }

    @Test
    fun calendarQuoteFromPrefillsForm() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            val day =
                AvailabilityDayResponse(
                    available = 3,
                    blocked = 0,
                    date = "2026-11-17",
                    outOfOrder = 0,
                    overbooked = false,
                    overbookingLimit = 0,
                    sold = 1,
                    total = 4,
                )
            source.availability =
                AvailabilityResponse(
                    from = "2026-11-16",
                    to = "2026-12-15",
                    roomTypes =
                        listOf(
                            RoomTypeAvailabilityResponse(
                                active = true,
                                code = "STD",
                                days = listOf(day),
                                name = "Standard",
                                overbookedDates = emptyList(),
                                roomTypeId = stdId,
                            ),
                        ),
                )
            lateinit var quote: QuoteState
            setContent {
                val scope = rememberCoroutineScope()
                val calendar = remember { AvailabilityState(source, scope).also { it.start() } }
                quote = remember { QuoteState(source, scope) }
                ZenyTheme { AvailabilityScreen(calendar, quote) }
            }
            waitForIdle()
            onNodeWithTag("availability-cell-$stdId-2026-11-17").performClick()
            onNodeWithText("Cotar a partir deste dia").performClick()
            waitForIdle()
            assertEquals("17/11/2026", quote.arrivalText)
            assertEquals("18/11/2026", quote.departureText)
            onNodeWithTag("quote-arrival").assertTextContains("17/11/2026")
        }
}
