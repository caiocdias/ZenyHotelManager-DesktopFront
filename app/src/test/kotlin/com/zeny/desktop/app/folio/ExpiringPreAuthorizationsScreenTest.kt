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
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val READ = "billing.folios.read"
private const val OPERATE = "billing.cashier.operate"
private const val TAB = "Cauções a vencer"

private class ScreenExpiringSource : ExpiringPreAuthorizationSource {
    var pages: suspend (String?) -> Page<CardPreAuthorizationResponse> = { Page(emptyList(), null) }
    val cursors = mutableListOf<String?>()

    override suspend fun page(
        to: BusinessDate,
        cursor: String?,
        limit: Int,
    ): Page<CardPreAuthorizationResponse> {
        cursors += cursor
        return pages(cursor)
    }
}

/** A terceira aba da área "Contas e caixa": a lista das cauções a vencer e "Abrir conta". */
@OptIn(ExperimentalTestApi::class)
class ExpiringPreAuthorizationsScreenTest {
    private val folios = FakeFolioSource()
    private val expiring = ScreenExpiringSource()

    private fun ComposeUiTest.area(
        permissions: Set<String> = setOf(READ, OPERATE),
        withPort: Boolean = true,
    ) {
        val connection =
            FolioConnection(
                folios,
                cashier = FakeCashierSource(),
                payments = FakePaymentSource(),
                expiring = expiring.takeIf { withPort },
            )
        setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, connection, "A", permissions) } }
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openTab() {
        onNodeWithText(TAB).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.scrollAndClick(tag: String) {
        onNodeWithTag("expiring-screen").performScrollToNode(hasTestTag(tag))
        click(tag)
    }

    @Test
    fun openFolioGoesToTheAccountsTabWithTheFolio() =
        runComposeUiTest {
            expiring.pages = {
                val row = preAuthorizationOf(amount = "300.00", capturable = "250.00").copy(folioId = OTHER_FOLIO_ID)
                Page(listOf(row), null)
            }
            area()
            openTab()
            onNodeWithText("R$ 300,00", substring = true).assertExists()

            scrollAndClick("expiring-open-0")

            onNodeWithTag("folio-panel").assertExists()
            onNodeWithTag("expiring-screen").assertDoesNotExist()
            assertEquals(listOf(OTHER_FOLIO_ID.toString()), folios.folioCalls)
        }

    @Test
    fun tabOnlyWithFolioReadAndKeepsCashierAsDefault() {
        runComposeUiTest {
            area()

            onNodeWithText("Caixa").assertExists()
            onNodeWithText("Contas").assertExists()
            onNodeWithText(TAB).assertExists()
            onNodeWithText("Turno de caixa").assertExists()
            assertTrue(expiring.cursors.isEmpty())
        }
        runComposeUiTest {
            area(permissions = setOf(OPERATE))

            onNodeWithText("Turno de caixa").assertExists()
            onNodeWithText(TAB).assertDoesNotExist()
        }
        runComposeUiTest {
            area(withPort = false)

            onNodeWithText("Contas").assertExists()
            onNodeWithText(TAB).assertDoesNotExist()
        }
    }

    @Test
    fun loadMoreShowsTheNextPage() =
        runComposeUiTest {
            expiring.pages = {
                if (it == null) {
                    Page(listOf(preAuthorizationOf(amount = "10.00")), "c-2")
                } else {
                    Page(listOf(preAuthorizationOf(amount = "20.00")), null)
                }
            }
            area()
            openTab()
            onNodeWithText("R$ 10,00", substring = true).assertExists()

            scrollAndClick("expiring-more")

            onNodeWithText("R$ 10,00", substring = true).assertExists()
            onNodeWithText("R$ 20,00", substring = true).assertExists()
            onNodeWithTag("expiring-more").assertDoesNotExist()
            assertEquals(listOf<String?>(null, "c-2"), expiring.cursors)
        }

    @Test
    fun readFailureShowsTheTextAndRetries() =
        runComposeUiTest {
            var fail = true
            expiring.pages = {
                if (fail) throw ApiError.Network(IllegalStateException("fora"))
                Page(listOf(preAuthorizationOf(amount = "40.00")), null)
            }
            area()
            openTab()

            onNodeWithText("Não foi possível falar com o servidor. Confira o endereço e a rede.").assertExists()
            onNodeWithText("Tentar de novo").assertExists()

            fail = false
            scrollAndClick("expiring-more")

            onNodeWithText("R$ 40,00", substring = true).assertExists()
            onNodeWithTag("expiring-error").assertDoesNotExist()
        }
}
