package com.zeny.desktop.app.folio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.ApprovalHost
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.approval.LocalApprovalCoordinatorOrNull
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
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val OPERATE = "billing.cashier.operate"
private const val RECEIVE = "billing.payments.receive"
private const val REFUND = "billing.payments.refund"
private const val RESERVATIONS_READ = "reservations.reservations.read"
private val FIRST = Uuid.parse("20000000-0000-0000-0000-0000000000a1")
private const val HOLDER = "30000000-0000-0000-0000-0000000000a1"

/** "Pagamentos", "Receber", "Reembolsar" e o atalho "Abrir turno" nas três entradas da conta. */
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

    /** Um recebimento com reembolsável, um reembolso e um recebimento já todo reembolsado, nessa ordem. */
    private fun refundable() =
        FakePaymentSource().apply {
            payments = {
                listOf(
                    paymentOf(amount = "100.00", refunded = "20.00", refundable = "80.00"),
                    paymentOf(amount = "20.00", type = "REFUND"),
                    paymentOf(amount = "50.00", refunded = "50.00", refundable = "0.00"),
                )
            }
        }

    private fun refundApproval() = ApiError.ApprovalRequired("PAYMENT_REFUND", REFUND, "payment", null, null)

    private fun ComposeUiTest.accounts(
        folios: FolioSource,
        payments: PaymentSource?,
        permissions: Set<String>,
        withApprovals: Boolean = false,
    ) {
        val connection = FolioConnection(folios, cashier = FakeCashierSource(), payments = payments)
        content(withApprovals) { AreaContent(Area.ACCOUNTS, connection, "A", permissions) }
        click("Contas", byText = true)
        click("folio-row-$FOLIO_ID")
    }

    /** O conteúdo no tema; com [withApprovals], com o coordenador da casca e o diálogo dele, como o `AppRoot`. */
    private fun ComposeUiTest.content(
        withApprovals: Boolean,
        content: @Composable () -> Unit,
    ) {
        setContent {
            ZenyTheme {
                if (withApprovals) {
                    val scope = rememberCoroutineScope()
                    val approvals = remember { ApprovalCoordinator({ _, _ -> "tok" }, emptyFlow(), scope) }
                    CompositionLocalProvider(LocalApprovalCoordinatorOrNull provides approvals) {
                        content()
                        ApprovalHost(approvals)
                    }
                } else {
                    content()
                }
            }
        }
        waitForIdle()
    }

    /** "Reembolsar" o recebimento da linha [index] com o valor sugerido e um motivo. */
    private fun ComposeUiTest.refund(index: Int) {
        scrollAndClick("refund-$index")
        onNodeWithTag("refund-reason").performTextInput("Cobrado em dobro")
        click("refund-submit")
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

    @Test
    fun refundOnlyOnAReceiptWithRefundableWithPermissionAndAnOpenFolio() {
        runComposeUiTest {
            accounts(FakeFolioSource(), refundable(), setOf(READ, OPERATE, RECEIVE))
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("payment-2"))
            onNodeWithTag("refund-0").assertExists()
            onNodeWithTag("refund-1").assertDoesNotExist()
            onNodeWithTag("refund-2").assertDoesNotExist()
            onNodeWithText("Reembolsado R$ 20,00").assertExists()
        }
        runComposeUiTest {
            accounts(FakeFolioSource(), refundable(), setOf(READ, OPERATE, REFUND))
            onNodeWithTag("receive-payment").assertDoesNotExist()
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("refund-0"))
            onNodeWithTag("refund-0").assertExists()
        }
        runComposeUiTest {
            accounts(FakeFolioSource(), refundable(), setOf(READ, OPERATE))
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("payment-0"))
            onNodeWithTag("refund-0").assertDoesNotExist()
        }
        runComposeUiTest {
            val closed = FakeFolioSource().apply { folio = { folioOf(status = FolioResponse.Status.CLOSED) } }
            accounts(closed, refundable(), setOf(READ, OPERATE, RECEIVE, REFUND))
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("payment-0"))
            onNodeWithTag("refund-0").assertDoesNotExist()
        }
    }

    @Test
    fun refundDialogSuggestsTheRefundableAndHidesCodesInCash() =
        runComposeUiTest {
            val payments = refundable()
            accounts(FakeFolioSource(), payments, setOf(READ, OPERATE, RECEIVE))

            scrollAndClick("refund-0")

            onNodeWithTag("refund-dialog").assertExists()
            onNodeWithText("Reembolsável: R$ 80,00").assertExists()
            onNodeWithTag("refund-nsu").assertDoesNotExist()
            onNodeWithTag("refund-authorization_code").assertDoesNotExist()
            onNodeWithTag("refund-reason").performTextInput("Cobrado em dobro")
            click("refund-submit")

            onNodeWithTag("refund-dialog").assertDoesNotExist()
            onNodeWithText("Reembolso registrado.").assertExists()
            assertEquals(
                "80.00",
                payments.refundCalls
                    .single()
                    .input.amount
                    .toApi(),
            )
        }

    @Test
    fun refundApprovalOpensTheShellDialogFromTheAccountsTab() =
        runComposeUiTest {
            val payments = refundable().apply { requiresApproval = refundApproval() }
            accounts(FakeFolioSource(), payments, setOf(READ, OPERATE, RECEIVE), withApprovals = true)

            refund(0)

            onNodeWithTag("approval-login").assertExists()
            assertEquals(listOf<String?>(null), payments.refundRuns.map { it.token })
        }

    @Test
    fun refundApprovalOpensTheShellDialogFromReception() =
        runComposeUiTest {
            val desk = reception()
            val payments = refundable().apply { requiresApproval = refundApproval() }
            val connection = FolioConnection(FakeFolioSource(), reception = desk, payments = payments)
            content(withApprovals = true) { AreaContent(Area.RECEPTION, connection, "A", desk.permissions) }
            click("row-$FIRST")
            click("open-folio")

            refund(0)

            onNodeWithTag("approval-login").assertExists()
            assertEquals(listOf<String?>(null), payments.refundRuns.map { it.token })
        }

    @Test
    fun openShiftAfterRefundInTheAccountsTabGoesToTheCashierTab() =
        runComposeUiTest {
            val payments =
                refundable().apply {
                    refundOutcomes += ApiError.Problem(422, "CASHIER_SHIFT_REQUIRED", null, emptyList())
                }
            accounts(FakeFolioSource(), payments, setOf(READ, OPERATE, RECEIVE))

            refund(0)
            onNodeWithText("É preciso um turno de caixa aberto.").assertExists()
            click("open-shift")

            onNodeWithText("Turno de caixa").assertExists()
            onNodeWithTag("refund-dialog").assertDoesNotExist()
            click("Contas", byText = true)
            onNodeWithTag("folio-panel").assertExists()
            onNodeWithTag("refund-dialog").assertDoesNotExist()
        }
}
