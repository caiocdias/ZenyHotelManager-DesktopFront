package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.PostingResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val REVERSE = "billing.postings.reverse"
private val ORIGINAL = Uuid.parse("50000000-0000-0000-0000-000000000001")
private val REVERSAL = Uuid.parse("50000000-0000-0000-0000-000000000002")
private const val UNCONFIRMED =
    "Não foi possível confirmar o estorno. Estorne de novo sem mudar nada; se fechar, confira a conta antes de refazer."
private const val SERVER_UNCONFIRMED =
    "O servidor não confirmou o estorno. Feche e confira a conta antes de refazer."

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class Approver(
    var cancel: Boolean = false,
) {
    val received = mutableListOf<ApprovableAction<PostingResponse>>()

    suspend fun approve(action: ApprovableAction<PostingResponse>): ApiResponse<PostingResponse>? {
        received += action
        return try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra do brief
class ReversalStateTest {
    private val approver = Approver()
    private var changes = 0

    private fun TestScope.reversal(source: FakeFolioSource) =
        ReversalState(source, backgroundScope, FOLIO_ID.toString(), ORIGINAL.toString(), approver::approve) {
            changes++
        }

    private fun approval() = ApiError.ApprovalRequired("POSTING_REVERSAL", REVERSE, "posting", null, null)

    @Test
    fun reversalNeedsReason() =
        runTest {
            val source = FakeFolioSource()
            val state = reversal(source)

            state.reason = "   "
            state.confirm()
            runCurrent()

            assertEquals("Informe o motivo do estorno.", state.message)
            assertTrue(source.reverseCalls.isEmpty())

            state.reason = "a".repeat(500)
            state.reason = "a".repeat(501)
            assertEquals(500, state.reason.length, "o motivo passa de 500 caracteres")

            state.reason = "  Cobrado em dobro  "
            state.confirm()
            runCurrent()
            assertEquals("Cobrado em dobro", source.reverseCalls.single().reason)
            assertEquals(ORIGINAL.toString(), source.reverseCalls.single().postingId)
            assertEquals(FOLIO_ID.toString(), source.reverseCalls.single().folioId)
        }

    @Test
    fun approvalRequiredGoesThroughApprove() =
        runTest {
            val source = FakeFolioSource().apply { requiresApproval = approval() }
            val state = reversal(source)
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertSame(source.actions.single(), approver.received.single())
            val key =
                source.reverseCalls
                    .single()
                    .intent.key
            assertEquals(listOf(ReverseRun(key, null), ReverseRun(key, "tok")), source.reverseRuns)
            assertTrue(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun cancelledApprovalLeavesTextAndUnlocks() =
        runTest {
            approver.cancel = true
            val source = FakeFolioSource().apply { requiresApproval = approval() }
            val state = reversal(source)
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertEquals("Aprovação cancelada. O estorno não foi feito.", state.message)
            assertFalse(state.busy)
            assertFalse(state.done)
            assertTrue(state.canConfirm)
            state.reason = "Outro motivo"
            assertEquals("Outro motivo", state.reason, "as entradas destravam")
            assertEquals(1, source.reverseRuns.size)
            assertEquals(0, changes)
        }

    @Test
    fun retryAfterNetworkKeepsTheKey() =
        runTest {
            val source = FakeFolioSource()
            source.reverseOutcomes += ApiError.Network(IOException("sem rede"))
            val state = reversal(source)
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertTrue(state.unconfirmed)
            assertEquals(UNCONFIRMED, state.message)
            state.reason = "Outro"
            assertEquals("Cobrado em dobro", state.reason)

            state.confirm()
            runCurrent()

            assertEquals(2, source.reverseCalls.size)
            assertSame(source.reverseCalls[0].intent, source.reverseCalls[1].intent)
            assertEquals(source.reverseRuns[0].key, source.reverseRuns[1].key)
            assertTrue(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun storedFailureIsCloseOnly() =
        runTest {
            val source = FakeFolioSource()
            source.reverseOutcomes += ApiError.Problem(502, "BAD_GATEWAY", null, emptyList())
            val state = reversal(source)
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertTrue(state.closeOnly)
            assertEquals(SERVER_UNCONFIRMED, state.message)
            assertFalse(state.canConfirm)
            state.confirm()
            runCurrent()
            assertEquals(1, source.reverseRuns.size)
            assertEquals(0, changes)
        }

    @Test
    fun closedFolioShowsMessageAndReloads() =
        runTest {
            val source = FakeFolioSource()
            source.postings = { listOf(postingOf(ORIGINAL, "Frigobar")) }
            source.reverseOutcomes += ApiError.Problem(409, "FOLIO_CLOSED", null, emptyList())
            val folio = started(source)
            folio.startReversal(ORIGINAL.toString())
            val state = assertNotNull(folio.reversal)
            state.reason = "Cobrado em dobro"
            source.folio = { folioOf(status = FolioResponse.Status.CLOSED) }

            state.confirm()
            runCurrent()

            assertEquals("A conta já está fechada.", state.message)
            assertFalse(state.busy)
            assertEquals(2, source.folioCalls.size)
            assertEquals(true, folio.view?.closed)
            assertFalse(folio.canReverse(folio.postings.single()))
        }

    @Test
    fun successReloadsAndKeepsTheOriginal() =
        runTest {
            val source = FakeFolioSource()
            source.postings = { listOf(postingOf(ORIGINAL, "Frigobar", "20.00")) }
            val folio = started(source)
            assertTrue(folio.canReverse(folio.postings.single()))
            source.reversal = { postingOf(REVERSAL, "Estorno: Frigobar", "-20.00", type = "REVERSAL") }
            source.folio = { folioOf(balance = "130.00") }
            source.postings = {
                listOf(
                    postingOf(ORIGINAL, "Frigobar", "20.00", reversedBy = REVERSAL),
                    postingOf(REVERSAL, "Estorno: Frigobar", "-20.00", type = "REVERSAL"),
                )
            }

            folio.startReversal(ORIGINAL.toString())
            val state = assertNotNull(folio.reversal)
            state.reason = "Cobrado em dobro"
            state.confirm()
            runCurrent()

            assertNull(folio.reversal, "o diálogo fecha depois do estorno")
            assertEquals(listOf("Frigobar", "Estorno: Frigobar"), folio.postings.map { it.description })
            assertEquals(listOf(true, false), folio.postings.map { it.reversed })
            assertEquals(listOf("Lançamento", "Estorno"), folio.postings.map { it.type })
            assertEquals("R$ 130,00", folio.view?.balance)
            assertTrue(folio.postings.none { folio.canReverse(it) })
        }

    private fun TestScope.started(source: FakeFolioSource) =
        FolioState(wiringOf(source, setOf(READ, REVERSE), approver::approve), backgroundScope, FOLIO_ID.toString())
            .also {
                it.start()
                runCurrent()
            }
}
