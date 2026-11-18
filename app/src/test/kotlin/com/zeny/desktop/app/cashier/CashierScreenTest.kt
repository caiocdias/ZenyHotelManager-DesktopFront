package com.zeny.desktop.app.cashier

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

private const val OPERATE = "billing.cashier.operate"
private const val FOLIOS_READ = "billing.folios.read"

/** A área "Contas e caixa" ligada à casca como o `AppRoot` a monta. */
@OptIn(ExperimentalTestApi::class)
class CashierScreenTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    private class Fake(
        private val cashier: CashierSource?,
        private val permissions: Set<String> = setOf(OPERATE, FOLIOS_READ),
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

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 11, 18))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun cashierSource(): CashierSource? = cashier

        override fun close() = Unit
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

    private fun ComposeUiTest.openArea() {
        onNodeWithText("Contas e caixa").performClick()
        waitForIdle()
    }

    @Test
    fun openingFromTheFormShowsThePanelAndTheHeader() {
        val source = FakeCashierSource()
        source.opener = { id, float ->
            val opened =
                shiftOf(number = "CX-0009", cashPointId = id, cashPointName = "Bar", openingFloat = float.toApi())
            source.current = { opened }
            opened
        }
        shell(Fake(source)) {
            onNodeWithText("Sem turno aberto").assertExists()
            openArea()

            onNodeWithTag("cashier-point-$BAR_POINT").performClick()
            onNodeWithTag("cashier-float").performTextInput("150,00")
            onNodeWithTag("cashier-open").performClick()
            waitForIdle()

            onNodeWithTag("cashier-shift").assertExists()
            onNodeWithText("Turno CX-0009").assertExists()
            onNodeWithText("R$ 150,00", substring = true).assertExists()
            onNodeWithText("Turno CX-0009 aberto · Bar").assertExists()
            onNodeWithText("Sem turno aberto").assertDoesNotExist()
            assertEquals(1, source.openCalls.size)
        }
    }

    @Test
    fun withoutOperateShowsTheNotice() {
        val source = FakeCashierSource()
        shell(Fake(source, permissions = setOf(FOLIOS_READ))) {
            openArea()

            onNodeWithText("Seu perfil não opera o caixa.").assertExists()
            onNodeWithTag("cashier-open").assertDoesNotExist()
            assertEquals(0, source.currentCalls)
        }
    }

    @Test
    fun withoutSourceShowsUnavailable() {
        shell(Fake(cashier = null)) {
            openArea()

            onNodeWithText("Caixa indisponível nesta conexão.").assertExists()
        }
    }

    @Test
    fun areaWithForeignSessionDrawsNothing() =
        runComposeUiTest {
            val source = FakeCashierSource()
            val fake = Fake(source, initial = SessionState.Active("u-1", "B", false))
            var sessionId by mutableStateOf("A")
            setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, fake, sessionId, setOf(OPERATE)) } }
            waitForIdle()

            onNodeWithTag("cashier-open").assertDoesNotExist()
            assertEquals(0, source.currentCalls, "a porta do caixa foi chamada com o conjunto de outra sessão")

            sessionId = "B"
            waitForIdle()

            onNodeWithTag("cashier-open").assertExists()
            assertEquals(1, source.currentCalls)
        }
}
