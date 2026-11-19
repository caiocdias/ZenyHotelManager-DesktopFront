package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.folio.FOLIO_ID
import com.zeny.desktop.app.folio.FakeFolioSource
import com.zeny.desktop.app.folio.FakePaymentSource
import com.zeny.desktop.app.folio.FolioConnection
import com.zeny.desktop.app.folio.folioOf
import com.zeny.desktop.app.folio.preAuthorizationOf
import com.zeny.desktop.app.folio.wiringOf
import com.zeny.desktop.app.reservations.confirmDetail
import com.zeny.desktop.app.reservations.searchItemOf
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CheckOutResult
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.theme.ZenyTheme
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val CHECK_OUT = "frontdesk.stays.checkout"
private const val FOLIOS_READ = "billing.folios.read"
private const val RECEIVE = "billing.payments.receive"

/** A situação da reserva hospedada no contrato (não há `CHECKED_IN`). */
private const val IN_HOUSE = "IN_HOUSE"
private val FIRST = Uuid.parse("20000000-0000-0000-0000-0000000000c1")
private val SECOND = Uuid.parse("20000000-0000-0000-0000-0000000000c2")

/** "Check-out" no detalhe da reserva da Recepção: o assistente, o "Abrir conta" e o aviso no painel do dia. */
@OptIn(ExperimentalTestApi::class)
class ReceptionCheckOutTest {
    private fun reception(status: String = IN_HOUSE) =
        FakeReceptionSource().apply {
            permissions = permissions + CHECK_OUT + FOLIOS_READ + RECEIVE
            pages[DayList.ARRIVALS to null] =
                Page(listOf(searchItemOf("R-1").copy(id = FIRST), searchItemOf("R-2").copy(id = SECOND)), null)
            pages[DayList.IN_HOUSE to null] = Page(listOf(searchItemOf("R-1").copy(id = FIRST)), null)
            reservations[FIRST.toString()] = confirmDetail(FIRST, "R-1", status = status, guaranteeType = "NONE")
            reservations[SECOND.toString()] =
                confirmDetail(SECOND, "R-2", status = IN_HOUSE, guaranteeType = "NONE")
            holderError = ApiError.Problem(404, null, null, emptyList())
            ratePlanError = ApiError.Problem(404, null, null, emptyList())
        }

    private fun settled() = FakeFolioSource().apply { byReservation = { listOf(folioOf(balance = "0.00")) } }

    /** A Recepção montada pelo `AppRoot`, com as portas da conexão falsa e o conjunto [permissions] da casca. */
    private fun ComposeUiTest.area(
        reception: FakeReceptionSource,
        checkOut: CheckOutSource?,
        permissions: Set<String> = reception.permissions,
        payments: FakePaymentSource = FakePaymentSource(),
        folios: FakeFolioSource = settled(),
    ) {
        val connection =
            FolioConnection(folios, reception = reception, payments = payments, checkOut = checkOut)
        setContent { ZenyTheme { AreaContent(Area.RECEPTION, connection, "A", permissions) } }
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** Rola o corpo do assistente até [tag] e clica nele. */
    private fun ComposeUiTest.clickInAssistant(tag: String) {
        onNodeWithTag("check-out-body").performScrollToNode(hasTestTag(tag))
        click(tag)
    }

    @Test
    fun checkOutNeedsThePermissionAndAnInHouseReservation() {
        runComposeUiTest {
            area(reception(), FakeCheckOutSource())
            click("row-$FIRST")
            onNodeWithTag("check-out").assertExists()
        }
        runComposeUiTest {
            val reception = reception()
            area(reception, FakeCheckOutSource(), permissions = reception.permissions - CHECK_OUT)
            click("row-$FIRST")
            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("check-out").assertDoesNotExist()
        }
        runComposeUiTest {
            area(reception(status = "CONFIRMED"), FakeCheckOutSource())
            click("row-$FIRST")
            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("check-out").assertDoesNotExist()
        }
        runComposeUiTest {
            area(reception(), checkOut = null)
            click("row-$FIRST")
            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("check-out").assertDoesNotExist()
        }
    }

    @Test
    fun checkOutOpensTheAssistant() =
        runComposeUiTest {
            val source = FakeCheckOutSource()
            area(reception(), source)
            click("row-$FIRST")

            click("check-out")

            onNodeWithTag("check-out-dialog").assertExists()
            assertEquals(1, source.quoteCalls)
            onNodeWithTag("check-out-conclude").assertIsEnabled()
        }

    @Test
    fun openFolioReleasesTheCautionAndTheReloadedAssistantUnlocks() =
        runComposeUiTest {
            val payments = FakePaymentSource().apply { preAuthorizations = { listOf(preAuthorizationOf()) } }
            area(reception(), FakeCheckOutSource(), payments = payments)
            click("row-$FIRST")
            click("check-out")
            onNodeWithTag("check-out-conclude").assertIsNotEnabled()

            clickInAssistant("check-out-pending-0-open")
            onNodeWithTag("folio-dialog").assertExists()
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("release-0"))
            click("release-0")
            payments.preAuthorizations = { listOf(preAuthorizationOf(status = "RELEASED", capturable = "0.00")) }
            click("release-submit")
            assertEquals(1, payments.releaseCalls.size)
            click("folio-dialog-close")

            onNodeWithTag("folio-dialog").assertDoesNotExist()
            onNodeWithTag("check-out-dialog").assertExists()
            onNodeWithTag("check-out-conclude").assertIsEnabled()
        }

    @Test
    fun successClosesNoticesAndReloadsTheDayPanel() =
        runComposeUiTest {
            val reception = reception()
            val source = FakeCheckOutSource()
            area(reception, source)
            click("tab-${DayList.IN_HOUSE.name}")
            click("row-$FIRST")
            click("check-out")
            val readsBefore = reception.reservationCalls.size
            reception.pages[DayList.IN_HOUSE to null] = Page(emptyList(), null)

            click("check-out-conclude")

            assertEquals(1, source.calls.size)
            onNodeWithTag("check-out-dialog").assertDoesNotExist()
            onNodeWithTag("reception-notice").assertTextEquals("Check-out concluído.")
            onNodeWithTag("row-$FIRST").assertDoesNotExist()
            assertTrue(reception.reservationCalls.size > readsBefore, "o detalhe é relido")
        }

    @Test
    fun switchingTheReservationClosesTheAssistant() =
        runComposeUiTest {
            val reception = reception()
            lateinit var detail: ReservationDetailState
            setContent {
                val scope = rememberCoroutineScope()
                val panel = remember { DayPanelState(reception, scope, reception.permissions) }
                detail = remember { ReservationDetailState(reception, scope) }
                ZenyTheme { ReceptionScreen(panel, detail, checkOut = FakeCheckOutSource()) }
            }
            waitForIdle()
            click("row-$FIRST")
            click("check-out")
            onNodeWithTag("check-out-dialog").assertExists()

            detail.open(SECOND.toString())
            waitForIdle()

            onNodeWithTag("check-out-dialog").assertDoesNotExist()
            // Voltar à primeira reserva não traz o assistente de volta.
            detail.open(FIRST.toString())
            waitForIdle()
            onNodeWithTag("check-out-dialog").assertDoesNotExist()
        }

    @Test
    fun closingUnconfirmedOrSwitchingTheReservationReloadsThePanel() =
        runComposeUiTest {
            val reception = reception()
            val source = FakeCheckOutSource().apply { checkOut = { throw ApiError.Network(IOException("x")) } }
            lateinit var detail: ReservationDetailState
            setContent {
                val scope = rememberCoroutineScope()
                val panel = remember { DayPanelState(reception, scope, reception.permissions) }
                detail = remember { ReservationDetailState(reception, scope) }
                ZenyTheme { ReceptionScreen(panel, detail, checkOut = source, folio = wiringOf(settled())) }
            }
            waitForIdle()
            click("row-$FIRST")
            click("check-out")
            click("check-out-conclude")
            val pagesBefore = reception.dayPageCalls.size

            click("check-out-close")

            onNodeWithTag("check-out-dialog").assertDoesNotExist()
            assertTrue(reception.dayPageCalls.size > pagesBefore, "o servidor pode ter feito o check-out")

            // A troca de reserva com o resultado desconhecido fecha o assistente e, com a tela ainda de pé, relê o
            // painel depois do fechamento (nunca dentro dele).
            click("check-out")
            click("check-out-conclude")
            val pagesThen = reception.dayPageCalls.size
            detail.open(SECOND.toString())
            waitForIdle()
            onNodeWithTag("check-out-dialog").assertDoesNotExist()
            assertTrue(reception.dayPageCalls.size > pagesThen, "o servidor pode ter feito o check-out")
        }

    @Test
    fun openFolioOnADueLineDropsTheStaleReceive() =
        runComposeUiTest {
            val source = FakeCheckOutSource().apply { checkOut = { dueWith("100.00") } }
            area(reception(), source)
            click("row-$FIRST")
            click("check-out")
            click("check-out-conclude")
            onNodeWithTag("check-out-receive-0").assertExists()

            clickInAssistant("check-out-due-0-open")
            onNodeWithTag("folio-dialog").assertExists()
            click("folio-dialog-close")

            onNodeWithTag("folio-dialog").assertDoesNotExist()
            onNodeWithTag("check-out-receive-0").assertDoesNotExist()
            onNodeWithTag("check-out-notice").assertTextEquals("Confira a conta e conclua de novo.")
            onNodeWithTag("check-out-conclude").assertIsEnabled()
        }

    @Test
    fun openFolioAndReceiveAreDisabledWhileTheResultIsUnknown() =
        runComposeUiTest {
            val source = FakeCheckOutSource().apply { checkOut = { dueWith("100.00") } }
            val folios = FakeFolioSource().apply { byReservation = { listOf(folioOf(balance = "100.00")) } }
            area(reception(), source, folios = folios)
            click("row-$FIRST")
            click("check-out")
            click("check-out-conclude")
            onNodeWithTag("check-out-receive-0").assertIsEnabled()
            onNodeWithTag("check-out-pending-0-open").assertIsEnabled()

            source.checkOut = { throw ApiError.Network(IOException("x")) }
            click("check-out-conclude")

            onNodeWithTag("check-out-receive-0").assertIsNotEnabled()
            onNodeWithTag("check-out-pending-0-open").assertIsNotEnabled()
            onNodeWithTag("check-out-due-0-open").assertIsNotEnabled()
        }

    @Test
    fun doneNoticeClearsWithAnotherReservationButThePaidOneStays() =
        runComposeUiTest {
            val reception = reception()
            val source = FakeCheckOutSource()
            val wiring = wiringOf(settled(), setOf(FOLIOS_READ, RECEIVE), payments = FakePaymentSource())
            lateinit var detail: ReservationDetailState
            setContent {
                val scope = rememberCoroutineScope()
                val panel = remember { DayPanelState(reception, scope, reception.permissions) }
                detail = remember { ReservationDetailState(reception, scope) }
                ZenyTheme { ReceptionScreen(panel, detail, checkOut = source, folio = wiring) }
            }
            waitForIdle()
            click("row-$FIRST")
            click("check-out")
            click("check-out-conclude")
            onNodeWithTag("reception-notice").assertTextEquals("Check-out concluído.")

            click("row-$SECOND")

            onNodeWithTag("reception-notice").assertDoesNotExist()

            source.checkOut = { dueWith("100.00") }
            click("check-out")
            click("check-out-conclude")
            click("check-out-receive-0")
            click("payment-submit")
            detail.open(FIRST.toString())
            waitForIdle()

            onNodeWithTag("check-out-dialog").assertDoesNotExist()
            onNodeWithTag("reception-notice")
                .assertTextEquals("O pagamento foi registrado, mas o check-out não foi concluído.")
        }

    /** O saldo devido do servidor só na primeira conta. */
    private fun dueWith(balance: String) =
        CheckOutResult.BalanceDue(listOf(dueOf(FOLIO_ID.toString(), "F-0001", balance)), Money.parse(balance))

    @Test
    fun theNoticeDiesWithTheSession() =
        runComposeUiTest {
            val reception = reception()
            var session by mutableStateOf("A")
            setContent {
                val scope = rememberCoroutineScope()
                val panel = remember(session) { DayPanelState(reception, scope, reception.permissions) }
                val detail = remember(session) { ReservationDetailState(reception, scope) }
                ZenyTheme { ReceptionScreen(panel, detail, checkOut = FakeCheckOutSource()) }
            }
            waitForIdle()
            click("row-$FIRST")
            click("check-out")
            click("check-out-conclude")
            onNodeWithTag("reception-notice").assertTextEquals("Check-out concluído.")

            session = "B"
            waitForIdle()

            onNodeWithTag("reception-notice").assertDoesNotExist()
        }
}
