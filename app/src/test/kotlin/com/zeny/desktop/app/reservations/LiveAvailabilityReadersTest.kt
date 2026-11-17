package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val WINDOW = 30
private val firstDay = BusinessDate.parse("2026-11-16")
private val standardId = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val luxoId = Uuid.parse("10000000-0000-0000-0000-000000000002")
private val planA = Uuid.parse("20000000-0000-0000-0000-000000000001")
private val planB = Uuid.parse("20000000-0000-0000-0000-000000000002")

private fun dateAt(offset: Int) = BusinessDate(firstDay.value.plusDays(offset.toLong())).toApi()

private fun roomType(
    id: Uuid,
    name: String,
    seed: Int,
    days: Int = WINDOW,
) = RoomTypeAvailabilityResponse(
    active = true,
    code = name.take(3).uppercase(),
    days =
        (0 until days).map {
            AvailabilityDayResponse(
                available = (it * 7 + seed) % 11 + 1,
                blocked = 0,
                date = dateAt(it),
                outOfOrder = 0,
                overbooked = false,
                overbookingLimit = 0,
                sold = 0,
                total = 12,
            )
        },
    name = name,
    overbookedDates = emptyList(),
    roomTypeId = id,
)

private fun calendarOf(vararg types: RoomTypeAvailabilityResponse) =
    AvailabilityResponse(from = dateAt(0), roomTypes = types.toList(), to = dateAt(WINDOW - 1))

private fun night(
    offset: Int,
    total: String,
) = QuoteNightResponse(
    accommodationAmount = total,
    additionalPackagesAmount = "0.00",
    baseRate = total,
    children = emptyList(),
    date = dateAt(offset),
    extraAdults = 0,
    extraAdultsAmount = "0.00",
    includedPackagesAmount = "0.00",
    packages = emptyList(),
    roomAmount = total,
    total = total,
)

private fun typeName(id: Uuid) = if (id == standardId) "Standard" else "Luxo"

private fun offer(
    typeId: Uuid,
    planId: Uuid,
    total: String,
    nights: List<String>,
    mealPlan: String = "BREAKFAST",
) = QuoteOfferResponse(
    accommodationTotal = total,
    additionalPackagesTotal = "0.00",
    availableUnits = 2,
    cancellationPolicyId = planId,
    category = QuoteOfferResponse.Category.NORMAL,
    guaranteePolicyId = planId,
    includedPackagesTotal = "0.00",
    mealPlan = mealPlan,
    nights = nights.mapIndexed { index, value -> night(index, value) },
    ratePlanCode = "PLAN",
    ratePlanId = planId,
    ratePlanName = "Balcão",
    roomTotal = total,
    roomTypeCode = typeName(typeId).take(3).uppercase(),
    roomTypeId = typeId,
    roomTypeName = typeName(typeId),
    total = total,
)

private fun quoteOf(
    offers: List<QuoteOfferResponse>,
    unavailable: List<QuoteUnavailableResponse> = emptyList(),
) = QuoteResponse(
    adults = 2,
    arrival = dateAt(7),
    channel = "DIRECT",
    childAges = emptyList(),
    departure = dateAt(9),
    nights = 2,
    offers = offers,
    unavailable = Option.Some(unavailable),
)

private fun refusal(
    typeId: Uuid,
    code: String,
    message: String,
) = QuoteUnavailableResponse(
    ratePlanCode = "PLAN",
    ratePlanId = planA,
    reasons = listOf(QuoteRefusalResponse(code = code, dates = emptyList(), message = message)),
    roomTypeCode = "X",
    roomTypeId = typeId,
)

private val standard = roomType(standardId, "Standard", seed = 3)
private val luxo = roomType(luxoId, "Luxo", seed = 5)

/**
 * Prova, sem rede, as leituras e comparações da `LiveAvailabilityCheck`: elas passam com a tela certa, leem as
 * células fora da área visível (a última data) e falham quando a tela difere da API em um detalhe.
 */
@OptIn(ExperimentalTestApi::class)
class LiveAvailabilityReadersTest {
    private class Rig(
        val source: FakeAvailabilitySource,
        val recording: RecordingSource,
        val calendar: AvailabilityState,
        val quote: QuoteState,
    )

    private fun ComposeUiTest.rig(
        calendar: AvailabilityResponse = calendarOf(standard, luxo),
        quote: QuoteResponse = quoteOf(emptyList()),
    ): Rig {
        val source =
            FakeAvailabilitySource().also {
                it.availability = calendar
                it.quote = quote
                it.businessDay =
                    com.zeny.desktop.core.facade
                        .BusinessDay(firstDay, null)
            }
        val recording = RecordingSource(source)
        lateinit var state: AvailabilityState
        lateinit var quoteState: QuoteState
        setContent {
            val scope = rememberCoroutineScope()
            state = remember { AvailabilityState(recording, scope, WINDOW) }
            quoteState = remember { QuoteState(recording, scope) }
            ZenyTheme { AvailabilityScreen(state, quoteState) }
        }
        runOnUiThread { state.start() }
        waitUntil(timeoutMillis = 10_000) { !state.loading && state.rows.isNotEmpty() }
        waitForIdle()
        return Rig(source, recording, state, quoteState)
    }

    private fun ComposeUiTest.quote(
        rig: Rig,
        adults: String = "2",
    ) {
        runOnUiThread {
            rig.quote.arrivalText = DateInput.format(BusinessDate.parse(dateAt(7)))
            rig.quote.departureText = DateInput.format(BusinessDate.parse(dateAt(9)))
            rig.quote.adultsText = adults
            rig.quote.quote()
        }
        waitUntil(timeoutMillis = 10_000) { rig.quote.quoted }
        waitForIdle()
    }

    private val names = setOf("Standard", "Luxo")

    @Test
    fun calendarMatchingTheApiPassesIncludingTheLastColumnOutsideTheViewport() =
        runDesktopComposeUiTest(1280, 1400) {
            val rig = rig()
            assertCalendarShows(rig.calendar, calendarOf(standard, luxo), WINDOW, names)
            // A 30ª coluna está a 2.436 dp da borda, bem fora dos 1.280 dp da janela: ainda assim é lida.
            assertEquals(
                standard.days
                    .last()
                    .available
                    .toString(),
                nodeText("availability-cell-$standardId-${dateAt(29)}"),
            )
        }

    @Test
    fun calendarFailsWhenOnlyTheLastCellOfTheLastRowDiffers() =
        runDesktopComposeUiTest(1280, 1400) {
            val rig = rig()
            val changed =
                luxo.copy(
                    days =
                        luxo.days.dropLast(1) + luxo.days.last().copy(available = luxo.days.last().available + 1),
                )
            assertFailsWith<AssertionError> {
                assertCalendarShows(
                    rig.calendar,
                    calendarOf(standard, changed),
                    WINDOW,
                    names,
                )
            }
        }

    @Test
    fun calendarFailsWhenTheApiHasAnotherRoomTypeSet() =
        runDesktopComposeUiTest(1280, 1400) {
            val rig = rig()
            assertFailsWith<AssertionError> { assertCalendarShows(rig.calendar, calendarOf(standard), WINDOW, names) }
            val other = luxo.copy(roomTypeId = Uuid.parse("10000000-0000-0000-0000-0000000000ff"))
            assertFailsWith<AssertionError> {
                assertCalendarShows(
                    rig.calendar,
                    calendarOf(standard, other),
                    WINDOW,
                    names,
                )
            }
        }

    @Test
    fun calendarOfDashesDoesNotMatchAnApiWithoutDays() =
        runDesktopComposeUiTest(1280, 1400) {
            val empty = calendarOf(roomType(standardId, "Standard", 3, days = 0), roomType(luxoId, "Luxo", 5, days = 0))
            val rig = rig(calendar = empty)
            val failure = assertFailsWith<AssertionError> { assertCalendarShows(rig.calendar, empty, WINDOW, names) }
            assertTrue(
                failure.message.orEmpty().contains("traços"),
                "a falha tem de ser a dos traços: ${failure.message}",
            )
        }

    @Test
    fun calendarFailsWhenTheWindowIsShorterThanExpected() =
        runDesktopComposeUiTest(1280, 1400) {
            val rig = rig()
            assertFailsWith<AssertionError> {
                assertCalendarShows(
                    rig.calendar,
                    calendarOf(standard, luxo),
                    WINDOW + 1,
                    names,
                )
            }
        }

    @Test
    fun recordingSourceLogsEachCallWithItsRequest() =
        runDesktopComposeUiTest(1280, 1400) {
            val rig = rig()
            runOnUiThread { rig.calendar.reload() }
            waitUntil(timeoutMillis = 10_000) { !rig.calendar.loading }
            assertEquals(1, rig.recording.calls("businessDay").size)
            assertEquals(
                listOf(
                    "${dateAt(0)}..${dateAt(29)}",
                    "${dateAt(0)}..${dateAt(29)}",
                ),
                rig.recording.calls("availability").map {
                    it.request
                },
            )
            quote(rig, adults = "4")
            assertEquals(
                listOf("${dateAt(7)}..${dateAt(9)} adults=4 children=[]"),
                rig.recording.calls("quote").map { it.request },
            )
        }

    private val apiOffers =
        listOf(
            offer(standardId, planA, "400.00", listOf("200.00", "200.00")),
            offer(luxoId, planB, "700.00", listOf("300.00", "400.00")),
        )

    @Test
    fun offersMatchRegardlessOfTheOrderTheServerAnswers() =
        runDesktopComposeUiTest(1280, 1400) {
            val rig = rig(quote = quoteOf(apiOffers))
            quote(rig)
            assertOffersMatch(rig.quote.offers, quoteOf(apiOffers.reversed()))
        }

    @Test
    fun offersFailWhenANightValueDiffers() =
        runDesktopComposeUiTest(1280, 1400) {
            val rig = rig(quote = quoteOf(apiOffers))
            quote(rig)
            val changed =
                apiOffers.map {
                    if (it.roomTypeId ==
                        luxoId
                    ) {
                        it.copy(nights = listOf(night(0, "300.00"), night(1, "401.00")))
                    } else {
                        it
                    }
                }
            assertFailsWith<AssertionError> { assertOffersMatch(rig.quote.offers, quoteOf(changed)) }
        }

    @Test
    fun offersFailWhenTheTotalDiffersOrTheCountDiffers() =
        runDesktopComposeUiTest(1280, 1400) {
            val rig = rig(quote = quoteOf(apiOffers))
            quote(rig)
            assertFailsWith<AssertionError> {
                assertOffersMatch(rig.quote.offers, quoteOf(listOf(apiOffers[0].copy(total = "401.00"), apiOffers[1])))
            }
            assertFailsWith<AssertionError> { assertOffersMatch(rig.quote.offers, quoteOf(apiOffers.take(1))) }
            assertFailsWith<AssertionError> {
                assertOffersMatch(
                    rig.quote.offers,
                    quoteOf(apiOffers + offer(luxoId, planA, "1.00", listOf("1.00"))),
                )
            }
        }

    @Test
    fun offersFailWhenTwoEqualNightsShowOnlyOnceOnScreen() =
        runDesktopComposeUiTest(1280, 1400) {
            // A tela mostra uma noite de 200,00; a API diz três: a contagem acusa, e um `contains` não acusaria.
            val shown = offer(standardId, planA, "400.00", listOf("200.00"))
            val rig = rig(quote = quoteOf(listOf(shown)))
            quote(rig)
            val api = offer(standardId, planA, "400.00", listOf("200.00", "200.00", "200.00"))
            assertFailsWith<AssertionError> { assertOffersMatch(rig.quote.offers, quoteOf(listOf(api))) }
        }

    @Test
    fun breakfastOfferWithoutTheLabelFails() =
        runDesktopComposeUiTest(1280, 1400) {
            val shown =
                offer(standardId, planA, "400.00", listOf("200.00", "200.00"), mealPlan = "ROOM_ONLY")
            val rig = rig(quote = quoteOf(listOf(shown)))
            quote(rig)
            assertFailsWith<AssertionError> {
                assertOffersMatch(rig.quote.offers, quoteOf(listOf(shown.copy(mealPlan = "BREAKFAST"))))
            }
        }

    private val showable = "O tipo de UH recebe até 3 adulto(s) e 0 criança(s)."

    @Test
    fun refusalsMatchTheApiAndShowNoRawCode() =
        runDesktopComposeUiTest(1280, 1400) {
            val api =
                quoteOf(
                    emptyList(),
                    listOf(
                        refusal(standardId, "OCCUPANCY_EXCEEDS_ROOM_TYPE", showable),
                        refusal(luxoId, "OCCUPANCY_EXCEEDS_ROOM_TYPE", showable),
                    ),
                )
            val rig = rig(quote = api)
            quote(rig, adults = "4")
            val texts = assertRefusalsShown(api, rig.calendar.rows.associate { it.roomTypeId to it.name })
            assertTrue(texts.all { it.contains("recebe até 3 adulto(s)") })
            assertNoRawCode(api, setOf("OCCUPANCY_EXCEEDS_ROOM_TYPE"))
        }

    @Test
    fun refusalCheckFailsWhenTheApiRefusesMoreThanTheScreenShows() =
        runDesktopComposeUiTest(1280, 1400) {
            val shown = quoteOf(emptyList(), listOf(refusal(standardId, "OCCUPANCY_EXCEEDS_ROOM_TYPE", showable)))
            val rig = rig(quote = shown)
            quote(rig, adults = "4")
            val api =
                quoteOf(
                    emptyList(),
                    listOf(
                        refusal(standardId, "OCCUPANCY_EXCEEDS_ROOM_TYPE", showable),
                        refusal(luxoId, "OCCUPANCY_EXCEEDS_ROOM_TYPE", showable),
                    ),
                )
            assertFailsWith<AssertionError> {
                assertRefusalsShown(
                    api,
                    rig.calendar.rows.associate {
                        it.roomTypeId to
                            it.name
                    },
                )
            }
        }

    @Test
    fun rawCodeInsideAnyNodeIsCaught() =
        runDesktopComposeUiTest(1280, 1400) {
            val leaking = "Falhou: OCCUPANCY_EXCEEDS_ROOM_TYPE para 4 adultos"
            val api = quoteOf(emptyList(), listOf(refusal(standardId, "OCCUPANCY_EXCEEDS_ROOM_TYPE", leaking)))
            val rig = rig(quote = api)
            quote(rig, adults = "4")
            val failure = assertFailsWith<AssertionError> { assertNoRawCode(api, setOf("OCCUPANCY_EXCEEDS_ROOM_TYPE")) }
            assertTrue(failure.message.orEmpty().contains("código cru"), failure.message)
        }

    @Test
    fun anUnknownCodeShapedTextIsCaughtEvenWhenTheApiDidNotListIt() =
        runDesktopComposeUiTest(1280, 1400) {
            val leaking = "Erro NEW_UNKNOWN_CODE na estadia"
            val api = quoteOf(emptyList(), listOf(refusal(standardId, "OTHER", leaking)))
            val rig = rig(quote = api)
            quote(rig, adults = "4")
            assertFailsWith<AssertionError> { assertNoRawCode(api, emptySet()) }
        }
}
