package com.zeny.desktop.app.folio

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.FolioResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val READ = "billing.folios.read"
private const val MANAGE = "billing.folios.manage"
private const val REOPEN = "billing.folios.reopen"
private const val CREATE = "billing.postings.create"

/** "Reabrir conta" no cabeçalho do painel real da conta encerrada e o diálogo dele. */
@OptIn(ExperimentalTestApi::class)
class FolioReopenScreenTest {
    private var panelState: FolioState? = null

    private fun closedSource() = FakeFolioSource().apply { folio = { folioOf(status = FolioResponse.Status.CLOSED) } }

    private fun wiring(
        folios: FakeFolioSource,
        account: FolioAccountSource?,
        permissions: Set<String>,
    ) = FolioWiring(
        folios,
        permissions,
        { it.run(null) },
        null,
        null,
        account = account,
        approveReopen = { it.run(null) },
    )

    private fun ComposeUiTest.panel(
        folios: FakeFolioSource,
        account: FolioAccountSource?,
        permissions: Set<String> = setOf(READ, REOPEN),
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val state =
                remember {
                    FolioState(wiring(folios, account, permissions), scope, "$FOLIO_ID").also { it.start() }
                }
            panelState = state
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
    fun buttonOnlyOnClosedFolioWithManageOrReopen() =
        runComposeUiTest {
            val open = FakeFolioSource().apply { folio = { folioOf() } }
            val closed = closedSource()
            val account = FakeFolioAccountSource()
            val cases =
                listOf(
                    Triple(wiring(closed, account, setOf(READ, REOPEN)), true, "encerrada, com reopen"),
                    Triple(wiring(closed, account, setOf(READ, MANAGE)), true, "encerrada, com manage"),
                    Triple(wiring(closed, account, setOf(READ, CREATE)), false, "sem manage nem reopen"),
                    Triple(wiring(closed, null, setOf(READ, REOPEN)), false, "sem porta"),
                    Triple(wiring(open, account, setOf(READ, REOPEN, MANAGE)), false, "conta aberta"),
                )
            var current by mutableStateOf(cases.first().first)
            setContent {
                val scope = rememberCoroutineScope()
                val state = remember(current) { FolioState(current, scope, "$FOLIO_ID").also { it.start() } }
                DisposableEffect(state) { onDispose { state.close() } }
                ZenyTheme { FolioPanel(state) }
            }
            cases.forEach { (wiring, shown, case) ->
                current = wiring
                waitForIdle()
                onNodeWithTag("folio-status").assertExists()
                val found = onAllNodesWithTag("folio-reopen").fetchSemanticsNodes().size
                assertEquals(if (shown) 1 else 0, found, case)
            }
        }

    @Test
    fun reopenFromTheHeaderRereadsTheFolio() =
        runComposeUiTest {
            val folios = closedSource()
            val account =
                FakeFolioAccountSource().apply {
                    reopenAction = {
                        ApprovableAction {
                            folios.folio = { folioOf() }
                            ApiResponse(folioOf(), null, 200)
                        }
                    }
                }
            panel(folios, account)
            onNodeWithTag("folio-status").assertTextContains("Conta encerrada", substring = true)

            scrollAndClick("folio-reopen")
            onNodeWithTag("reopen-dialog").assertExists()
            onNodeWithTag("reopen-reason").performTextInput("  Fechada por engano ")
            click("reopen-submit")

            assertEquals("Fechada por engano", account.reopens.single().reason)
            onNodeWithTag("reopen-dialog").assertDoesNotExist()
            onNodeWithTag("folio-status").assertTextContains("Aberta", substring = true)
            onNodeWithTag("folio-notice").assertTextContains("Conta reaberta.")
            onNodeWithTag("folio-reopen").assertDoesNotExist()
        }

    @Test
    fun oneDialogAtATime() =
        runComposeUiTest {
            panel(closedSource(), FakeFolioAccountSource(), setOf(READ, MANAGE, REOPEN, CREATE))
            val state = checkNotNull(panelState)

            scrollAndClick("folio-reopen")
            onNodeWithTag("reopen-dialog").assertExists()
            runOnIdle {
                state.startAccount()
                state.startPosting()
                state.startReopen()
            }
            waitForIdle()
            assertNull(state.account)
            assertNull(state.posting)

            click("reopen-back")
            onNodeWithTag("reopen-dialog").assertDoesNotExist()
            assertNull(state.reopen)
        }
}
