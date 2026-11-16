package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Os botões novos do painel de detalhe: só existem com a ação, e cada um chama a sua. */
@OptIn(ExperimentalTestApi::class)
class ReservationDetailActionsTest {
    private val newTags = listOf("open-reservation-change", "open-reservation-confirm", "open-reservation-confirmation")

    private fun ComposeUiTest.panel(actions: DetailActions) =
        setContent {
            val scope = rememberCoroutineScope()
            val detail = remember { ReservationDetailState(FakeReceptionSource(), scope) }
            ZenyTheme { ReservationDetailPanel(detail, actions) }
        }

    @Test
    fun newButtonsOnlyWithActions() =
        runComposeUiTest {
            panel(DetailActions(onCheckIn = {}, onMove = {}, onUndo = {}, onCard = {}))
            waitForIdle()

            newTags.forEach { onNodeWithTag(it).assertDoesNotExist() }
            onNodeWithTag("open-check-in").assertExists()
            onNodeWithTag("open-room-move").assertExists()
            onNodeWithTag("open-undo-check-in").assertExists()
            onNodeWithTag("open-registration-card").assertExists()
        }

    @Test
    fun buttonsCallTheirActions() =
        runComposeUiTest {
            val calls = mutableListOf<String>()
            panel(
                DetailActions(
                    onChange = { calls += "change" },
                    onConfirmReservation = { calls += "confirm" },
                    onConfirmationPdf = { calls += "pdf" },
                ),
            )
            waitForIdle()

            onNodeWithText("Alterar").performClick()
            onNodeWithText("Confirmar reserva").performClick()
            onNodeWithText("Confirmação (PDF)").performClick()

            assertEquals(listOf("change", "confirm", "pdf"), calls)
            onNodeWithTag("open-reservation-change").assertExists()
            onNodeWithTag("open-reservation-confirm").assertExists()
            onNodeWithTag("open-reservation-confirmation").assertExists()
        }
}
