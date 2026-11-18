package com.zeny.desktop.app.reception

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AppController
import com.zeny.desktop.app.AppRoot
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.rooms.FakeRoomsSource
import com.zeny.desktop.app.rooms.RoomsSource
import com.zeny.desktop.app.rooms.roomId
import com.zeny.desktop.app.rooms.roomStatus
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val PANEL_DATE = "Data operacional: 13/11/2026"
private const val CHECKIN = "frontdesk.stays.checkin"
private const val GUESTS_MANAGE = "guests.profiles.manage"

/** O que o botão do walk-in pede (`WALK_IN_PERMISSIONS` da tela), sem o cadastro de hóspede. */
private val walkInPermissions =
    setOf(
        CHECKIN,
        "reservations.reservations.manage",
        "reservations.assignments.manage",
        "reservations.availability.read",
        "reservations.reservations.read",
        "rooms.status.read",
        "guests.profiles.read",
    )

private val holderUuid = Uuid.parse("30000000-0000-0000-0000-000000000001")
private val reservationUuid = Uuid.parse("20000000-0000-0000-0000-000000000001")

private fun arrival() =
    ReservationListItemResponse(
        adults = 2,
        arrival = "2026-11-13",
        arrivalOverdue = false,
        bookingCode = "B-1",
        bookingId = Uuid.random(),
        channel = "DIRECT",
        children = 0,
        code = "R-1",
        dayUse = false,
        departure = "2026-11-15",
        departureOverdue = false,
        folioId = Uuid.random(),
        holderGuestId = holderUuid,
        id = reservationUuid,
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = Uuid.random(),
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "CONFIRMED",
        total = "500.00",
        version = 1,
    )

/** Um falso pronto para o walk-in até o passo do hóspede: uma oferta e uma UH vaga e pronta do tipo dela. */
private fun receptionForWalkIn() =
    FakeReceptionSource().apply {
        businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
        stayQuote =
            QuoteResponse(
                adults = 1,
                arrival = "2026-11-13",
                channel = "DIRECT",
                childAges = emptyList(),
                departure = "2026-11-15",
                nights = 2,
                offers = listOf(walkInOffer()),
            )
        roomStatusList += roomStatus(roomId(101), "101").copy(readyForCheckIn = true)
    }

private fun walkInOffer() =
    QuoteOfferResponse(
        accommodationTotal = "300.00",
        additionalPackagesTotal = "0.00",
        availableUnits = 1,
        cancellationPolicyId = Uuid.random(),
        category = QuoteOfferResponse.Category.NORMAL,
        guaranteePolicyId = Uuid.random(),
        includedPackagesTotal = "0.00",
        mealPlan = "BREAKFAST",
        nights = emptyList(),
        ratePlanCode = "BAR",
        ratePlanId = Uuid.random(),
        ratePlanName = "Balcão",
        roomTotal = "300.00",
        roomTypeCode = "STD",
        roomTypeId = Uuid.parse(typeId(1)),
        roomTypeName = "Standard",
        total = "300.00",
    )

/** Um falso com uma chegada de titular fictício. */
private fun receptionWithArrival() =
    FakeReceptionSource().apply {
        pages[DayList.ARRIVALS to null] = Page(listOf(arrival()), null)
        holders[holderUuid.toString()] = HolderSummary("Ana Fictícia", null)
    }

/** O painel do dia ligado à casca: só a Recepção o abre, uma assinatura por sessão, fechada ao sair. */
@OptIn(ExperimentalTestApi::class)
class ReceptionShellTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    private class Fake(
        private val permissions: Set<String> = setOf(CHECKIN),
        private val reception: ReceptionSource? = FakeReceptionSource(),
        private val rooms: RoomsSource? = null,
        initial: SessionState = SessionState.LoggedOut,
    ) : ServerConnection {
        override val state = MutableStateFlow(initial)

        override suspend fun login(
            login: String,
            password: String,
        ) {
            state.value = SessionState.Active("u-1", "s-1", false)
        }

        override suspend fun changePassword(
            current: String,
            new: String,
        ) = error("não usado")

        override suspend fun retryPasswordChangeRefresh() = Unit

        override suspend fun logout() {
            state.value = SessionState.LoggedOut
        }

        // O menu segue o conjunto do teste; a Recepção, o que a porta falsa devolvia (ADR 0013: um `me` só).
        override suspend fun me(): Me =
            Me("u-1", "ana", "Ana Souza", permissions + (reception as? FakeReceptionSource)?.permissions.orEmpty())

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 3, 7))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun roomsSource(): RoomsSource? = rooms

        override fun receptionSource(): ReceptionSource? = reception

        override fun close() = Unit
    }

    /** A janela de mentira: o teste liga e desliga o foco. */
    private class Window : WindowInfo {
        var focused by mutableStateOf(true)
        override val isWindowFocused: Boolean get() = focused
    }

    private fun shell(
        fake: Fake,
        devMode: Boolean = false,
        after: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { AppController(Settings(), { fake }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        after()
    }

    private fun ComposeUiTest.openReception() {
        onNodeWithText("Recepção").performClick()
        waitForIdle()
    }

    @Test
    fun receptionAreaOpensPanel() {
        val reception = FakeReceptionSource()
        shell(Fake(reception = reception)) {
            onNodeWithText("Recepção (em breve)").assertDoesNotExist()
            onNodeWithText("Recepção").assertExists()
            onNodeWithText(PANEL_DATE).assertDoesNotExist()
            assertEquals(0, reception.subscriptions, "assinou antes de a área abrir")
            assertTrue(reception.dayPageCalls.isEmpty(), "leu as listas antes de a área abrir")

            openReception()

            onNodeWithText(PANEL_DATE).assertExists()
            onNodeWithTag("tab-ARRIVALS").assertExists()
            assertEquals(1, reception.subscriptions)
            assertEquals(1, reception.activeSubscriptions)
            assertEquals(listOf(setOf("rooms", "end-of-day")), reception.subscribedTopics)
            assertEquals(DayList.entries.toSet(), reception.dayPageCalls.map { it.list }.toSet())
        }
    }

    @Test
    fun subscriptionOnlyWhileAreaOpen() {
        val reception = FakeReceptionSource()
        val rooms = FakeRoomsSource()
        shell(Fake(permissions = setOf(CHECKIN, "rooms.status.read"), reception = reception, rooms = rooms)) {
            openReception()
            assertEquals(1, reception.activeSubscriptions)

            onNodeWithText("Governança").performClick()
            waitForIdle()

            onNodeWithText(PANEL_DATE).assertDoesNotExist()
            onNodeWithText("Mapa de UHs").assertExists()
            assertEquals(1, reception.subscriptions)
            assertEquals(0, reception.activeSubscriptions, "a assinatura da recepção seguiu viva fora da área")
            assertEquals(1, reception.cancelledSubscriptions)

            openReception()

            onNodeWithText(PANEL_DATE).assertExists()
            assertEquals(2, reception.subscriptions)
            assertEquals(1, reception.activeSubscriptions)
        }
    }

    @Test
    fun noPanelWithoutActiveSession() =
        runComposeUiTest {
            val reception = FakeReceptionSource()
            val fake = Fake(reception = reception, initial = SessionState.LoggedOut)
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, fake, null, reception.permissions) } }
            waitForIdle()

            onNodeWithText(PANEL_DATE).assertDoesNotExist()
            assertEquals(0, reception.subscriptions, "assinou sem sessão: ficaria para a próxima")
            assertEquals(0, reception.businessDayCalls)
        }

    @Test
    fun logoutClosesPanel() {
        val reception = FakeReceptionSource()
        shell(Fake(reception = reception)) {
            openReception()
            assertEquals(1, reception.activeSubscriptions)

            onNodeWithText("Sair").performClick()
            waitForIdle()

            onNodeWithText("Entrar").assertExists()
            onNodeWithText(PANEL_DATE).assertDoesNotExist()
            assertEquals(1, reception.subscriptions)
            assertEquals(0, reception.activeSubscriptions, "a assinatura seguiu viva depois do logout")
        }
    }

    @Test
    fun sessionSwitchRecreatesPanel() {
        val reception = receptionWithArrival()
        val fake = Fake(reception = reception)
        shell(fake) {
            openReception()
            onNodeWithText("Ana Fictícia").assertExists()
            assertEquals(listOf(holderUuid.toString()), reception.holderCalls)
            assertEquals(1, reception.subscriptions)

            fake.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()

            onNodeWithText(PANEL_DATE).assertExists()
            assertEquals(2, reception.subscriptions, "o painel não renasceu com a sessão nova")
            assertEquals(1, reception.activeSubscriptions, "a assinatura da sessão anterior seguiu viva")
            // O cache de titulares é do painel da sessão: o novo relê o titular.
            assertEquals(listOf(holderUuid.toString(), holderUuid.toString()), reception.holderCalls)
        }
    }

    @Test
    fun connectionWithoutReceptionShowsUnavailable() =
        shell(Fake(reception = null)) {
            openReception()

            onNodeWithText("Recepção indisponível nesta conexão.").assertExists()
            onNodeWithText(PANEL_DATE).assertDoesNotExist()
        }

    @Test
    fun areaHiddenWithoutPermission() {
        val reception = FakeReceptionSource()
        shell(Fake(permissions = setOf("guests.profiles.read"), reception = reception), devMode = true) {
            onNodeWithText("Hóspedes (em breve)").assertExists()
            onNodeWithText("Recepção", substring = true).assertDoesNotExist()
            assertEquals(0, reception.subscriptions)
            assertEquals(0, reception.businessDayCalls)
        }
    }

    @Test
    fun windowFocusRefreshesPanelAndDetail() =
        runComposeUiTest {
            val reception = receptionWithArrival()
            val fake = Fake(reception = reception, initial = SessionState.Active("u-1", "s-1", false))
            val window = Window()
            setContent {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    ZenyTheme { AreaContent(Area.RECEPTION, fake, "s-1", reception.permissions) }
                }
            }
            waitForIdle()
            onNodeWithTag("row-$reservationUuid").performClick()
            waitForIdle()
            assertEquals(listOf(reservationUuid.toString()), reception.reservationCalls)
            val pages = reception.dayPageCalls.size

            window.focused = false
            waitForIdle()

            assertEquals(pages, reception.dayPageCalls.size, "a perda do foco atualizou o painel")
            assertEquals(1, reception.reservationCalls.size, "a perda do foco releu o detalhe")

            window.focused = true
            waitForIdle()

            assertEquals(pages + DayList.entries.size, reception.dayPageCalls.size, "o ganho do foco não atualizou")
            assertEquals(2, reception.reservationCalls.size, "o ganho do foco não releu o detalhe")
        }

    @Test
    fun walkInGetsTheShellSet() {
        for (manage in listOf(true, false)) {
            // A porta da Recepção não tem o walk-in: o conjunto do diálogo vem só do `me` da casca.
            val reception = receptionForWalkIn().apply { permissions = emptySet() }
            val me = if (manage) walkInPermissions + GUESTS_MANAGE else walkInPermissions
            shell(Fake(permissions = me, reception = reception)) {
                openReception()
                onNodeWithTag("open-walk-in").performClick()
                waitForIdle()
                onNodeWithTag("walk-in-departure").performScrollTo().performTextInput("15/11/2026")
                for (tag in listOf("walk-in-quote", "offer-choose-0", "room-choose-0", "swap-guest")) {
                    onNodeWithTag(tag).performScrollTo().performClick()
                    waitForIdle()
                }

                onNodeWithTag("tab-guest-name").assertExists()
                if (manage) {
                    onNodeWithTag("guest-create").assertExists()
                } else {
                    onNodeWithTag("guest-create").assertDoesNotExist()
                }
            }
        }
    }
}
