package com.zeny.desktop.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.rooms.RoomsSource
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

/** A casca pertence à sessão vigente: outra sessão, sem passar pelo login, nunca mostra o usuário anterior. */
@OptIn(ExperimentalTestApi::class)
class AppRootSessionSwitchTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    /** O `me` responde pelo usuário da sessão corrente. */
    private class Fake : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)

        override suspend fun login(
            login: String,
            password: String,
        ) {
            state.value = SessionState.Active("ana", "s-1", false)
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
            when ((state.value as SessionState.Active).userId) {
                "ana" -> Me("ana", "ana", "Ana Souza", setOf("rooms.status.read"))
                else -> Me("bia", "bia", "Bia Lima", setOf("guests.profiles.read"))
            }

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 3, 7))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun roomsSource(): RoomsSource? = null

        override fun close() = Unit
    }

    @Test
    fun anotherSessionInsideShellShowsOnlyItsUser() =
        runComposeUiTest {
            val fake = Fake()
            setContent {
                val scope = rememberCoroutineScope()
                val controller = remember { AppController(Settings(), { fake }, { ProbeResult.Ok("1.0") }, scope) }
                ZenyTheme { AppRoot(controller, devMode = true) }
            }
            onNodeWithTag("login-user").performTextInput("ana")
            onNodeWithTag("login-password").performTextInput("segredo-123")
            onNodeWithText("Entrar").performClick()
            waitForIdle()
            onNodeWithText("Ana Souza").assertExists()
            onNodeWithText("Governança").assertExists()

            fake.state.value = SessionState.Active("bia", "s-9", false)
            waitForIdle()

            onNodeWithText("Bia Lima").assertExists()
            onNodeWithText("Ana Souza").assertDoesNotExist()
            onNodeWithText("Hóspedes (em breve)").assertExists()
            onNodeWithText("Governança", substring = true).assertDoesNotExist()
        }
}
