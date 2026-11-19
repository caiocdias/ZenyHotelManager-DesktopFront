package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationPenaltyResponse
import com.zeny.desktop.sdk.model.CancellationResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.uuid.Uuid

private val pdRes = Uuid.parse("20000000-0000-0000-0000-0000000000e2")

private fun pdDetail(
    charged: String,
    cancelledWith: String? = null,
): ReservationDetailResponse {
    val base = confirmDetail(pdRes, code = "R1", status = if (cancelledWith == null) "CONFIRMED" else "CANCELLED")
    val cancellation =
        cancelledWith?.let {
            CancellationResponse(
                cancelledAt = "2026-11-16T12:00:00Z",
                penalty = CancellationPenaltyResponse(amount = it, deadline = "2026-11-15T17:00:00Z", waived = false),
                previousStatus = "CONFIRMED",
                reason = "GUEST_REQUEST",
            )
        }
    return base.copy(
        reservation =
            base.reservation.copy(
                penaltiesCharged = charged,
                cancellation = cancellation?.let { Option.Some(it) } ?: Option.Undefined,
            ),
    )
}

/**
 * Ataque do testador de etapa (M4 E12): o `penaltiesCharged` ilegível ou negativo no diálogo antes de cancelar e no
 * resumo depois dele. Nenhum dos dois afirma multa anterior.
 */
@OptIn(ExperimentalTestApi::class)
class ReservationCancelDialogPenaltyAttackTest {
    private suspend fun approve(
        action: ApprovableAction<ReservationDetailResponse>,
    ): ApiResponse<ReservationDetailResponse>? = action.run(null)

    private fun ComposeUiTest.dialog(source: FakeReservationCancelSource) {
        setContent {
            val scope = rememberCoroutineScope()
            val state = remember { ReservationCancelState(source, scope, pdRes.toString(), ::approve) {} }
            ZenyTheme { ReservationCancelDialog(state) {} }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.cancelWith(charged: String) {
        val source =
            FakeReservationCancelSource().apply {
                reservations[pdRes.toString()] = pdDetail(charged)
                response = pdDetail(charged, cancelledWith = "0.00")
            }
        dialog(source)
        onNodeWithTag("cancel-terms").assertExists()
        onNodeWithText("de multa de cancelamento na conta", substring = true).assertDoesNotExist()
        onNodeWithText("R$ -", substring = true).assertDoesNotExist()

        onNodeWithTag("cancel-submit").performClick()
        waitForIdle()
    }

    @Test
    fun unreadableChargedSaysNothingBeforeOrAfter() =
        runComposeUiTest {
            cancelWith("cento e cinquenta")

            onNodeWithTag("cancel-done").assertTextEquals("Reserva R1 cancelada.")
        }

    @Test
    fun negativeChargedNeverClaimsAPreviousPenalty() =
        runComposeUiTest {
            cancelWith("-150.00")

            onNodeWithText("já tem", substring = true).assertDoesNotExist()
            onNodeWithText("R$ -", substring = true).assertDoesNotExist()
            onNodeWithTag("cancel-done").assertExists()
        }
}
