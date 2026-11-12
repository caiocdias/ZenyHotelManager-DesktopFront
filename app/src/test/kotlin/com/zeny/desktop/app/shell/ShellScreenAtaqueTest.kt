package com.zeny.desktop.app.shell

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ataques à tela da casca (Task 7): topo com texto estranho e mensagens de erro sem dado cru do servidor. */
@OptIn(ExperimentalTestApi::class)
class ShellScreenAtaqueTest {
    private class Fake(
        val hotel: HotelInfo = HotelInfo("Hotel Mar Azul", configured = true),
        val me: () -> Me = { Me("u-1", "ana", "Ana Souza", setOf("rooms.status.read")) },
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

        override suspend fun retryPasswordChangeRefresh() = error("não usado")

        override suspend fun logout() = error("não usado")

        override suspend fun me(): Me = me.invoke()

        override suspend fun hotel(): HotelInfo = hotel

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 3, 7))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun close() = Unit
    }

    private fun ComposeUiTest.shell(
        connection: Fake,
        onLogout: () -> Unit = {},
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val state = remember { ShellState(connection, devMode = true, scope = scope) }
            ZenyTheme { ShellScreen(state, onLogout) }
        }
        waitForIdle()
    }

    @Test
    fun veryLongHotelNameKeepsLogoutReachable() =
        runComposeUiTest {
            var logouts = 0
            val name = "Grande Hotel e Pousada Beira Mar das Águas Cristalinas ".repeat(12)
            shell(Fake(hotel = HotelInfo(name, configured = true)), onLogout = { logouts++ })

            onNodeWithText("Sair").assertIsDisplayed()
            onNodeWithText("Sair").performClick()
            assertEquals(1, logouts)
        }

    @Test
    fun hotelNameWithMarkupAndSymbolsIsShownAsPlainText() =
        runComposeUiTest {
            val name = "<b>Hotel</b> & \"Cia\" {x} 🏨 ‮abc"
            shell(Fake(hotel = HotelInfo(name, configured = true)))

            onNodeWithText(name).assertExists()
            onNodeWithText("Sair").assertIsDisplayed()
        }

    @Test
    fun serverProblemInMeShowsOnlyFriendlyMessageWithRetry() =
        runComposeUiTest {
            val raw = """{"code":"INTERNAL_BOOM","detail":"stack"}"""
            shell(Fake(me = { throw ApiError.Problem(500, "INTERNAL_BOOM", raw, emptyList()) }))

            onNodeWithText("INTERNAL_BOOM", substring = true).assertDoesNotExist()
            onNodeWithText("{", substring = true).assertDoesNotExist()
            onNodeWithText("Tentar de novo").assertExists()
            onNodeWithText("Sair").assertIsDisplayed()
        }
}
