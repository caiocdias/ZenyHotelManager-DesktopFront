package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.cashier.FakeCashierSource
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reservations.FakeReservationSearchSource
import com.zeny.desktop.app.reservations.confirmDetail
import com.zeny.desktop.app.reservations.searchItemOf
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.FolioResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val OPERATE = "billing.cashier.operate"
private const val RECEIVE = "billing.payments.receive"
private const val RESERVATIONS_READ = "reservations.reservations.read"
private val FIRST = Uuid.parse("20000000-0000-0000-0000-0000000000a1")
private const val HOLDER = "30000000-0000-0000-0000-0000000000a1"

/** "Pagamentos", "Receber" e o atalho "Abrir turno" nas três entradas da conta. */
@OptIn(ExperimentalTestApi::class)
class FolioPaymentsScreenTest {
    private fun shiftRequired() =
        FakePaymentSource().apply {
            receive = { throw ApiError.Problem(422, "CASHIER_SHIFT_REQUIRED", null, emptyList()) }
        }

    private fun reception() =
        FakeReceptionSource().apply {
            permissions = permissions + setOf(READ, RECEIVE, OPERATE)
            pages[DayList.ARRIVALS to null] = Page(listOf(searchItemOf("R-1").copy(id = FIRST)), null)
            reservations[FIRST.toString()] = confirmDetail(FIRST, "R-1", status = "CONFIRMED", guaranteeType = "NONE")
            holderError = ApiError.Problem(404, null, null, emptyList())
            ratePlanError = ApiError.Problem(404, null, null, emptyList())
        }

    private fun ComposeUiTest.accounts(
        folios: FolioSource,
        payments: PaymentSource?,
        permissions: Set<String>,
    ) {
        val connection = FolioConnection(folios, cashier = FakeCashierSource(), payments = payments)
        setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, connection, "A", permissions) } }
        waitForIdle()
        click("Contas", byText = true)
        click("folio-row-$FOLIO_ID")
    }

    private fun ComposeUiTest.click(
        target: String,
        byText: Boolean = false,
    ) {
        (if (byText) onNodeWithText(target) else onNodeWithTag(target)).performClick()
        waitForIdle()
    }

    /** Rola o painel da conta até [tag] e clica nele. */
    private fun ComposeUiTest.scrollAndClick(tag: String) {
        onNodeWithTag("folio-panel").performScrollToNode(hasTestTag(tag))
        click(tag)
    }

    /** "Receber" com o valor sugerido e "Abrir turno" depois da recusa do servidor. */
    private fun ComposeUiTest.receiveAndOpenShift() {
        scrollAndClick("receive-payment")
        click("payment-submit")
        onNodeWithText("É preciso um turno de caixa aberto.").assertExists()
        click("open-shift")
    }

    @Test
    fun receiveNeedsPermissionAndAnOpenFolio() {
        runComposeUiTest {
            accounts(FakeFolioSource(), FakePaymentSource(), setOf(READ, OPERATE))
            onNodeWithText("Pagamentos").assertExists()
            onNodeWithTag("receive-payment").assertDoesNotExist()
        }
        runComposeUiTest {
            accounts(FakeFolioSource(), FakePaymentSource(), setOf(READ, OPERATE, RECEIVE))
            onNodeWithTag("receive-payment").assertExists()
        }
        runComposeUiTest {
            val closed = FakeFolioSource().apply { folio = { folioOf(status = FolioResponse.Status.CLOSED) } }
            accounts(closed, FakePaymentSource(), setOf(READ, OPERATE, RECEIVE))
            onNodeWithText("Situação: Conta encerrada").assertExists()
            onNodeWithTag("receive-payment").assertDoesNotExist()
        }
    }

    @Test
    fun newPaymentShowsInTheTableAfterConfirm() =
        runComposeUiTest {
            val payments = FakePaymentSource()
            accounts(FakeFolioSource(), payments, setOf(READ, OPERATE, RECEIVE))
            onNodeWithText("Nenhum pagamento nesta conta.").assertExists()

            scrollAndClick("receive-payment")
            onNodeWithTag("payment-dialog").assertExists()
            payments.payments = { listOf(paymentOf(amount = "150.00", method = "CASH")) }
            click("payment-submit")

            onNodeWithTag("payment-dialog").assertDoesNotExist()
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("payment-0"))
            onNodeWithText("Dinheiro", substring = true).assertExists()
            onNodeWithText("Pagamento registrado.").assertExists()
            val amount =
                payments.receiveCalls
                    .single()
                    .input.amount
            assertEquals("150.00", amount.toApi(), "o saldo sugerido")
        }

    @Test
    fun openShiftInTheAccountsTabGoesToTheCashierTab() =
        runComposeUiTest {
            accounts(FakeFolioSource(), shiftRequired(), setOf(READ, OPERATE, RECEIVE))

            receiveAndOpenShift()

            onNodeWithText("Turno de caixa").assertExists()
            onNodeWithTag("payment-dialog").assertDoesNotExist()
            click("Contas", byText = true)
            onNodeWithTag("folio-panel").assertExists()
            onNodeWithTag("payment-dialog").assertDoesNotExist()
        }

    @Test
    fun withoutCashierOperateOnlyTheText() =
        runComposeUiTest {
            val desk = reception()
            val connection = FolioConnection(FakeFolioSource(), reception = desk, payments = shiftRequired())
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, connection, "A", desk.permissions - OPERATE) } }
            waitForIdle()
            click("row-$FIRST")
            click("open-folio")
            scrollAndClick("receive-payment")
            click("payment-submit")

            onNodeWithText("É preciso um turno de caixa aberto.").assertExists()
            onNodeWithTag("open-shift").assertDoesNotExist()
        }

    @Test
    fun openShiftFromReceptionGoesToTheAccountsArea() =
        runComposeUiTest {
            val desk = reception()
            var area by mutableStateOf(Area.RECEPTION)
            val connection =
                FolioConnection(
                    FakeFolioSource(),
                    cashier = FakeCashierSource(),
                    reception = desk,
                    payments = shiftRequired(),
                )
            setContent {
                ZenyTheme {
                    AreaContent(area, connection, "A", desk.permissions, onOpenShift = { area = Area.ACCOUNTS })
                }
            }
            waitForIdle()
            click("row-$FIRST")
            click("open-folio")

            receiveAndOpenShift()

            assertEquals(Area.ACCOUNTS, area)
            onNodeWithText("Turno de caixa").assertExists()
            onNodeWithTag("folio-dialog").assertDoesNotExist()
        }

    @Test
    fun openShiftFromReservationsGoesToTheAccountsArea() =
        runComposeUiTest {
            val search =
                FakeReservationSearchSource().apply {
                    reservations += searchItemOf("R-1", holderGuestId = HOLDER).copy(id = FIRST)
                    holders[HOLDER] = HolderSummary("Ana Fictícia", null)
                }
            var area by mutableStateOf(Area.RESERVATIONS)
            val connection =
                FolioConnection(
                    FakeFolioSource(),
                    cashier = FakeCashierSource(),
                    reception = reception(),
                    search = search,
                    payments = shiftRequired(),
                )
            val permissions = setOf(RESERVATIONS_READ, READ, RECEIVE, OPERATE)
            setContent {
                ZenyTheme { AreaContent(area, connection, "A", permissions, onOpenShift = { area = Area.ACCOUNTS }) }
            }
            waitForIdle()
            click("row-$FIRST")
            click("open-folio")

            receiveAndOpenShift()

            assertEquals(Area.ACCOUNTS, area)
            onNodeWithText("Turno de caixa").assertExists()
        }
}
