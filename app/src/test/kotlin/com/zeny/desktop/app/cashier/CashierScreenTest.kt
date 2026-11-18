package com.zeny.desktop.app.cashier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.zeny.desktop.sdk.model.CashMovementResponse
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private const val OPERATE = "billing.cashier.operate"
private const val FOLIOS_READ = "billing.folios.read"
private const val SHIFTS_READ = "billing.shifts.read"

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
    fun cashPointsFailureOffersRetry() =
        runComposeUiTest {
            val source = FakeCashierSource()
            source.points = { throw ApiError.Network(RuntimeException("sem rede")) }
            val fake = Fake(source, initial = SessionState.Active("u-1", "A", false))
            setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, fake, "A", setOf(OPERATE)) } }
            waitForIdle()

            onNodeWithTag("cashier-points-error").assertExists()
            onNodeWithTag("cashier-point-$BAR_POINT").assertDoesNotExist()

            source.points = { listOf(cashPointOf(), cashPointOf(BAR_POINT, "Bar")) }
            onNodeWithTag("cashier-points-retry").performClick()
            waitForIdle()

            onNodeWithTag("cashier-points-error").assertDoesNotExist()
            onNodeWithTag("cashier-point-$BAR_POINT").assertExists()
            assertEquals(2, source.cashPointsCalls)
        }

    private fun ComposeUiTest.area(
        source: FakeCashierSource,
        permissions: Set<String> = setOf(OPERATE, SHIFTS_READ),
    ) {
        val fake = Fake(source, initial = SessionState.Active("u-1", "A", false))
        setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, fake, "A", permissions) } }
        waitForIdle()
    }

    /** Um botão da tela, que rola: rola até ele antes do clique. */
    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    /** Um botão do rodapé de um diálogo, que não rola. */
    private fun ComposeUiTest.clickInDialog(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    @Test
    fun withdrawalDialogRecordsAndTheMovementAppears() =
        runComposeUiTest {
            val source = FakeCashierSource()
            source.current = { shiftOf() }
            source.recorder = { _, amount, reason ->
                val recorded = movementOf(CashMovementResponse.Type.WITHDRAWAL, amount.toApi(), reason)
                source.current = { shiftOf(movements = listOf(recorded)) }
                recorded
            }
            area(source)
            onNodeWithText("Nenhum movimento no turno.").assertExists()

            click("cashier-withdrawal")
            onNodeWithTag("movement-dialog").assertExists()
            onNodeWithTag("movement-amount").performTextInput("50,00")
            onNodeWithTag("movement-reason").performTextInput("Depósito no cofre")
            clickInDialog("movement-submit")

            onNodeWithTag("movement-dialog").assertDoesNotExist()
            onNodeWithText("Sangria · R$ 50,00 · Depósito no cofre").assertExists()
            assertEquals(1, source.movementCalls.size)
        }

    @Test
    fun closeFormShowsNoExpectedValue() =
        runComposeUiTest {
            val source = FakeCashierSource()
            source.current = {
                shiftOf(
                    expectedVisible = true,
                    expected = listOf(expectedOf("CASH", "987.65")),
                    expectedTotal = "987.65",
                )
            }
            area(source)
            onNodeWithText("Total esperado: R$ 987,65").assertExists()

            click("cashier-close-shift")

            onNodeWithTag("close-dialog").assertExists()
            onNodeWithTag("close-count-CASH").assertExists()
            onNodeWithTag("close-count-BANK_TRANSFER").assertExists()
            onAllNodes(hasAnyAncestor(hasTestTag("close-dialog")) and hasText("987", substring = true))
                .assertCountEquals(0)
            onAllNodes(hasAnyAncestor(hasTestTag("close-dialog")) and hasText("sperado", substring = true))
                .assertCountEquals(0)
        }

    @Test
    fun reportShowsTheDifferenceInBrlPerMethod() =
        runComposeUiTest {
            val source = FakeCashierSource()
            source.current = { shiftOf() }
            source.closer = {
                reportOf(
                    counts =
                        listOf(
                            countOf("CASH", expected = "150.00", declared = "140.00", difference = "-10.00"),
                            countOf("PIX", expected = "50.00", declared = "50.00", difference = "0.00"),
                        ),
                    differenceTotal = "-10.00",
                )
            }
            area(source)

            click("cashier-close-shift")
            onNodeWithTag("close-count-CASH").performTextInput("140,00")
            onNodeWithTag("close-count-PIX").performTextInput("50,00")
            clickInDialog("close-submit")

            onNodeWithTag("close-dialog").assertDoesNotExist()
            onNodeWithTag("cashier-report").assertExists()
            onNodeWithText("Diferença: -R$ 10,00").assertExists()
            onNodeWithText("Diferença: R$ 0,00").assertExists()
            onNodeWithText("Diferença total: -R$ 10,00").assertExists()

            click("cashier-report-dismiss")
            onNodeWithTag("cashier-report").assertDoesNotExist()
            click("cashier-view-report")
            onNodeWithTag("cashier-report").assertExists()
            assertEquals(1, source.reportCalls.size)
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
