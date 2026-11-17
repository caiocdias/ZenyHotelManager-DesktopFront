package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.GuestPick
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.NightlyRateInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.BookingResponse
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val naType = Uuid.parse("10000000-0000-0000-0000-0000000000a1")
private val naPlan = Uuid.parse("20000000-0000-0000-0000-0000000000a1")
private const val NA_GUEST_ID = "70000000-0000-0000-0000-0000000000a1"
private val naGuest = GuestPick(NA_GUEST_ID, HolderSummary("Ana Ataque", "CPF ***.***.***-01"))
private val naStay = QuotedStay(BusinessDate.parse("2026-11-13"), BusinessDate.parse("2026-11-16"), 2, emptyList())

private val naOffer =
    QuoteOfferResponse(
        accommodationTotal = "300.00",
        additionalPackagesTotal = "0.00",
        availableUnits = 2,
        cancellationPolicyId = Uuid.random(),
        category = QuoteOfferResponse.Category.NORMAL,
        guaranteePolicyId = Uuid.random(),
        includedPackagesTotal = "0.00",
        mealPlan = "BREAKFAST",
        nights = emptyList(),
        ratePlanCode = "BAR",
        ratePlanId = naPlan,
        ratePlanName = "Balcão",
        roomTotal = "300.00",
        roomTypeCode = "STD",
        roomTypeId = naType,
        roomTypeName = "Standard",
        total = "300.00",
    )

private fun naResponse(): BookingResponse =
    BookingResponse(
        channel = "DIRECT",
        code = "B-77",
        createdAt = "2026-11-12T10:00:00Z",
        holderGuestId = Uuid.parse(NA_GUEST_ID),
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
                    code = "R-77",
                    createdAt = "2026-11-12T10:00:00Z",
                    dayUse = false,
                    departure = "2026-11-16",
                    folioId = Uuid.random(),
                    guaranteeTerms =
                        GuaranteeTermsResponse(name = "Sem", policyId = Uuid.random(), type = "NONE", version = 1),
                    guests = emptyList(),
                    id = Uuid.parse("30000000-0000-0000-0000-0000000000a9"),
                    nightlyRates = emptyList(),
                    penaltiesCharged = "0.00",
                    ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                    ratePlanId = naPlan,
                    roomTotal = "1.00",
                    roomTypeId = naType,
                    segment = "LEISURE",
                    status = "CONFIRMED",
                    total = "655.37",
                    version = 1,
                ),
            ),
        restrictionAlerts = emptyList(),
        version = 1,
    )

private fun naApproval() = ApiError.ApprovalRequired("DISCOUNT", "rates.discounts.apply", "res", null, null)

/**
 * Ataques à `NewReservationState` (M3 E03): a reserva é criada uma vez só (duplo clique, aprovação e fechamento no meio
 * do envio, resposta definitiva depois de um resultado desconhecido) e a diária negociada vai como foi digitada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewReservationStateAtaqueTest {
    private var changes = 0
    private val guests = FakeReceptionSource().apply { businessDay = BusinessDay(naStay.arrival, ZoneId.of("UTC")) }

    /** Como o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com o token. */
    private suspend fun approveAlways(action: ApprovableAction<BookingResponse>): ApiResponse<BookingResponse>? =
        try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            action.run("tok")
        }

    private fun source() = FakeNewReservationSource().apply { response = naResponse() }

    private fun TestScope.ready(
        source: FakeNewReservationSource,
        approve: suspend (ApprovableAction<BookingResponse>) -> ApiResponse<BookingResponse>? = ::approveAlways,
    ): NewReservationState =
        NewReservationState(
            source,
            guests,
            backgroundScope,
            naOffer,
            naStay,
            setOf("guests.profiles.read", "guests.profiles.manage"),
            approve,
        ) { changes++ }.also { it.picker.pick(naGuest) }

    private fun TestScope.confirmNow(state: NewReservationState) {
        state.confirm()
        runCurrent()
    }

    @Test
    fun doubleClickInTheSameFrameCreatesOnce() =
        runTest {
            val source = source()
            val state = ready(source)

            // Dois cliques antes de qualquer despacho: o segundo tem de ver o primeiro.
            state.confirm()
            state.confirm()
            runCurrent()

            assertEquals(1, source.calls.size)
            assertEquals(1, source.runs.size)
            assertNotNull(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun definitiveAnswerAfterUnknownUnlocksAndTheNextAttemptTakesANewKey() =
        runTest {
            val source =
                source().apply {
                    outcomes += ApiError.Network(IOException("offline"))
                    outcomes += ApiError.Problem(409, "ROOM_TYPE_UNAVAILABLE", "detalhe", emptyList())
                }
            val state = ready(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            confirmNow(state)

            // A recusa definitiva encerra a intenção: a tela destrava e a próxima tentativa é outra intenção.
            assertFalse(state.unconfirmed)
            assertNull(state.done)
            state.origin = "PHONE"
            assertEquals("PHONE", state.origin)
            confirmNow(state)

            val keys = source.runs.map { it.key }
            assertEquals(3, keys.size)
            assertEquals(keys[0], keys[1])
            assertNotEquals(keys[1], keys[2])
            assertNotNull(state.done)
        }

    @Test
    fun lostApprovedRunRetriesWithTheSameIntent() =
        runTest {
            // A 1ª execução sem token pede aprovação; a aprovada se perde na rede; a repetição tem de levar a MESMA
            // `Intent` (o `core` reenvia a chave girada e o token guardados nela).
            val source =
                source().apply {
                    requiresApproval = naApproval()
                    outcomes += ApiError.Network(IOException("caiu depois do envio"))
                }
            val state = ready(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertNull(state.done)
            confirmNow(state)

            assertEquals(2, source.calls.size)
            assertSame(source.calls[0].intent, source.calls[1].intent)
            assertNotNull(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun closeWhileApprovalIsPendingNeverSendsTheApprovedRun() =
        runTest {
            val source = source().apply { requiresApproval = naApproval() }
            val granted = CompletableDeferred<String>()
            val state =
                ready(source) { action ->
                    try {
                        action.run(null)
                    } catch (_: ApiError.ApprovalRequired) {
                        action.run(granted.await())
                    }
                }

            confirmNow(state)
            assertTrue(state.busy)
            state.close()
            granted.complete("tok")
            runCurrent()

            assertEquals(listOf<String?>(null), source.runs.map { it.token })
            assertNull(state.done)
            assertEquals(0, changes)
        }

    @Test
    fun negotiatedRateInAnyAcceptedFormatGoesAsTheSameMoneyEveryNight() =
        runTest {
            val cases =
                mapOf(
                    "R$ 1.234,56" to BigDecimal("1234.56"),
                    " 0,01 " to BigDecimal("0.01"),
                    "99" to BigDecimal("99.00"),
                )
            for ((typed, expected) in cases) {
                val source = source()
                val state = ready(source)
                state.nightlyRateText = typed
                confirmNow(state)

                val rates =
                    source.calls
                        .single()
                        .input.nightlyRates
                assertEquals(
                    listOf("2026-11-13", "2026-11-14", "2026-11-15").map(BusinessDate::parse),
                    rates.map(NightlyRateInput::date),
                    typed,
                )
                rates.forEach { assertEquals(0, it.roomAmount.amount.compareTo(expected), typed) }
                assertEquals(1, rates.map(NightlyRateInput::roomAmount).toSet().size, typed)
                assertNull(state.rateError, typed)
            }
        }

    @Test
    fun confirmIsBlockedWhileTheHolderIsBeingRegistered() =
        runTest {
            val created = GuestPick("70000000-0000-0000-0000-0000000000a2", HolderSummary("Caio Ataque", null))
            guests.holdCreateGuest = true
            guests.createdGuest = created
            val source = source()
            val state = ready(source)
            assertTrue(state.canConfirm)
            val fields =
                state.picker.form.fields
                    .associateBy { it.name }
            fields.getValue("fullName").onChange("Caio Ataque")
            fields.getValue("documentType").onChange("CPF")
            fields.getValue("documentNumber").onChange("12345678909")
            fields.getValue("issuingCountry").onChange("")
            fields.getValue("nationality").onChange("BR")
            fields.getValue("birthDate").onChange("10/05/1990")

            state.picker.create()
            runCurrent()
            assertTrue(state.picker.creating)
            assertFalse(state.canConfirm)
            confirmNow(state)
            assertTrue(source.calls.isEmpty())

            guests.heldCreateGuests.forEach { it.complete(Unit) }
            runCurrent()
            assertEquals(created, state.holder)
            confirmNow(state)
            assertEquals(
                created.id,
                source.calls
                    .single()
                    .input.holderGuestId,
            )
        }

    @Test
    fun emptyRateSendsNoNightlyRatesAndNoMoneyIsComputed() =
        runTest {
            val source = source()
            val state = ready(source)
            state.nightlyRateText = "   "

            confirmNow(state)

            assertEquals(
                emptyList(),
                source.calls
                    .single()
                    .input.nightlyRates,
            )
            // O total mostrado é o do servidor, não o da oferta.
            assertTrue(assertNotNull(state.done).total.contains("655,37"))
        }

    @Test
    fun negativeOrZeroRateNeverReachesTheServerNorTakesAKey() =
        runTest {
            val source = source()
            val state = ready(source)

            for (text in listOf("-R$ 10,00", "R$ -10,00", "0", "0,00")) {
                state.nightlyRateText = text
                confirmNow(state)
                assertEquals("Informe um valor válido para a diária.", state.rateError, text)
            }
            assertTrue(source.calls.isEmpty())
            assertFalse(state.busy)
            assertFalse(state.unconfirmed)
        }
}
