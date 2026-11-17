package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.reception.ReceptionText
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
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private val typeA = Uuid.parse("30000000-0000-0000-0000-00000000000a")
private val typeB = Uuid.parse("30000000-0000-0000-0000-00000000000b")
private val planX = Uuid.parse("40000000-0000-0000-0000-00000000000a")
private val planY = Uuid.parse("40000000-0000-0000-0000-00000000000b")

private fun nt(
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

private fun of(
    total: String,
    nights: List<QuoteNightResponse> = emptyList(),
    meal: String = "ROOM_ONLY",
    plan: Uuid = planX,
    planName: String = "Plano X",
) = QuoteOfferResponse(
    accommodationTotal = total,
    additionalPackagesTotal = "0.00",
    availableUnits = 1,
    cancellationPolicyId = planX,
    category = QuoteOfferResponse.Category.NORMAL,
    guaranteePolicyId = planX,
    includedPackagesTotal = "0.00",
    mealPlan = meal,
    nights = nights,
    ratePlanCode = "PX",
    ratePlanId = plan,
    ratePlanName = planName,
    roomTotal = total,
    roomTypeCode = "TA",
    roomTypeId = typeA,
    roomTypeName = "Tipo A",
    total = total,
)

private fun un(
    type: Uuid,
    typeCode: String,
    plan: Uuid,
    planCode: String,
    vararg reasons: QuoteRefusalResponse,
) = QuoteUnavailableResponse(
    ratePlanCode = planCode,
    ratePlanId = plan,
    reasons = reasons.toList(),
    roomTypeCode = typeCode,
    roomTypeId = type,
)

private fun reason(
    code: String,
    message: String,
) = QuoteRefusalResponse(code = code, dates = emptyList(), message = message)

private fun reply(
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

@OptIn(ExperimentalTestApi::class)
class QuotePanelAtaqueTest {
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

    private fun ComposeUiTest.count(
        text: String,
        substring: Boolean = false,
    ) = onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    @Test
    fun totalDifferentFromSumOfNightsIsShownAsServerSaid() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote = reply(listOf(of("700.00", listOf(nt("2026-11-16", "100.00"), nt("2026-11-17", "200.00")))))
            panel(source)
            submit()
            val node = onNodeWithTag("quote-offer-0")
            node.assertTextContains("Total R$ 700,00", substring = true)
            node.assertTextContains("16/11 R$ 100,00", substring = true)
            node.assertTextContains("17/11 R$ 200,00", substring = true)
            assertEquals(0, count("R$ 300,00", substring = true))
        }

    @Test
    fun decimalShapesAreFormattedLikeReceptionMoney() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            val totals = listOf("12345.6", "7", "0.5", "-5.50", "1234567.89", "0")
            val expected = listOf("R$ 12.345,60", "R$ 7,00", "R$ 0,50", "R$ -5,50", "R$ 1.234.567,89", "R$ 0,00")
            source.quote = reply(totals.map { of(it) })
            panel(source)
            submit()
            totals.forEachIndexed { i, raw ->
                val want = ReceptionText.money(raw)
                assertEquals(expected[i], want, "formato de referência para $raw")
                onNodeWithTag("quote-offer-$i").assertTextContains("Total $want", substring = true)
            }
        }

    @Test
    fun malformedNightShowsDashOnlyOnThatNight() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote = reply(listOf(of("200.00", listOf(nt("2026-11-16", "xx"), nt("2026-11-17", "100.00")))))
            panel(source)
            submit()
            val node = onNodeWithTag("quote-offer-0")
            node.assertTextContains("16/11 —", substring = true)
            node.assertTextContains("17/11 R$ 100,00", substring = true)
            node.assertTextContains("Total R$ 200,00", substring = true)
        }

    @Test
    fun malformedOfferDoesNotBreakNeighbourOffer() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote =
                reply(
                    listOf(
                        of("1,5", listOf(nt("2026-11-16", "9.99"))),
                        of("12345.6", listOf(nt("2026-11-16", "12345.6"))),
                    ),
                )
            panel(source)
            submit()
            onNodeWithTag("quote-offer-0").assertTextContains("Total —", substring = true)
            onNodeWithTag("quote-offer-0").assertTextContains("16/11 R$ 9,99", substring = true)
            onNodeWithTag("quote-offer-1").assertTextContains("Total R$ 12.345,60", substring = true)
            onNodeWithTag("quote-offer-1").assertTextContains("16/11 R$ 12.345,60", substring = true)
        }

    @Test
    fun unknownMealPlanShowsOutro() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote = reply(listOf(of("10.00", meal = "ALL_INCLUSIVE")))
            panel(source)
            submit()
            onNodeWithTag("quote-offer-0")
                .assertTextContains("Tipo A · Plano X · Outro (ALL_INCLUSIVE)", substring = true)
        }

    @Test
    fun unavailableFallsBackToTypeCodeWhenCalendarDoesNotKnowTheType() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote =
                reply(
                    unavailable =
                        listOf(
                            un(typeA, "CODA", planY, "PY", reason("RATE_NOT_PRICED", "Sem preço.")),
                            un(typeB, "CODB", planY, "PY", reason("RATE_NOT_PRICED", "Sem preço.")),
                        ),
                )
            panel(source, mapOf(typeA.toString() to "Nome do A", "outro" to "Outro"))
            submit()
            onNodeWithTag("quote-unavailable-0").assertTextContains("Nome do A · PY", substring = true)
            onNodeWithTag("quote-unavailable-1").assertTextContains("CODB · PY", substring = true)
            assertEquals(0, count("CODA", substring = true))
        }

    @Test
    fun planNameComesFromOfferWithSameIdEvenWhenOfferIsForAnotherType() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote =
                reply(
                    offers = listOf(of("10.00", plan = planY, planName = "Plano Y")),
                    unavailable =
                        listOf(
                            un(typeB, "CODB", planY, "PY-CODE", reason("RATE_NOT_PRICED", "Sem preço.")),
                            un(typeB, "CODB", planX, "PX-CODE", reason("RATE_NOT_PRICED", "Sem preço.")),
                        ),
                )
            panel(source)
            submit()
            onNodeWithTag("quote-unavailable-0").assertTextContains("CODB · Plano Y", substring = true)
            onNodeWithTag("quote-unavailable-1").assertTextContains("CODB · PX-CODE", substring = true)
        }

    @Test
    fun severalReasonsOfOneCombinationAreAllShownWithoutRawData() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote =
                reply(
                    unavailable =
                        listOf(
                            un(
                                typeA,
                                "CODA",
                                planX,
                                "PX",
                                reason("CLOSED_TO_ARRIVAL", "CLOSED_TO_ARRIVAL"),
                                reason("MIN_STAY_NOT_MET", "{\"code\":\"MIN_STAY_NOT_MET\"}"),
                                reason("SOMETHING_NEW", "SOMETHING_NEW"),
                                reason("MAX_STAY_EXCEEDED", "A estadia passa do máximo."),
                            ),
                        ),
                )
            panel(source, mapOf(typeA.toString() to "Tipo A"))
            submit()
            val node = onNodeWithTag("quote-unavailable-0")
            node.assertTextContains("A data está fechada para chegada.", substring = true)
            node.assertTextContains("A estadia é mais curta que o mínimo do plano.", substring = true)
            node.assertTextContains("A estadia passa do máximo.", substring = true)
            for (raw in listOf("CLOSED_TO_ARRIVAL", "MIN_STAY_NOT_MET", "SOMETHING_NEW", "{")) {
                assertEquals(0, count(raw, substring = true), "nenhum nó mostra $raw")
            }
            assertEquals(0, count("-0000-", substring = true), "nenhum UUID")
        }

    @Test
    fun noUuidAppearsInOfferOrUnavailableNodes() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote =
                reply(
                    offers = listOf(of("10.00", listOf(nt("2026-11-16", "5.00")))),
                    unavailable = listOf(un(typeB, "CODB", planY, "PY", reason("RATE_NOT_PRICED", "Sem preço."))),
                )
            panel(source)
            submit()
            assertEquals(0, count("-0000-", substring = true))
            assertEquals(0, count("30000000", substring = true))
            assertEquals(0, count("40000000", substring = true))
        }

    @Test
    fun quotedWithOffersButNoUnavailableHidesUnavailableHeading() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote = reply(listOf(of("10.00")))
            panel(source)
            submit()
            assertEquals(0, count("Indisponíveis"))
            assertEquals(0, count("Nenhuma combinação para essa estadia."))
        }

    @Test
    fun onlyUnavailableDoesNotShowNoCombinationMessage() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote =
                reply(unavailable = listOf(un(typeA, "CODA", planX, "PX", reason("RATE_NOT_PRICED", "Sem preço."))))
            panel(source)
            submit()
            assertEquals(0, count("Nenhuma combinação para essa estadia."))
            onNodeWithText("Indisponíveis").assertExists()
        }

    @Test
    fun beforeAnyQuoteNoResultIsShown() =
        runComposeUiTest {
            panel(FakeAvailabilitySource())
            assertEquals(0, count("Nenhuma combinação para essa estadia."))
            assertEquals(0, count("Indisponíveis"))
        }

    @Test
    fun loadingShowsIndicatorAndDisablesSubmitThenReenables() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            val gate = source.hold(FakeAvailabilitySource.Op.QUOTE)
            panel(source)
            assertEquals(0, count("Carregando…"))
            submit()
            onNodeWithTag("quote-submit").assertIsNotEnabled()
            onNodeWithText("Carregando…").assertExists()
            gate.complete(Unit)
            waitForIdle()
            onNodeWithTag("quote-submit").assertIsEnabled()
            assertEquals(0, count("Carregando…"))
        }

    @Test
    fun errorWithoutFieldErrorsIsShownWithoutTheCause() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.failures[FakeAvailabilitySource.Op.QUOTE] = IOException("boom")
            val state = panel(source)
            submit()
            onNodeWithText(state.error!!).assertExists()
            assertEquals(0, count("boom", substring = true))
        }

    @Test
    fun childAgeErrorsOfSeveralPositionsAreJoinedInTheAgesField() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.failures[FakeAvailabilitySource.Op.QUOTE] =
                ApiError.Problem(
                    400,
                    "STAY_INVALID",
                    null,
                    listOf(
                        FieldError("childAges[0]", "X", "Primeira idade inválida."),
                        FieldError("childAges[3]", "X", "Quarta idade inválida."),
                        FieldError("departure", "X", "Saída inválida."),
                    ),
                )
            val state = panel(source)
            state.childAgesText = "1,2,3,4"
            submit()
            onNodeWithTag("quote-children").assertTextContains("Primeira idade inválida.", substring = true)
            onNodeWithTag("quote-children").assertTextContains("Quarta idade inválida.", substring = true)
            assertEquals(1, count("Saída inválida."))
            assertEquals(0, count(state.error!!))
        }

    @Test
    fun enterInEveryFieldSubmits() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            panel(source)
            val tags = listOf("quote-arrival", "quote-departure", "quote-adults", "quote-children")
            tags.forEachIndexed { i, tag ->
                onNodeWithTag(tag).performClick()
                onNodeWithTag(tag).performKeyInput { pressKey(Key.Enter) }
                waitForIdle()
                assertEquals(i + 1, source.quoteCalls.size, "Enter em $tag")
            }
        }

    @Test
    fun calendarPrefillAcrossMonthEndFillsBothFieldsAndClearsPreviousResult() =
        runComposeUiTest {
            val source = FakeAvailabilitySource()
            source.quote = reply(listOf(of("10.00")))
            val day =
                AvailabilityDayResponse(
                    available = 3,
                    blocked = 0,
                    date = "2026-11-30",
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
                                code = "TA",
                                days = listOf(day),
                                name = "Tipo A",
                                overbookedDates = emptyList(),
                                roomTypeId = typeA,
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
            quote.arrivalText = "16/11/2026"
            quote.departureText = "18/11/2026"
            waitForIdle()
            onNodeWithTag("quote-submit").performClick()
            waitForIdle()
            onNodeWithTag("quote-offer-0").assertExists()
            onNodeWithTag("availability-cell-$typeA-2026-11-30").performScrollTo().performClick()
            onNodeWithText("Cotar a partir deste dia").performClick()
            waitForIdle()
            assertEquals("30/11/2026", quote.arrivalText)
            assertEquals("01/12/2026", quote.departureText)
            onNodeWithTag("quote-arrival").assertTextContains("30/11/2026")
            onNodeWithTag("quote-departure").assertTextContains("01/12/2026")
            assertEquals(0, count("Total R$", substring = true))
        }
}
