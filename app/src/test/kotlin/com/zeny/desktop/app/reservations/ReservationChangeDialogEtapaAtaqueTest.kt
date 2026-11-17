package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.design.theme.ZenyTheme
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val ccRes = Uuid.parse("20000000-0000-0000-0000-0000000000c6")
private const val CC_ARRIVAL_MESSAGE = "A chegada não pode mudar com o hóspede em casa."
private const val CC_DEPARTURE_MESSAGE = "A saída cai fora do período vendido."

/**
 * Ataques da M3 E06, Task 5, ao `ReservationChangeDialog`: o botão "Salvar alteração" acompanha `canConfirm` em cada
 * estado (lendo, lida, falha na leitura, ocupado, concluído, fechado) e o erro do campo `arrival` aparece uma vez só,
 * no campo somente leitura da reserva hospedada.
 */
@OptIn(ExperimentalTestApi::class)
class ReservationChangeDialogEtapaAtaqueTest {
    private var closes = 0

    private fun source(status: String = "CONFIRMED") =
        FakeReservationChangeSource().apply {
            val detail = confirmDetail(ccRes, code = "R-1", status = status)
            versions[ccRes.toString()] = ReservationVersion(detail, "\"v1\"")
            changeResponse = confirmDetail(ccRes, code = "R-1", status = status)
        }

    private fun ComposeUiTest.dialog(source: FakeReservationChangeSource): ReservationChangeState {
        lateinit var created: ReservationChangeState
        setContent {
            val scope = rememberCoroutineScope()
            val state =
                remember {
                    ReservationChangeState(source, scope, ccRes.toString(), false, { it.run(null) }) {}
                        .also { created = it }
                }
            ZenyTheme { ReservationChangeDialog(state, onClose = { closes++ }) }
        }
        waitForIdle()
        return created
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.editRequests() {
        onNodeWithTag("change-requests").performTextInput(" e vista")
        waitForIdle()
    }

    private fun problem(vararg errors: FieldError) = ApiError.Problem(422, "STAY_INVALID", null, errors.toList())

    // --- O botão acompanha canConfirm ------------------------------------------------------------------------------

    @Test
    fun submitIsDisabledWhileTheReservationIsBeingRead() =
        runComposeUiTest {
            val server = source().apply { holdVersion = true }
            val state = dialog(server)

            assertTrue(state.loading)
            onNodeWithTag("change-submit").assertIsNotEnabled()
            server.heldVersions.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("change-submit").assertIsEnabled()
        }

    @Test
    fun submitStaysDisabledWhenTheReadFailed() =
        runComposeUiTest {
            val server = source().apply { versionError = IOException("caiu") }
            dialog(server)

            onNodeWithTag("change-submit").assertIsNotEnabled()
            click("change-submit")
            assertTrue(server.changeCalls.isEmpty())
        }

    @Test
    fun submitIsDisabledWhileTheChangeIsInFlightAndGoneWhenItIsDone() =
        runComposeUiTest {
            val server = source().apply { holdChange = true }
            val state = dialog(server)
            editRequests()

            click("change-submit")

            assertTrue(state.busy)
            onNodeWithTag("change-submit").assertIsNotEnabled()
            click("change-submit")
            assertEquals(1, server.changeCalls.size)
            server.heldChanges.forEach { it.complete(Unit) }
            waitForIdle()
            onNodeWithTag("change-done").assertExists()
            onNodeWithTag("change-submit").assertDoesNotExist()
        }

    @Test
    fun submitIsEnabledAgainAfterARefusalAndAfterAStaleVersionReload() =
        runComposeUiTest {
            val server = source()
            server.changeOutcomes += problem(FieldError("departure", "INVALID", CC_DEPARTURE_MESSAGE))
            server.changeOutcomes += ApiError.PreconditionFailed(null)
            dialog(server)
            editRequests()

            click("change-submit")
            onNodeWithTag("change-submit").assertIsEnabled()
            click("change-submit")

            onNodeWithTag("change-notice").assertExists()
            onNodeWithTag("change-submit").assertIsEnabled()
        }

    @Test
    fun submitIsDisabledOnceTheStateIsClosed() =
        runComposeUiTest {
            val server = source()
            val state = dialog(server)
            onNodeWithTag("change-submit").assertIsEnabled()

            click("change-close")

            assertEquals(1, closes)
            assertTrue(!state.canConfirm)
            onNodeWithTag("change-submit").assertIsNotEnabled()
            click("change-submit")
            assertTrue(server.changeCalls.isEmpty())
        }

    // --- O erro de `arrival` no campo somente leitura --------------------------------------------------------------

    @Test
    fun arrivalErrorOnAnInHouseReservationShowsOnceAndNeverTheFieldKey() =
        runComposeUiTest {
            val server = source(status = "IN_HOUSE")
            server.changeOutcomes += problem(FieldError("arrival", "INVALID", CC_ARRIVAL_MESSAGE))
            dialog(server)
            editRequests()

            click("change-submit")

            onAllNodesWithText(CC_ARRIVAL_MESSAGE).assertCountEquals(1)
            onNodeWithText("arrival", substring = true).assertDoesNotExist()
            onNodeWithText("reservations[0]", substring = true).assertDoesNotExist()
            onNodeWithTag("change-arrival").assertIsNotEnabled()
        }

    @Test
    fun arrivalAndDepartureErrorsTogetherEachShowOnceOnTheirOwnFields() =
        runComposeUiTest {
            val server = source(status = "IN_HOUSE")
            server.changeOutcomes +=
                problem(
                    FieldError("arrival", "INVALID", CC_ARRIVAL_MESSAGE),
                    FieldError("departure", "INVALID", CC_DEPARTURE_MESSAGE),
                )
            dialog(server)
            editRequests()

            click("change-submit")

            onAllNodesWithText(CC_ARRIVAL_MESSAGE).assertCountEquals(1)
            onAllNodesWithText(CC_DEPARTURE_MESSAGE).assertCountEquals(1)
        }

    @Test
    fun arrivalErrorOnAnEditableReservationStillShowsOnce() =
        runComposeUiTest {
            val server = source()
            server.changeOutcomes += problem(FieldError("arrival", "INVALID", "A chegada cai fora do período."))
            dialog(server)
            editRequests()

            click("change-submit")

            onAllNodesWithText("A chegada cai fora do período.").assertCountEquals(1)
        }

    @Test
    fun arrivalErrorClearsOnTheNextAttempt() =
        runComposeUiTest {
            val server = source(status = "IN_HOUSE")
            server.changeOutcomes += problem(FieldError("arrival", "INVALID", CC_ARRIVAL_MESSAGE))
            dialog(server)
            editRequests()
            click("change-submit")
            onAllNodesWithText(CC_ARRIVAL_MESSAGE).assertCountEquals(1)

            click("change-submit")

            onAllNodesWithText(CC_ARRIVAL_MESSAGE).assertCountEquals(0)
            onNodeWithTag("change-done").assertExists()
        }
}
