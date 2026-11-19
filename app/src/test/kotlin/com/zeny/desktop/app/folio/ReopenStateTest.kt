package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.FolioResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UNCONFIRMED =
    "Não foi possível confirmar a reabertura. A conta foi relida; confira antes de tentar de novo."
private const val NOT_CLOSED = "A conta ainda não está fechada."

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class ReopenApprover(
    var cancel: Boolean = false,
) {
    var asked = 0

    suspend fun approve(action: ApprovableAction<FolioResponse>): ApiResponse<FolioResponse>? {
        asked++
        return try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReopenStateTest {
    private val approver = ReopenApprover()
    private var done = 0
    private var rereads = 0

    private fun TestScope.reopen(source: FakeFolioAccountSource) =
        ReopenState(
            source,
            backgroundScope,
            FOLIO_ID.toString(),
            approver::approve,
            { done++ },
            { rereads++ },
        )

    private fun approval() =
        ApiError.ApprovalRequired("billing.folio.reopen", "billing.folios.reopen", "folio", null, null)

    private fun ok() = ApiResponse(accountFolioOf(), null, 200)

    @Test
    fun reopenAsksApprovalAndRereads() =
        runTest {
            val tokens = mutableListOf<String?>()
            val source =
                FakeFolioAccountSource().apply {
                    reopenAction = {
                        ApprovableAction { token ->
                            tokens += token
                            if (token == null) throw approval() else ok()
                        }
                    }
                }
            val state = reopen(source)

            state.reason = "  Fechada por engano  "
            state.confirm()
            runCurrent()

            assertEquals(listOf(null, "tok"), tokens, "a aprovação passa pelo approve")
            assertEquals("Fechada por engano", source.reopens.single().reason)
            assertEquals(FOLIO_ID.toString(), source.reopens.single().folioId)
            assertEquals(1, done)
            assertFalse(state.busy)
        }

    @Test
    fun reopenApprovalCancelledSaysSo() =
        runTest {
            approver.cancel = true
            val source =
                FakeFolioAccountSource().apply { reopenAction = { ApprovableAction { throw approval() } } }
            val state = reopen(source)

            state.reason = "Engano"
            state.confirm()
            runCurrent()

            assertEquals("Aprovação cancelada. A conta não foi reaberta.", state.message)
            assertEquals(0, done)
            assertFalse(state.busy)
            assertTrue(state.canConfirm)
        }

    @Test
    fun doubleConfirmReopensOnce() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source =
                FakeFolioAccountSource().apply {
                    reopenAction = { ApprovableAction { gate.await().let { ok() } } }
                }
            val state = reopen(source)

            state.reason = "Engano"
            state.confirm()
            state.confirm()
            runCurrent()
            assertTrue(state.busy)
            assertFalse(state.canConfirm)
            gate.complete(Unit)
            runCurrent()

            assertEquals(1, source.reopens.size)
            assertEquals(1, approver.asked)
            assertEquals(1, done)
        }

    @Test
    fun emptyReasonIsRefusedWithText() =
        runTest {
            val source = FakeFolioAccountSource()
            val state = reopen(source)

            state.reason = "   "
            state.confirm()
            runCurrent()

            assertEquals("Informe o motivo.", state.message)
            assertTrue(source.reopens.isEmpty())
            state.reason = "a".repeat(500)
            state.reason = "a".repeat(501)
            assertEquals(500, state.reason.length, "a edição acima de 500 é recusada")
        }

    @Test
    fun reopenUnknownRereadsAndAllowsRetryByClick() =
        runTest {
            val failures =
                listOf<ApiError>(
                    ApiError.Network(IOException("caiu")),
                    ApiError.Problem(503, "X", null, emptyList()),
                )
            failures.forEach { failure ->
                rereads = 0
                var calls = 0
                val source =
                    FakeFolioAccountSource().apply {
                        reopenAction = { ApprovableAction { if (calls++ == 0) throw failure else ok() } }
                    }
                val state = reopen(source)
                state.reason = "Engano"

                state.confirm()
                runCurrent()

                assertEquals(UNCONFIRMED, state.message, "$failure")
                assertEquals(1, rereads)
                assertTrue(state.unconfirmed)
                assertTrue(state.canConfirm, "destrava para repetir por clique")
                assertEquals(1, source.reopens.size, "sem repetição sozinha")

                state.confirm()
                runCurrent()
                assertEquals(2, source.reopens.size)
                assertNull(state.message)
            }
        }

    @Test
    fun reopenNotClosedShowsTextAndRereads() =
        runTest {
            val source =
                FakeFolioAccountSource().apply {
                    reopenAction = {
                        ApprovableAction { throw ApiError.Problem(409, "FOLIO_NOT_CLOSED", null, emptyList()) }
                    }
                }
            val state = reopen(source)
            state.reason = "Engano"

            state.confirm()
            runCurrent()

            assertEquals(NOT_CLOSED, state.message)
            assertEquals(1, rereads)
            assertEquals(0, done)
            assertFalse(state.unconfirmed)
            assertTrue(state.canConfirm)
        }

    @Test
    fun closeDropsLateAnswers() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source =
                FakeFolioAccountSource().apply {
                    reopenAction = { ApprovableAction { gate.await().let { ok() } } }
                }
            val state = reopen(source)
            state.reason = "Engano"
            state.confirm()
            runCurrent()

            state.close()
            gate.complete(Unit)
            runCurrent()

            assertEquals(0, done)
            assertEquals(0, rereads)
            assertFalse(state.canConfirm)
            state.confirm()
            runCurrent()
            assertEquals(1, source.reopens.size)
        }

    @Test
    fun toStringKeepsTheReasonOut() =
        runTest {
            val state = reopen(FakeFolioAccountSource())
            state.reason = "segredo do motivo"
            assertFalse(state.toString().contains("segredo"))
        }
}
