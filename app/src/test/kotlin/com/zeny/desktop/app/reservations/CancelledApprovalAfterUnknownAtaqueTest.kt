package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.http.ApiResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val caRes = Uuid.parse("20000000-0000-0000-0000-0000000000e6")
private const val CA_CODE = "R-E6"

/** Como o `ApprovalCoordinator`: sem token, e no `APPROVAL_REQUIRED` repete com token ou desiste (`null`). */
private class CaApprover {
    var cancel = false
    var asks = 0

    suspend fun <T : Any> approve(action: ApprovableAction<T>): ApiResponse<T>? =
        try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            asks++
            if (cancel) null else action.run("tok")
        }
}

private fun caNetwork() = ApiError.Network(IOException("offline"))

/**
 * Ataques da M3 E06, Task 6: a aprovação cancelada depois de um resultado desconhecido destrava o formulário, e a
 * `Idempotency-Key` segue a regra de cada estado (nova reserva e cancelamento trocam a chave ao editar; a reativação
 * não tem o que editar). Nada é enviado sem o usuário pedir, e o resultado desconhecido que não é seguido de
 * `APPROVAL_REQUIRED` continua travando.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelledApprovalAfterUnknownAtaqueTest {
    private val approver = CaApprover()
    private val guests = FakeReceptionSource().apply { businessDay = BusinessDay(etStay.arrival, ZoneId.of("UTC")) }

    // --- Nova reserva --------------------------------------------------------------------------------------------

    private fun newSource() = FakeNewReservationSource().apply { response = etResponse() }

    private fun TestScope.newState(source: FakeNewReservationSource) =
        NewReservationState(
            source,
            guests,
            backgroundScope,
            etOffer(),
            etStay,
            setOf("guests.profiles.read"),
            approver::approve,
        ) {}.also { it.picker.pick(etGuest) }

    private fun TestScope.go(state: NewReservationState) {
        state.confirm()
        runCurrent()
    }

    private fun tokenless(source: FakeNewReservationSource) = source.runs.filter { it.token == null }.map { it.key }

    /** Resultado desconhecido seguido de `APPROVAL_REQUIRED` cancelado: devolve o estado destravado. */
    private fun TestScope.unknownThenCancelled(source: FakeNewReservationSource): NewReservationState {
        source.outcomes += caNetwork()
        val state = newState(source)
        go(state)
        assertTrue(state.unconfirmed, "pré-condição: resultado desconhecido")
        source.requiresApproval = etApproval()
        approver.cancel = true
        go(state)
        return state
    }

    @Test
    fun newReservationResendAfterCancelledApprovalKeepsTheKeyAndNothingGoesOutUnasked() =
        runTest {
            val source = newSource()
            val state = unknownThenCancelled(source)

            assertFalse(state.unconfirmed)
            assertEquals(2, source.runs.size, "só o envio falho e o que pediu aprovação; nada além disso")
            runCurrent()
            assertEquals(2, source.runs.size)
            approver.cancel = false
            go(state)

            val keys = tokenless(source)
            assertEquals(3, keys.size)
            assertEquals(1, keys.toSet().size, "desconhecido, aprovação cancelada e reenvio: uma chave só")
            assertNotNull(state.done)
        }

    @Test
    fun newReservationEditAfterCancelledApprovalTakesANewKeyOnResend() =
        runTest {
            val source = newSource()
            val state = unknownThenCancelled(source)

            state.specialRequests = "Andar alto"
            assertEquals("Andar alto", state.specialRequests)
            approver.cancel = false
            go(state)

            val keys = tokenless(source)
            assertEquals(3, keys.size)
            assertEquals(keys[0], keys[1])
            assertNotEquals(keys[1], keys[2], "a edição depois do destravamento é outra intenção")
            assertEquals(
                "Andar alto",
                source.calls
                    .last()
                    .input.specialRequests,
            )
            assertNotNull(state.done)
            assertEquals(1, source.runs.count { it.token != null }, "uma execução aprovada só")
        }

    @Test
    fun newReservationHolderSwapAfterCancelledApprovalTakesANewKey() =
        runTest {
            val source = newSource()
            val state = unknownThenCancelled(source)

            state.picker.pick(etOtherGuest)
            approver.cancel = false
            go(state)

            val keys = tokenless(source)
            assertEquals(
                etOtherGuest.id,
                source.calls
                    .last()
                    .input.holderGuestId,
            )
            assertNotEquals(keys[1], keys[2])
            assertNotNull(state.done)
        }

    @Test
    fun newReservationSecondCancelledApprovalStaysUnlockedWithTheSameKey() =
        runTest {
            val source = newSource()
            val state = unknownThenCancelled(source)

            go(state)

            assertFalse(state.unconfirmed)
            assertFalse(state.busy)
            assertNull(state.done)
            assertNull(state.error)
            assertTrue(state.canConfirm)
            assertEquals(3, source.runs.size)
            assertEquals(2, approver.asks)
            assertEquals(1, tokenless(source).toSet().size)
            approver.cancel = false
            go(state)
            assertNotNull(state.done)
            assertEquals(1, tokenless(source).toSet().size)
        }

    @Test
    fun newReservationUnknownResultNotFollowedByApprovalKeepsLockedAndTheKey() =
        runTest {
            val source = newSource()
            source.outcomes += caNetwork()
            source.outcomes += caNetwork()
            val state = newState(source)

            go(state)
            go(state)

            assertTrue(state.unconfirmed, "outro resultado desconhecido: continua travado")
            state.origin = "PHONE"
            state.specialRequests = "x"
            state.picker.pick(etOtherGuest)
            assertEquals("FRONT_DESK", state.origin)
            assertEquals("", state.specialRequests)
            assertEquals(etGuest.id, state.holder?.id)
            assertEquals(0, approver.asks)
            go(state)
            assertEquals(1, tokenless(source).toSet().size)
            assertNotNull(state.done)
        }

    @Test
    fun newReservationCancelledApprovalWithoutPriorUnknownResultStaysUnlockedAndEditable() =
        runTest {
            val source = newSource().apply { requiresApproval = etApproval() }
            approver.cancel = true
            val state = newState(source)

            go(state)

            assertFalse(state.unconfirmed)
            state.segment = "BUSINESS"
            assertEquals("BUSINESS", state.segment)
            assertNull(state.done)
        }

    @Test
    fun newReservationRealCoordinatorCancelAfterUnknownUnlocksEditsAndResendsWithANewKey() =
        runTest {
            val source = newSource()
            val coordinator = ApprovalCoordinator({ _, _ -> "tok" }, emptyFlow(), backgroundScope)
            val state =
                NewReservationState(
                    source,
                    guests,
                    backgroundScope,
                    etOffer(),
                    etStay,
                    setOf("guests.profiles.read"),
                    { coordinator.run(it) },
                ) {}.also { it.picker.pick(etGuest) }
            source.outcomes += caNetwork()
            go(state)
            assertTrue(state.unconfirmed)
            source.requiresApproval = etApproval()

            go(state)
            assertNotNull(coordinator.pending, "o diálogo de aprovação abriu")
            coordinator.cancel()
            runCurrent()

            assertNull(coordinator.pending)
            assertFalse(state.unconfirmed)
            assertFalse(state.busy)
            assertEquals(2, source.runs.size, "cancelar a aprovação não envia nada")
            state.nightlyRateText = "250,00"
            source.requiresApproval = null
            go(state)

            val keys = tokenless(source)
            assertEquals(3, keys.size)
            assertEquals(keys[0], keys[1])
            assertNotEquals(keys[1], keys[2])
            assertNotNull(state.done)
            coordinator.close()
        }

    // --- Cancelar a reserva --------------------------------------------------------------------------------------

    private fun cancelSource() =
        FakeReservationCancelSource().apply {
            reservations[caRes.toString()] = confirmDetail(caRes, code = CA_CODE, status = "CONFIRMED")
            response = confirmDetail(caRes, code = CA_CODE, status = "CANCELLED")
        }

    private fun TestScope.cancelState(source: FakeReservationCancelSource) =
        ReservationCancelState(source, backgroundScope, caRes.toString(), approver::approve) {}.also {
            it.start()
            runCurrent()
        }

    private fun TestScope.go(state: ReservationCancelState) {
        state.confirm()
        runCurrent()
    }

    private fun TestScope.cancelUnknownThenCancelled(source: FakeReservationCancelSource): ReservationCancelState {
        source.outcomes += caNetwork()
        val state = cancelState(source)
        go(state)
        assertTrue(state.unconfirmed, "pré-condição: resultado desconhecido")
        source.requiresApproval = etApproval()
        approver.cancel = true
        go(state)
        return state
    }

    private fun tokenless(source: FakeReservationCancelSource) = source.runs.filter { it.token == null }.map { it.key }

    @Test
    fun cancelResendAfterCancelledApprovalKeepsTheKeyAndNothingGoesOutUnasked() =
        runTest {
            val source = cancelSource()
            val state = cancelUnknownThenCancelled(source)

            assertFalse(state.unconfirmed)
            assertEquals(2, source.runs.size)
            runCurrent()
            assertEquals(2, source.runs.size)
            approver.cancel = false
            go(state)

            assertEquals(1, tokenless(source).toSet().size)
            assertEquals(3, tokenless(source).size)
            assertNotNull(state.done)
        }

    @Test
    fun cancelEditAfterCancelledApprovalTakesANewKeyOnResend() =
        runTest {
            val source = cancelSource()
            val state = cancelUnknownThenCancelled(source)

            state.reason = "OTHER"
            val chosen = state.reason
            approver.cancel = false
            go(state)

            val keys = tokenless(source)
            assertEquals(3, keys.size)
            assertEquals(keys[0], keys[1])
            assertNotEquals(keys[1], keys[2], "a edição do motivo é outra intenção")
            assertEquals(
                chosen,
                source.calls
                    .last()
                    .input.reason,
            )
            assertNotNull(state.done)
        }

    @Test
    fun cancelSecondCancelledApprovalStaysUnlockedWithTheSameKey() =
        runTest {
            val source = cancelSource()
            val state = cancelUnknownThenCancelled(source)

            go(state)

            assertFalse(state.unconfirmed)
            assertTrue(state.canConfirm)
            assertNull(state.error)
            assertEquals(1, tokenless(source).toSet().size)
            approver.cancel = false
            go(state)
            assertNotNull(state.done)
            assertEquals(1, tokenless(source).toSet().size)
        }

    @Test
    fun cancelUnknownResultNotFollowedByApprovalKeepsLockedAndTheKey() =
        runTest {
            val source = cancelSource()
            source.outcomes += caNetwork()
            source.outcomes += caNetwork()
            val state = cancelState(source)

            go(state)
            go(state)

            assertTrue(state.unconfirmed)
            val before = state.reason
            state.reason = "OTHER"
            state.notes = "x"
            state.waivePenalty = true
            assertEquals(before, state.reason)
            assertEquals("", state.notes)
            assertFalse(state.waivePenalty)
            go(state)
            assertEquals(1, tokenless(source).toSet().size)
            assertNotNull(state.done)
        }

    // --- Reativar a reserva --------------------------------------------------------------------------------------

    private fun reactivateSource() =
        FakeReservationReactivateSource().apply {
            response = confirmDetail(caRes, code = CA_CODE, status = "CONFIRMED")
        }

    private fun TestScope.reactivateState(source: FakeReservationReactivateSource) =
        ReservationReactivateState(source, backgroundScope, caRes.toString(), CA_CODE, approver::approve) {}

    private fun TestScope.go(state: ReservationReactivateState) {
        state.confirm()
        runCurrent()
    }

    private fun tokenless(source: FakeReservationReactivateSource) =
        source.runs.filter { it.token == null }.map { it.key }

    private fun TestScope.reactivateUnknownThenCancelled(
        source: FakeReservationReactivateSource,
    ): ReservationReactivateState {
        source.outcomes += caNetwork()
        val state = reactivateState(source)
        go(state)
        assertTrue(state.unconfirmed, "pré-condição: resultado desconhecido")
        source.requiresApproval = etApproval()
        approver.cancel = true
        go(state)
        return state
    }

    @Test
    fun reactivateResendAfterCancelledApprovalKeepsTheKeyAndNothingGoesOutUnasked() =
        runTest {
            val source = reactivateSource()
            val state = reactivateUnknownThenCancelled(source)

            assertFalse(state.unconfirmed)
            assertNull(state.error)
            assertEquals(2, source.runs.size)
            runCurrent()
            assertEquals(2, source.runs.size)
            approver.cancel = false
            go(state)

            assertEquals(3, tokenless(source).size)
            assertEquals(1, tokenless(source).toSet().size)
            assertNotNull(state.done)
        }

    @Test
    fun reactivateSecondCancelledApprovalStaysUnlockedWithTheSameKey() =
        runTest {
            val source = reactivateSource()
            val state = reactivateUnknownThenCancelled(source)

            go(state)

            assertFalse(state.unconfirmed)
            assertFalse(state.busy)
            assertNull(state.done)
            assertEquals(1, tokenless(source).toSet().size)
            approver.cancel = false
            go(state)
            assertNotNull(state.done)
        }

    @Test
    fun reactivateUnknownResultNotFollowedByApprovalStaysUnconfirmed() =
        runTest {
            val source = reactivateSource()
            source.outcomes += caNetwork()
            source.outcomes += caNetwork()
            val state = reactivateState(source)

            go(state)
            go(state)

            assertTrue(state.unconfirmed)
            assertNotNull(state.error)
            go(state)
            assertEquals(1, tokenless(source).toSet().size)
            assertFalse(state.unconfirmed)
            assertNotNull(state.done)
        }
}
