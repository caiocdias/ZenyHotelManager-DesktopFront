package com.zeny.desktop.app.folio

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.cashier.FakeCashierSource
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.theme.ZenyTheme
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

private const val READ = "billing.folios.read"
private const val OPERATE = "billing.cashier.operate"
private const val RECEIVE = "billing.payments.receive"

/** A seção "Cauções" do painel da conta: registrar e liberar na aba "Contas". */
@OptIn(ExperimentalTestApi::class)
class PreAuthorizationSectionTest {
    private fun ComposeUiTest.accounts(
        payments: PaymentSource?,
        permissions: Set<String> = setOf(READ, OPERATE, RECEIVE),
    ) {
        val connection = FolioConnection(FakeFolioSource(), cashier = FakeCashierSource(), payments = payments)
        setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, connection, "A", permissions) } }
        waitForIdle()
        onNodeWithText("Contas").performClick()
        waitForIdle()
        click("folio-row-$FOLIO_ID")
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** Rola o painel da conta até [tag] e clica nele. */
    private fun ComposeUiTest.scrollAndClick(tag: String) {
        onNodeWithTag("folio-panel").performScrollToNode(hasTestTag(tag))
        click(tag)
    }

    @Test
    fun sectionAppearsWithThePort() {
        runComposeUiTest {
            accounts(FakePaymentSource())
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("register-pre-authorization"))
            onNodeWithText("Cauções").assertExists()
            onNodeWithText("Nenhuma caução nesta conta.").assertExists()
        }
        runComposeUiTest {
            accounts(null)
            onNodeWithText("Cauções").assertDoesNotExist()
        }
    }

    @Test
    fun registerShowsTheNewRowInTheTable() =
        runComposeUiTest {
            val payments = FakePaymentSource()
            accounts(payments)
            // A data de amanhã no fuso do hotel do falso, para ficar dentro da validade com o relógio real.
            val tomorrow = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(1)

            scrollAndClick("register-pre-authorization")
            onNodeWithTag("pre-authorization-dialog").assertExists()
            onNodeWithTag("pre-authorization-amount").performTextInput("300,00")
            onNodeWithTag("pre-authorization-expires").performTextInput(DateInput.format(BusinessDate(tomorrow)))
            onNodeWithTag("pre-authorization-authorization_code").performTextInput("AUT123")
            payments.preAuthorizations = { listOf(preAuthorizationOf(cardBrand = "Visa", cardLast4 = "1234")) }
            click("pre-authorization-submit")

            onNodeWithTag("pre-authorization-dialog").assertDoesNotExist()
            assertEquals(
                "300.00",
                payments.registerCalls
                    .single()
                    .input.amount
                    .toApi(),
            )
            onNodeWithText("Caução registrada.").assertExists()
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("pre-authorization-row-0"))
            onNodeWithText("Ativa · Visa final 1234", substring = true).assertExists()
        }

    @Test
    fun releaseShowsTheReleasedStatus() =
        runComposeUiTest {
            val payments = FakePaymentSource().apply { preAuthorizations = { listOf(preAuthorizationOf()) } }
            accounts(payments)

            scrollAndClick("release-0")
            onNodeWithText("A sobra de R$ 300,00 será liberada na maquininha.").assertExists()
            payments.preAuthorizations = {
                listOf(preAuthorizationOf(status = "RELEASED", capturable = "0.00", released = "250.00"))
            }
            click("release-submit")

            onNodeWithTag("release-dialog").assertDoesNotExist()
            assertEquals(1, payments.releaseCalls.size)
            onNodeWithText("Sobra liberada.").assertExists()
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("pre-authorization-row-0"))
            onNodeWithText("Liberada", substring = true).assertExists()
            onNodeWithText("Liberado: R$ 250,00").assertExists()
            onNodeWithTag("release-0").assertDoesNotExist()
        }

    @Test
    fun noButtonsWithoutReceive() =
        runComposeUiTest {
            val payments = FakePaymentSource().apply { preAuthorizations = { listOf(preAuthorizationOf()) } }
            accounts(payments, setOf(READ, OPERATE))

            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("pre-authorization-row-0"))
            onNodeWithText("Cauções").assertExists()
            onNodeWithTag("register-pre-authorization").assertDoesNotExist()
            onNodeWithTag("release-0").assertDoesNotExist()
        }
}
