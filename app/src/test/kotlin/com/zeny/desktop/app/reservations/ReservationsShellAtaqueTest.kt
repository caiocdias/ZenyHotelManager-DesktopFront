package com.zeny.desktop.app.reservations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
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
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val DOC = "52998224725"
private const val DOC2 = "11144477735"
private const val H2 = "30000000-0000-0000-0000-000000000002"
private const val HOLDER_ID = "30000000-0000-0000-0000-000000000001"

private fun shellDetailFor(item: ReservationListItemResponse) =
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

private fun SemanticsNodeInteraction.assertFieldEmpty() {
    val text =
        fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()
    assertEquals("", text, "o campo guardou o valor da sessão anterior")
}

/** Ataques ao isolamento entre sessões e ao foco da área Reservas, pela `AreaContent` com sessão controlada. */
@OptIn(ExperimentalTestApi::class)
class ReservationsShellAtaqueTest {
    private class Window : WindowInfo {
        var focused by mutableStateOf(true)
        override val isWindowFocused: Boolean get() = focused
    }

    private class Conn(
        private val search: ReservationSearchSource?,
        private val reception: ReceptionSource?,
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

        override fun close() = Unit
    }

    private fun search(): FakeReservationSearchSource =
        FakeReservationSearchSource().apply {
            reservations += searchItemOf(code = "R-1", holderGuestId = HOLDER_ID)
            holders[HOLDER_ID] = HolderSummary("Ana Fictícia", null)
            documentHolders["CPF" to DOC] = setOf(HOLDER_ID)
        }

    private fun receptionFor(search: FakeReservationSearchSource) =
        FakeReceptionSource().apply {
            search.reservations.forEach { item ->
                reservations[item.id.toString()] = shellDetailFor(item)
                holders[item.holderGuestId.toString()] = HolderSummary("Ana Fictícia", null)
                histories[item.id.toString()] = emptyList()
                ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
            }
        }

    private fun ComposeUiTest.area(
        conn: Conn,
        window: Window = Window(),
    ) {
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                ZenyTheme { AreaContent(Area.RESERVATIONS, conn) }
            }
        }
        waitForIdle()
    }

    @Test
    fun windowFocusRereadsOpenReservationWithoutRepeatingSearch() =
        runComposeUiTest {
            val search = search()
            val reception = receptionFor(search)
            val window = Window()
            area(Conn(search, reception), window)
            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertExists()
            assertEquals(1, reception.reservationCalls.size)
            val searches = search.searchCalls.size
            val days = search.businessDayCalls

            window.focused = false
            waitForIdle()
            assertEquals(1, reception.reservationCalls.size, "a perda do foco releu o detalhe")

            window.focused = true
            waitForIdle()

            assertEquals(2, reception.reservationCalls.size, "o ganho do foco não releu a reserva aberta")
            assertEquals(searches, search.searchCalls.size, "o foco refez a busca")
            assertEquals(days, search.businessDayCalls, "o foco refez a carga inicial")
        }

    @Test
    fun windowFocusWithNothingOpenReadsNothing() =
        runComposeUiTest {
            val search = search()
            val reception = receptionFor(search)
            val window = Window()
            area(Conn(search, reception), window)

            window.focused = false
            waitForIdle()
            window.focused = true
            waitForIdle()

            assertTrue(reception.reservationCalls.isEmpty(), "leu reserva sem nenhuma aberta")
            assertEquals(1, search.searchCalls.size)
        }

    @Test
    fun withoutReceptionSourceScreenWorksWithoutPanelAndFocusIsHarmless() =
        runComposeUiTest {
            val search = search()
            val window = Window()
            area(Conn(search, reception = null), window)

            onNodeWithText("R-1").assertExists()
            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertDoesNotExist()
            window.focused = false
            waitForIdle()
            window.focused = true
            waitForIdle()
            onNodeWithText("R-1").assertExists()
            onNodeWithTag("document-number").assertExists()
        }

    @Test
    fun logoutAndLoginOfAnotherSessionBringsNothingFromThePreviousOne() =
        runComposeUiTest {
            val search = search()
            val reception = receptionFor(search)
            val conn = Conn(search, reception)
            area(conn)
            onNodeWithTag("search-name").performTextInput("Ana")
            onNodeWithTag("document-number").performTextInput(DOC)
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            onNodeWithTag("searched-document").assertExists()
            onNodeWithText("R-1").performClick()
            waitForIdle()
            onNodeWithTag("reservation-detail").assertExists()

            conn.state.value = SessionState.LoggedOut
            waitForIdle()
            onNodeWithTag("document-number").assertDoesNotExist()
            onNodeWithTag("searched-document").assertDoesNotExist()
            onNodeWithTag("reservation-detail").assertDoesNotExist()

            conn.state.value = SessionState.Active("u-2", "s-2", false)
            waitForIdle()

            onNodeWithTag("searched-document").assertDoesNotExist()
            onNodeWithTag("reservation-detail").assertDoesNotExist()
            onNodeWithTag("search-name").assertFieldEmpty()
            onNodeWithTag("document-number").assertFieldEmpty()
            onNodeWithText("R-1").assertExists()
        }

    @Test
    fun resultInFlightFromBeforeSessionSwitchDoesNotLandInTheNewSession() =
        runComposeUiTest {
            val search = search()
            val reception = receptionFor(search)
            val conn = Conn(search, reception)
            area(conn)
            val gate = search.hold(FakeReservationSearchSource.Op.SEARCH_BY_DOCUMENT)
            onNodeWithTag("document-number").performTextInput(DOC)
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            assertEquals(1, search.documentCalls.size)

            conn.state.value = SessionState.Active("u-2", "s-2", false)
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()

            onNodeWithTag("searched-document").assertDoesNotExist()
            onNodeWithText("R-1").assertExists()
            onNodeWithTag("document-number").assertFieldEmpty()
        }

    @Test
    fun lateHolderFromBeforeSessionSwitchDoesNotBreakTheNewSession() =
        runComposeUiTest {
            val search = search()
            val reception = receptionFor(search)
            val conn = Conn(search, reception)
            val gate = search.hold(FakeReservationSearchSource.Op.HOLDER)
            area(conn)

            conn.state.value = SessionState.Active("u-2", "s-2", false)
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()

            onNodeWithText("R-1").assertExists()
            onNodeWithText("Ana Fictícia").assertExists()
            assertEquals(2, search.holderCalls.size, "uma leitura de titular por sessão, sem repetição")
            assertEquals(2, search.businessDayCalls)
        }

    private fun twoHolderSource() =
        search().apply {
            reservations +=
                searchItemOf(code = "R-2", arrival = "2026-01-10", departure = "2026-01-12", holderGuestId = H2)
            holders[H2] = HolderSummary("Bia Fictícia", null)
            documentHolders["CPF" to DOC2] = setOf(H2)
        }

    private fun ComposeUiTest.submitHeldDocumentSearch(source: FakeReservationSearchSource) =
        source.hold(FakeReservationSearchSource.Op.SEARCH_BY_DOCUMENT).also {
            onNodeWithTag("document-number").performTextInput(DOC2)
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            assertEquals(1, source.documentCalls.size)
        }

    @Test
    fun sessionSwitchCancelsInFlightDocumentSearch() =
        runComposeUiTest {
            val search = twoHolderSource()
            val conn = Conn(search, receptionFor(search))
            area(conn)
            val gate = submitHeldDocumentSearch(search)

            conn.state.value = SessionState.Active("u-2", "s-2", false)
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()

            assertTrue(H2 !in search.holderCalls, "a consulta da sessão anterior seguiu viva depois do close()")
        }

    @Test
    fun leavingAreaCancelsInFlightDocumentSearch() =
        runComposeUiTest {
            val search = twoHolderSource()
            val conn = Conn(search, receptionFor(search))
            var current by mutableStateOf(Area.RESERVATIONS)
            setContent {
                CompositionLocalProvider(LocalWindowInfo provides Window()) {
                    ZenyTheme { AreaContent(current, conn) }
                }
            }
            waitForIdle()
            val gate = submitHeldDocumentSearch(search)

            current = Area.GUESTS
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()

            assertTrue(H2 !in search.holderCalls, "a consulta seguiu viva depois de sair da área")
        }

    @Test
    fun sessionSwitchCancelsInFlightDetailLoad() =
        runComposeUiTest {
            val search = search()
            val reception = receptionFor(search)
            val conn = Conn(search, reception)
            area(conn)
            reception.holdReservation = true
            onNodeWithText("R-1").performClick()
            waitForIdle()
            assertEquals(1, reception.reservationCalls.size)

            conn.state.value = SessionState.Active("u-2", "s-2", false)
            waitForIdle()
            reception.heldReservations.forEach { it.second.complete(Unit) }
            waitForIdle()

            assertTrue(reception.holderCalls.isEmpty(), "o detalhe da sessão anterior seguiu vivo depois do close()")
        }

    @Test
    fun leavingAreaCancelsInFlightDetailLoad() =
        runComposeUiTest {
            val search = search()
            val reception = receptionFor(search)
            val conn = Conn(search, reception)
            var current by mutableStateOf(Area.RESERVATIONS)
            setContent {
                CompositionLocalProvider(LocalWindowInfo provides Window()) {
                    ZenyTheme { AreaContent(current, conn) }
                }
            }
            waitForIdle()
            reception.holdReservation = true
            onNodeWithText("R-1").performClick()
            waitForIdle()

            current = Area.GUESTS
            waitForIdle()
            reception.heldReservations.forEach { it.second.complete(Unit) }
            waitForIdle()

            assertTrue(reception.holderCalls.isEmpty(), "o detalhe seguiu vivo depois de sair da área")
        }

    @Test
    fun openDetailOfPreviousSessionIsNotReloadedOnFocusInTheNewOne() =
        runComposeUiTest {
            val search = search()
            val reception = receptionFor(search)
            val conn = Conn(search, reception)
            val window = Window()
            area(conn, window)
            onNodeWithText("R-1").performClick()
            waitForIdle()
            assertEquals(1, reception.reservationCalls.size)

            conn.state.value = SessionState.Active("u-2", "s-2", false)
            waitForIdle()
            window.focused = false
            waitForIdle()
            window.focused = true
            waitForIdle()

            assertEquals(1, reception.reservationCalls.size, "o foco leu a reserva da sessão anterior")
            onNodeWithTag("reservation-detail").assertDoesNotExist()
        }
}
