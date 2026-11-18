package com.zeny.desktop.app.shell

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
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reservations.FakeReservationSearchSource
import com.zeny.desktop.app.reservations.ReservationSearchSource
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.traces.FakeTracesSource
import com.zeny.desktop.app.traces.TracesSource
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

private val everything =
    setOf(
        "frontdesk.stays.checkin",
        "frontdesk.traces.read",
        "frontdesk.traces.manage",
        "frontdesk.traces.resolve",
        "reservations.reservations.read",
        "reservations.reservations.manage",
        "reservations.availability.read",
        "rooms.status.read",
        "endofday.runs.read",
        "guests.profiles.read",
    )

/** Uma leitura do `me` por sessão (ADR 0013): a casca lê, e as áreas só recebem o conjunto. */
@OptIn(ExperimentalTestApi::class)
class ShellMeReadCountTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    /** A conexão que conta as leituras do `me`, com as portas da Recepção, dos Recados e da busca de reservas. */
    private class Conn : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
        var meCalls = 0
        private val reception = FakeReceptionSource()
        private val traces = FakeTracesSource()
        private val search = FakeReservationSearchSource()

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

        override suspend fun me(): Me {
            meCalls++
            return Me("u-1", "ana", "Ana Souza", everything)
        }

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 11, 13))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun receptionSource(): ReceptionSource = reception

        override fun tracesSource(): TracesSource = traces

        override fun reservationSearchSource(): ReservationSearchSource = search

        override fun close() = Unit
    }

    private fun ComposeUiTest.click(text: String) {
        onNodeWithText(text).performClick()
        waitForIdle()
    }

    @Test
    fun oneMeReadAcrossAreas() =
        runComposeUiTest {
            val conn = Conn()
            setContent {
                val scope = rememberCoroutineScope()
                val controller = remember { AppController(Settings(), { conn }, { ProbeResult.Ok("1.0") }, scope) }
                ZenyTheme { AppRoot(controller, devMode = false) }
            }
            onNodeWithTag("login-user").performTextInput("ana")
            onNodeWithTag("login-password").performTextInput("segredo-123")
            click("Entrar")
            assertEquals(1, conn.meCalls, "a casca não leu o me uma vez")

            click("Recepção")
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            onNodeWithTag("tab-TRACES").assertExists()
            click("Reservas")
            click("Recepção")
            onNodeWithTag("tab-TRACES").assertExists()

            assertEquals(1, conn.meCalls, "uma área leu o me por conta própria")
        }
}
