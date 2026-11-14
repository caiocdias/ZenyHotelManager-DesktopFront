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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AppController
import com.zeny.desktop.app.AppRoot
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.rooms.FakeRoomsSource
import com.zeny.desktop.app.rooms.RoomsSource
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val DATE_TEXT = "Data operacional: 13/11/2026"
private const val CHECKIN_PERM = "frontdesk.stays.checkin"
private const val CHECKOUT_PERM = "frontdesk.stays.checkout"

private val oldHolder = Uuid.parse("30000000-0000-0000-0000-0000000000a1")
private val newHolder = Uuid.parse("30000000-0000-0000-0000-0000000000b2")
private val oldReservation = Uuid.parse("20000000-0000-0000-0000-0000000000a1")
private val newReservation = Uuid.parse("20000000-0000-0000-0000-0000000000b2")

private fun arrivalOf(
    id: Uuid,
    holder: Uuid,
) = ReservationListItemResponse(
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
    holderGuestId = holder,
    id = id,
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

private fun fakeWithOld() =
    FakeReceptionSource().apply {
        pages[DayList.ARRIVALS to null] = Page(listOf(arrivalOf(oldReservation, oldHolder)), null)
        holders[oldHolder.toString()] = HolderSummary("Ana Fictícia", null)
        holders[newHolder.toString()] = HolderSummary("Bia Fictícia", null)
    }

/** Ataques à ligação da Recepção na casca: permissões, assinatura por sessão, foco e fechamento. */
@OptIn(ExperimentalTestApi::class)
class ReceptionShellAtaqueTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    private class Fake(
        private val permissions: Set<String> = setOf(CHECKIN_PERM),
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

        override suspend fun me(): Me = Me("u-1", "ana", "Ana Souza", permissions)

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

    private class Window(
        start: Boolean = true,
    ) : WindowInfo {
        var focused by mutableStateOf(start)
        override val isWindowFocused: Boolean get() = focused
    }

    private fun ComposeUiTest.login() {
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
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
        login()
        after()
    }

    @Test
    fun checkoutPermissionAloneShowsReceptionOutsideDevMode() {
        val reception = FakeReceptionSource()
        shell(Fake(permissions = setOf(CHECKOUT_PERM), reception = reception)) {
            onNodeWithText("Recepção").assertExists()
            onNodeWithText("Recepção (em breve)").assertDoesNotExist()
            onNodeWithText("Recepção").performClick()
            waitForIdle()
            onNodeWithText(DATE_TEXT).assertExists()
            assertEquals(1, reception.activeSubscriptions)
        }
    }

    @Test
    fun receptionAbsentOutsideDevModeWithoutFrontdeskPermission() {
        val reception = FakeReceptionSource()
        shell(Fake(permissions = setOf("rooms.status.read"), reception = reception, rooms = FakeRoomsSource())) {
            onNodeWithText("Governança").assertExists()
            onNodeWithText("Recepção", substring = true).assertDoesNotExist()
            assertEquals(0, reception.subscriptions)
        }
    }

    @Test
    fun receptionWithPermissionIsImplementedInDevMode() {
        shell(Fake(), devMode = true) {
            onNodeWithText("Recepção").assertExists()
            onNodeWithText("Recepção (em breve)").assertDoesNotExist()
        }
    }

    @Test
    fun loginAfterLogoutStartsFreshPanelWithEmptyHolderCache() {
        val reception = fakeWithOld()
        shell(Fake(reception = reception)) {
            onNodeWithText("Recepção").performClick()
            waitForIdle()
            onNodeWithText("Ana Fictícia").assertExists()
            onNodeWithText("Sair").performClick()
            waitForIdle()
            assertEquals(0, reception.activeSubscriptions)

            login()
            onNodeWithText("Recepção").performClick()
            waitForIdle()

            onNodeWithText("Ana Fictícia").assertExists()
            assertEquals(2, reception.subscriptions)
            assertEquals(1, reception.activeSubscriptions)
            assertEquals(listOf(oldHolder.toString(), oldHolder.toString()), reception.holderCalls)
        }
    }

    @Test
    fun sessionSwitchShowsNoRowFromPreviousSessionWhileLoading() {
        val reception = fakeWithOld()
        val fake = Fake(reception = reception)
        shell(fake) {
            onNodeWithText("Recepção").performClick()
            waitForIdle()
            onNodeWithTag("row-$oldReservation").assertExists()
            reception.holdDayPage = true
            reception.pages[DayList.ARRIVALS to null] = Page(listOf(arrivalOf(newReservation, newHolder)), null)

            fake.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()

            onNodeWithTag("row-$oldReservation").assertDoesNotExist()
            onNodeWithText("Ana Fictícia").assertDoesNotExist()

            reception.holdDayPage = false
            reception.heldDayPages.forEach { it.complete(Unit) }
            waitForIdle()

            onNodeWithTag("row-$newReservation").assertExists()
            onNodeWithTag("row-$oldReservation").assertDoesNotExist()
            onNodeWithText("Bia Fictícia").assertExists()
            assertEquals(1, reception.activeSubscriptions)
        }
    }

    @Test
    fun sessionSwitchClosesOpenDetail() {
        val reception = fakeWithOld()
        val fake = Fake(reception = reception)
        shell(fake) {
            onNodeWithText("Recepção").performClick()
            waitForIdle()
            onNodeWithTag("row-$oldReservation").performClick()
            waitForIdle()
            val reads = reception.reservationCalls.size
            assertEquals(1, reads)

            fake.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()

            // Detalhe novo: nada aberto, então nenhuma leitura de reserva na sessão nova.
            assertEquals(reads, reception.reservationCalls.size)
        }
    }

    @Test
    fun focusEventsDoNothingWithoutFocusChange() =
        runComposeUiTest {
            val reception = fakeWithOld()
            val fake = Fake(reception = reception, initial = SessionState.Active("u-1", "s-1", false))
            val window = Window(start = true)
            setContent {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    ZenyTheme { AreaContent(Area.RECEPTION, fake) }
                }
            }
            waitForIdle()

            // Primeira composição focada: só a carga inicial das três listas, nenhuma releitura de reserva.
            assertEquals(DayList.entries.size, reception.dayPageCalls.size)
            assertTrue(reception.reservationCalls.isEmpty())
            val pages = reception.dayPageCalls.size

            window.focused = true
            waitForIdle()

            assertEquals(pages, reception.dayPageCalls.size, "foco repetido atualizou")

            // Ganho de foco sem reserva aberta: atualiza o painel e não lê reserva nenhuma.
            window.focused = false
            waitForIdle()
            window.focused = true
            waitForIdle()

            assertEquals(pages + DayList.entries.size, reception.dayPageCalls.size)
            assertTrue(reception.reservationCalls.isEmpty(), "leu reserva sem detalhe aberto")
        }

    @Test
    fun windowStartingUnfocusedRefreshesOnlyOnGain() =
        runComposeUiTest {
            val reception = fakeWithOld()
            val fake = Fake(reception = reception, initial = SessionState.Active("u-1", "s-1", false))
            val window = Window(start = false)
            setContent {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    ZenyTheme { AreaContent(Area.RECEPTION, fake) }
                }
            }
            waitForIdle()
            val pages = reception.dayPageCalls.size

            window.focused = true
            waitForIdle()

            assertEquals(pages + DayList.entries.size, reception.dayPageCalls.size, "o ganho de foco não atualizou")
        }

    @Test
    fun leavingAreaStopsFocusReadsAndDropsOpenDetail() =
        runComposeUiTest {
            val reception = fakeWithOld()
            val fake = Fake(reception = reception, initial = SessionState.Active("u-1", "s-1", false))
            val window = Window()
            var area by mutableStateOf(Area.RECEPTION)
            setContent {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    ZenyTheme { AreaContent(area, fake) }
                }
            }
            waitForIdle()
            onNodeWithTag("row-$oldReservation").performClick()
            waitForIdle()
            assertEquals(1, reception.reservationCalls.size)

            area = Area.RESERVATIONS
            waitForIdle()
            assertEquals(0, reception.activeSubscriptions)
            val pages = reception.dayPageCalls.size

            window.focused = false
            waitForIdle()
            window.focused = true
            waitForIdle()

            assertEquals(pages, reception.dayPageCalls.size, "a recepção fechada leu as listas com o foco")
            assertEquals(1, reception.reservationCalls.size, "a recepção fechada releu a reserva com o foco")

            area = Area.RECEPTION
            waitForIdle()

            // Estado novo: nenhum detalhe aberto, nenhuma releitura da reserva da visita anterior.
            assertEquals(1, reception.reservationCalls.size)
            assertEquals(2, reception.subscriptions)
            assertEquals(1, reception.activeSubscriptions)
        }

    @Test
    fun unavailableReceptionDoesNotSubscribeAndNotMapMessage() {
        shell(Fake(reception = null)) {
            onNodeWithText("Recepção").performClick()
            waitForIdle()
            onNodeWithText("Recepção indisponível nesta conexão.").assertExists()
            onNodeWithText("Mapa indisponível nesta conexão.").assertDoesNotExist()
        }
    }
}
