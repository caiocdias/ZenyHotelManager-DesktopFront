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
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val raRes = Uuid.parse("20000000-0000-0000-0000-0000000000d1")
private const val RA_CODE = "R1"

private const val RA_UNCONFIRMED_TEXT =
    "Não foi possível confirmar a reativação. Conclua de novo sem mudar nada; " +
        "se fechar, confira a reserva na Busca antes de refazer."

private const val RA_SERVER_UNCONFIRMED_TEXT =
    "O servidor não confirmou a reativação. Feche e confira a reserva na Busca antes de refazer."

private const val RA_QUESTION =
    "Reativar a reserva R1? Ela volta a Confirmada, com o preço que tinha. " +
        "A ação pode pedir a aprovação de um superior."

private fun raSource() =
    FakeReservationReactivateSource().apply {
        response = confirmDetail(raRes, code = RA_CODE, status = "CONFIRMED")
    }

private fun raProblem(
    status: Int,
    code: String,
) = ApiError.Problem(status, code, "detalhe do servidor", emptyList())

private fun raApproval() =
    ApiError.ApprovalRequired("REACTIVATE", "reservations.reservations.reactivate", "res", null, null)

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class ReactivateApprover(
    var cancel: Boolean = false,
) {
    var calls = 0
    val received = mutableListOf<ApprovableAction<ReservationDetailResponse>>()

    suspend fun approve(action: ApprovableAction<ReservationDetailResponse>): ApiResponse<ReservationDetailResponse>? {
        calls++
        received += action
        return try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationReactivateStateTest {
    private val approver = ReactivateApprover()
    private var changes = 0

    private fun TestScope.state(source: FakeReservationReactivateSource) =
        ReservationReactivateState(source, backgroundScope, raRes.toString(), RA_CODE, approver::approve) {
            changes++
        }

    private fun TestScope.confirmNow(state: ReservationReactivateState) {
        state.confirm()
        runCurrent()
    }

    private fun tokenlessKeys(source: FakeReservationReactivateSource) =
        source.runs.filter { it.token == null }.map { it.key }

    @Test
    fun confirmGoesThroughApprove() =
        runTest {
            val source = raSource().apply { requiresApproval = raApproval() }
            val state = state(source)

            confirmNow(state)

            assertEquals(raRes.toString(), source.calls.single().id)
            assertSame(source.actions.single(), approver.received.single())
            assertEquals(listOf(null, "tok"), source.runs.map { it.token })
            assertEquals("Reserva R1 reativada.", state.done)
            assertNull(state.error)
            assertFalse(state.busy)
            assertEquals(1, changes)
        }

    @Test
    fun approvalCancelledKeepsIntentAndDialog() =
        runTest {
            val source = raSource().apply { requiresApproval = raApproval() }
            approver.cancel = true
            val state = state(source)

            confirmNow(state)
            assertNull(state.done)
            assertFalse(state.busy)
            assertFalse(state.unconfirmed)
            assertNull(state.error)
            assertEquals(0, changes)
            approver.cancel = false
            confirmNow(state)

            assertSame(source.calls[0].intent, source.calls[1].intent)
            assertEquals(2, tokenlessKeys(source).size)
            assertEquals(tokenlessKeys(source)[0], tokenlessKeys(source)[1])
            assertEquals("Reserva R1 reativada.", state.done)
        }

    @Test
    fun unknownResultKeepsIntent() =
        runTest {
            val source = raSource().apply { outcomes += ApiError.Network(IOException("offline")) }
            val state = state(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertNull(state.done)
            assertEquals(RA_UNCONFIRMED_TEXT, state.error)
            assertEquals(0, changes)
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
            assertSame(source.calls[0].intent, source.calls[1].intent)
            assertEquals("Reserva R1 reativada.", state.done)
            assertFalse(state.unconfirmed)
            assertNull(state.error)
        }

    @Test
    fun keyInUseAndGatewayKeepIntent() =
        runTest {
            val source =
                raSource().apply {
                    outcomes += raProblem(409, "IDEMPOTENCY_KEY_IN_USE")
                    outcomes += ApiError.Unexpected(502)
                }
            val state = state(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertEquals(RA_UNCONFIRMED_TEXT, state.error)
            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertEquals(RA_UNCONFIRMED_TEXT, state.error)
            confirmNow(state)

            assertSame(source.calls[0].intent, source.calls[1].intent)
            assertSame(source.calls[1].intent, source.calls[2].intent)
            assertNotNull(state.done)
        }

    @Test
    fun serverErrorShowsServerUnconfirmedText() =
        runTest {
            val source = raSource().apply { outcomes += raProblem(500, "INTERNAL_ERROR") }
            val state = state(source)

            confirmNow(state)

            assertTrue(state.unconfirmed)
            assertEquals(RA_SERVER_UNCONFIRMED_TEXT, state.error)
            confirmNow(state)
            assertSame(source.calls[0].intent, source.calls[1].intent)
        }

    @Test
    fun refusalShowsCatalogText() =
        runTest {
            val source =
                raSource().apply {
                    outcomes += raProblem(422, "RESERVATION_STAY_ENDED")
                    outcomes += raProblem(409, "ROOM_TYPE_UNAVAILABLE")
                }
            val state = state(source)

            confirmNow(state)
            assertEquals("A estadia desta reserva já terminou.", state.error)
            assertFalse(state.unconfirmed)
            assertNull(state.done)

            confirmNow(state)
            assertEquals("Não há UH disponível deste tipo em todas as noites.", state.error)
            assertFalse(state.error.orEmpty().contains("ROOM_TYPE_UNAVAILABLE"))
            assertFalse(state.error.orEmpty().contains("RESERVATION_STAY_ENDED"))
            assertEquals(0, changes)

            val keys = tokenlessKeys(source)
            assertEquals(2, keys.size)
            assertNotEquals(keys[0], keys[1])
            assertNotSame(source.calls[0].intent, source.calls[1].intent)
        }

    @Test
    fun doneShowsOnlyClose() =
        runTest {
            val source = raSource()
            val state = state(source)

            confirmNow(state)
            assertEquals("Reserva R1 reativada.", state.done)

            confirmNow(state)
            assertEquals(1, source.calls.size)
            assertEquals(1, changes)
        }

    @Test
    fun submitDisabledWhileBusy() =
        runTest {
            val source = raSource().apply { hold = true }
            val state = state(source)

            state.confirm()
            runCurrent()
            assertTrue(state.busy)
            state.confirm()
            runCurrent()
            assertEquals(1, source.calls.size)

            source.release()
            runCurrent()
            assertFalse(state.busy)
            assertNotNull(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun closeDropsLateAnswer() =
        runTest {
            val source = raSource().apply { hold = true }
            val state = state(source)

            state.confirm()
            runCurrent()
            state.close()
            source.release()
            runCurrent()

            assertNull(state.done)
            assertEquals(0, changes)
            state.confirm()
            runCurrent()
            assertEquals(1, source.calls.size)
        }

    @Test
    fun onChangedOnlyOnSuccess() =
        runTest {
            val source =
                raSource().apply {
                    outcomes += ApiError.Network(IOException("offline"))
                    outcomes += raProblem(422, "RESERVATION_STAY_ENDED")
                }
            val state = state(source)

            confirmNow(state)
            assertEquals(0, changes)
            confirmNow(state)
            assertEquals(0, changes)
            confirmNow(state)
            assertEquals(1, changes)
            confirmNow(state)
            assertEquals(1, changes)
        }
}

@OptIn(ExperimentalTestApi::class)
class ReservationReactivateDialogTest {
    private var changes = 0
    private var closes = 0
    private val approver = ReactivateApprover()

    private fun ComposeUiTest.dialog(source: FakeReservationReactivateSource): ReservationReactivateState {
        lateinit var created: ReservationReactivateState
        setContent {
            val scope = rememberCoroutineScope()
            val state =
                remember {
                    ReservationReactivateState(source, scope, raRes.toString(), RA_CODE, approver::approve) {
                        changes++
                    }.also { created = it }
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
    fun asksBeforeReactivating() =
        runComposeUiTest {
            val source = raSource()
            dialog(source)

            onNodeWithTag("reactivate-question").assertTextEquals(RA_QUESTION)
            onNodeWithTag("reactivate-submit").assertIsEnabled()
            onNodeWithTag("reactivate-close").assertIsEnabled()
            onNodeWithTag("reactivate-done").assertDoesNotExist()
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun submitAndCloseDisabledWhileBusy() =
        runComposeUiTest {
            val source = raSource().apply { hold = true }
            dialog(source)

            click("reactivate-submit")

            onNodeWithTag("reactivate-submit").assertIsNotEnabled()
            onNodeWithTag("reactivate-close").assertIsNotEnabled()
            assertEquals(1, source.calls.size)
            source.held.single().complete(Unit)
            waitForIdle()
            onNodeWithTag("reactivate-done").assertExists()
        }

    @Test
    fun doneShowsOnlyClose() =
        runComposeUiTest {
            val state = dialog(raSource().apply { requiresApproval = raApproval() })

            click("reactivate-submit")

            onNodeWithTag("reactivate-done").assertTextEquals("Reserva R1 reativada.")
            onNodeWithTag("reactivate-question").assertDoesNotExist()
            onNodeWithTag("reactivate-submit").assertDoesNotExist()
            assertEquals(1, changes)
            click("reactivate-close")
            assertEquals(1, closes)
            assertNotNull(state.done)
        }

    @Test
    fun refusalIsShownWithoutCode() =
        runComposeUiTest {
            val source = raSource().apply { outcomes += raProblem(422, "RESERVATION_STAY_ENDED") }
            dialog(source)

            click("reactivate-submit")

            onNodeWithTag("reactivate-error").assertTextEquals("A estadia desta reserva já terminou.")
            onNodeWithText("RESERVATION_STAY_ENDED", substring = true).assertDoesNotExist()
            onNodeWithTag("reactivate-submit").assertIsEnabled()
            onNodeWithTag("reactivate-question").assertExists()
        }

    @Test
    fun unconfirmedKeepsRetryAndClose() =
        runComposeUiTest {
            val source = raSource().apply { outcomes += ApiError.Network(IOException("caiu")) }
            dialog(source)

            click("reactivate-submit")

            onNodeWithTag("reactivate-error").assertTextEquals(RA_UNCONFIRMED_TEXT)
            onNodeWithTag("reactivate-submit").assertIsEnabled()
            onNodeWithTag("reactivate-close").assertIsEnabled()
            click("reactivate-submit")
            onNodeWithTag("reactivate-done").assertExists()
            assertSame(source.calls[0].intent, source.calls[1].intent)
        }

    @Test
    fun closeClosesStateThenCallsOnClose() =
        runComposeUiTest {
            val source = raSource()
            val state = dialog(source)

            click("reactivate-close")

            assertEquals(1, closes)
            state.confirm()
            waitForIdle()
            assertTrue(source.calls.isEmpty())
        }
}
