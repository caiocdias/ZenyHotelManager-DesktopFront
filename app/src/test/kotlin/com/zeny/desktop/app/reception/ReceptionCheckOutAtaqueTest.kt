package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.folio.FakeFolioSource
import com.zeny.desktop.app.folio.FakePaymentSource
import com.zeny.desktop.app.folio.FolioConnection
import com.zeny.desktop.app.folio.folioOf
import com.zeny.desktop.app.reservations.confirmDetail
import com.zeny.desktop.app.reservations.searchItemOf
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CheckOutResult
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private const val CHECK_OUT = "frontdesk.stays.checkout"
private const val FOLIOS_READ = "billing.folios.read"
private const val RECEIVE = "billing.payments.receive"
private const val IN_HOUSE = "IN_HOUSE"
private val GUEST = Uuid.parse("20000000-0000-0000-0000-0000000000d1")
private val LEAVING = Uuid.parse("20000000-0000-0000-0000-0000000000d2")

/** Ataque ao "Check-out" na Recepção: a reserva que já saiu, a aba "Saídas", a troca de área e de sessão. */
@OptIn(ExperimentalTestApi::class)
class ReceptionCheckOutAtaqueTest {
    private fun reception(status: String = IN_HOUSE) =
        FakeReceptionSource().apply {
            permissions = permissions + CHECK_OUT + FOLIOS_READ + RECEIVE
            pages[DayList.ARRIVALS to null] = Page(listOf(searchItemOf("R-1").copy(id = GUEST)), null)
            pages[DayList.DEPARTURES to null] = Page(listOf(searchItemOf("R-9").copy(id = LEAVING)), null)
            reservations[GUEST.toString()] = confirmDetail(GUEST, "R-1", status = status, guaranteeType = "NONE")
            reservations[LEAVING.toString()] =
                confirmDetail(LEAVING, "R-9", status = IN_HOUSE, guaranteeType = "NONE")
            holderError = ApiError.Problem(404, null, null, emptyList())
            ratePlanError = ApiError.Problem(404, null, null, emptyList())
        }

    private fun settled() = FakeFolioSource().apply { byReservation = { listOf(folioOf(balance = "0.00")) } }

    private fun connectionOf(
        reception: FakeReceptionSource,
        checkOut: CheckOutSource,
    ) = FolioConnection(settled(), reception = reception, payments = FakePaymentSource(), checkOut = checkOut)

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    @Test
    fun checkedOutReservationHasNoCheckOut() =
        runComposeUiTest {
            val reception = reception(status = "CHECKED_OUT")
            val connection = connectionOf(reception, FakeCheckOutSource())
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, connection, "A", reception.permissions) } }
            waitForIdle()
            click("row-$GUEST")

            onNodeWithTag("reservation-detail").assertExists()
            onNodeWithTag("check-out").assertDoesNotExist()
        }

    @Test
    fun departuresTabRowOpensTheAssistant() =
        runComposeUiTest {
            val reception = reception()
            val source = FakeCheckOutSource()
            val connection = connectionOf(reception, source)
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, connection, "A", reception.permissions) } }
            waitForIdle()
            click("tab-${DayList.DEPARTURES.name}")
            click("row-$LEAVING")
            click("check-out")

            onNodeWithTag("check-out-dialog").assertExists()
            click("check-out-conclude")
            assertEquals(listOf(LEAVING.toString()), source.calls.map { it.reservationId })
            onNodeWithTag("reception-notice").assertTextEquals("Check-out concluído.")
        }

    @Test
    fun leavingTheAreaClosesTheAssistantAndComingBackDoesNotReopenIt() =
        runComposeUiTest {
            val reception = reception()
            val source = FakeCheckOutSource()
            var area by mutableStateOf(Area.RECEPTION)
            val connection = connectionOf(reception, source)
            setContent { ZenyTheme { AreaContent(area, connection, "A", reception.permissions + "rooms.status.read") } }
            waitForIdle()
            click("row-$GUEST")
            click("check-out")
            onNodeWithTag("check-out-dialog").assertExists()

            area = Area.HOUSEKEEPING
            waitForIdle()
            onNodeWithTag("check-out-dialog").assertDoesNotExist()

            area = Area.RECEPTION
            waitForIdle()
            onNodeWithTag("check-out-dialog").assertDoesNotExist()
            assertEquals(0, source.calls.size)
        }

    @Test
    fun sessionSwitchWithTheCheckOutInFlightClosesWithoutNoticeNorReload() =
        runComposeUiTest {
            val reception = reception()
            val answer = CompletableDeferred<Unit>()
            val source =
                FakeCheckOutSource().apply {
                    checkOut = {
                        answer.await()
                        CheckOutResult.Done
                    }
                }
            var session by mutableStateOf("A")
            val connection = connectionOf(reception, source)
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, connection, session, reception.permissions) } }
            waitForIdle()
            click("row-$GUEST")
            click("check-out")
            click("check-out-conclude")
            assertEquals(1, source.calls.size)

            session = "B"
            waitForIdle()
            onNodeWithTag("check-out-dialog").assertDoesNotExist()
            val pagesAfterSwitch = reception.dayPageCalls.size
            answer.complete(Unit)
            waitForIdle()

            onNodeWithTag("check-out-dialog").assertDoesNotExist()
            onNodeWithTag("reception-notice").assertDoesNotExist()
            assertEquals(pagesAfterSwitch, reception.dayPageCalls.size, "a resposta tardia não mexe na outra sessão")
        }
}
