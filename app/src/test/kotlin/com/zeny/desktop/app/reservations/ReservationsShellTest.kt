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
import kotlin.test.assertTrue

private const val DAY = "Data operacional: 13/11/2026"
private const val DOCUMENT = "52998224725"
private const val HOLDER = "30000000-0000-0000-0000-000000000001"

/** A busca de reservas ligada à casca: só a área "Reservas" a abre, um estado por sessão, descartado ao sair. */
@OptIn(ExperimentalTestApi::class)
class ReservationsShellTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    private class Fake(
        private val search: ReservationSearchSource?,
        private val reception: ReceptionSource? = FakeReceptionSource(),
    ) : ServerConnection {
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

        override fun reservationSearchSource(): ReservationSearchSource? = search

        override fun close() = Unit
    }

    private fun source() =
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
    fun reservationsAreaOpensSearch() {
        val source = source()
        shell(Fake(source)) {
            onNodeWithText("Reservas (em breve)").assertDoesNotExist()
            assertEquals(0, source.businessDayCalls, "carregou antes de a área abrir")

            open("Reservas")

            onNodeWithText(DAY).assertExists()
            onNodeWithText("R-1").assertExists()
            onNodeWithText("Ana Fictícia").assertExists()
            assertEquals(1, source.businessDayCalls)
            assertEquals(1, source.searchCalls.size)
        }
    }

    @Test
    fun leavingAreaClosesStates() {
        val source = source()
        shell(Fake(source)) {
            open("Reservas")
            onNodeWithTag("document-number").performTextInput(DOCUMENT)
            onNodeWithTag("document-submit").performClick()
            waitForIdle()
            onNodeWithTag("searched-document").assertExists()
            val holderCalls = source.holderCalls.size

            open("Recepção")
            onNodeWithTag("document-number").assertDoesNotExist()
            open("Reservas")

            // Estado novo: sem documento mascarado, nova carga e titulares relidos (o cache morreu com o anterior).
            onNodeWithTag("searched-document").assertDoesNotExist()
            assertEquals(2, source.businessDayCalls)
            assertTrue(source.holderCalls.size > holderCalls, "o cache de titulares sobreviveu à saída da área")
        }
    }

    @Test
    fun sessionSwitchRecreatesState() {
        val source = source()
        val fake = Fake(source)
        shell(fake) {
            open("Reservas")
            assertEquals(1, source.businessDayCalls)
            assertEquals(1, source.holderCalls.size)

            fake.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()

            onNodeWithText(DAY).assertExists()
            assertEquals(2, source.businessDayCalls, "o estado não renasceu com a sessão nova")
            assertEquals(2, source.holderCalls.size, "o cache de titulares atravessou a sessão")
        }
    }

    @Test
    fun withoutSourceShowsUnavailable() {
        shell(Fake(search = null)) {
            open("Reservas")

            onNodeWithText("Reservas indisponíveis nesta conexão.").assertExists()
            onNodeWithText(DAY).assertDoesNotExist()
        }
    }
}
