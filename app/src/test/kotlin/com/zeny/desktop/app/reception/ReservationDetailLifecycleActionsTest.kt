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

/** Os botões de ciclo de vida do painel de detalhe: só existem com a ação, e cada um chama a sua. */
@OptIn(ExperimentalTestApi::class)
class ReservationDetailLifecycleActionsTest {
    private val lifecycleTags = listOf("open-reservation-cancel", "open-reservation-reactivate")

    private fun ComposeUiTest.panel(actions: DetailActions) =
        setContent {
            val scope = rememberCoroutineScope()
            val detail = remember { ReservationDetailState(FakeReceptionSource(), scope) }
            ZenyTheme { ReservationDetailPanel(detail, actions) }
        }

    @Test
    fun lifecycleButtonsOnlyWithActions() =
        runComposeUiTest {
            panel(DetailActions(onCheckIn = {}, onMove = {}, onUndo = {}, onCard = {}, onChange = {}))
            waitForIdle()

            lifecycleTags.forEach { onNodeWithTag(it).assertDoesNotExist() }
            onNodeWithTag("open-check-in").assertExists()
            onNodeWithTag("open-reservation-change").assertExists()
        }

    @Test
    fun buttonsCallTheirActions() =
        runComposeUiTest {
            val calls = mutableListOf<String>()
            panel(
                DetailActions(
                    onCancel = { calls += "cancel" },
                    onReactivate = { calls += "reactivate" },
                ),
            )
            waitForIdle()

            onNodeWithText("Cancelar reserva").performClick()
            onNodeWithText("Reativar").performClick()

            assertEquals(listOf("cancel", "reactivate"), calls)
            onNodeWithTag("open-reservation-cancel").assertExists()
            onNodeWithTag("open-reservation-reactivate").assertExists()
        }
}
