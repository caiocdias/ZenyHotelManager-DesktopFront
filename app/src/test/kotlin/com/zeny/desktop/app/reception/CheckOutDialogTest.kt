package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.folio.FOLIO_ID
import com.zeny.desktop.app.folio.FakeFolioSource
import com.zeny.desktop.app.folio.FakePaymentSource
import com.zeny.desktop.app.folio.folioOf
import com.zeny.desktop.app.folio.preAuthorizationOf
import com.zeny.desktop.app.folio.wiringOf
import com.zeny.desktop.core.facade.CheckOutResult
import com.zeny.desktop.core.facade.LateCheckOutQuote
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test

private const val RESERVATION = "20000000-0000-0000-0000-0000000000e2"
private val PERMISSIONS = setOf("billing.folios.read", "billing.payments.receive")

/** A tela do assistente de check-out ([CheckOutDialog]). */
@OptIn(ExperimentalTestApi::class)
class CheckOutDialogTest {
    private fun ComposeUiTest.dialog(
        source: FakeCheckOutSource,
        folios: FakeFolioSource = FakeFolioSource(),
        payments: FakePaymentSource = FakePaymentSource(),
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val state =
                remember {
                    val wiring = wiringOf(folios, PERMISSIONS, payments = payments)
                    CheckOutState(source, wiring, scope, RESERVATION) { _, _ -> }
                }
            ZenyTheme { CheckOutDialog(state) }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** Rola o corpo do assistente até [tag] e clica nele. */
    private fun ComposeUiTest.scrollAndClick(tag: String) {
        onNodeWithTag("check-out-body").performScrollToNode(hasTestTag(tag))
        click(tag)
    }

    /** A linha (um `Row` com o texto e os botões) mostra [text]. */
    private fun row(text: String) = hasAnyDescendant(hasText(text))

    private fun owing() =
        FakeCheckOutSource().apply {
            checkOut = {
                val due = listOf(dueOf(FOLIO_ID.toString(), "F-0001", "100.00"))
                CheckOutResult.BalanceDue(due, Money.parse("100.00"))
            }
        }

    @Test
    fun pendingAndTotalShow() =
        runComposeUiTest {
            dialog(owing(), FakeFolioSource().apply { byReservation = { listOf(folioOf(balance = "150.00")) } })

            onNodeWithTag("check-out-pending-0").assert(row("Conta F-0001: Falta receber R$ 150,00"))
            click("check-out-conclude")

            onNodeWithTag("check-out-body").performScrollToNode(hasTestTag("check-out-total"))
            onNodeWithTag("check-out-due-0").assert(row("Conta F-0001: Falta receber R$ 100,00"))
            onNodeWithTag("check-out-total").assertTextEquals("Total devido: R$ 100,00")
        }

    @Test
    fun capturablePreAuthorizationDisablesConclude() =
        runComposeUiTest {
            val payments = FakePaymentSource().apply { preAuthorizations = { listOf(preAuthorizationOf()) } }

            dialog(FakeCheckOutSource(), payments = payments)

            onNodeWithTag("check-out-conclude").assertIsNotEnabled()
            onNodeWithTag("check-out-blocked").assertTextEquals("Capture ou libere a caução antes de concluir.")
        }

    @Test
    fun receiveOnTheDueOpensThePaymentWithTheAmount() =
        runComposeUiTest {
            dialog(owing())
            click("check-out-conclude")

            scrollAndClick("check-out-receive-0")

            onNodeWithTag("payment-dialog").assertExists()
            onNodeWithTag("check-out-dialog").assertDoesNotExist()
            onNodeWithTag("payment-amount").assertTextContains("R$ 100,00")
        }

    @Test
    fun lateShowsTheTimeAndTheFeeUnchecked() =
        runComposeUiTest {
            val source = FakeCheckOutSource().apply { quote = { LateCheckOutQuote("14:00", Money.parse("80.00")) } }

            dialog(source, FakeFolioSource().apply { byReservation = { listOf(folioOf(balance = "0.00")) } })

            onNodeWithTag("check-out-late-offer").assertTextEquals("Late check-out até 14:00: R$ 80,00")
            onNodeWithTag("check-out-charge-late").assertIsOff()
            onNodeWithTag("check-out-late-line").assertDoesNotExist()
            onNodeWithTag("check-out-conclude").assertIsEnabled()
            scrollAndClick("check-out-charge-late")
            onNodeWithTag("check-out-late-line").assertTextEquals("Late check-out: R$ 80,00")
        }
}
