package com.zeny.desktop.app.folio

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.ApprovalHost
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.approval.LocalApprovalCoordinatorOrNull
import com.zeny.desktop.app.cashier.FakeCashierSource
import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.PaymentResponse
import kotlinx.coroutines.flow.emptyFlow
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val READ = "billing.folios.read"
private const val OPERATE = "billing.cashier.operate"
private const val RECEIVE = "billing.payments.receive"
private const val REFUND = "billing.payments.refund"
private const val LONG = 1001

/** Os campos do recebimento no cartão: os que `locked` tem de travar. */
private val PAYMENT_FIELDS =
    listOf(
        "payment-amount",
        "payment-deposit",
        "payment-card_brand",
        "payment-card_last4",
        "payment-nsu",
        "payment-authorization_code",
        "payment-method-CASH",
        "payment-method-PIX",
    )
private val REFUND_FIELDS = listOf("refund-amount", "refund-reason", "refund-nsu", "refund-authorization_code")

/**
 * Ataque da etapa aos diálogos (revisão final, Minor 8): os campos travados com `locked`, o reembolso concluído pelo
 * coordenador real até o `run(token)` e a colagem longa no NSU, na autorização e no motivo.
 */
@OptIn(ExperimentalTestApi::class)
class PaymentDialogsAtaqueTest {
    private val grants = mutableListOf<ApprovalInput>()

    /** A aba "Contas" com a conta aberta; com [withApprovals], com o coordenador real e o diálogo da casca. */
    private fun ComposeUiTest.accounts(
        payments: PaymentSource,
        withApprovals: Boolean = false,
    ) {
        val connection = FolioConnection(FakeFolioSource(), cashier = FakeCashierSource(), payments = payments)
        val permissions = setOf(READ, OPERATE, RECEIVE, REFUND)
        setContent {
            ZenyTheme {
                if (withApprovals) {
                    val scope = rememberCoroutineScope()
                    val approvals =
                        remember {
                            ApprovalCoordinator({ _, input ->
                                grants += input
                                "tok-gerente"
                            }, emptyFlow(), scope)
                        }
                    CompositionLocalProvider(LocalApprovalCoordinatorOrNull provides approvals) {
                        AreaContent(Area.ACCOUNTS, connection, "A", permissions)
                        ApprovalHost(approvals)
                    }
                } else {
                    AreaContent(Area.ACCOUNTS, connection, "A", permissions)
                }
            }
        }
        waitForIdle()
        onNodeWithText("Contas").performClick()
        waitForIdle()
        click("folio-row-$FOLIO_ID")
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.scrollAndClick(tag: String) {
        onNodeWithTag("folio-panel").performScrollToNode(hasTestTag(tag))
        click(tag)
    }

    private fun cardReceipt() =
        FakePaymentSource().apply {
            payments = { listOf(paymentOf(amount = "100.00", method = "CREDIT_CARD", refundable = "100.00")) }
        }

    @Test
    fun paymentFieldsAreDisabledWhileInFlightAndUnconfirmed() =
        runComposeUiTest {
            val late = LateAnswer<PaymentResponse>()
            val payments = FakePaymentSource().apply { receive = { late.await() } }
            accounts(payments)
            scrollAndClick("receive-payment")
            click("payment-method-CREDIT_CARD")
            onNodeWithTag("payment-amount").performTextClearance()
            onNodeWithTag("payment-amount").performTextInput("10,00")
            PAYMENT_FIELDS.forEach { onNodeWithTag(it).assertIsEnabled() }

            click("payment-submit")
            assertTrue(late.asked)
            PAYMENT_FIELDS.forEach { onNodeWithTag(it).assertIsNotEnabled() }

            late.fail(ApiError.Network(IOException("sem rede")))
            waitForIdle()
            PAYMENT_FIELDS.forEach { onNodeWithTag(it).assertIsNotEnabled() }
            assertEquals(1, payments.receiveCalls.size)
        }

    @Test
    fun refundFieldsAreDisabledWhileInFlightAndUnconfirmed() =
        runComposeUiTest {
            val late = LateAnswer<PaymentResponse>()
            val payments = cardReceipt().apply { refund = { late.await() } }
            accounts(payments)
            scrollAndClick("refund-0")
            onNodeWithTag("refund-reason").performTextInput("Cobrado em dobro")
            REFUND_FIELDS.forEach { onNodeWithTag(it).assertIsEnabled() }

            click("refund-submit")
            assertTrue(late.asked)
            REFUND_FIELDS.forEach { onNodeWithTag(it).assertIsNotEnabled() }

            late.fail(ApiError.Network(IOException("sem rede")))
            waitForIdle()
            REFUND_FIELDS.forEach { onNodeWithTag(it).assertIsNotEnabled() }
        }

    /** O coordenador real pede o Gerente, concede o token e a ação roda de novo com ele; o reembolso é concluído. */
    @Test
    fun refundCompletesThroughTheRealCoordinatorWithTheToken() =
        runComposeUiTest {
            val payments =
                cardReceipt().apply {
                    requiresApproval = ApiError.ApprovalRequired("PAYMENT_REFUND", REFUND, "payment", null, null)
                }
            accounts(payments, withApprovals = true)
            scrollAndClick("refund-0")
            onNodeWithTag("refund-reason").performTextInput("Cobrado em dobro")
            click("refund-submit")

            onNodeWithTag("approval-login").performTextInput("joana.gerente")
            onNodeWithTag("approval-pin").performTextInput("1234")
            onNodeWithTag("approval-reason").performTextInput("Cobrança duplicada")
            click("approval-confirm")

            assertEquals(listOf(null, "tok-gerente"), payments.refundRuns.map { it.token })
            assertEquals(1, payments.refundCalls.size, "a aprovação reaproveita a ação, sem outro pedido")
            assertEquals(listOf(ApprovalInput("joana.gerente", "1234", "Cobrança duplicada")), grants)
            onNodeWithTag("approval-login").assertDoesNotExist()
            onNodeWithTag("refund-dialog").assertDoesNotExist()
            onNodeWithText("Reembolso registrado.").assertExists()
        }

    /** Hoje: o NSU e a autorização longos entram no campo, são recusados na confirmação, com texto, e nunca saem. */
    @Test
    fun longPasteInPaymentCodesIsRefusedOnConfirmAndNeverSent() =
        runComposeUiTest {
            val payments = FakePaymentSource()
            accounts(payments)
            scrollAndClick("receive-payment")
            click("payment-method-PIX")
            onNodeWithTag("payment-amount").performTextClearance()
            onNodeWithTag("payment-amount").performTextInput("10,00")
            onNodeWithTag("payment-nsu").performTextInput("A".repeat(LONG))
            onNodeWithTag("payment-authorization_code").performTextInput("1".repeat(LONG))
            waitForIdle()
            click("payment-submit")

            assertTrue(payments.receiveCalls.isEmpty(), "o código longo não vai à rede")
            onNodeWithTag("payment-dialog").assertExists()
            onNodeWithText(FolioText.problem(PaymentProblem.NSU)).assertExists()
            onNodeWithText(FolioText.problem(PaymentProblem.AUTHORIZATION_CODE)).assertExists()
        }

    /** Hoje: o motivo longo é recusado em silêncio na edição (M8); o NSU longo, na confirmação, com texto. */
    @Test
    fun longPasteInRefundReasonAndCodesIsNeverSent() =
        runComposeUiTest {
            val payments = cardReceipt()
            accounts(payments)
            scrollAndClick("refund-0")
            onNodeWithTag("refund-reason").performTextInput("a".repeat(LONG))
            onNodeWithTag("refund-nsu").performTextInput("A".repeat(LONG))
            onNodeWithTag("refund-authorization_code").performTextInput("1".repeat(LONG))
            waitForIdle()
            onNodeWithTag("refund-reason").assertTextContains("0/200", substring = true)
            click("refund-submit")

            assertTrue(payments.refundCalls.isEmpty(), "o reembolso com o colado longo não vai à rede")
            onNodeWithTag("refund-dialog").assertExists()
            onNodeWithText(FolioText.problem(PaymentProblem.REASON)).assertExists()
            onNodeWithText(FolioText.problem(PaymentProblem.NSU)).assertExists()
            onNodeWithText(FolioText.problem(PaymentProblem.AUTHORIZATION_CODE)).assertExists()
        }
}
