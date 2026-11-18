package com.zeny.desktop.app.shell

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.cashier.CashierSource
import com.zeny.desktop.app.cashier.FakeCashierSource
import com.zeny.desktop.app.cashier.shiftOf
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
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class ShellScreenUiTest {
    private class FakeConnection(
        var me: () -> Me = { Me("u-1", "ana", "Ana Souza", setOf("rooms.status.read", "guests.profiles.read")) },
        private val cashier: CashierSource? = null,
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

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 3, 7))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun cashierSource(): CashierSource? = cashier

        override fun close() = Unit
    }

    private fun ComposeUiTest.shell(
        connection: FakeConnection,
        devMode: Boolean,
        onLogout: () -> Unit = {},
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val state = remember { ShellState(connection, devMode, scope) }
            ZenyTheme { ShellScreen(state, onLogout) }
        }
        waitForIdle()
    }

    @Test
    fun headerShowsHotelBusinessDateUserAndLogout() =
        runComposeUiTest {
            var logouts = 0
            shell(FakeConnection(), devMode = false, onLogout = { logouts++ })

            onNodeWithText("Hotel Mar Azul").assertExists()
            onNodeWithText("Data operacional: 07/03/2026").assertExists()
            onNodeWithText("Ana Souza").assertExists()
            onNodeWithText("Sair").performClick()
            assertEquals(1, logouts)
        }

    @Test
    fun devModeListsMappedAreasAsComingSoon() =
        runComposeUiTest {
            shell(FakeConnection(), devMode = true)

            onNodeWithText("Hóspedes (em breve)").assertExists()
            onNodeWithText("Governança").assertExists()
            onNodeWithText("Recepção (em breve)").assertDoesNotExist()
            onNodeWithText("Selecione uma área no menu.").assertExists()
        }

    @Test
    fun releaseHidesAreasWithoutScreen() =
        runComposeUiTest {
            shell(FakeConnection(), devMode = false)

            onNodeWithText("Hóspedes", substring = true).assertDoesNotExist()
            onNodeWithText("Governança").assertExists()
            onNodeWithText("Selecione uma área no menu.").assertExists()
        }

    @Test
    fun meErrorShowsMessageRetryAndKeepsLogout() =
        runComposeUiTest {
            val connection = FakeConnection(me = { throw ApiError.Network(RuntimeException("sem rede")) })
            var logouts = 0
            shell(connection, devMode = false, onLogout = { logouts++ })

            onNodeWithText(ApiError.Network(RuntimeException()).userMessage).assertExists()
            onNodeWithText("Sair").performClick()
            assertEquals(1, logouts)

            connection.me = { Me("u-1", "ana", "Ana Souza", emptySet()) }
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()

            onNodeWithText("Tentar de novo").assertDoesNotExist()
            onNodeWithText("Ana Souza").assertExists()
        }

    @Test
    fun shiftTextForEachIndicator() {
        assertEquals("Turno CX-0001 aberto · Recepção", shiftText(ShiftIndicator.Open("CX-0001", "Recepção")))
        assertEquals("Sem turno aberto", shiftText(ShiftIndicator.None))
        assertEquals("Turno não verificado", shiftText(ShiftIndicator.Unknown))
        assertNull(shiftText(ShiftIndicator.Hidden))
    }

    @Test
    fun headerShowsTheShiftOfTheOperator() =
        runComposeUiTest {
            val cashier = FakeCashierSource()
            cashier.current = { shiftOf(number = "CX-0002", cashPointName = "Bar") }
            val operator = { Me("u-1", "ana", "Ana Souza", setOf("rooms.status.read", "billing.cashier.operate")) }
            shell(FakeConnection(me = operator, cashier = cashier), devMode = false)

            onNodeWithText("Turno CX-0002 aberto · Bar").assertExists()
        }

    @Test
    fun headerSaysUnverifiedWhenTheShiftReadFails() =
        runComposeUiTest {
            val cashier = FakeCashierSource()
            cashier.current = { throw ApiError.Network(RuntimeException("sem rede")) }
            val operator = { Me("u-1", "ana", "Ana Souza", setOf("rooms.status.read", "billing.cashier.operate")) }
            shell(FakeConnection(me = operator, cashier = cashier), devMode = false)

            onNodeWithText("Turno não verificado").assertExists()
            onNodeWithText("Governança").assertExists()
            onNodeWithText("Tentar de novo").assertDoesNotExist()
        }

    @Test
    fun headerHidesTheShiftWithoutOperate() =
        runComposeUiTest {
            val cashier = FakeCashierSource()
            cashier.current = { shiftOf() }
            shell(FakeConnection(cashier = cashier), devMode = false)

            onNodeWithText("Turno", substring = true).assertDoesNotExist()
            assertEquals(0, cashier.currentCalls)
        }
}
