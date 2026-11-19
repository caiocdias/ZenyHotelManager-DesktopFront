package com.zeny.desktop.app.folio

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CompanyChoice
import com.zeny.desktop.core.facade.VersionedFolio
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.FolioResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val READ = "billing.folios.read"
private const val MANAGE = "billing.folios.manage"
private const val CREATE = "billing.postings.create"
private const val COMPANIES = "guests.companies.read"
private const val STALE_TEXT =
    "Alguém alterou este registro. Recarregue e tente de novo. Os valores foram relidos; confira e salve de novo."
private val CNPJ = Regex("""\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2}""")

/** "Pagador e limite" no cabeçalho do painel real da conta e o diálogo dele. */
@OptIn(ExperimentalTestApi::class)
class FolioAccountDialogTest {
    private var panelState: FolioState? = null

    private fun wiring(
        folios: FakeFolioSource,
        account: FolioAccountSource?,
        permissions: Set<String>,
    ) = FolioWiring(folios, permissions, { it.run(null) }, null, null, account = account)

    private fun ComposeUiTest.panel(
        folios: FakeFolioSource,
        account: FolioAccountSource?,
        permissions: Set<String> = setOf(READ, MANAGE, COMPANIES),
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
    fun buttonOnlyWithManageAndOpenFolio() =
        runComposeUiTest {
            val open = FakeFolioSource().apply { folio = { accountFolioOf() } }
            val closed = FakeFolioSource().apply { folio = { accountFolioOf(status = FolioResponse.Status.CLOSED) } }
            val account = FakeFolioAccountSource()
            val cases =
                listOf(
                    Triple(wiring(open, account, setOf(READ, MANAGE)), true, "com manage e porta"),
                    Triple(wiring(open, account, setOf(READ, COMPANIES)), false, "sem manage"),
                    Triple(wiring(open, null, setOf(READ, MANAGE)), false, "sem porta"),
                    Triple(wiring(closed, account, setOf(READ, MANAGE)), false, "conta encerrada"),
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
                val found = onAllNodesWithTag("folio-account").fetchSemanticsNodes().size
                assertEquals(if (shown) 1 else 0, found, case)
            }
        }

    @Test
    fun searchAndChooseACompany() =
        runComposeUiTest {
            val folios = FakeFolioSource().apply { folio = { accountFolioOf() } }
            val account =
                FakeFolioAccountSource().apply {
                    companies = { name, _ ->
                        val all =
                            listOf(
                                CompanyChoice("$PAYER_ID", "Acme Turismo"),
                                CompanyChoice("$OTHER_PAYER_ID", "Viagens Sul"),
                            )
                        Page(all.filter { name == null || it.name.contains(name, ignoreCase = true) }, null)
                    }
                }
            panel(folios, account)

            scrollAndClick("folio-account")
            onNodeWithTag("account-current").assertTextContains("Acme Turismo", substring = true)
            onNodeWithTag("account-search").performTextInput("viagens")
            click("account-search-submit")
            onNodeWithTag("account-company-0").assertTextContains("Viagens Sul")
            onNodeWithTag("account-company-1").assertDoesNotExist()
            click("account-company-0")
            onNodeWithTag("account-payer").assertTextContains("Viagens Sul", substring = true)

            val cnpjNodes =
                onAllNodes(
                    SemanticsMatcher("CNPJ") { node ->
                        val texts =
                            node.config
                                .getOrNull(SemanticsProperties.Text)
                                .orEmpty()
                                .map { it.text }
                        texts.any { CNPJ.containsMatchIn(it) }
                    },
                ).fetchSemanticsNodes()
            assertEquals(0, cnpjNodes.size)

            click("account-submit")
            assertEquals("$OTHER_PAYER_ID", account.updates.single().payerCompanyId)
            assertEquals("viagens", account.companyPageCalls.last().first)
        }

    @Test
    fun oneDialogAtATime() =
        runComposeUiTest {
            val folios = FakeFolioSource().apply { folio = { accountFolioOf() } }
            panel(folios, FakeFolioAccountSource(), setOf(READ, MANAGE, COMPANIES, CREATE))
            val state = checkNotNull(panelState)

            scrollAndClick("folio-account")
            onNodeWithTag("account-dialog").assertExists()
            runOnIdle { state.startPosting() }
            waitForIdle()
            assertNull(state.posting)
            onNodeWithTag("posting-dialog").assertDoesNotExist()

            click("account-back")
            onNodeWithTag("account-dialog").assertDoesNotExist()
            runOnIdle { state.startPosting() }
            waitForIdle()
            onNodeWithTag("posting-dialog").assertExists()
            runOnIdle { state.startAccount() }
            waitForIdle()
            assertNull(state.account)
        }

    @Test
    fun staleVersionShowsTextAndRereads() =
        runComposeUiTest {
            val folios = FakeFolioSource().apply { folio = { accountFolioOf() } }
            val account = FakeFolioAccountSource()
            account.update = {
                val other = accountFolioOf(OTHER_PAYER_ID, "800.00")
                folios.folio = { other }
                account.folio = { VersionedFolio(other, "\"v9\"") }
                throw ApiError.PreconditionFailed(null)
            }
            account.companyById = { CompanyChoice(it, if (it == "$OTHER_PAYER_ID") "Viagens Sul" else "Acme Turismo") }
            panel(folios, account)
            onNodeWithTag("folio-limit").assertTextContains("Limite: R$ 1.500,00")

            scrollAndClick("folio-account")
            onNodeWithTag("account-limit").performTextClearance()
            onNodeWithTag("account-limit").performTextInput("100,00")
            click("account-submit")

            onNodeWithTag("account-message").assertTextContains(STALE_TEXT)
            onNodeWithTag("account-limit").assertTextContains("R$ 800,00")
            onNodeWithTag("account-current").assertTextContains("Viagens Sul", substring = true)
            onNodeWithTag("folio-limit").assertTextContains("Limite: R$ 800,00")
            assertEquals(1, account.updates.size)
        }

    @Test
    fun successRereadsAndNotifies() =
        runComposeUiTest {
            val folios = FakeFolioSource().apply { folio = { accountFolioOf() } }
            val account = FakeFolioAccountSource()
            val done = account.update
            account.update = {
                folios.folio = { accountFolioOf(creditLimit = "2000.00") }
                done(it)
            }
            panel(folios, account)

            scrollAndClick("folio-account")
            onNodeWithTag("account-limit").performTextClearance()
            onNodeWithTag("account-limit").performTextInput("2.000,00")
            click("account-submit")

            onNodeWithTag("account-dialog").assertDoesNotExist()
            onNodeWithTag("folio-notice").assertTextContains("Pagador e limite atualizados.")
            onNodeWithTag("folio-limit").assertTextContains("Limite: R$ 2.000,00")
            val hint = onAllNodesWithText("Seu perfil não pode escolher a empresa pagadora.").fetchSemanticsNodes()
            assertEquals(0, hint.size)
        }
}
