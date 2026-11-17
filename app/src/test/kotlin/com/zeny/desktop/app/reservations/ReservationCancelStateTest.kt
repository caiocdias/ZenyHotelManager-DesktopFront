package com.zeny.desktop.app.reservations

import androidx.compose.runtime.snapshots.Snapshot
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.CancelInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.CancellationPenaltyResponse
import com.zeny.desktop.sdk.model.CancellationResponse
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val rxRes = Uuid.parse("20000000-0000-0000-0000-0000000000c1")
private const val RX_TERMS = "Cancelamento sem multa até 24 h antes do check-in; depois, multa de 1 diária(s)."

private const val RX_UNCONFIRMED_TEXT =
    "Não foi possível confirmar o cancelamento. Conclua de novo sem mudar nada; " +
        "se fechar, confira a reserva na Busca antes de refazer."

private const val RX_SERVER_UNCONFIRMED_TEXT =
    "O servidor não confirmou o cancelamento. Feche e confira a reserva na Busca antes de refazer."

private fun rxCancellation(
    amount: String,
    waived: Boolean,
) = CancellationResponse(
    cancelledAt = "2026-11-16T12:00:00Z",
    penalty = CancellationPenaltyResponse(amount = amount, deadline = "2026-11-15T17:00:00Z", waived = waived),
    previousStatus = "CONFIRMED",
    reason = "GUEST_REQUEST",
)

private fun rxDetail(
    status: String = "CONFIRMED",
    cancellation: CancellationResponse? = null,
) = ReservationDetailResponse(
    bookingCode = "B-1",
    holderGuestId = Uuid.random(),
    reservation =
        ReservationResponse(
            additionalPackagesTotal = "0.00",
            adults = 2,
            arrival = "2026-11-20",
            bookingId = Uuid.random(),
            cancellation = if (cancellation != null) Option.Some(cancellation) else Option.Undefined,
            cancellationTerms =
                CancellationTermsResponse(
                    deadlineHours = 24,
                    name = "Flexível",
                    penaltyNights = Option.Some(1),
                    penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                    policyId = Uuid.random(),
                    version = 1,
                ),
            childAges = emptyList(),
            code = "R1",
            createdAt = "2026-11-10T12:00:00Z",
            dayUse = false,
            departure = "2026-11-23",
            folioId = Uuid.random(),
            guaranteeTerms =
                GuaranteeTermsResponse(
                    name = "Sem garantia",
                    policyId = Uuid.random(),
                    type = "NONE",
                    version = 1,
                ),
            guests = emptyList(),
            id = rxRes,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
            ratePlanId = Uuid.random(),
            roomTotal = "960.00",
            roomTypeId = Uuid.random(),
            segment = "LEISURE",
            status = status,
            total = "960.00",
            version = 3,
        ),
    restrictionAlerts = emptyList(),
)

private fun rxProblem(
    status: Int,
    code: String,
) = ApiError.Problem(status, code, "detalhe do servidor", emptyList())

private fun rxApproval() =
    ApiError.ApprovalRequired("PENALTY_WAIVER", "reservations.penalties.waive", "res", null, null)

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class RxApprover(
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
@Suppress("TooManyFunctions") // um teste por regra do brief
class ReservationCancelStateTest {
    private val id = rxRes.toString()
    private var changes = 0
    private val approver = RxApprover()

    private fun source() =
        FakeReservationCancelSource().apply {
            reservations[id] = rxDetail()
            response = rxDetail(status = "CANCELLED", cancellation = rxCancellation("320.00", false))
        }

    private fun TestScope.state(source: FakeReservationCancelSource) =
        ReservationCancelState(source, backgroundScope, id, approver::approve) { changes++ }

    private fun TestScope.started(source: FakeReservationCancelSource): ReservationCancelState =
        state(source).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.confirmNow(state: ReservationCancelState) {
        state.confirm()
        runCurrent()
    }

    private fun tokenlessKeys(source: FakeReservationCancelSource) =
        source.runs.filter { it.token == null }.map { it.key }

    @Test
    fun termsShownBeforeConfirm() =
        runTest {
            val source = source()
            val state = state(source)
            assertNull(state.terms)

            state.start()
            runCurrent()

            assertEquals(listOf(id), source.readCalls)
            assertEquals(RX_TERMS, state.terms)
            assertEquals("R1", state.code)
            assertFalse(state.loading)
            assertNull(state.error)
            assertTrue(source.calls.isEmpty())
            assertTrue(state.canConfirm)
        }

    @Test
    fun confirmNeedsTerms() =
        runTest {
            val source = source().apply { holdRead = true }
            val state = state(source)

            state.start()
            runCurrent()
            assertTrue(state.loading)
            assertFalse(state.canConfirm)
            state.confirm()
            runCurrent()
            assertTrue(source.calls.isEmpty())
            assertTrue(source.runs.isEmpty())

            source.releaseRead()
            runCurrent()
            assertTrue(state.canConfirm)
        }

    @Test
    fun failedLoadBlocksConfirmAndStartRetries() =
        runTest {
            val failure = rxProblem(404, "RESERVATION_NOT_FOUND")
            val source = source().apply { readError = failure }
            val state = started(source)

            assertEquals(failure.userMessage, state.error)
            assertNull(state.terms)
            assertFalse(state.loading)
            assertFalse(state.canConfirm)
            state.confirm()
            runCurrent()
            assertTrue(source.calls.isEmpty())

            source.readError = null
            state.start()
            runCurrent()

            assertEquals(2, source.readCalls.size)
            assertEquals(RX_TERMS, state.terms)
            assertNull(state.error)
            assertTrue(state.canConfirm)
        }

    /**
     * O Compose só recompõe o que leu estado observável: o que `canConfirm` lê antes da carga tem de mudar quando
     * ela termina. Lido fora de um snapshot, o valor certo esconderia a falta de assinatura.
     */
    @Test
    fun canConfirmIsObservable() =
        runTest {
            val state = state(source())
            val read = mutableSetOf<Any>()
            Snapshot.takeSnapshot(readObserver = { read += it }).run { enter { assertFalse(state.canConfirm) } }
            val written = mutableSetOf<Any>()
            val handle = Snapshot.registerApplyObserver { changed, _ -> written.addAll(changed) }
            try {
                state.start()
                runCurrent()
                Snapshot.sendApplyNotifications()
            } finally {
                handle.dispose()
            }

            assertTrue(state.canConfirm)
            assertTrue(read.any { it in written }, "nada do que canConfirm lia antes da carga mudou")
        }

    @Test
    fun canConfirmIsObservableAfterClose() =
        runTest {
            val state = started(source())
            val read = mutableSetOf<Any>()
            Snapshot.takeSnapshot(readObserver = { read += it }).run { enter { assertTrue(state.canConfirm) } }
            val written = mutableSetOf<Any>()
            val handle = Snapshot.registerApplyObserver { changed, _ -> written.addAll(changed) }
            try {
                state.close()
                Snapshot.sendApplyNotifications()
            } finally {
                handle.dispose()
            }

            assertFalse(state.canConfirm)
            assertTrue(read.any { it in written }, "nada do que canConfirm lia mudou com o close")
        }

    @Test
    fun confirmSendsReasonNotesAndWaiver() =
        runTest {
            val source = source()
            val state = started(source)
            assertEquals("GUEST_REQUEST", state.reason)
            assertFalse(state.waivePenalty)

            state.reason = "DUPLICATE"
            state.notes = "Reserva repetida"
            state.waivePenalty = true
            confirmNow(state)

            val input = CancelInput("DUPLICATE", true, "Reserva repetida")
            assertEquals(listOf(CancelCall(id, input, source.calls[0].intent)), source.calls)
            assertEquals(1, changes)
        }

    @Test
    fun defaultsGoAsGuestRequestWithoutWaiver() =
        runTest {
            val source = source()
            val state = started(source)

            confirmNow(state)

            assertEquals(CancelInput("GUEST_REQUEST", false, null), source.calls.single().input)
        }

    @Test
    fun blankNotesGoAsNull() =
        runTest {
            val source = source()
            val state = started(source)
            state.notes = "   "

            confirmNow(state)

            val sent = source.calls.single().input
            assertNull(sent.notes)
        }

    @Test
    fun notesOverLimitAreRefused() =
        runTest {
            val state = started(source())
            val limit = "a".repeat(500)

            state.notes = limit
            assertEquals(limit, state.notes)
            state.notes = limit + "b"

            assertEquals(limit, state.notes)
        }

    @Test
    fun reasonsAreTheOfferedOnes() =
        runTest {
            assertEquals(
                listOf(
                    "GUEST_REQUEST" to "Pedido do hóspede",
                    "HOTEL_REQUEST" to "Decisão do hotel",
                    "DUPLICATE" to "Reserva duplicada",
                    "NO_GUARANTEE" to "Sem garantia",
                    "OTHER" to "Outro motivo",
                ),
                CANCEL_REASONS,
            )
            val state = started(source())

            state.reason = "EXPIRED"
            assertEquals("GUEST_REQUEST", state.reason)
            state.reason = "guest_request"
            assertEquals("GUEST_REQUEST", state.reason)
            state.reason = "OTHER"
            assertEquals("OTHER", state.reason)
        }

    @Test
    fun doneFollowsServerPenalty() =
        runTest {
            val cases =
                listOf(
                    Triple("320.00", false, "Reserva R1 cancelada. Multa lançada na conta: R$ 320,00."),
                    Triple("320.00", true, "Reserva R1 cancelada. Multa isenta: R$ 320,00."),
                    Triple("0.00", false, "Reserva R1 cancelada. Sem multa."),
                )
            for ((amount, waived, expected) in cases) {
                val source = source().apply { response = rxDetail("CANCELLED", rxCancellation(amount, waived)) }
                val state = started(source)
                state.waivePenalty = amount == "0.00"

                confirmNow(state)

                assertEquals(expected, state.done)
                assertNull(state.error)
            }
        }

    @Test
    fun doneWithoutCancellationIsJustCancelled() =
        runTest {
            val source = source().apply { response = rxDetail("CANCELLED", null) }
            val state = started(source)

            confirmNow(state)

            assertEquals("Reserva R1 cancelada.", state.done)
        }

    @Test
    fun waiverGoesThroughApprove() =
        runTest {
            val source = source().apply { requiresApproval = rxApproval() }
            val state = started(source)
            state.waivePenalty = true

            confirmNow(state)

            assertSame(source.actions.single(), approver.received.single())
            assertEquals(listOf(null, "tok"), source.runs.map { it.token })
            val sent = source.calls.single().input
            assertTrue(sent.waivePenalty)
            assertEquals("Reserva R1 cancelada. Multa lançada na conta: R$ 320,00.", state.done)
            assertEquals(1, changes)
        }

    @Test
    fun approvalCancelledLeavesReservation() =
        runTest {
            val source = source().apply { requiresApproval = rxApproval() }
            approver.cancel = true
            val state = started(source)
            state.waivePenalty = true

            confirmNow(state)
            assertNull(state.done)
            assertFalse(state.busy)
            assertFalse(state.unconfirmed)
            assertNull(state.error)
            assertEquals(0, changes)
            assertTrue(state.canConfirm)
            approver.cancel = false
            confirmNow(state)

            assertSame(source.calls[0].intent, source.calls[1].intent)
            assertEquals(2, tokenlessKeys(source).size)
            assertEquals(tokenlessKeys(source)[0], tokenlessKeys(source)[1])
            assertEquals("Reserva R1 cancelada. Multa lançada na conta: R$ 320,00.", state.done)
        }

    @Test
    fun cancelledApprovalAfterUnknownResultUnlocksTheForm() =
        runTest {
            val reference = source().apply { requiresApproval = rxApproval() }
            approver.cancel = true
            val referenceState = started(reference)
            referenceState.waivePenalty = true
            confirmNow(referenceState)
            approver.cancel = false
            confirmNow(referenceState)

            val source = source().apply { outcomes += ApiError.Network(IOException("offline")) }
            val state = started(source)
            state.waivePenalty = true
            confirmNow(state)
            assertTrue(state.unconfirmed)
            source.requiresApproval = rxApproval()
            approver.cancel = true
            confirmNow(state)

            assertFalse(state.unconfirmed)
            assertNull(state.error)
            assertNull(state.done)
            assertFalse(state.busy)
            assertTrue(state.canConfirm)
            approver.cancel = false
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(3, keys.size)
            assertEquals(1, keys.toSet().size)
            assertSame(source.calls[0].intent, source.calls[2].intent)
            assertEquals(1, tokenlessKeys(reference).toSet().size)
            assertEquals(tokenlessKeys(reference).size, keys.size - 1)
            assertEquals("Reserva R1 cancelada. Multa lançada na conta: R$ 320,00.", state.done)
        }

    @Test
    fun inputsAcceptEditsAfterCancelledApprovalFollowingUnknownResult() =
        runTest {
            val source = source().apply { outcomes += ApiError.Network(IOException("offline")) }
            val state = started(source)
            state.waivePenalty = true
            confirmNow(state)
            source.requiresApproval = rxApproval()
            approver.cancel = true
            confirmNow(state)

            state.notes = "Pedido do hóspede"

            assertEquals("Pedido do hóspede", state.notes)
        }

    @Test
    fun unknownResultKeepsIntent() =
        runTest {
            val source = source().apply { outcomes += ApiError.Network(IOException("offline")) }
            val state = started(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertNull(state.done)
            assertEquals(RX_UNCONFIRMED_TEXT, state.error)
            assertEquals(0, changes)
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
            assertSame(source.calls[0].intent, source.calls[1].intent)
            assertEquals("Reserva R1 cancelada. Multa lançada na conta: R$ 320,00.", state.done)
            assertFalse(state.unconfirmed)
            assertNull(state.error)
        }

    @Test
    fun keyInUseKeepsIntent() =
        runTest {
            val source = source().apply { outcomes += rxProblem(409, "IDEMPOTENCY_KEY_IN_USE") }
            val state = started(source)

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertEquals(RX_UNCONFIRMED_TEXT, state.error)
            confirmNow(state)

            assertSame(source.calls[0].intent, source.calls[1].intent)
            assertTrue(state.done != null)
        }

    @Test
    fun serverErrorShowsServerUnconfirmedText() =
        runTest {
            val source = source().apply { outcomes += rxProblem(500, "INTERNAL_ERROR") }
            val state = started(source)

            confirmNow(state)

            assertTrue(state.unconfirmed)
            assertEquals(RX_SERVER_UNCONFIRMED_TEXT, state.error)
            confirmNow(state)
            assertSame(source.calls[0].intent, source.calls[1].intent)
        }

    @Test
    fun gatewayErrorShowsUnknownResultText() =
        runTest {
            val source = source().apply { outcomes += ApiError.Unexpected(502) }
            val state = started(source)

            confirmNow(state)

            assertTrue(state.unconfirmed)
            assertEquals(RX_UNCONFIRMED_TEXT, state.error)
        }

    @Test
    fun changedInputTakesNewIntent() =
        runTest {
            val source = source().apply { requiresApproval = rxApproval() }
            approver.cancel = true
            val state = started(source)

            confirmNow(state)
            state.reason = "OTHER"
            confirmNow(state)
            state.notes = "Motivo detalhado"
            confirmNow(state)
            state.waivePenalty = true
            confirmNow(state)

            val keys = tokenlessKeys(source)
            assertEquals(4, keys.size)
            assertEquals(4, keys.toSet().size)
        }

    @Test
    fun refusalShowsCatalogText() =
        runTest {
            val refusal = rxProblem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
            val source = source().apply { outcomes += refusal }
            val state = started(source)

            confirmNow(state)

            assertEquals("Esta ação não é permitida na situação atual da reserva.", state.error)
            assertEquals(refusal.userMessage, state.error)
            assertFalse(state.error.orEmpty().contains("RESERVATION_TRANSITION_NOT_ALLOWED"))
            assertFalse(state.unconfirmed)
            assertNull(state.done)
            assertEquals(0, changes)

            confirmNow(state)
            val keys = tokenlessKeys(source)
            assertNotEquals(keys[0], keys[1])
            assertNotSame(source.calls[0].intent, source.calls[1].intent)
        }

    @Test
    fun lockedWhileBusyAndAfterDone() =
        runTest {
            val source = source().apply { hold = true }
            val state = started(source)

            state.confirm()
            runCurrent()
            assertTrue(state.busy)
            assertFalse(state.canConfirm)
            state.reason = "OTHER"
            state.notes = "x"
            state.waivePenalty = true
            state.confirm()
            runCurrent()
            assertEquals("GUEST_REQUEST", state.reason)
            assertEquals("", state.notes)
            assertFalse(state.waivePenalty)
            assertEquals(1, source.calls.size)

            source.release()
            runCurrent()
            assertTrue(state.done != null)
            assertFalse(state.busy)

            state.reason = "OTHER"
            state.notes = "x"
            state.waivePenalty = true
            state.confirm()
            runCurrent()
            assertEquals("GUEST_REQUEST", state.reason)
            assertEquals("", state.notes)
            assertFalse(state.waivePenalty)
            assertFalse(state.canConfirm)
            assertEquals(1, source.calls.size)
            assertEquals(1, changes)
        }

    @Test
    fun lockedWhileUnconfirmed() =
        runTest {
            val source = source().apply { outcomes += ApiError.Network(IOException("offline")) }
            val state = started(source)
            state.notes = "Pedido por telefone"

            confirmNow(state)
            assertTrue(state.unconfirmed)
            assertTrue(state.canConfirm)

            state.reason = "OTHER"
            state.notes = "outra"
            state.waivePenalty = true
            runCurrent()

            assertEquals("GUEST_REQUEST", state.reason)
            assertEquals("Pedido por telefone", state.notes)
            assertFalse(state.waivePenalty)
            assertEquals(RX_UNCONFIRMED_TEXT, state.error)

            confirmNow(state)
            assertEquals(source.calls[0].input, source.calls[1].input)
            assertTrue(state.done != null)
        }

    @Test
    fun closeDropsLateAnswer() =
        runTest {
            val source = source().apply { hold = true }
            val state = started(source)

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
    fun closeDropsLateRead() =
        runTest {
            val source = source().apply { holdRead = true }
            val state = state(source)

            state.start()
            runCurrent()
            state.close()
            source.releaseRead()
            runCurrent()

            assertNull(state.terms)
            assertFalse(state.canConfirm)
        }

    @Test
    fun onChangedOnlyOnSuccess() =
        runTest {
            val source =
                source().apply {
                    outcomes += ApiError.Network(IOException("offline"))
                    outcomes += rxProblem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
                }
            val state = started(source)

            confirmNow(state)
            assertEquals(0, changes)
            confirmNow(state)
            assertEquals(0, changes)
            confirmNow(state)
            assertEquals(1, changes)
            confirmNow(state)
            assertEquals(1, changes)
        }

    @Test
    fun toStringHidesNotes() =
        runTest {
            val state = started(source())
            state.notes = "hóspede com problema de saúde"

            assertFalse(state.toString().contains("saúde"))
        }
}
