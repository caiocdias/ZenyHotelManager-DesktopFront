package com.zeny.desktop.app.folio

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.VersionedFolio
import com.zeny.desktop.design.theme.ZenyTheme
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val ATK_READ = "billing.folios.read"
private const val ATK_MANAGE = "billing.folios.manage"
private const val ATK_COMPANIES = "guests.companies.read"

/**
 * Ataque do testador de etapa (M4 E12) ao diálogo "Pagador e limite": o pagador atual não é afirmado sem a leitura da
 * conta (em voo ou com falha) e, sem `guests.companies.read`, o diálogo diz por que não escolhe a empresa.
 */
@OptIn(ExperimentalTestApi::class)
class FolioAccountDialogAttackTest {
    private fun ComposeUiTest.openAccount(
        account: FakeFolioAccountSource,
        permissions: Set<String> = setOf(ATK_READ, ATK_MANAGE, ATK_COMPANIES),
    ) {
        val folios = FakeFolioSource().apply { folio = { accountFolioOf() } }
        val wiring = FolioWiring(folios, permissions, { it.run(null) }, null, null, account = account)
        setContent {
            val scope = rememberCoroutineScope()
            val state = remember { FolioState(wiring, scope, "$FOLIO_ID").also { it.start() } }
            DisposableEffect(state) { onDispose { state.close() } }
            ZenyTheme { FolioPanel(state) }
        }
        waitForIdle()
        onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("folio-account"))
        onNodeWithTag("folio-account").performClick()
        waitForIdle()
    }

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString(" ") { it.text }

    /** Antes da leitura da conta, o diálogo não pode dizer que a conta não tem pagador. */
    @Test
    fun currentPayerIsNotClaimedBeforeTheRead() =
        runComposeUiTest {
            val reading = LateAnswer<VersionedFolio>()
            openAccount(FakeFolioAccountSource().apply { folio = { reading.await() } })

            onNodeWithTag("account-dialog").assertExists()
            val current = onNodeWithTag("account-current").text()
            assertFalse(FolioText.NO_PAYER in current, "sem leitura, o diálogo afirma: $current")
        }

    /** Com a leitura da conta falhando, o diálogo não pode dizer que a conta não tem pagador. */
    @Test
    fun currentPayerIsNotClaimedWhenTheReadFails() =
        runComposeUiTest {
            openAccount(FakeFolioAccountSource().apply { folio = { throw ApiError.Network(IOException("caiu")) } })

            onNodeWithTag("account-message").assertExists()
            val current = onNodeWithTag("account-current").text()
            assertFalse(FolioText.NO_PAYER in current, "com a leitura falhando, o diálogo afirma: $current")
        }

    @Test
    fun withoutCompanyReadTheDialogSaysWhyAndHasNoSearch() =
        runComposeUiTest {
            val account = FakeFolioAccountSource()
            openAccount(account, setOf(ATK_READ, ATK_MANAGE))

            onNodeWithTag("account-payer-hint").assertExists()
            onNodeWithTag("account-search").assertDoesNotExist()
            onNodeWithTag("account-no-payer").assertDoesNotExist()
            val generic = onAllNodesWithText("Empresa pagadora definida", substring = true).fetchSemanticsNodes()
            assertEquals(1, generic.size)
            assertEquals(0, account.companyCalls.size + account.companyPageCalls.size)
        }
}
