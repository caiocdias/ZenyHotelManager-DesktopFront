package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.cashier.FakeCashierSource
import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
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
    private val payments = FakePaymentSource()

    private fun ComposeUiTest.area(
        permissions: Set<String> = setOf(READ, OPERATE),
        withPort: Boolean = true,
    ) {
        val connection =
            FolioConnection(
                folios,
                cashier = FakeCashierSource(),
                payments = payments,
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

    @Test
    fun emptyMessageDoesNotShowBeforeTheFirstReadStarts() =
        runComposeUiTest {
            area()
            mainClock.autoAdvance = false
            onNodeWithText(TAB).performClick()
            // O primeiro quadro da aba: o efeito de entrada ainda não rodou, e a leitura não começou.
            mainClock.advanceTimeByFrame()

            onNodeWithText("Nenhuma caução a vencer até a data.").assertDoesNotExist()
        }

    @Test
    fun emptyMessageDoesNotShowWhileTheBusinessDayIsInFlight() =
        runComposeUiTest {
            val lateDay = LateAnswer<BusinessDay>()
            payments.businessDay = { lateDay.await() }
            area()
            openTab()
            assertTrue(lateDay.asked)

            onNodeWithText("Nenhuma caução a vencer até a data.").assertDoesNotExist()
        }

    @Test
    fun emptyMessageShowsAfterAnEmptyRead() =
        runComposeUiTest {
            area()
            openTab()

            onNodeWithText("Nenhuma caução a vencer até a data.").assertExists()
        }

    @Test
    fun leavingTheTabAndComingBackRereads() =
        runComposeUiTest {
            area()
            openTab()
            assertEquals(1, expiring.cursors.size)

            onNodeWithText("Caixa").performClick()
            waitForIdle()
            openTab()

            assertEquals(2, expiring.cursors.size)
        }

    @Test
    fun searchByTheUiReadsTheTypedDate() =
        runComposeUiTest {
            expiring.pages = { Page(listOf(preAuthorizationOf(amount = "40.00")), null) }
            area()
            openTab()
            onNodeWithTag("expiring-until").performTextClearance()
            onNodeWithTag("expiring-until").performTextInput("30/11/2026")
            waitForIdle()

            click("expiring-search")

            assertEquals(2, expiring.cursors.size)
            onNodeWithText("Data inválida.").assertDoesNotExist()
        }

    @Test
    fun invalidDateByTheUiShowsTheTextAndDoesNotRead() =
        runComposeUiTest {
            area()
            openTab()
            val reads = expiring.cursors.size
            onNodeWithTag("expiring-until").performTextClearance()
            onNodeWithTag("expiring-until").performTextInput("31/02/2026")
            waitForIdle()

            click("expiring-search")

            onNodeWithText("Data inválida.").assertExists()
            assertEquals(reads, expiring.cursors.size)
        }

    @Test
    fun theUiTreeNeverCarriesCardData() =
        runComposeUiTest {
            expiring.pages = {
                Page(listOf(preAuthorizationOf(cardBrand = "VISA", cardLast4 = "4242", nsu = "NSU987654")), null)
            }
            area()
            openTab()

            onNodeWithTag("expiring-row-0").assertExists()
            listOf("VISA", "4242", "AUT123", "NSU987654").forEach {
                onNodeWithText(it, substring = true).assertDoesNotExist()
            }
        }

    @Test
    fun sessionChangeWithTheTabOpenDropsTheScreen() =
        runComposeUiTest {
            val connection =
                FolioConnection(
                    folios,
                    cashier = FakeCashierSource(),
                    payments = payments,
                    expiring = expiring,
                )
            var session by mutableStateOf<String?>("A")
            setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, connection, session, setOf(READ, OPERATE)) } }
            waitForIdle()
            openTab()
            onNodeWithTag("expiring-screen").assertExists()

            session = "B"
            waitForIdle()

            onNodeWithTag("expiring-screen").assertDoesNotExist()
        }
}
