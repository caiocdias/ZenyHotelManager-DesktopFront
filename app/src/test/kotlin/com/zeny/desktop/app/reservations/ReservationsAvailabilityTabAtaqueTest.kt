package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val HOLDER_ID = "30000000-0000-0000-0000-000000000001"

private fun tabDetailFor(item: ReservationListItemResponse) =
    ReservationDetailResponse(
        bookingCode = item.bookingCode,
        holderGuestId = item.holderGuestId,
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = 2,
                arrival = item.arrival,
                bookingId = item.bookingId,
                cancellationTerms =
                    CancellationTermsResponse(
                        24,
                        "Flexível",
                        penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                        policyId = Uuid.random(),
                        version = 1,
                    ),
                childAges = emptyList(),
                code = item.code,
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                departure = item.departure,
                folioId = item.folioId,
                guaranteeTerms =
                    GuaranteeTermsResponse(
                        name = "Sem garantia",
                        policyId = Uuid.random(),
                        type = "NONE",
                        version = 1,
                    ),
                guests = emptyList(),
                id = item.id,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = item.ratePlanId,
                roomTotal = "300.00",
                roomTypeId = Uuid.parse(typeId(1)),
                segment = "LEISURE",
                status = item.status,
                total = "300.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

private const val TAB = "Disponibilidade e cotação"
private const val READ = "reservations.availability.read"
private val planId = Uuid.parse("20000000-0000-0000-0000-000000000001")

private fun offerOf() =
    QuoteOfferResponse(
        accommodationTotal = "655.37",
        additionalPackagesTotal = "0.00",
        availableUnits = 2,
        cancellationPolicyId = planId,
        category = QuoteOfferResponse.Category.NORMAL,
        guaranteePolicyId = planId,
        includedPackagesTotal = "0.00",
        mealPlan = "BREAKFAST",
        nights = emptyList(),
        ratePlanCode = "FLEX",
        ratePlanId = planId,
        ratePlanName = "Flexível",
        roomTotal = "655.37",
        roomTypeCode = "STD",
        roomTypeId = planId,
        roomTypeName = "Standard",
        total = "655.37",
    )

/** Ataques à casca da aba de disponibilidade: permissão, ciclo de vida por sessão e troca de aba. */
@OptIn(ExperimentalTestApi::class)
class ReservationsAvailabilityTabAtaqueTest {
    private class Conn(
        private val search: ReservationSearchSource?,
        private val reception: ReceptionSource?,
        private val availability: AvailabilitySource?,
    ) : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.Active("u-1", "s-1", false))

        override suspend fun login(
            login: String,
            password: String,
        ) = error("não usado")

        override suspend fun changePassword(
            current: String,
            new: String,
        ) = error("não usado")

        override suspend fun retryPasswordChangeRefresh() = Unit

        override suspend fun logout() {
            state.value = SessionState.LoggedOut
        }

        override suspend fun me(): Me = Me("u-1", "ana", "Ana Souza", emptySet())

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 3, 7))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun receptionSource(): ReceptionSource? = reception

        override fun reservationSearchSource(): ReservationSearchSource? = search

        override fun availabilitySource(): AvailabilitySource? = availability

        override fun close() = Unit
    }

    private fun search(): FakeReservationSearchSource =
        FakeReservationSearchSource().apply {
            reservations += searchItemOf(code = "R-1", holderGuestId = HOLDER_ID)
            holders[HOLDER_ID] = HolderSummary("Ana Fictícia", null)
        }

    private fun receptionFor(search: FakeReservationSearchSource) =
        FakeReceptionSource().apply {
            search.reservations.forEach { item ->
                reservations[item.id.toString()] = tabDetailFor(item)
                holders[item.holderGuestId.toString()] = HolderSummary("Ana Fictícia", null)
                histories[item.id.toString()] = emptyList()
                ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
            }
        }

    private fun availability(permissions: Set<String> = setOf(READ)) =
        FakeAvailabilitySource().apply { this.permissions = permissions }

    private fun ComposeUiTest.area(conn: Conn) {
        setContent { ZenyTheme { AreaContent(Area.RESERVATIONS, conn) } }
        waitForIdle()
    }

    private fun ComposeUiTest.click(label: String) {
        onNodeWithText(label).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.quote() {
        onNodeWithTag("quote-arrival").performTextInput("16/11/2026")
        onNodeWithTag("quote-departure").performTextInput("18/11/2026")
        click("Cotar")
    }

    private fun ComposeUiTest.assertSearchOnly() {
        onNodeWithText(TAB).assertDoesNotExist()
        onNodeWithText("Busca").assertDoesNotExist()
        onNodeWithText("R-1").assertExists()
    }

    @Test
    fun tabHiddenWhilePermissionsNotRead() =
        runComposeUiTest {
            val source = availability()
            val gate = source.hold(FakeAvailabilitySource.Op.PERMISSIONS)
            area(Conn(search(), null, source))

            assertSearchOnly()
            assertEquals(0, source.availabilityCalls.size)
            gate.complete(Unit)
            waitForIdle()
            onNodeWithText(TAB).assertExists()
            onNodeWithText("Busca").assertExists()
            assertEquals(0, source.availabilityCalls.size, "carregou o calendário sem a aba aberta")
        }

    @Test
    fun tabHiddenWhenPermissionsFail() =
        runComposeUiTest {
            val source = availability()
            source.failures[FakeAvailabilitySource.Op.PERMISSIONS] = IllegalStateException("falhou")
            area(Conn(search(), null, source))

            assertSearchOnly()
        }

    @Test
    fun unrelatedPermissionDoesNotShowTab() =
        runComposeUiTest {
            area(Conn(search(), null, availability(setOf("reservations.availability.write"))))

            assertSearchOnly()
        }

    @Test
    fun reopeningTabDoesNotReloadCalendar() =
        runComposeUiTest {
            val source = availability()
            area(Conn(search(), null, source))

            repeat(3) {
                click(TAB)
                click("Busca")
            }
            click(TAB)

            assertEquals(1, source.availabilityCalls.size)
            assertEquals(1, source.businessDayCalls)
        }

    @Test
    fun returningToSearchKeepsResultAndOpenDetailWithoutCallingSources() =
        runComposeUiTest {
            val search = search()
            val reception = receptionFor(search)
            area(Conn(search, reception, availability()))
            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertExists()
            val searches = search.searchCalls.size
            val reads = reception.reservationCalls.size

            click(TAB)
            click("Busca")

            assertTrue(onAllNodesWithText("R-1").fetchSemanticsNodes().isNotEmpty())
            onNodeWithTag("reservation-detail").assertExists()
            assertEquals(searches, search.searchCalls.size)
            assertEquals(reads, reception.reservationCalls.size)
        }

    @Test
    fun returningToAvailabilityKeepsTheQuote() =
        runComposeUiTest {
            val source = availability()
            source.quote = source.quote.copy(offers = listOf(offerOf()))
            area(Conn(search(), null, source))
            click(TAB)
            quote()
            onNodeWithTag("quote-offer-0").assertExists()
            val quotes = source.quoteCalls.size

            click("Busca")
            click(TAB)

            onNodeWithTag("quote-offer-0").assertExists()
            assertEquals(quotes, source.quoteCalls.size)
            assertEquals(1, source.availabilityCalls.size)
        }

    @Test
    fun lateQuoteAfterLeavingAreaIsDropped() =
        runComposeUiTest {
            val source = availability()
            source.quote = source.quote.copy(offers = listOf(offerOf()))
            val gate = source.hold(FakeAvailabilitySource.Op.QUOTE)
            val conn = Conn(search(), null, source)
            var shown by mutableStateOf(true)
            setContent { ZenyTheme { if (shown) AreaContent(Area.RESERVATIONS, conn) } }
            waitForIdle()
            click(TAB)
            quote()
            assertEquals(1, source.quoteCalls.size)

            shown = false
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()
            shown = true
            waitForIdle()
            click(TAB)

            onNodeWithTag("quote-offer-0").assertDoesNotExist()
            assertEquals(1, source.quoteCalls.size)
        }

    @Test
    fun lateCalendarAfterLeavingAreaIsDropped() =
        runComposeUiTest {
            val source = availability()
            val gate = source.hold(FakeAvailabilitySource.Op.AVAILABILITY)
            val conn = Conn(search(), null, source)
            var shown by mutableStateOf(true)
            setContent { ZenyTheme { if (shown) AreaContent(Area.RESERVATIONS, conn) } }
            waitForIdle()
            click(TAB)
            assertEquals(1, source.availabilityCalls.size)

            shown = false
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()

            assertEquals(1, source.availabilityCalls.size)
            assertEquals(1, source.businessDayCalls)
        }

    @Test
    fun newSessionWithPermissionBuildsFreshStates() =
        runComposeUiTest {
            val source = availability()
            val conn = Conn(search(), null, source)
            area(conn)
            click(TAB)
            assertEquals(1, source.availabilityCalls.size)

            conn.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()
            if (onAllNodesWithText(TAB).fetchSemanticsNodes().isNotEmpty()) click(TAB)

            assertEquals(2, source.availabilityCalls.size)
        }
}
