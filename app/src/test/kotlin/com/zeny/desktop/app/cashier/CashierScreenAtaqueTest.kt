package com.zeny.desktop.app.cashier

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
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CashMovementResponse
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private const val OPERATE = "billing.cashier.operate"
private const val SHIFTS_READ = "billing.shifts.read"

/**
 * Ataque à tela do caixa: o seletor do fechamento cego, o `403` dos pontos, a troca de sessão e a forma
 * desconhecida.
 */
@OptIn(ExperimentalTestApi::class)
class CashierScreenAtaqueTest {
    private class Connection(
        private val cashier: CashierSource,
        initial: SessionState,
    ) : ServerConnection {
        override val state = MutableStateFlow(initial)

        override suspend fun login(
            login: String,
            password: String,
        ) = error("não usado")

        override suspend fun changePassword(
            current: String,
            new: String,
        ) = error("não usado")

        override suspend fun retryPasswordChangeRefresh() = Unit

        override suspend fun logout() = error("não usado")

        override suspend fun me(): Me = error("não usado")

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 11, 18))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun cashierSource(): CashierSource = cashier

        override fun close() = Unit
    }

    private fun ComposeUiTest.area(
        source: FakeCashierSource,
        permissions: Set<String> = setOf(OPERATE, SHIFTS_READ),
    ): Connection {
        val connection = Connection(source, SessionState.Active("u-1", "A", false))
        setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, connection, "A", permissions) } }
        waitForIdle()
        return connection
    }

    private fun inCloseDialog(text: String) =
        hasAnyAncestor(hasTestTag("close-dialog")) and hasText(text, substring = true)

    @Test
    fun closeDialogSelectorFindsTheDialogChildrenButNotThePanelBehind() =
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
            onNodeWithTag("cashier-close-shift").performScrollTo().performClick()
            waitForIdle()

            // Controle positivo: o seletor da `closeFormShowsNoExpectedValue` acha os filhos do diálogo...
            onAllNodes(inCloseDialog("Informe o valor contado")).assertCountEquals(1)
            onNodeWithTag("close-count-PIX").performTextInput("987,65")
            waitForIdle()
            onAllNodes(inCloseDialog("987")).assertCountEquals(1)
            // ...e não acha o painel atrás dele, onde o esperado visível continua.
            onNodeWithText("Total esperado: R$ 987,65").assertExists()
            onAllNodes(inCloseDialog("Total esperado")).assertCountEquals(0)
        }

    @Test
    fun forbiddenCashPointsOfferNoRetry() =
        runComposeUiTest {
            val source = FakeCashierSource()
            source.points = { throw ApiError.Problem(403, "FORBIDDEN", null, emptyList()) }
            area(source)

            onNodeWithText("Seu perfil não pode listar os pontos de caixa.").assertExists()
            onNodeWithTag("cashier-points-retry").assertDoesNotExist()
            onNodeWithText("Tentar de novo").assertDoesNotExist()
            assertEquals(1, source.cashPointsCalls)
        }

    @Test
    fun sessionSwitchWithAnOpenShiftDropsTheAreaAndTheMovementInFlight() =
        runComposeUiTest {
            val source = FakeCashierSource()
            source.current = { shiftOf() }
            val late = LateAnswer<CashMovementResponse>()
            source.recorder = { _, _, _ -> late.await() }
            val connection = area(source)
            onNodeWithTag("cashier-shift").assertExists()

            onNodeWithTag("cashier-withdrawal").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("movement-amount").performTextInput("50,00")
            onNodeWithTag("movement-reason").performTextInput("Cofre")
            onNodeWithTag("movement-submit").performClick()
            waitForIdle()
            val reads = source.currentCalls

            connection.state.value = SessionState.Active("u-2", "B", false)
            waitForIdle()

            onNodeWithTag("cashier-shift").assertDoesNotExist()
            onNodeWithTag("movement-dialog").assertDoesNotExist()

            late.answer(movementOf())
            waitForIdle()

            onNodeWithTag("cashier-shift").assertDoesNotExist()
            onNodeWithTag("movement-dialog").assertDoesNotExist()
            assertEquals(reads, source.currentCalls, "o movimento respondido depois da troca de sessão releu o turno")
            assertEquals(1, source.movementCalls.size)
        }

    @Test
    fun reportWithUnknownMethodShowsOtherAndTheRest() =
        runComposeUiTest {
            val source = FakeCashierSource()
            source.current = { shiftOf() }
            source.closer = {
                reportOf(
                    counts =
                        listOf(
                            countOf("VOUCHER", expected = "10.00", declared = "0.00", difference = "-10.00"),
                            countOf("PIX", expected = "50.00", declared = "50.00", difference = "0.00"),
                        ),
                    differenceTotal = "-10.00",
                )
            }
            area(source)

            onNodeWithTag("cashier-close-shift").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("close-submit").performClick()
            waitForIdle()

            onNodeWithText("Outro (VOUCHER)").assertExists()
            onNodeWithText("Pix").assertExists()
            onNodeWithText("Diferença: -R$ 10,00").assertExists()
            onNodeWithText("Diferença total: -R$ 10,00").assertExists()
        }
}
