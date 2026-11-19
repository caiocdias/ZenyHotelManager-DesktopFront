package com.zeny.desktop.app.folio

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransferResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val TRANSFER = "billing.postings.transfer"
private val ITEM = Uuid.parse("50000000-0000-0000-0000-0000000000f1")
private val CHARGE = Uuid.parse("50000000-0000-0000-0000-0000000000f2")
private val IN_ITEM = Uuid.parse("50000000-0000-0000-0000-0000000000f4")
private val IN_CHARGE = Uuid.parse("50000000-0000-0000-0000-0000000000f5")

/**
 * Ataque do testador ao critério 2 da M4 E08 ("nas duas contas"), na aba "Contas": depois de transferir, a conta de
 * origem mostra a saída do item e da taxa, e a de destino a entrada dos dois, cada uma pelo que o servidor devolveu.
 */
@OptIn(ExperimentalTestApi::class)
class FolioTransferAttackScreenTest {
    private val item = postingOf(id = ITEM, description = "Frigobar", amount = "20.00").withRemaining("20.00")
    private val charge =
        postingOf(id = CHARGE, description = "Taxa de serviço", amount = "2.00", type = "SERVICE_CHARGE")
            .copy(serviceChargeForPostingId = Option.Some(ITEM))
            .withRemaining("2.00")
    private val own = folioOf()
    private val other = folioOf(id = OTHER_FOLIO_ID, number = "F-0002", balance = "40.00")

    private val ledger: MutableMap<String, List<PostingResponse>> =
        mutableMapOf(FOLIO_ID.toString() to listOf(item, charge), OTHER_FOLIO_ID.toString() to emptyList())

    private val source =
        FakeFolioSource().apply {
            pages = { Page(listOf(own, other), null) }
            folio = { id -> if (id == OTHER_FOLIO_ID.toString()) other else own }
            postings = { id -> ledger[id].orEmpty() }
        }

    /** A transferência no falso: a saída do item e da taxa ficam aqui; a entrada dos dois vai à outra conta. */
    private val corrections =
        FakeFolioCorrectionSource().apply {
            transferred = {
                val outgoing = postingOf(description = "Frigobar", amount = "-20.00", type = "TRANSFER_OUT")
                val outgoingCharge =
                    postingOf(description = "Taxa de serviço", amount = "-2.00", type = "TRANSFER_OUT")
                val incoming =
                    postingOf(id = IN_ITEM, description = "Frigobar", amount = "20.00", type = "TRANSFER_IN")
                        .copy(folioId = OTHER_FOLIO_ID)
                        .withRemaining("20.00")
                val incomingCharge =
                    postingOf(id = IN_CHARGE, description = "Taxa de serviço", amount = "2.00", type = "TRANSFER_IN")
                        .copy(folioId = OTHER_FOLIO_ID, serviceChargeForPostingId = Option.Some(IN_ITEM))
                        .withRemaining("2.00")
                ledger[FOLIO_ID.toString()] =
                    listOf(item.withRemaining("0.00"), charge.withRemaining("0.00"), outgoing, outgoingCharge)
                ledger[OTHER_FOLIO_ID.toString()] = listOf(incoming, incomingCharge)
                TransferResponse(emptyList())
            }
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
    fun transferShowsTheOutgoingInTheOriginAndTheIncomingInTheTarget() =
        runComposeUiTest {
            setContent {
                val scope = rememberCoroutineScope()
                val accounts =
                    remember { FolioAccounts(correctionWiringOf(source, corrections, setOf(READ, TRANSFER)), scope) }
                DisposableEffect(accounts) { onDispose { accounts.close() } }
                ZenyTheme { FolioListScreen(accounts) }
            }
            waitForIdle()

            click("folio-row-$FOLIO_ID")
            onNodeWithText("Frigobar · Transferência (saída)", substring = true).assertDoesNotExist()
            scrollAndClick("posting-actions-0")
            click("posting-transfer-0")
            click("transfer-target-$OTHER_FOLIO_ID")
            click("transfer-submit")
            onNodeWithTag("folio-notice").assertTextContains("Transferido para a conta F-0002.")
            onNodeWithText("Frigobar · Transferência (saída)", substring = true).assertExists()
            onNodeWithText("Taxa de serviço · Transferência (saída)", substring = true).assertExists()

            click("folio-row-$OTHER_FOLIO_ID")
            onNodeWithText("Frigobar · Transferência (entrada)", substring = true).assertExists()
            onNodeWithText("Taxa de serviço · Transferência (entrada)", substring = true).assertExists()
            onNodeWithText("Frigobar · Transferência (saída)", substring = true).assertDoesNotExist()

            click("folio-row-$FOLIO_ID")
            onNodeWithText("Frigobar · Transferência (saída)", substring = true).assertExists()
            onNodeWithText("Taxa de serviço · Transferência (saída)", substring = true).assertExists()
            onNodeWithText("Frigobar · Transferência (entrada)", substring = true).assertDoesNotExist()
        }
}
