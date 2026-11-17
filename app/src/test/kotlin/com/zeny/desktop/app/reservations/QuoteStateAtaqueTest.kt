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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val planId = Uuid.parse("20000000-0000-0000-0000-000000000001")
private val typeId = Uuid.parse("10000000-0000-0000-0000-000000000001")

private const val DATES = "Informe a chegada e a saída no formato dd/mm/aaaa."
private const val NOT_AFTER = "A saída precisa ser depois da chegada."
private const val TOO_LONG = "A estadia vai até 365 noites."
private const val ADULTS = "Informe de 1 a 40 adultos."
private const val AGES = "Informe as idades das crianças em números, separadas por vírgula."
private const val CHILDREN = "Informe até 20 crianças."

private fun offerOf(code: String) =
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

private fun answerOf(
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

private fun invalidStay(vararg fields: Pair<String, String>) =
    ApiError.Problem(400, "STAY_INVALID", null, fields.map { FieldError(it.first, "STAY_INVALID", it.second) })

@OptIn(ExperimentalCoroutinesApi::class)
class QuoteStateAtaqueTest {
    private val quoteOp = FakeAvailabilitySource.Op.QUOTE

    private fun TestScope.state(fake: FakeAvailabilitySource = FakeAvailabilitySource()) =
        QuoteState(fake, backgroundScope)

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

    private fun TestScope.refusal(
        message: String,
        label: String,
        setup: QuoteState.() -> Unit,
    ) {
        val fake = FakeAvailabilitySource()
        val s = state(fake)
        s.setup()
        s.quote()
        runCurrent()
        assertEquals(message, s.validation, label)
        assertEquals(0, fake.quoteCalls.size, label)
        assertFalse(s.loading, label)
    }

    private fun TestScope.accepted(
        label: String,
        setup: QuoteState.() -> Unit,
    ): QuoteCall {
        val fake = FakeAvailabilitySource()
        val s = state(fake)
        s.setup()
        s.quote()
        runCurrent()
        assertNull(s.validation, label)
        assertEquals(1, fake.quoteCalls.size, label)
        return fake.quoteCalls.single()
    }

    @Test
    fun nightBoundaries() =
        runTest {
            accepted("1 noite") { fill("16/11/2026", "17/11/2026") }
            accepted("365 noites") { fill("16/11/2026", "16/11/2027") }
            refusal(TOO_LONG, "366 noites") { fill("16/11/2026", "17/11/2027") }
        }

    @Test
    fun adultsBoundaries() =
        runTest {
            for (valid in listOf("1", "40", " 3 ", "+2")) {
                accepted("adults='$valid'") { fill(adults = valid) }
            }
            for (bad in listOf("0", "41", "-1", "", "  ", "2 3", "2.5", "1e1", "99999999999")) {
                refusal(ADULTS, "adults='$bad'") { fill(adults = bad) }
            }
        }

    @Test
    fun agesBoundaries() =
        runTest {
            val call = accepted("20 idades") { fill(ages = List(20) { "1" }.joinToString(",")) }
            assertEquals(List(20) { 1 }, call.childAges)
            refusal(CHILDREN, "21 idades") { fill(ages = List(21) { "1" }.joinToString(",")) }
            for (bad in listOf("5,,9", "5,", ",5", ",", "5.5", "99999999999", "2147483648", "a,5", "5 9")) {
                refusal(AGES, "ages='$bad'") { fill(ages = bad) }
            }
        }

    @Test
    fun agesWithSpacesAreParsed() =
        runTest {
            assertEquals(listOf(5, 9), accepted("espacos") { fill(ages = " 5 ,  9 ") }.childAges)
            assertEquals(emptyList(), accepted("so espacos") { fill(ages = "   ") }.childAges)
        }

    @Test
    fun datesAreCheckedStrictly() =
        runTest {
            refusal(DATES, "29/02 nao bissexto") { fill("29/02/2027", "01/03/2027") }
            refusal(DATES, "chegada com ano de 2 digitos") { fill("16/11/26", "18/11/26") }
            refusal(DATES, "saida com ano de 2 digitos") { fill("16/11/2026", "18/11/26") }
            accepted("29/02 bissexto") { fill("29/02/2028", "01/03/2028") }
        }

    @Test
    fun firstProblemByPriorityWins() =
        runTest {
            refusal(DATES, "datas antes de adultos") { fill("x", "18/11/2026", "0") }
            refusal(NOT_AFTER, "ordem antes de adultos") { fill("18/11/2026", "16/11/2026", "0") }
        }

    @Test
    fun invalidInputDuringSlowQuoteDropsOldAnswer() =
        runTest {
            val fake = FakeAvailabilitySource().apply { quote = answerOf(listOf(offerOf("OLD"))) }
            val state = state(fake)
            state.fill()
            val gate = fake.hold(quoteOp)
            state.quote()
            runCurrent()
            assertTrue(state.loading)
            state.fill(adults = "0")
            state.quote()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(ADULTS, state.validation)
            assertTrue(state.offers.isEmpty())
            assertFalse(state.quoted)
            assertFalse(state.loading)
            assertNull(state.error)
        }

    @Test
    fun slowQuoteThenFastQuoteKeepsFastAndLoadingEnds() =
        runTest {
            val fake = FakeAvailabilitySource()
            val state = state(fake)
            state.fill()
            val slow = fake.hold(quoteOp)
            fake.quote = answerOf(listOf(offerOf("SLOW")))
            state.quote()
            runCurrent()
            val fast = fake.hold(quoteOp)
            fast.complete(Unit)
            fake.quote = answerOf(listOf(offerOf("FAST")))
            state.quote()
            runCurrent()
            assertEquals(listOf("FAST"), state.offers.map { it.ratePlanCode })
            slow.complete(Unit)
            runCurrent()
            assertEquals(listOf("FAST"), state.offers.map { it.ratePlanCode })
            assertFalse(state.loading)
            assertTrue(state.quoted)
        }

    @Test
    fun slowQuoteFailureAfterFastSuccessIsIgnored() =
        runTest {
            val fake = FakeAvailabilitySource()
            val state = state(fake)
            state.fill()
            val slow = fake.hold(quoteOp)
            fake.failures[quoteOp] = invalidStay("adults" to "Ruim.")
            state.quote()
            runCurrent()
            val fast = fake.hold(quoteOp)
            fast.complete(Unit)
            fake.failures.clear()
            state.quote()
            runCurrent()
            slow.complete(Unit)
            runCurrent()
            assertNull(state.error)
            assertTrue(state.fieldErrors.isEmpty())
            assertTrue(state.quoted)
        }

    @Test
    fun loadingStaysTrueUntilTheLatestQuoteAnswers() =
        runTest {
            val fake = FakeAvailabilitySource()
            val state = state(fake)
            state.fill()
            val first = fake.hold(quoteOp)
            state.quote()
            runCurrent()
            val second = fake.hold(quoteOp)
            state.quote()
            runCurrent()
            first.complete(Unit)
            runCurrent()
            assertTrue(state.loading)
            assertFalse(state.quoted)
            second.complete(Unit)
            runCurrent()
            assertFalse(state.loading)
            assertTrue(state.quoted)
        }

    @Test
    fun closeWithHeldQuoteLeavesNothingBehindEvenOnFailure() =
        runTest {
            val fake = FakeAvailabilitySource()
            fake.failures[quoteOp] = ApiError.Problem(500, "X", null, emptyList())
            val state = state(fake)
            state.fill()
            val gate = fake.hold(quoteOp)
            state.quote()
            runCurrent()
            state.close()
            gate.complete(Unit)
            runCurrent()
            assertNull(state.error)
            assertTrue(state.fieldErrors.isEmpty())
            assertFalse(state.loading)
        }

    @Test
    fun prefillAfterCloseDoesNothing() =
        runTest {
            val state = state()
            state.close()
            state.prefill(BusinessDate.parse("2026-12-01"))
            assertEquals("", state.arrivalText)
            assertEquals("", state.departureText)
        }

    @Test
    fun prefillDuringQuoteDropsTheLateAnswer() =
        runTest {
            val fake = FakeAvailabilitySource().apply { quote = answerOf(listOf(offerOf("LATE"))) }
            val state = state(fake)
            state.fill()
            val gate = fake.hold(quoteOp)
            state.quote()
            runCurrent()
            state.prefill(BusinessDate.parse("2026-12-01"))
            gate.complete(Unit)
            runCurrent()
            assertTrue(state.offers.isEmpty())
            assertFalse(state.quoted)
            assertFalse(state.loading)
            assertEquals("01/12/2026", state.arrivalText)
            assertEquals("02/12/2026", state.departureText)
        }

    @Test
    fun prefillDuringQuoteDropsTheLateFailure() =
        runTest {
            val fake = FakeAvailabilitySource()
            fake.failures[quoteOp] = invalidStay("departure" to "Ruim.")
            val state = state(fake)
            state.fill()
            val gate = fake.hold(quoteOp)
            state.quote()
            runCurrent()
            state.prefill(BusinessDate.parse("2026-12-01"))
            gate.complete(Unit)
            runCurrent()
            assertNull(state.error)
            assertTrue(state.fieldErrors.isEmpty())
        }

    @Test
    fun stayInvalidMapsEveryNamedFieldAndToleratesUnknown() =
        runTest {
            val fake = FakeAvailabilitySource()
            fake.failures[quoteOp] =
                invalidStay(
                    "departure" to "Saída ruim.",
                    "adults" to "Adultos ruins.",
                    "childAges[1]" to "Idade ruim.",
                    "algoNovo" to "Campo que o app não conhece.",
                )
            val state = state(fake)
            state.fill(ages = "3,4")
            state.quote()
            runCurrent()
            assertEquals(
                mapOf(
                    "departure" to "Saída ruim.",
                    "adults" to "Adultos ruins.",
                    "childAges[1]" to "Idade ruim.",
                    "algoNovo" to "Campo que o app não conhece.",
                ),
                state.fieldErrors,
            )
            assertNotNull(state.error)
            assertFalse(state.quoted)
            assertFalse(state.loading)
        }

    @Test
    fun errorThenSuccessClearsEverything() =
        runTest {
            val fake = FakeAvailabilitySource()
            fake.failures[quoteOp] = invalidStay("adults" to "Ruim.")
            val state = state(fake)
            state.fill()
            state.quote()
            runCurrent()
            assertNotNull(state.error)
            fake.failures.clear()
            fake.quote = answerOf(listOf(offerOf("A")))
            state.quote()
            runCurrent()
            assertNull(state.error)
            assertNull(state.validation)
            assertTrue(state.fieldErrors.isEmpty())
            assertEquals(listOf("A"), state.offers.map { it.ratePlanCode })
        }

    @Test
    fun validationThenValidQuoteClearsValidation() =
        runTest {
            val state = state()
            state.fill(adults = "0")
            state.quote()
            assertNotNull(state.validation)
            state.fill()
            state.quote()
            runCurrent()
            assertNull(state.validation)
            assertTrue(state.quoted)
        }

    @Test
    fun forbiddenUsesCatalogTextWithoutFieldErrors() =
        runTest {
            val fake = FakeAvailabilitySource()
            val problem = ApiError.Problem(403, "FORBIDDEN", null, emptyList())
            fake.failures[quoteOp] = problem
            val state = state(fake)
            state.fill()
            state.quote()
            runCurrent()
            assertEquals(problem.userMessage, state.error)
            assertTrue(state.error!!.isNotBlank())
            assertTrue(state.fieldErrors.isEmpty())
        }

    @Test
    fun nonApiExceptionBecomesNetworkText() =
        runTest {
            val fake = FakeAvailabilitySource()
            fake.failures[quoteOp] = IllegalStateException("boom interno")
            val state = state(fake)
            state.fill()
            state.quote()
            runCurrent()
            assertEquals(ApiError.Network(RuntimeException()).userMessage, state.error)
            assertFalse(state.error!!.contains("boom"))
            assertFalse(state.loading)
        }

    @Test
    fun unavailableVariantsAndEmptyOffers() =
        runTest {
            val variants =
                listOf<Option<List<QuoteUnavailableResponse?>?>>(
                    Option.Undefined,
                    Option.Some(null),
                    Option.Some(listOf(null, null)),
                    Option.Some(emptyList()),
                )
            for (variant in variants) {
                val fake = FakeAvailabilitySource().apply { quote = answerOf(unavailable = variant) }
                val state = state(fake)
                state.fill()
                state.quote()
                runCurrent()
                assertTrue(state.quoted, variant.toString())
                assertTrue(state.offers.isEmpty(), variant.toString())
                assertTrue(state.unavailable.isEmpty(), variant.toString())
                assertNull(state.error)
            }
        }

    @Test
    fun toStringCarriesNoStayData() =
        runTest {
            val state = state()
            state.fill(ages = "7")
            val text = state.toString()
            assertFalse(text.contains("16/11/2026"))
            assertTrue(text.startsWith("com.zeny.desktop.app.reservations.QuoteState@"))
        }
}
