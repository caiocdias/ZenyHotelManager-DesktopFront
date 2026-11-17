package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val rfRes = Uuid.parse("20000000-0000-0000-0000-0000000000c1")

private fun rfProblem(
    status: Int,
    code: String,
) = ApiError.Problem(status, code, "detalhe do servidor", listOf(FieldError("guarantee", code, "campo")))

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra do brief
class ReservationConfirmStateTest {
    private val id = rfRes.toString()
    private var changes = 0

    private fun source(guaranteeType: String = "CARD") =
        FakeReservationConfirmSource().apply {
            details[id] = confirmDetail(rfRes, guaranteeType = guaranteeType)
            confirmResponse = confirmDetail(rfRes, status = "CONFIRMED", guaranteeType = guaranteeType)
        }

    private fun TestScope.state(source: FakeReservationConfirmSource) =
        ReservationConfirmState(source, backgroundScope, id) { changes++ }

    private fun TestScope.started(source: FakeReservationConfirmSource): ReservationConfirmState =
        state(source).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.confirmNow(state: ReservationConfirmState) {
        state.confirm()
        runCurrent()
    }

    @Test
    fun startPreselectsPolicyGuarantee() =
        runTest {
            val deposit = started(source("DEPOSIT"))
            assertEquals("DEPOSIT", deposit.guarantee)
            assertEquals("Garantia exigida: Depósito de 30,00% até 7 dia(s) antes da chegada", deposit.terms)
            assertEquals("R-1", deposit.code)
            assertFalse(deposit.loading)

            val card = started(source("CARD"))
            assertEquals("CARD", card.guarantee)
            assertEquals("Garantia exigida: Cartão", card.terms)

            val none = started(source("NONE"))
            assertEquals("CARD", none.guarantee)
            assertNull(none.terms)
        }

    @Test
    fun confirmSendsGuaranteeWithIntentKey() =
        runTest {
            val server = source("DEPOSIT")
            val state = started(server)
            assertTrue(state.canConfirm)

            confirmNow(state)

            assertEquals(1, server.confirmCalls.size)
            assertEquals(id, server.confirmCalls.single().id)
            assertEquals("DEPOSIT", server.confirmCalls.single().guarantee)
            assertTrue(
                server.confirmCalls
                    .single()
                    .intentKey
                    .isNotBlank(),
            )
            assertEquals("Reserva R-1 confirmada.", state.done)
            assertNull(state.error)
            assertFalse(state.busy)
            assertFalse(state.canConfirm)
            assertEquals(1, changes)
        }

    @Test
    fun confirmAfterDoneDoesNothing() =
        runTest {
            val server = source()
            val state = started(server)
            confirmNow(state)

            confirmNow(state)

            assertEquals(1, server.confirmCalls.size)
            assertEquals(1, changes)
        }

    @Test
    fun unknownResultKeepsIntent() =
        runTest {
            val server = source()
            server.confirmOutcomes +=
                listOf(
                    ApiError.Network(IOException("caiu")),
                    rfProblem(409, "IDEMPOTENCY_KEY_IN_USE"),
                    rfProblem(503, "SERVICE_UNAVAILABLE"),
                    null,
                )
            val state = started(server)

            repeat(4) { confirmNow(state) }

            val keys = server.confirmCalls.map { it.intentKey }
            assertEquals(4, keys.size)
            assertEquals(1, keys.toSet().size)
            assertEquals("Reserva R-1 confirmada.", state.done)
        }

    @Test
    fun definitiveRefusalTakesNewIntent() =
        runTest {
            val server = source()
            server.confirmOutcomes += listOf(rfProblem(422, "DEPOSIT_INSUFFICIENT"), null)
            val state = started(server)

            repeat(2) { confirmNow(state) }

            assertEquals(2, server.confirmCalls.size)
            assertNotEquals(server.confirmCalls[0].intentKey, server.confirmCalls[1].intentKey)
        }

    @Test
    fun changedGuaranteeTakesNewIntent() =
        runTest {
            val server = source()
            server.confirmOutcomes += List(2) { ApiError.Network(IOException("caiu")) }
            val state = started(server)

            confirmNow(state)
            state.guarantee = "DEPOSIT"
            confirmNow(state)
            confirmNow(state)

            val calls = server.confirmCalls
            assertEquals(listOf("CARD", "DEPOSIT", "DEPOSIT"), calls.map { it.guarantee })
            assertNotEquals(calls[0].intentKey, calls[1].intentKey)
            assertEquals(calls[1].intentKey, calls[2].intentKey)
        }

    @Test
    fun guaranteeIgnoresValuesOutsideTheContract() =
        runTest {
            val state = started(source())

            state.guarantee = "NONE"
            state.guarantee = "BITCOIN"

            assertEquals("CARD", state.guarantee)
        }

    @Test
    fun refusalShowsCatalogText() =
        runTest {
            val expected =
                mapOf(
                    "PROVISIONAL_RESERVATION_EXPIRED" to 422,
                    "GUARANTEE_METHOD_NOT_ACCEPTED" to 422,
                    "DEPOSIT_INSUFFICIENT" to 422,
                )
            for ((code, status) in expected) {
                val server = source()
                server.confirmOutcomes += rfProblem(status, code)
                val state = started(server)

                confirmNow(state)

                val shown = checkNotNull(state.error)
                assertEquals(ApiError.Problem(status, code, null, emptyList()).userMessage, shown)
                assertFalse(shown.contains(code), "o código cru não aparece: $shown")
                assertNull(state.done)
                assertFalse(state.busy)
                assertTrue(state.canConfirm)
            }
            assertEquals(0, changes)
        }

    @Test
    fun depositLineShowsServerValues() =
        runTest {
            val server = source("DEPOSIT")
            server.details[id] = confirmDetail(rfRes, guaranteeType = "DEPOSIT", requiredDeposit = "216.00")
            assertEquals("Depósito exigido: ${ReceptionText.money("216.00")}", started(server).deposit)

            server.details[id] =
                confirmDetail(rfRes, guaranteeType = "DEPOSIT", requiredDeposit = "216.00", depositReceived = "100.00")
            assertEquals(
                "Depósito exigido: ${ReceptionText.money("216.00")} · recebido: ${ReceptionText.money("100.00")}",
                started(server).deposit,
            )

            server.details[id] = confirmDetail(rfRes, guaranteeType = "CARD")
            assertNull(started(server).deposit)

            // sem `requiredDeposit`, o recebido sozinho não vira linha
            server.details[id] = confirmDetail(rfRes, guaranteeType = "DEPOSIT", depositReceived = "100.00")
            assertNull(started(server).deposit)
        }

    @Test
    fun depositInsufficientShowsOnlyCatalogText() =
        runTest {
            val server = source("DEPOSIT")
            server.details[id] = confirmDetail(rfRes, guaranteeType = "DEPOSIT", requiredDeposit = "216.00")
            server.confirmOutcomes += rfProblem(422, "DEPOSIT_INSUFFICIENT")
            val state = started(server)

            confirmNow(state)

            assertEquals("O depósito é insuficiente.", state.error)
        }

    @Test
    fun closeDropsLateAnswer() =
        runTest {
            val server = source()
            server.holdConfirm = true
            val state = started(server)

            state.confirm()
            runCurrent()
            assertTrue(state.busy)
            state.close()
            server.heldConfirms.forEach { it.complete(Unit) }
            runCurrent()

            assertNull(state.done)
            assertNull(state.error)
            assertEquals(0, changes)
            state.confirm()
            runCurrent()
            assertEquals(1, server.confirmCalls.size)
        }

    @Test
    fun closeDropsLateRead() =
        runTest {
            val server = source()
            server.holdRead = true
            val state = state(server)

            state.start()
            runCurrent()
            assertTrue(state.loading)
            state.close()
            server.heldReads.forEach { it.complete(Unit) }
            runCurrent()

            assertNull(state.code)
            assertNull(state.terms)
            assertFalse(state.canConfirm)
        }

    @Test
    fun readFailureShowsTextAndBlocksConfirm() =
        runTest {
            val server = source()
            server.readError = ApiError.Network(IOException("caiu"))
            val state = started(server)

            assertEquals("Não foi possível falar com o servidor. Confira o endereço e a rede.", state.error)
            assertFalse(state.loading)
            assertFalse(state.canConfirm)
            state.confirm()
            runCurrent()
            assertTrue(server.confirmCalls.isEmpty())
        }

    @Test
    fun confirmIsBlockedWhileBusy() =
        runTest {
            val server = source()
            server.holdConfirm = true
            val state = started(server)

            state.confirm()
            runCurrent()
            state.confirm()
            runCurrent()
            state.guarantee = "DEPOSIT"

            assertEquals(1, server.confirmCalls.size)
            assertEquals("CARD", state.guarantee)
            assertFalse(state.canConfirm)
            server.heldConfirms.single().complete(Unit)
            runCurrent()
            assertEquals("Reserva R-1 confirmada.", state.done)
        }

    @Test
    fun onChangedOnlyOnSuccess() =
        runTest {
            val server = source()
            server.confirmOutcomes +=
                listOf(
                    ApiError.Network(IOException("caiu")),
                    rfProblem(409, "RESERVATION_TRANSITION_NOT_ALLOWED"),
                    null,
                )
            val state = started(server)

            confirmNow(state)
            assertEquals(0, changes)
            confirmNow(state)
            assertEquals(0, changes)
            confirmNow(state)
            assertEquals(1, changes)
        }

    @Test
    fun errorClearsWhenTryingAgain() =
        runTest {
            val server = source()
            server.confirmOutcomes += ApiError.Network(IOException("caiu"))
            val state = started(server)
            confirmNow(state)
            assertTrue(state.error != null)

            server.holdConfirm = true
            state.confirm()
            runCurrent()
            assertNull(state.error)
            server.heldConfirms.forEach { it.complete(Unit) }
            runCurrent()
            assertEquals("Reserva R-1 confirmada.", state.done)
        }
}
