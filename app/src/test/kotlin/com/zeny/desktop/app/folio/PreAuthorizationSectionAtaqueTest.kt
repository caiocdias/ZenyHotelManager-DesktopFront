package com.zeny.desktop.app.folio

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
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test

private const val READ = "billing.folios.read"
private const val OPERATE = "billing.cashier.operate"
private const val RECEIVE = "billing.payments.receive"
private const val EMPTY = "Nenhuma caução nesta conta."

/** Ataque à seção "Cauções" na tela: o texto vazio antes da primeira leitura e "Capturar" com capturável zero. */
@OptIn(ExperimentalTestApi::class)
class PreAuthorizationSectionAtaqueTest {
    private fun ComposeUiTest.accounts(payments: PaymentSource) {
        val connection = FolioConnection(FakeFolioSource(), cashier = FakeCashierSource(), payments = payments)
        setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, connection, "A", setOf(READ, OPERATE, RECEIVE)) } }
        waitForIdle()
        onNodeWithText("Contas").performClick()
        waitForIdle()
        onNodeWithTag("folio-row-$FOLIO_ID").performClick()
        waitForIdle()
    }

    @Test
    fun emptyTextWaitsForTheFirstReadOnScreen() =
        runComposeUiTest {
            val gate = CompletableDeferred<List<CardPreAuthorizationResponse>>()
            val payments = FakePaymentSource().apply { preAuthorizations = { gate.await() } }
            accounts(payments)
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("register-pre-authorization"))
            onNodeWithText("Cauções").assertExists()

            onNodeWithText(EMPTY).assertDoesNotExist()

            gate.complete(emptyList())
            waitForIdle()
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("register-pre-authorization"))
            onNodeWithText(EMPTY).assertExists()
        }

    @Test
    fun zeroCapturableShowsReleaseButNoCapture() =
        runComposeUiTest {
            val payments =
                FakePaymentSource().apply {
                    preAuthorizations = { listOf(preAuthorizationOf(status = "ACTIVE", capturable = "0.00")) }
                }
            accounts(payments)
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("release-0"))

            onNodeWithTag("release-0").assertExists()
            onNodeWithTag("capture-0").assertDoesNotExist()
        }
}
