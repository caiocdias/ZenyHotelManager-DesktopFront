package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.cashier.FakeCashierSource
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reservations.FakeReservationSearchSource
import com.zeny.desktop.app.reservations.confirmDetail
import com.zeny.desktop.app.reservations.searchItemOf
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val OPERATE = "billing.cashier.operate"
private const val RECEIVE = "billing.payments.receive"
private const val RESERVATIONS_READ = "reservations.reservations.read"
private val FIRST = Uuid.parse("20000000-0000-0000-0000-0000000000b1")
private const val HOLDER = "30000000-0000-0000-0000-0000000000b1"

/** Ataque da etapa à tela dos pagamentos: a linha nova amarrada ao valor e o atalho que fecha o diálogo "Conta". */
@OptIn(ExperimentalTestApi::class)
class FolioPaymentsScreenAtaqueTest {
    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.scrollAndClick(tag: String) {
        onNodeWithTag("folio-panel").performScrollToNode(hasTestTag(tag))
        click(tag)
    }

    /** A linha `payment-0` mostra o que o servidor devolveu depois de "Receber", não o que a tela digitou. */
    @Test
    fun theFirstRowAfterReceivingShowsTheServerAmountAndMethod() =
        runComposeUiTest {
            val payments = FakePaymentSource()
            val connection = FolioConnection(FakeFolioSource(), cashier = FakeCashierSource(), payments = payments)
            setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, connection, "A", setOf(READ, OPERATE, RECEIVE)) } }
            waitForIdle()
            onNodeWithText("Contas").performClick()
            waitForIdle()
            click("folio-row-$FOLIO_ID")

            scrollAndClick("receive-payment")
            onNodeWithTag("payment-amount").performTextClearance()
            onNodeWithTag("payment-amount").performTextInput("1.234,56")
            click("payment-method-PIX")
            payments.payments = { listOf(paymentOf(amount = "1234.56", method = "PIX")) }
            click("payment-submit")

            assertEquals(
                Money.parse("1234.56"),
                payments.receiveCalls
                    .single()
                    .input.amount,
            )
            onNodeWithTag("payment-dialog").assertDoesNotExist()
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("payment-0"))
            val inRow = hasAnyAncestor(hasTestTag("payment-0"))
            onNode(inRow and hasText("R$ 1.234,56", substring = true), useUnmergedTree = true).assertExists()
            onNode(inRow and hasText("Pix", substring = true), useUnmergedTree = true).assertExists()
            onNodeWithTag("payment-1").assertDoesNotExist()
            assertTrue(payments.paymentCalls.size >= 2, "os pagamentos são relidos depois de receber")
        }

    /** Nas Reservas, "Abrir turno" leva à área "Contas e caixa" e não deixa o diálogo "Conta" aberto. */
    @Test
    fun openShiftFromReservationsClosesTheFolioDialog() =
        runComposeUiTest {
            val search =
                FakeReservationSearchSource().apply {
                    reservations += searchItemOf("R-1", holderGuestId = HOLDER).copy(id = FIRST)
                    holders[HOLDER] = HolderSummary("Ana Fictícia", null)
                }
            val desk =
                FakeReceptionSource().apply {
                    pages[DayList.ARRIVALS to null] = Page(listOf(searchItemOf("R-1").copy(id = FIRST)), null)
                    reservations[FIRST.toString()] =
                        confirmDetail(FIRST, "R-1", status = "CONFIRMED", guaranteeType = "NONE")
                    holderError = ApiError.Problem(404, null, null, emptyList())
                    ratePlanError = ApiError.Problem(404, null, null, emptyList())
                }
            val payments =
                FakePaymentSource().apply {
                    receive = { throw ApiError.Problem(422, "CASHIER_SHIFT_REQUIRED", null, emptyList()) }
                }
            var area by mutableStateOf(Area.RESERVATIONS)
            val connection =
                FolioConnection(
                    FakeFolioSource(),
                    cashier = FakeCashierSource(),
                    reception = desk,
                    search = search,
                    payments = payments,
                )
            val permissions = setOf(RESERVATIONS_READ, READ, RECEIVE, OPERATE)
            setContent {
                ZenyTheme { AreaContent(area, connection, "A", permissions, onOpenShift = { area = Area.ACCOUNTS }) }
            }
            waitForIdle()
            click("row-$FIRST")
            click("open-folio")
            onNodeWithTag("folio-dialog").assertExists()
            scrollAndClick("receive-payment")
            click("payment-submit")
            onNodeWithText("É preciso um turno de caixa aberto.").assertExists()
            click("open-shift")

            assertEquals(Area.ACCOUNTS, area)
            onNodeWithTag("folio-dialog").assertDoesNotExist()
            onNodeWithTag("payment-dialog").assertDoesNotExist()

            area = Area.RESERVATIONS
            waitForIdle()
            onNodeWithTag("folio-dialog").assertDoesNotExist()
            onNodeWithTag("payment-dialog").assertDoesNotExist()
        }
}
