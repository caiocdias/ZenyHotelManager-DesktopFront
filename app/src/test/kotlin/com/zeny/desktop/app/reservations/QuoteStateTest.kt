package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.QuoteUnavailableResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private fun date(text: String) = BusinessDate.parse(text)

private val planId = Uuid.parse("20000000-0000-0000-0000-000000000001")
private val typeId = Uuid.parse("10000000-0000-0000-0000-000000000001")

private const val DATE_MESSAGE = "Informe a chegada e a saída no formato dd/mm/aaaa."
private const val AGES_MESSAGE = "Informe as idades das crianças em números, separadas por vírgula."

private fun offer(code: String) =
    QuoteOfferResponse(
        accommodationTotal = "200.00",
        additionalPackagesTotal = "0.00",
        availableUnits = 2,
        cancellationPolicyId = planId,
        category = QuoteOfferResponse.Category.NORMAL,
        guaranteePolicyId = planId,
        includedPackagesTotal = "0.00",
        mealPlan = "BB",
        nights = emptyList(),
        ratePlanCode = code,
        ratePlanId = planId,
        ratePlanName = code,
        roomTotal = "200.00",
        roomTypeCode = "STD",
        roomTypeId = typeId,
        roomTypeName = "Standard",
        total = "200.00",
    )

private fun refused(code: String) =
    QuoteUnavailableResponse(
        ratePlanCode = code,
        ratePlanId = planId,
        reasons = emptyList(),
        roomTypeCode = "STD",
        roomTypeId = typeId,
    )

private fun answer(
    offers: List<QuoteOfferResponse> = emptyList(),
    unavailable: Option<List<QuoteUnavailableResponse?>?> = Option.Undefined,
) = QuoteResponse(
    adults = 2,
    arrival = "2026-11-16",
    channel = "DIRECT",
    childAges = emptyList(),
    departure = "2026-11-18",
    nights = 2,
    offers = offers,
    unavailable = unavailable,
)

@OptIn(ExperimentalCoroutinesApi::class)
class QuoteStateTest {
    private fun TestScope.state(source: FakeAvailabilitySource = FakeAvailabilitySource()) =
        QuoteState(source, backgroundScope)

    private fun QuoteState.fill(
        arrival: String = "16/11/2026",
        departure: String = "18/11/2026",
        adults: String = "2",
        ages: String = "",
    ) {
        arrivalText = arrival
        departureText = departure
        adultsText = adults
        childAgesText = ages
    }

    @Test
    fun invalidStayDoesNotHitServer() =
        runTest {
            val cases =
                listOf(
                    listOf("x", "18/11/2026", "2", "") to DATE_MESSAGE,
                    listOf("16/11/2026", "", "2", "") to DATE_MESSAGE,
                    listOf("16/11/2026", "16/11/2026", "2", "") to "A saída precisa ser depois da chegada.",
                    listOf("16/11/2026", "15/11/2026", "2", "") to "A saída precisa ser depois da chegada.",
                    listOf("16/11/2026", "17/11/2027", "2", "") to "A estadia vai até 365 noites.",
                    listOf("16/11/2026", "18/11/2026", "0", "") to "Informe de 1 a 40 adultos.",
                    listOf("16/11/2026", "18/11/2026", "41", "") to "Informe de 1 a 40 adultos.",
                    listOf("16/11/2026", "18/11/2026", "dois", "") to "Informe de 1 a 40 adultos.",
                    listOf("16/11/2026", "18/11/2026", "2", "a") to AGES_MESSAGE,
                    listOf("16/11/2026", "18/11/2026", "2", "-1") to AGES_MESSAGE,
                    listOf("16/11/2026", "18/11/2026", "2", "5,,9") to AGES_MESSAGE,
                    listOf("16/11/2026", "18/11/2026", "2", List(21) { "3" }.joinToString(",")) to
                        "Informe até 20 crianças.",
                )
            for ((input, message) in cases) {
                val fake = FakeAvailabilitySource()
                val state = state(fake)
                state.fill(input[0], input[1], input[2], input[3])
                state.quote()
                runCurrent()
                assertEquals(message, state.validation, input.toString())
                assertEquals(0, fake.quoteCalls.size, input.toString())
                assertFalse(state.loading)
                assertFalse(state.quoted)
            }
        }

    @Test
    fun exactly365NightsAndTwentyAgesAreValid() =
        runTest {
            val fake = FakeAvailabilitySource()
            val state = state(fake)
            state.fill("16/11/2026", "16/11/2027", "40", List(20) { "3" }.joinToString(","))
            state.quote()
            runCurrent()
            assertNull(state.validation)
            assertEquals(1, fake.quoteCalls.size)
        }

    @Test
    fun quoteSendsParsedStay() =
        runTest {
            val fake = FakeAvailabilitySource()
            val state = state(fake)
            state.fill(ages = "5, 9")
            state.quote()
            runCurrent()
            assertEquals(
                listOf(QuoteCall(date("2026-11-16"), date("2026-11-18"), 2, listOf(5, 9))),
                fake.quoteCalls,
            )
            assertNull(state.validation)
        }

    @Test
    fun defaultsAreTwoAdultsAndNoChildren() =
        runTest {
            val state = state()
            assertEquals("2", state.adultsText)
            assertEquals("", state.childAgesText)
        }

    @Test
    fun emptyAnswerIsQuotedWithNoOffers() =
        runTest {
            val state = state()
            assertFalse(state.quoted)
            state.fill()
            state.quote()
            runCurrent()
            assertTrue(state.quoted)
            assertTrue(state.offers.isEmpty())
            assertTrue(state.unavailable.isEmpty())
            assertFalse(state.loading)
        }

    @Test
    fun offersAreExposed() =
        runTest {
            val fake = FakeAvailabilitySource().apply { quote = answer(listOf(offer("A"), offer("B"))) }
            val state = state(fake)
            state.fill()
            state.quote()
            runCurrent()
            assertEquals(listOf("A", "B"), state.offers.map { it.ratePlanCode })
        }

    @Test
    fun undefinedUnavailableIsEmpty() =
        runTest {
            val fake = FakeAvailabilitySource().apply { quote = answer(unavailable = Option.Undefined) }
            val state = state(fake)
            state.fill()
            state.quote()
            runCurrent()
            assertTrue(state.quoted)
            assertTrue(state.unavailable.isEmpty())
        }

    @Test
    fun nullUnavailableIsEmpty() =
        runTest {
            val fake = FakeAvailabilitySource().apply { quote = answer(unavailable = Option.Some(null)) }
            val state = state(fake)
            state.fill()
            state.quote()
            runCurrent()
            assertTrue(state.unavailable.isEmpty())
        }

    @Test
    fun nullUnavailableItemsAreDropped() =
        runTest {
            val fake =
                FakeAvailabilitySource().apply {
                    quote = answer(unavailable = Option.Some(listOf(refused("A"), null, refused("B"))))
                }
            val state = state(fake)
            state.fill()
            state.quote()
            runCurrent()
            assertEquals(listOf("A", "B"), state.unavailable.map { it.ratePlanCode })
        }

    @Test
    fun newerQuoteWins() =
        runTest {
            val fake = FakeAvailabilitySource()
            val state = state(fake)
            state.fill()
            val gate = fake.hold(FakeAvailabilitySource.Op.QUOTE)
            fake.quote = answer(listOf(offer("OLD")))
            state.quote()
            runCurrent()
            state.fill(adults = "3")
            fake.quote = answer(listOf(offer("NEW")))
            state.quote()
            runCurrent()
            assertEquals(2, fake.quoteCalls.size)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("NEW"), state.offers.map { it.ratePlanCode })
            assertTrue(state.quoted)
            assertFalse(state.loading)
        }

    @Test
    fun olderQuoteFailureDoesNotOverwriteNewer() =
        runTest {
            val fake = FakeAvailabilitySource()
            val state = state(fake)
            state.fill()
            val gate = fake.hold(FakeAvailabilitySource.Op.QUOTE)
            fake.failures[FakeAvailabilitySource.Op.QUOTE] = ApiError.Problem(500, "X", null, emptyList())
            state.quote()
            runCurrent()
            fake.failures.clear()
            fake.quote = answer(listOf(offer("NEW")))
            state.quote()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertNull(state.error)
            assertEquals(listOf("NEW"), state.offers.map { it.ratePlanCode })
        }

    @Test
    fun closeDropsLateQuote() =
        runTest {
            val fake = FakeAvailabilitySource().apply { quote = answer(listOf(offer("LATE"))) }
            val state = state(fake)
            state.fill()
            val gate = fake.hold(FakeAvailabilitySource.Op.QUOTE)
            state.quote()
            runCurrent()
            state.close()
            gate.complete(Unit)
            runCurrent()
            assertTrue(state.offers.isEmpty())
            assertFalse(state.quoted)
            assertFalse(state.loading)
        }

    @Test
    fun quoteAfterCloseDoesNothing() =
        runTest {
            val fake = FakeAvailabilitySource()
            val state = state(fake)
            state.fill()
            state.close()
            state.quote()
            runCurrent()
            assertEquals(0, fake.quoteCalls.size)
        }

    @Test
    fun prefillSetsNextDayDeparture() =
        runTest {
            val fake = FakeAvailabilitySource().apply { quote = answer(listOf(offer("A"))) }
            val state = state(fake)
            state.fill()
            state.quote()
            runCurrent()
            state.prefill(date("2026-12-31"))
            assertEquals("31/12/2026", state.arrivalText)
            assertEquals("01/01/2027", state.departureText)
            assertTrue(state.offers.isEmpty())
            assertFalse(state.quoted)
        }

    @Test
    fun serverErrorShowsCatalogText() =
        runTest {
            val fake = FakeAvailabilitySource()
            fake.failures[FakeAvailabilitySource.Op.QUOTE] = ApiError.Problem(403, "FORBIDDEN", null, emptyList())
            val state = state(fake)
            state.fill()
            state.quote()
            runCurrent()
            assertEquals(ApiError.Problem(403, "FORBIDDEN", null, emptyList()).userMessage, state.error)
            assertFalse(state.quoted)
            assertFalse(state.loading)
            assertTrue(state.fieldErrors.isEmpty())
        }

    @Test
    fun fieldErrorShowsNextToField() =
        runTest {
            val fake = FakeAvailabilitySource()
            val problem =
                ApiError.Problem(
                    400,
                    "STAY_INVALID",
                    null,
                    listOf(FieldError("childAges[0]", "STAY_INVALID", "Idade fora do limite.")),
                )
            fake.failures[FakeAvailabilitySource.Op.QUOTE] = problem
            val state = state(fake)
            state.fill(ages = "99")
            state.quote()
            runCurrent()
            assertEquals(mapOf("childAges[0]" to "Idade fora do limite."), state.fieldErrors)
            assertEquals(problem.userMessage, state.error)
        }

    @Test
    fun nextQuoteClearsPreviousErrors() =
        runTest {
            val fake = FakeAvailabilitySource()
            fake.failures[FakeAvailabilitySource.Op.QUOTE] =
                ApiError.Problem(400, "STAY_INVALID", null, listOf(FieldError("adults", "X", "Ruim.")))
            val state = state(fake)
            state.fill()
            state.quote()
            runCurrent()
            fake.failures.clear()
            state.quote()
            runCurrent()
            assertNull(state.error)
            assertTrue(state.fieldErrors.isEmpty())
            assertTrue(state.quoted)
        }
}
