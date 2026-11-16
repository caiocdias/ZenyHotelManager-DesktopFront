package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.theme.ZenyTheme
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private val rdRes = Uuid.parse("20000000-0000-0000-0000-0000000000c2")

@OptIn(ExperimentalTestApi::class)
class ReservationConfirmDialogTest {
    private var changes = 0
    private var closes = 0
    private var confirmations = 0

    private fun source(guaranteeType: String = "DEPOSIT") =
        FakeReservationConfirmSource().apply {
            details[rdRes.toString()] =
                confirmDetail(rdRes, guaranteeType = guaranteeType, requiredDeposit = "216.00")
            confirmResponse = confirmDetail(rdRes, status = "CONFIRMED", guaranteeType = guaranteeType)
        }

    private fun ComposeUiTest.dialog(
        source: FakeReservationConfirmSource,
        withConfirmation: Boolean = true,
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val state = remember { ReservationConfirmState(source, scope, rdRes.toString()) { changes++ } }
            ZenyTheme {
                ReservationConfirmDialog(
                    state,
                    onConfirmation = if (withConfirmation) ({ confirmations++ }) else null,
                    onClose = { closes++ },
                )
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    @Test
    fun showsTermsAndChoices() =
        runComposeUiTest {
            dialog(source())

            onNodeWithTag("confirm-terms").assertTextEquals(
                "Garantia exigida: Depósito de 30,00% até 7 dia(s) antes da chegada",
            )
            onNodeWithText("Depósito exigido: R$ 216,00").assertExists()
            onNodeWithTag("confirm-guarantee-CARD").assertExists()
            onNodeWithTag("confirm-guarantee-DEPOSIT").assertExists()
            onNodeWithTag("confirm-submit").assertIsEnabled()
        }

    @Test
    fun choosingGuaranteeSendsIt() =
        runComposeUiTest {
            val server = source("DEPOSIT")
            dialog(server)

            click("confirm-guarantee-CARD")
            click("confirm-submit")

            assertEquals(listOf("CARD"), server.confirmCalls.map { it.guarantee })
        }

    @Test
    fun submitDisabledWhileBusy() =
        runComposeUiTest {
            val server = source()
            server.holdConfirm = true
            dialog(server)

            click("confirm-submit")

            onNodeWithTag("confirm-submit").assertIsNotEnabled()
            onNodeWithTag("confirm-close").assertIsNotEnabled()
            assertEquals(1, server.confirmCalls.size)
            server.heldConfirms.single().complete(Unit)
            waitForIdle()
            onNodeWithTag("confirm-done").assertExists()
        }

    @Test
    fun errorIsShown() =
        runComposeUiTest {
            val server = source()
            server.confirmOutcomes += ApiError.Problem(422, "DEPOSIT_INSUFFICIENT", null, emptyList())
            dialog(server)

            click("confirm-submit")

            onNodeWithTag("confirm-error").assertTextEquals("O depósito é insuficiente.")
            onNodeWithText("DEPOSIT_INSUFFICIENT", substring = true).assertDoesNotExist()
            onNodeWithTag("confirm-submit").assertIsEnabled()
        }

    @Test
    fun networkErrorIsShown() =
        runComposeUiTest {
            val server = source()
            server.confirmOutcomes += ApiError.Network(IOException("caiu"))
            dialog(server)

            click("confirm-submit")

            onNodeWithTag("confirm-error")
                .assertTextEquals("Não foi possível falar com o servidor. Confira o endereço e a rede.")
        }

    @Test
    fun doneShowsConfirmationAndClose() =
        runComposeUiTest {
            dialog(source())

            click("confirm-submit")

            onNodeWithTag("confirm-done").assertTextEquals("Reserva R-1 confirmada.")
            onNodeWithTag("confirm-submit").assertDoesNotExist()
            onNodeWithTag("confirm-guarantee-CARD").assertDoesNotExist()
            click("confirm-confirmation")
            assertEquals(1, confirmations)
            click("confirm-close")
            assertEquals(1, closes)
            assertEquals(1, changes)
        }

    @Test
    fun confirmationButtonOnlyWithCallback() =
        runComposeUiTest {
            dialog(source(), withConfirmation = false)

            click("confirm-submit")

            onNodeWithTag("confirm-done").assertExists()
            onNodeWithTag("confirm-confirmation").assertDoesNotExist()
            onNodeWithTag("confirm-close").assertExists()
        }
}
