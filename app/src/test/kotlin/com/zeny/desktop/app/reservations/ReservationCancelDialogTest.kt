package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationPenaltyResponse
import com.zeny.desktop.sdk.model.CancellationResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import de.quati.kotlin.util.Option
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val cdRes = Uuid.parse("20000000-0000-0000-0000-0000000000c8")
private const val CD_TERMS =
    "Cancelamento sem multa até 24 h antes do check-in; depois, multa conforme a política Flexível."

private const val CD_UNCONFIRMED_TEXT =
    "Não foi possível confirmar o cancelamento. Conclua de novo sem mudar nada; " +
        "se fechar, confira a reserva na Busca antes de refazer."

private fun cdCancelled(
    amount: String,
    waived: Boolean,
): ReservationDetailResponse {
    val base = confirmDetail(cdRes, code = "R1", status = "CANCELLED")
    val cancellation =
        CancellationResponse(
            cancelledAt = "2026-11-16T12:00:00Z",
            penalty = CancellationPenaltyResponse(amount = amount, deadline = "2026-11-15T17:00:00Z", waived = waived),
            previousStatus = "CONFIRMED",
            reason = "GUEST_REQUEST",
        )
    return base.copy(reservation = base.reservation.copy(cancellation = Option.Some(cancellation)))
}

@OptIn(ExperimentalTestApi::class)
class ReservationCancelDialogTest {
    private var changes = 0
    private var closes = 0

    private fun source() =
        FakeReservationCancelSource().apply {
            reservations[cdRes.toString()] = confirmDetail(cdRes, code = "R1", status = "CONFIRMED")
            response = cdCancelled("320.00", false)
        }

    private suspend fun approve(
        action: ApprovableAction<ReservationDetailResponse>,
    ): ApiResponse<ReservationDetailResponse>? = action.run(null)

    private fun ComposeUiTest.dialog(source: FakeReservationCancelSource): ReservationCancelState {
        lateinit var created: ReservationCancelState
        setContent {
            val scope = rememberCoroutineScope()
            val state =
                remember {
                    ReservationCancelState(source, scope, cdRes.toString(), ::approve) { changes++ }
                        .also { created = it }
                }
            ZenyTheme { ReservationCancelDialog(state) { closes++ } }
        }
        waitForIdle()
        return created
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    @Test
    fun termsAreVisibleBeforeSubmit() =
        runComposeUiTest {
            val source = source()
            dialog(source)

            onNodeWithText("Cancelar a reserva R1").assertExists()
            onNodeWithTag("cancel-terms").assertTextEquals(CD_TERMS)
            onNodeWithText("A multa é lançada na conta da reserva conforme o prazo da política.").assertExists()
            onNodeWithTag("cancel-submit").assertIsEnabled()
            onNodeWithTag("cancel-done").assertDoesNotExist()
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun submitDisabledWithoutTerms() =
        runComposeUiTest {
            val source = source().apply { readError = IOException("caiu") }
            dialog(source)

            onNodeWithTag("cancel-terms").assertDoesNotExist()
            onNodeWithTag("cancel-error").assertExists()
            onNodeWithTag("cancel-submit").assertIsNotEnabled()
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun reasonChipsSelect() =
        runComposeUiTest {
            val source = source()
            dialog(source)

            onNodeWithTag("cancel-reason-GUEST_REQUEST").assertIsSelected()
            CANCEL_REASONS.forEach { (value, label) ->
                onNodeWithTag("cancel-reason-$value").assertExists()
                onNodeWithText(label).assertExists()
            }
            click("cancel-reason-HOTEL_REQUEST")
            onNodeWithTag("cancel-reason-HOTEL_REQUEST").assertIsSelected()
            onNodeWithTag("cancel-notes").performTextInput("Overbooking")
            click("cancel-submit")

            val input = source.calls.single().input
            assertEquals("HOTEL_REQUEST", input.reason)
            assertEquals("Overbooking", input.notes)
            assertEquals(false, input.waivePenalty)
        }

    @Test
    fun waiveCheckboxToggles() =
        runComposeUiTest {
            val source = source()
            dialog(source)

            onNodeWithText("Isentar a multa (pode pedir a aprovação de um superior)").assertExists()
            onNodeWithTag("cancel-waive").assertIsOff()
            click("cancel-waive")
            onNodeWithTag("cancel-waive").assertIsOn()
            click("cancel-waive")
            onNodeWithTag("cancel-waive").assertIsOff()
            click("cancel-waive")
            click("cancel-submit")

            val input = source.calls.single().input
            assertTrue(input.waivePenalty)
        }

    @Test
    fun submitDisabledWhileLoadingOrBusy() =
        runComposeUiTest {
            val source = source().apply { holdRead = true }
            dialog(source)

            onNodeWithTag("cancel-submit").assertIsNotEnabled()
            source.releaseRead()
            waitForIdle()
            onNodeWithTag("cancel-submit").assertIsEnabled()

            source.hold = true
            click("cancel-submit")

            onNodeWithTag("cancel-submit").assertIsNotEnabled()
            onNodeWithTag("cancel-close").assertIsNotEnabled()
            onNodeWithTag("cancel-waive").assertIsNotEnabled()
            assertEquals(1, source.calls.size)
            source.held.single().complete(Unit)
            waitForIdle()
            onNodeWithTag("cancel-done").assertExists()
        }

    @Test
    fun doneShowsPenaltyTextAndOnlyClose() =
        runComposeUiTest {
            val state = dialog(source())

            click("cancel-submit")

            onNodeWithTag("cancel-done").assertTextEquals("Reserva R1 cancelada. Multa lançada na conta: R$ 320,00.")
            onNodeWithTag("cancel-terms").assertDoesNotExist()
            onNodeWithTag("cancel-submit").assertDoesNotExist()
            onNodeWithTag("cancel-waive").assertDoesNotExist()
            onNodeWithText("Voltar").assertDoesNotExist()
            assertEquals(1, changes)
            click("cancel-close")
            assertEquals(1, closes)
            assertNotNull(state.done)
        }

    @Test
    fun backClosesWithoutCancelling() =
        runComposeUiTest {
            val source = source()
            dialog(source)

            onNodeWithText("Voltar").performClick()
            waitForIdle()

            assertEquals(1, closes)
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun errorShowsCatalogTextWithoutCode() =
        runComposeUiTest {
            val source =
                source().apply {
                    outcomes += ApiError.Problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED", "detalhe", emptyList())
                }
            dialog(source)

            click("cancel-submit")

            onNodeWithTag("cancel-error")
                .assertTextEquals("Esta ação não é permitida na situação atual da reserva.")
            onNodeWithText("RESERVATION_TRANSITION_NOT_ALLOWED", substring = true).assertDoesNotExist()
            onNodeWithTag("cancel-submit").assertIsEnabled()
            onNodeWithTag("cancel-terms").assertExists()
        }

    @Test
    fun unconfirmedOffersRetry() =
        runComposeUiTest {
            val source = source().apply { outcomes += ApiError.Network(IOException("caiu")) }
            dialog(source)

            click("cancel-submit")

            onNodeWithTag("cancel-error").assertTextEquals(CD_UNCONFIRMED_TEXT)
            onNodeWithText("Tentar de novo").assertExists()
            onNodeWithText("Cancelar reserva").assertDoesNotExist()
            onNodeWithTag("cancel-submit").assertIsEnabled()
            onNodeWithTag("cancel-close").assertIsEnabled()
            click("cancel-submit")
            onNodeWithTag("cancel-done").assertExists()
            assertSame(source.calls[0].intent, source.calls[1].intent)
        }
}
