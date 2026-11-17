package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AppController
import com.zeny.desktop.app.AppRoot
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TAB = "Disponibilidade e cotação"
private const val READ = "reservations.availability.read"
private const val DOCUMENT = "52998224725"
private const val HOLDER = "30000000-0000-0000-0000-000000000001"

/** A aba de disponibilidade e cotação na área Reservas: só com a permissão, aberta sob demanda, fechada ao sair. */
@OptIn(ExperimentalTestApi::class)
class ReservationsAvailabilityTabTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    /** As duas portas falsas devolvem o mesmo [permissions], como na produção (as permissões efetivas da sessão). */
    private class Fake(
        private val search: ReservationSearchSource,
        private val availability: FakeAvailabilitySource?,
        permissions: Set<String>,
    ) : ServerConnection {
        private val reception = FakeReceptionSource().apply { this.permissions = permissions }

        init {
            availability?.permissions = permissions
        }

        override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)

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

        override suspend fun me(): Me =
            Me("u-1", "ana", "Ana Souza", setOf("reservations.reservations.read", "frontdesk.stays.checkin"))

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 3, 7))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun receptionSource(): ReceptionSource? = reception

        override fun reservationSearchSource(): ReservationSearchSource = search

        override fun availabilitySource(): AvailabilitySource? = availability

        override fun close() = Unit
    }

    private fun search() =
        FakeReservationSearchSource().apply {
            reservations += searchItemOf(code = "R-1", holderGuestId = HOLDER)
            holders[HOLDER] = HolderSummary("Ana Fictícia", null)
            documentHolders["CPF" to DOCUMENT] = setOf(HOLDER)
        }

    private fun shell(
        fake: Fake,
        after: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { AppController(Settings(), { fake }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        after()
    }

    private fun ComposeUiTest.open(label: String) {
        onNodeWithText(label).performClick()
        waitForIdle()
    }

    @Test
    fun availabilityTabShowsCalendar() {
        val source = FakeAvailabilitySource()
        shell(Fake(search(), source, setOf(READ))) {
            open("Reservas")
            open(TAB)

            onNodeWithText("Recarregar").assertExists()
            onNodeWithTag("quote-submit").assertExists()
            assertEquals(1, source.availabilityCalls.size)
        }
    }

    @Test
    fun tabHiddenWithoutAvailabilityRead() {
        shell(Fake(search(), FakeAvailabilitySource(), emptySet())) {
            open("Reservas")

            onNodeWithText(TAB).assertDoesNotExist()
            onNodeWithText("Busca").assertDoesNotExist()
            onNodeWithText("R-1").assertExists()
        }
    }

    @Test
    fun availabilityNotLoadedUntilTabOpens() {
        val source = FakeAvailabilitySource()
        shell(Fake(search(), source, setOf(READ))) {
            open("Reservas")

            onNodeWithText(TAB).assertExists()
            assertEquals(0, source.availabilityCalls.size)

            open(TAB)
            assertEquals(1, source.availabilityCalls.size)
        }
    }

    @Test
    fun returningToSearchKeepsResults() {
        val search = search()
        shell(Fake(search, FakeAvailabilitySource(), setOf(READ))) {
            open("Reservas")
            onNodeWithText("R-1").assertExists()
            val searches = search.searchCalls.size

            open(TAB)
            open("Busca")

            onNodeWithText("R-1").assertExists()
            assertEquals(searches, search.searchCalls.size)
        }
    }

    @Test
    fun leavingAreaClosesBothStates() {
        val source = FakeAvailabilitySource()
        shell(Fake(search(), source, setOf(READ))) {
            open("Reservas")
            open(TAB)
            assertEquals(1, source.availabilityCalls.size)

            open("Recepção")
            open("Reservas")
            // Estado novo: a área volta à busca e só carrega ao abrir a aba de novo.
            assertEquals(1, source.availabilityCalls.size)
            open(TAB)
            assertEquals(2, source.availabilityCalls.size)
            assertEquals(2, source.businessDayCalls)
        }
    }

    @Test
    fun nullSourceHidesTab() {
        shell(Fake(search(), null, setOf(READ))) {
            open("Reservas")

            onNodeWithText(TAB).assertDoesNotExist()
            onNodeWithText("R-1").assertExists()
        }
    }
}
