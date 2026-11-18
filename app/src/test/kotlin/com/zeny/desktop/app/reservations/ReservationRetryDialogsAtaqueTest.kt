package com.zeny.desktop.app.reservations

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val rdRes = Uuid.parse("20000000-0000-0000-0000-0000000000f6")
private const val RD_READ_CODE = "RESERVATION_NOT_FOUND"

/** Aprova ou desiste (`null`) no `APPROVAL_REQUIRED`, como o `ApprovalCoordinator`. */
private class RdApprover {
    var cancel = false

    suspend fun approve(action: ApprovableAction<ReservationDetailResponse>): ApiResponse<ReservationDetailResponse>? =
        try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
}

/**
 * Ataques da M3 E06, Task 3, no diálogo de cancelar: o "Tentar de novo" da leitura (`cancel-reload`) só existe quando a
 * leitura falhou e nada mais está em curso, e a mensagem nunca leva o `code` cru.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ReservationCancelReloadAtaqueTest {
    private var closes = 0
    private val approver = RdApprover()

    private fun source() =
        FakeReservationCancelSource().apply {
            reservations[rdRes.toString()] = confirmDetail(rdRes, code = "R1", status = "CONFIRMED")
            response = confirmDetail(rdRes, code = "R1", status = "CANCELLED")
        }

    private fun ComposeUiTest.dialog(source: FakeReservationCancelSource): ReservationCancelState {
        lateinit var created: ReservationCancelState
        setContent {
            val scope = rememberCoroutineScope()
            val state =
                remember {
                    ReservationCancelState(source, scope, rdRes.toString(), approver::approve) {}
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
    fun reloadIsAbsentAndSubmitEnabledWhenTheReadWorked() =
        runComposeUiTest {
            dialog(source())

            onNodeWithTag("cancel-reload").assertDoesNotExist()
            onNodeWithTag("cancel-error").assertDoesNotExist()
            onNodeWithTag("cancel-submit").assertIsEnabled()
        }

    @Test
    fun reloadIsNotOfferedWhileTheReadIsInFlightAndAppearsWhenItFails() =
        runComposeUiTest {
            val source =
                source().apply {
                    readError = IOException("caiu")
                    holdRead = true
                }
            dialog(source)

            onNodeWithText("Buscando a reserva...").assertExists()
            onNodeWithTag("cancel-reload").assertDoesNotExist()
            onNodeWithTag("cancel-submit").assertIsNotEnabled()
            source.releaseRead()
            waitForIdle()

            onNodeWithText("Buscando a reserva...").assertDoesNotExist()
            onNodeWithTag("cancel-reload").assertExists()
            onNodeWithTag("cancel-reload").assertIsEnabled()
            onNodeWithTag("cancel-submit").assertIsNotEnabled()
        }

    @Test
    fun reloadDuringTheRetryHidesTheButtonUntilTheNewReadEnds() =
        runComposeUiTest {
            val source = source().apply { readError = IOException("caiu") }
            dialog(source)
            onNodeWithTag("cancel-reload").assertExists()
            source.holdRead = true

            click("cancel-reload")

            assertEquals(2, source.readCalls.size)
            onNodeWithTag("cancel-reload").assertDoesNotExist()
            onNodeWithTag("cancel-error").assertDoesNotExist()
            onNodeWithText("Buscando a reserva...").assertExists()
            source.releaseRead()
            waitForIdle()
            onNodeWithTag("cancel-reload").assertExists()
        }

    @Test
    fun reloadThatFailsAgainKeepsTheButtonAndAllowsAThirdTry() =
        runComposeUiTest {
            val source = source().apply { readError = IOException("caiu") }
            dialog(source)

            click("cancel-reload")
            click("cancel-reload")

            assertEquals(3, source.readCalls.size)
            onNodeWithTag("cancel-reload").assertExists()
            onNodeWithTag("cancel-error").assertExists()
            onNodeWithTag("cancel-submit").assertIsNotEnabled()
            source.readError = null
            click("cancel-reload")
            onNodeWithTag("cancel-terms").assertExists()
            onNodeWithTag("cancel-reload").assertDoesNotExist()
            onNodeWithTag("cancel-error").assertDoesNotExist()
            onNodeWithTag("cancel-submit").assertIsEnabled()
        }

    @Test
    fun readFailureWithAServerCodeShowsNoRawCodeAndStillOffersReload() =
        runComposeUiTest {
            val source =
                source().apply { readError = ApiError.Problem(404, RD_READ_CODE, "detalhe interno", emptyList()) }
            dialog(source)

            onNodeWithText(RD_READ_CODE, substring = true).assertDoesNotExist()
            onNodeWithText("detalhe interno", substring = true).assertDoesNotExist()
            onNodeWithTag("cancel-error").assertExists()
            onNodeWithTag("cancel-reload").assertExists()
        }

    @Test
    fun reloadIsNotOfferedForARefusalOfTheCancellationItself() =
        runComposeUiTest {
            val source =
                source().apply {
                    outcomes += ApiError.Problem(422, "RESERVATION_TRANSITION_NOT_ALLOWED", "x", emptyList())
                }
            dialog(source)

            click("cancel-submit")

            onNodeWithTag("cancel-error").assertExists()
            onNodeWithTag("cancel-reload").assertDoesNotExist()
            onNodeWithTag("cancel-terms").assertExists()
        }

    @Test
    fun reloadIsNotOfferedAfterTheCancellationIsDone() =
        runComposeUiTest {
            dialog(source())

            click("cancel-submit")

            onNodeWithTag("cancel-done").assertExists()
            onNodeWithTag("cancel-reload").assertDoesNotExist()
        }

    @Test
    fun cancelledApprovalAfterUnknownResultGivesTheOriginalLabelBack() =
        runComposeUiTest {
            val source = source().apply { outcomes += ApiError.Network(IOException("caiu")) }
            val state = dialog(source)
            click("cancel-submit")
            onNodeWithTag("cancel-submit").assertTextEquals("Tentar de novo")
            assertTrue(state.unconfirmed)
            source.requiresApproval = etApproval()
            approver.cancel = true

            click("cancel-submit")

            assertFalse(state.unconfirmed)
            onNodeWithTag("cancel-submit").assertTextEquals("Cancelar reserva")
            onNodeWithTag("cancel-submit").assertIsEnabled()
            onNodeWithTag("cancel-reason-OTHER").assertIsEnabled()
            onNodeWithTag("cancel-notes").assertIsEnabled()
            onNodeWithTag("cancel-reload").assertDoesNotExist()
        }

    @Test
    fun overlappingReadsKeepOnlyTheLastOne() =
        runTest {
            val source = source().apply { holdRead = true }
            val state =
                ReservationCancelState(source, backgroundScope, rdRes.toString(), approver::approve) {}
            source.readError = IOException("a primeira falha")
            state.start()
            runCurrent()
            source.readError = null
            state.start()
            runCurrent()

            source.releaseRead()
            runCurrent()

            assertNotNull(state.terms)
            assertNull(state.error)
            assertFalse(state.loading)
        }
}

/**
 * Ataques da M3 E06, Tasks 3 e 6, no diálogo de reativar: o rótulo "Tentar de novo" só vale enquanto o resultado é
 * desconhecido; qualquer resposta definitiva, ou a aprovação cancelada, devolve "Reativar".
 */
@OptIn(ExperimentalTestApi::class)
class ReservationReactivateLabelAtaqueTest {
    private var closes = 0
    private val approver = RdApprover()

    private fun source() =
        FakeReservationReactivateSource().apply {
            response = confirmDetail(rdRes, code = "R1", status = "CONFIRMED")
        }

    private fun ComposeUiTest.dialog(source: FakeReservationReactivateSource): ReservationReactivateState {
        lateinit var created: ReservationReactivateState
        setContent {
            val scope = rememberCoroutineScope()
            val state =
                remember {
                    ReservationReactivateState(source, scope, rdRes.toString(), "R1", approver::approve) {}
                        .also { created = it }
                }
            ZenyTheme { ReservationReactivateDialog(state) { closes++ } }
        }
        waitForIdle()
        return created
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    @Test
    fun labelIsReactivateBeforeAnyAttempt() =
        runComposeUiTest {
            dialog(source())

            onNodeWithTag("reactivate-submit").assertTextEquals("Reativar")
        }

    // A asserção de `BAD_GATEWAY` em `labelStaysRetryAcrossRepeatedUnknownResults` não pode falhar: o `Unexpected(502)`
    // não tem `code`. A prova de que o desconhecido não vaza o status cru é esta.
    @Test
    fun unexpected502ShowsTheUnconfirmedTextAndNoRawStatus() =
        runComposeUiTest {
            val source = source().apply { outcomes += ApiError.Unexpected(502) }
            dialog(source)

            click("reactivate-submit")

            onNodeWithTag("reactivate-error")
                .assertTextContains("Não foi possível confirmar a reativação", substring = true)
            onNodeWithText("502", substring = true).assertDoesNotExist()
            onNodeWithTag("reactivate-submit").assertTextEquals("Tentar de novo")
        }

    @Test
    fun labelStaysRetryAcrossRepeatedUnknownResults() =
        runComposeUiTest {
            val source =
                source().apply {
                    outcomes += ApiError.Network(IOException("a"))
                    outcomes += ApiError.Unexpected(502)
                }
            dialog(source)

            click("reactivate-submit")
            click("reactivate-submit")

            onNodeWithTag("reactivate-submit").assertTextEquals("Tentar de novo")
            onNodeWithText("BAD_GATEWAY", substring = true).assertDoesNotExist()
            click("reactivate-submit")
            onNodeWithTag("reactivate-done").assertExists()
            assertEquals(
                1,
                source.runs
                    .map { it.key }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun aStoredProblem5xxAfterAnUnknownResultOffersOnlyCloseAndNeverCallsAgain() =
        runComposeUiTest {
            val source =
                source().apply {
                    outcomes += ApiError.Network(IOException("a"))
                    outcomes += ApiError.Problem(502, "BAD_GATEWAY", "x", emptyList())
                }
            dialog(source)
            click("reactivate-submit")
            click("reactivate-submit")

            onNodeWithTag("reactivate-submit").assertDoesNotExist()
            onNodeWithTag("reactivate-close").assertExists()
            onNodeWithText("BAD_GATEWAY", substring = true).assertDoesNotExist()
            assertEquals(2, source.runs.size)
            assertEquals(
                1,
                source.runs
                    .map { it.key }
                    .toSet()
                    .size,
            )
            // Fechar está habilitado e fecha de fato, sem outra chamada.
            onNodeWithTag("reactivate-close").assertIsEnabled()
            assertEquals(0, closes)
            click("reactivate-close")
            assertEquals(1, closes)
            assertEquals(2, source.runs.size)
        }

    @Test
    fun definitiveRefusalAfterUnknownResultGivesTheOriginalLabelBackWithoutRawCode() =
        runComposeUiTest {
            val source =
                source().apply {
                    outcomes += ApiError.Network(IOException("a"))
                    outcomes += ApiError.Problem(422, "RESERVATION_STAY_ENDED", "x", emptyList())
                }
            dialog(source)
            click("reactivate-submit")

            click("reactivate-submit")

            onNodeWithTag("reactivate-submit").assertTextEquals("Reativar")
            onNodeWithText("RESERVATION_STAY_ENDED", substring = true).assertDoesNotExist()
            onNodeWithTag("reactivate-error").assertTextEquals("A estadia desta reserva já terminou.")
        }

    @Test
    fun cancelledApprovalAfterUnknownResultGivesTheOriginalLabelBackAndSendsNothingMore() =
        runComposeUiTest {
            val source = source().apply { outcomes += ApiError.Network(IOException("a")) }
            val state = dialog(source)
            click("reactivate-submit")
            onNodeWithTag("reactivate-submit").assertTextEquals("Tentar de novo")
            source.requiresApproval = etApproval()
            approver.cancel = true

            click("reactivate-submit")

            assertFalse(state.unconfirmed)
            onNodeWithTag("reactivate-submit").assertTextEquals("Reativar")
            onNodeWithTag("reactivate-submit").assertIsEnabled()
            assertEquals(2, source.runs.size)
            assertEquals(0, closes)
        }
}
