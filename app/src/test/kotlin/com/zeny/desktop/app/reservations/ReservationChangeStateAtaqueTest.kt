package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
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

private val caRes = Uuid.parse("20000000-0000-0000-0000-0000000000c1")
private val caType = Uuid.parse("10000000-0000-0000-0000-0000000000c1")
private val caPlan = Uuid.parse("40000000-0000-0000-0000-0000000000c1")
private val caOtherType = Uuid.parse("10000000-0000-0000-0000-0000000000c2")
private val caOtherPlan = Uuid.parse("40000000-0000-0000-0000-0000000000c2")
private const val CA_STALE = "Alguém alterou esta reserva. Os dados foram recarregados; confira e tente de novo."

@Suppress("LongParameterList") // fixture: cada campo que os ataques variam, com padrão
private fun caVersion(
    etag: String = "\"v1\"",
    status: String = "CONFIRMED",
    arrival: String = "2026-11-20",
    departure: String = "2026-11-23",
    adults: Int = 2,
    segment: String = "LEISURE",
    specialRequests: String? = "Andar alto",
) = ReservationVersion(
    ReservationDetailResponse(
        bookingCode = "B-1",
        holderGuestId = Uuid.random(),
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = adults,
                arrival = arrival,
                bookingId = Uuid.random(),
                cancellationTerms =
                    CancellationTermsResponse(
                        24,
                        "Flexível",
                        penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                        policyId = Uuid.random(),
                        version = 1,
                    ),
                childAges = emptyList(),
                code = "R-1",
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                departure = departure,
                folioId = Uuid.random(),
                guaranteeTerms =
                    GuaranteeTermsResponse(name = "Sem garantia", policyId = Uuid.random(), type = "NONE", version = 1),
                guests = emptyList(),
                id = caRes,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = caPlan,
                roomTotal = "720.00",
                roomTypeId = caType,
                segment = segment,
                specialRequests = Option.Some(specialRequests),
                status = status,
                total = "720.00",
                version = 3,
            ),
        restrictionAlerts = emptyList(),
    ),
    etag,
)

private val caOther =
    QuoteOfferResponse(
        accommodationTotal = "300.00",
        additionalPackagesTotal = "0.00",
        availableUnits = 2,
        cancellationPolicyId = caOtherPlan,
        category = QuoteOfferResponse.Category.NORMAL,
        guaranteePolicyId = caOtherPlan,
        includedPackagesTotal = "0.00",
        mealPlan = "BB",
        nights = emptyList(),
        ratePlanCode = "LUX",
        ratePlanId = caOtherPlan,
        ratePlanName = "Luxo",
        roomTotal = "300.00",
        roomTypeCode = "LUX",
        roomTypeId = caOtherType,
        roomTypeName = "Luxo",
        total = "300.00",
    )

/**
 * Ataques à `ReservationChangeState` (M3 E03): o `412` sob edição concorrente, o `412` na repetição aprovada, a
 * reserva que vira hospedada entre a leitura e a confirmação e o segmento desconhecido numa hospedada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReservationChangeStateAtaqueTest {
    private val id = caRes.toString()
    private var changes = 0

    private suspend fun approveAlways(
        action: ApprovableAction<ReservationDetailResponse>,
    ): ApiResponse<ReservationDetailResponse>? =
        try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            action.run("tok")
        }

    private fun source() =
        FakeReservationChangeSource().apply {
            versions[id] = caVersion()
            changeResponse = caVersion().reservation
            quote =
                QuoteResponse(
                    adults = 2,
                    arrival = "2026-11-20",
                    channel = "DIRECT",
                    childAges = emptyList(),
                    departure = "2026-11-24",
                    nights = 4,
                    offers = listOf(caOther),
                )
        }

    private fun TestScope.started(source: FakeReservationChangeSource): ReservationChangeState =
        ReservationChangeState(source, backgroundScope, id, true, ::approveAlways) { changes++ }.also {
            it.start()
            runCurrent()
        }

    private fun TestScope.confirmNow(state: ReservationChangeState) {
        state.confirm()
        runCurrent()
    }

    @Test
    fun editsAndConfirmDuringTheStaleReloadAreIgnored() =
        runTest {
            val source = source().apply { changeOutcomes += ApiError.PreconditionFailed(null) }
            val state = started(source)
            state.departureText = "24/11/2026"
            source.versions[id] = caVersion(etag = "\"v2\"", departure = "2026-11-25", segment = "EVENT")
            source.holdVersion = true

            confirmNow(state)
            // A recarga do 412 está em voo: nada do que o usuário fizer agora pode sobreviver a ela nem ir à rede.
            assertTrue(state.loading)
            assertFalse(state.canConfirm)
            state.departureText = "30/11/2026"
            state.segment = "BUSINESS"
            state.specialRequests = "digitado durante a recarga"
            state.confirm()
            runCurrent()
            assertEquals(1, source.changeCalls.size)

            source.heldVersions.forEach { it.complete(Unit) }
            runCurrent()
            assertEquals(CA_STALE, state.notice)
            assertEquals("25/11/2026", state.departureText)
            assertEquals("EVENT", state.segment)
            assertEquals("Andar alto", state.specialRequests)
            assertTrue(state.canConfirm)
        }

    @Test
    fun staleReloadThatTurnsInHouseLocksTheStayAndDropsTheOption() =
        runTest {
            val source = source().apply { changeOutcomes += ApiError.PreconditionFailed(null) }
            val state = started(source)
            state.departureText = "24/11/2026"
            state.loadOptions()
            runCurrent()
            state.choose(caOther)
            assertEquals(caOther, state.option)
            // Entre a leitura e a confirmação alguém fez o check-in.
            source.versions[id] =
                caVersion(etag = "\"v2\"", status = "IN_HOUSE", arrival = "2026-11-12", departure = "2026-11-15")

            confirmNow(state)
            assertEquals(CA_STALE, state.notice)
            assertTrue(state.inHouse)
            assertNull(state.option)
            assertTrue(state.options.isEmpty())

            state.arrivalText = "10/11/2026"
            state.adultsText = "4"
            state.loadOptions()
            runCurrent()
            state.choose(caOther)
            state.departureText = "16/11/2026"
            confirmNow(state)

            assertEquals("12/11/2026", state.arrivalText)
            assertEquals("2", state.adultsText)
            assertNull(state.option)
            assertEquals(1, source.quoteCalls.size, "a hospedada não cota")
            val sent = source.changeCalls.last()
            assertEquals("\"v2\"", sent.etag)
            assertEquals(BusinessDate.parse("2026-11-12"), sent.input.arrival)
            assertEquals(BusinessDate.parse("2026-11-16"), sent.input.departure)
            assertEquals(2, sent.input.adults)
            assertEquals(caType.toString(), sent.input.roomTypeId)
            assertEquals(caPlan.toString(), sent.input.ratePlanId)
        }

    @Test
    fun staleOnTheApprovedRunAlsoReloadsAndWarns() =
        runTest {
            val source =
                source().apply {
                    changeRequiresApproval = ApiError.ApprovalRequired("CHANGE", "p", "res", null, null)
                    changeOutcomes += ApiError.PreconditionFailed(null)
                }
            val state = started(source)
            state.departureText = "24/11/2026"
            source.versions[id] = caVersion(etag = "\"v2\"")

            confirmNow(state)

            assertEquals(listOf(null, "tok"), source.changeRuns.map { it.token })
            assertEquals(CA_STALE, state.notice)
            assertNull(state.error)
            assertNull(state.done)
            assertEquals("23/11/2026", state.departureText)
            assertEquals(0, changes)
            confirmNow(state)
            assertEquals(1, source.changeCalls.size, "depois do 412 a confirmação sem edição não vai à rede")
        }

    @Test
    fun inHouseWithUnknownSegmentAsksForItAndSendsOnlyTheDepartureChange() =
        runTest {
            val source =
                source().apply {
                    businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), null)
                    versions[id] =
                        caVersion(
                            status = "IN_HOUSE",
                            arrival = "2026-11-12",
                            departure = "2026-11-15",
                            segment = "GROUP",
                        )
                }
            val state = started(source)
            assertNull(state.segment)
            state.departureText = "17/11/2026"

            confirmNow(state)
            assertEquals("Escolha o segmento.", state.validation)
            assertTrue(source.changeCalls.isEmpty())

            state.segment = "BUSINESS"
            confirmNow(state)
            val sent = source.changeCalls.single().input
            assertEquals(BusinessDate.parse("2026-11-12"), sent.arrival)
            assertEquals(BusinessDate.parse("2026-11-17"), sent.departure)
            assertEquals("BUSINESS", sent.segment)
            assertEquals(emptyList(), sent.nightlyRates)
            assertNotNull(state.done)
        }

    @Test
    fun inHouseDepartureBeforeTheBusinessDateIsRefusedLocally() =
        runTest {
            val source =
                source().apply {
                    businessDay = BusinessDay(BusinessDate.parse("2026-11-14"), null)
                    versions[id] = caVersion(status = "IN_HOUSE", arrival = "2026-11-12", departure = "2026-11-16")
                }
            val state = started(source)
            // Depois da chegada (a validação da estadia passa), mas antes da data operacional.
            state.departureText = "13/11/2026"

            confirmNow(state)

            assertEquals("A saída precisa ser depois da data operacional (14/11/2026).", state.validation)
            assertTrue(source.changeCalls.isEmpty())
        }
}
