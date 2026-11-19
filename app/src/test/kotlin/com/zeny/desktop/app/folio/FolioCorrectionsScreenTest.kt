package com.zeny.desktop.app.folio

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.PostingResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val CREATE = "billing.postings.create"
private const val REMOVE = "billing.servicecharge.remove"
private val ITEM = Uuid.parse("50000000-0000-0000-0000-0000000000b1")
private val CHARGE = Uuid.parse("50000000-0000-0000-0000-0000000000b2")
private val ADJUSTMENT = Uuid.parse("50000000-0000-0000-0000-0000000000b3")

/** "Ajustar" e "Remover a taxa" no menu da linha e o diálogo de ajuste, no painel real da conta. */
@OptIn(ExperimentalTestApi::class)
class FolioCorrectionsScreenTest {
    private val item = postingOf(id = ITEM, description = "Frigobar", amount = "20.00").withRemaining("20.00")
    private val charge =
        postingOf(id = CHARGE, description = "Taxa de serviço", amount = "2.00", type = "SERVICE_CHARGE")
            .copy(serviceChargeForPostingId = Option.Some(ITEM))
            .withRemaining("2.00")
    private val adjustment =
        postingOf(id = ADJUSTMENT, description = "Ajuste", amount = "-5.00", type = "ADJUSTMENT").correcting(ITEM)

    private fun ComposeUiTest.panel(
        corrections: FakeFolioCorrectionSource,
        postings: List<PostingResponse> = listOf(item, charge, adjustment),
    ) {
        val source = FakeFolioSource().apply { this.postings = { postings } }
        setContent {
            val scope = rememberCoroutineScope()
            val state =
                remember {
                    FolioState(correctionWiringOf(source, corrections, setOf(READ, CREATE, REMOVE)), scope, "$FOLIO_ID")
                        .also { it.start() }
                }
            DisposableEffect(state) { onDispose { state.close() } }
            ZenyTheme { FolioPanel(state) }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.scrollAndClick(tag: String) {
        onNodeWithTag("folio-panel").performScrollToNode(hasTestTag(tag))
        click(tag)
    }

    @Test
    fun rowMenuOffersOnlyWhatEachRowAllows() =
        runComposeUiTest {
            panel(FakeFolioCorrectionSource())

            onNodeWithText("Corrige: Frigobar").assertExists()

            scrollAndClick("posting-actions-0")
            onNodeWithTag("posting-adjust-0").assertExists()
            onNodeWithTag("posting-remove-charge-0").assertDoesNotExist()
            onNodeWithTag("posting-adjust-0").performClick()
            waitForIdle()
            click("adjustment-back")

            scrollAndClick("posting-actions-1")
            onNodeWithTag("posting-remove-charge-1").assertExists()
            onNodeWithTag("posting-adjust-1").assertDoesNotExist()
            click("posting-remove-charge-1")
            onNodeWithTag("removal-text").assertTextContains("O servidor registra o motivo: pedido do cliente.")
            click("removal-back")

            onNodeWithTag("posting-actions-2").assertDoesNotExist()
        }

    @Test
    fun adjustmentDialogShowsTheRemainingAndLocksInFlight() =
        runComposeUiTest {
            val gate = CompletableDeferred<Unit>()
            val corrections =
                FakeFolioCorrectionSource().apply {
                    val done = adjusted
                    adjusted = {
                        gate.await()
                        done()
                    }
                }
            panel(corrections)

            scrollAndClick("posting-actions-0")
            click("posting-adjust-0")
            onNodeWithTag("adjustment-remaining").assertTextContains("Resta: R$ 20,00")
            onNodeWithTag("adjustment-amount").performTextInput("5,00")
            onNodeWithTag("adjustment-reason").performTextInput("Cobrado a mais")
            click("adjustment-submit")

            onNodeWithTag("adjustment-amount").assertIsNotEnabled()
            onNodeWithTag("adjustment-reason").assertIsNotEnabled()
            gate.complete(Unit)
            waitForIdle()
            onNodeWithTag("adjustment-dialog").assertDoesNotExist()
            onNodeWithTag("folio-notice").assertTextContains("Ajuste lançado.")
        }
}
