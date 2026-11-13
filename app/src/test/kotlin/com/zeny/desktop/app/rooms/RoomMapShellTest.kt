package com.zeny.desktop.app.rooms

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
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

/** O mapa de UHs ligado à casca: só a Governança o abre, uma assinatura por sessão, fechada ao sair. */
@OptIn(ExperimentalTestApi::class)
class RoomMapShellTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    private class Fake(
        private val permissions: Set<String> = setOf("rooms.status.read"),
        private val rooms: RoomsSource? = FakeRoomsSource(),
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

        override fun close() = Unit
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

    private fun ComposeUiTest.openHousekeeping() {
        onNodeWithText("Governança").performClick()
        waitForIdle()
    }

    @Test
    fun housekeepingAreaOpensMap() {
        val rooms = FakeRoomsSource()
        shell(Fake(rooms = rooms)) {
            onNodeWithText("Governança (em breve)").assertDoesNotExist()
            onNodeWithText("Governança").assertExists()
            onNodeWithText("Mapa de UHs").assertDoesNotExist()
            assertEquals(0, rooms.subscriptions, "assinou antes de a área abrir")

            openHousekeeping()

            onNodeWithText("Mapa de UHs").assertExists()
            assertEquals(1, rooms.subscriptions)
            assertEquals(1, rooms.activeSubscriptions)
            assertEquals(listOf(setOf("rooms")), rooms.subscribedTopics)
        }
    }

    @Test
    fun streamOnlyWhileAreaOpen() =
        runComposeUiTest {
            val rooms = FakeRoomsSource()
            val fake = Fake(rooms = rooms, initial = SessionState.Active("u-1", "s-1", false))
            var area by mutableStateOf(Area.GUESTS)
            setContent { ZenyTheme { AreaContent(area, fake) } }
            waitForIdle()
            assertEquals(0, rooms.subscriptions, "outra área assinou o mapa")

            area = Area.HOUSEKEEPING
            waitForIdle()
            onNodeWithText("Mapa de UHs").assertExists()
            assertEquals(1, rooms.activeSubscriptions)

            area = Area.GUESTS
            waitForIdle()
            onNodeWithText("Mapa de UHs").assertDoesNotExist()
            assertEquals(1, rooms.subscriptions)
            assertEquals(0, rooms.activeSubscriptions, "a assinatura seguiu viva fora da área")
        }

    @Test
    fun noMapWithoutActiveSession() =
        runComposeUiTest {
            val rooms = FakeRoomsSource()
            val fake = Fake(rooms = rooms, initial = SessionState.LoggedOut)
            setContent { ZenyTheme { AreaContent(Area.HOUSEKEEPING, fake) } }
            waitForIdle()

            onNodeWithText("Mapa de UHs").assertDoesNotExist()
            assertEquals(0, rooms.subscriptions, "assinou sem sessão: ficaria para a próxima")
        }

    @Test
    fun logoutClosesMap() {
        val rooms = FakeRoomsSource()
        shell(Fake(rooms = rooms)) {
            openHousekeeping()
            assertEquals(1, rooms.activeSubscriptions)

            onNodeWithText("Sair").performClick()
            waitForIdle()

            onNodeWithText("Entrar").assertExists()
            assertEquals(1, rooms.subscriptions)
            assertEquals(0, rooms.activeSubscriptions, "a assinatura seguiu viva depois do logout")
        }
    }

    @Test
    fun sessionSwitchRecreatesMap() {
        val rooms = FakeRoomsSource()
        val fake = Fake(rooms = rooms)
        shell(fake) {
            openHousekeeping()
            assertEquals(1, rooms.subscriptions)

            fake.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()

            onNodeWithText("Mapa de UHs").assertExists()
            assertEquals(2, rooms.subscriptions, "o mapa não renasceu com a sessão nova")
            assertEquals(1, rooms.activeSubscriptions, "a assinatura da sessão anterior seguiu viva")
        }
    }

    @Test
    fun connectionWithoutRoomsShowsUnavailable() =
        shell(Fake(rooms = null)) {
            openHousekeeping()

            onNodeWithText("Mapa indisponível nesta conexão.").assertExists()
            onNodeWithText("Mapa de UHs").assertDoesNotExist()
        }

    @Test
    fun areaWithoutPermissionHidden() {
        val rooms = FakeRoomsSource()
        shell(Fake(permissions = setOf("guests.profiles.read"), rooms = rooms), devMode = true) {
            onNodeWithText("Hóspedes (em breve)").assertExists()
            onNodeWithText("Governança", substring = true).assertDoesNotExist()
            assertEquals(0, rooms.subscriptions)
        }
    }
}
