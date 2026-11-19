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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransferResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val TRANSFER = "billing.postings.transfer"
private val ITEM = Uuid.parse("50000000-0000-0000-0000-0000000000d1")
private val CHARGE = Uuid.parse("50000000-0000-0000-0000-0000000000d2")
private val COFFEE = Uuid.parse("50000000-0000-0000-0000-0000000000d3")
private val IN_ITEM = Uuid.parse("50000000-0000-0000-0000-0000000000d4")
private val IN_CHARGE = Uuid.parse("50000000-0000-0000-0000-0000000000d5")
private val THIRD_FOLIO_ID = Uuid.parse("60000000-0000-0000-0000-000000000003")

/** "Transferir" e "Dividir" no menu da linha e os dois diálogos, no painel real e na aba "Contas". */
@OptIn(ExperimentalTestApi::class)
class FolioTransferScreenTest {
    private val item = postingOf(id = ITEM, description = "Frigobar", amount = "20.00").withRemaining("20.00")
    private val charge =
        postingOf(id = CHARGE, description = "Taxa de serviço", amount = "2.00", type = "SERVICE_CHARGE")
            .copy(serviceChargeForPostingId = Option.Some(ITEM))
            .withRemaining("2.00")
    private val coffee = postingOf(id = COFFEE, description = "Café", amount = "8.00").withRemaining("8.00")
    private val own = folioOf()
    private val other = folioOf(id = OTHER_FOLIO_ID, number = "F-0002", balance = "40.00")
    private val third = folioOf(id = THIRD_FOLIO_ID, number = "F-0003", balance = "0.00")

    private val ledger: MutableMap<String, List<PostingResponse>> =
        mutableMapOf(FOLIO_ID.toString() to listOf(item, charge, coffee), OTHER_FOLIO_ID.toString() to emptyList())

    private val source =
        FakeFolioSource().apply {
            pages = { cursor -> if (cursor == null) Page(listOf(own, other), "p2") else Page(listOf(third), null) }
            folio = { id -> if (id == OTHER_FOLIO_ID.toString()) other else own }
            postings = { id -> ledger[id].orEmpty() }
        }

    /** A transferência no falso: o item entra na outra conta com a taxa dele logo abaixo. */
    private val corrections =
        FakeFolioCorrectionSource().apply {
            transferred = {
                val incoming =
                    postingOf(id = IN_ITEM, description = "Frigobar", amount = "20.00", type = "TRANSFER_IN")
                        .copy(folioId = OTHER_FOLIO_ID)
                        .withRemaining("20.00")
                val incomingCharge =
                    postingOf(id = IN_CHARGE, description = "Taxa de serviço", amount = "2.00", type = "TRANSFER_IN")
                        .copy(folioId = OTHER_FOLIO_ID, serviceChargeForPostingId = Option.Some(IN_ITEM))
                        .withRemaining("2.00")
                ledger[FOLIO_ID.toString()] = listOf(item.withRemaining("0.00"), charge, coffee)
                ledger[OTHER_FOLIO_ID.toString()] = listOf(incoming, incomingCharge)
                TransferResponse(emptyList())
            }
        }

    private fun ComposeUiTest.panel() {
        setContent {
            val scope = rememberCoroutineScope()
            val state =
                remember {
                    FolioState(correctionWiringOf(source, corrections, setOf(READ, TRANSFER)), scope, "$FOLIO_ID")
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
    fun transferAndSplitOnlyOnTransferableRows() =
        runComposeUiTest {
            panel()

            scrollAndClick("posting-actions-0")
            onNodeWithTag("posting-transfer-0").assertExists()
            onNodeWithTag("posting-split-0").assertExists()
            click("posting-transfer-0")
            click("transfer-back")

            onNodeWithTag("posting-actions-1").assertDoesNotExist()
            scrollAndClick("posting-actions-2")
            onNodeWithTag("posting-transfer-2").assertExists()
        }

    @Test
    fun transferChoosesTheTargetWithLoadMore() =
        runComposeUiTest {
            panel()

            scrollAndClick("posting-actions-0")
            click("posting-transfer-0")
            onNodeWithTag("transfer-posting-$ITEM").assertExists()
            onNodeWithTag("transfer-posting-$CHARGE").assertDoesNotExist()
            onNodeWithTag("transfer-target-$FOLIO_ID").assertDoesNotExist()
            onNodeWithTag("transfer-target-$OTHER_FOLIO_ID").assertTextContains("F-0002", substring = true)
            onNodeWithTag("transfer-target-$THIRD_FOLIO_ID").assertDoesNotExist()
            click("transfer-targets-more")
            onNodeWithTag("transfer-target-$THIRD_FOLIO_ID").assertExists()

            click("transfer-target-$OTHER_FOLIO_ID")
            click("transfer-submit")

            onNodeWithTag("transfer-dialog").assertDoesNotExist()
            onNodeWithTag("folio-notice").assertTextContains("Transferido para a conta F-0002.")
            assertEquals(OTHER_FOLIO_ID.toString(), corrections.transferCalls.single().targetFolioId)
        }

    @Test
    fun splitShowsThePartsAndTheRemaining() =
        runComposeUiTest {
            panel()

            scrollAndClick("posting-actions-0")
            click("posting-split-0")
            onNodeWithTag("split-remaining").assertTextContains("Resta: R$ 20,00")
            onNodeWithText("Esta conta (fica)", substring = true).assertExists()
            click("split-target-$OTHER_FOLIO_ID")
            onNodeWithTag("split-part-1").assertExists()
            onNodeWithTag("split-part-0").performTextInput("60")
            onNodeWithTag("split-part-1").performTextInput("40")
            click("split-submit")

            onNodeWithTag("split-dialog").assertDoesNotExist()
            onNodeWithTag("folio-notice").assertTextContains("Dividido entre 2 contas.")
            assertEquals(
                2,
                corrections.splitCalls
                    .single()
                    .parts.size,
            )
        }

    @Test
    fun targetFolioInTheAccountsTabShowsTheIncomingEntry() =
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
            scrollAndClick("posting-actions-0")
            click("posting-transfer-0")
            click("transfer-target-$OTHER_FOLIO_ID")
            click("transfer-submit")
            onNodeWithTag("folio-notice").assertTextContains("Transferido para a conta F-0002.")

            click("folio-row-$OTHER_FOLIO_ID")
            onNodeWithText("Frigobar · Transferência (entrada)", substring = true).assertExists()
            onNodeWithText("Taxa de serviço · Transferência (entrada)", substring = true).assertExists()
        }
}
