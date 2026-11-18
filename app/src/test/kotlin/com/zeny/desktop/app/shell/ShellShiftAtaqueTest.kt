package com.zeny.desktop.app.shell

import com.zeny.desktop.app.cashier.CashierSource
import com.zeny.desktop.app.cashier.FakeCashierSource
import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.app.cashier.shiftOf
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CashierShiftResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val OPERATE = "billing.cashier.operate"

/**
 * Ataque ao indicador de turno do topo: a resposta atrasada que chega mesmo com o job cancelado ([LateAnswer]) não
 * sobrescreve o valor depois de `refreshShift`, `reload` nem `close`, e o `refreshShift` no meio de um `reload` não lê
 * o turno com o conjunto velho.
 */
class ShellShiftAtaqueTest {
    private class Connection(
        var permissions: Set<String> = setOf(OPERATE, "billing.folios.read"),
        var cashier: CashierSource?,
    ) : ServerConnection {
        var me: suspend () -> Me = { Me("u-1", "rec", "Recepcionista", permissions) }
        override val state = MutableStateFlow<SessionState>(SessionState.Active("u-1", "s-1", false))

        override suspend fun login(
            login: String,
            password: String,
        ) = error("não usado")

        override suspend fun changePassword(
            current: String,
            new: String,
        ) = error("não usado")

        override suspend fun retryPasswordChangeRefresh() = error("não usado")

        override suspend fun logout() = error("não usado")

        override suspend fun me(): Me = me.invoke()

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 11, 18))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun cashierSource(): CashierSource? = cashier

        override fun close() = Unit
    }

    private fun TestScope.loaded(connection: Connection) =
        ShellState(connection, devMode = false, scope = this).also { advanceUntilIdle() }

    /** Uma porta cuja primeira leitura fica pendurada em [late]; as seguintes respondem "sem turno". */
    private fun hanging(late: LateAnswer<CashierShiftResponse?>): FakeCashierSource {
        val cashier = FakeCashierSource()
        cashier.current = {
            cashier.current = { null }
            late.await()
        }
        return cashier
    }

    @Test
    fun lateAnswerIgnoringCancellationIsDroppedAfterRefresh() =
        runTest {
            val late = LateAnswer<CashierShiftResponse?>()
            val cashier = hanging(late)
            val state = loaded(Connection(cashier = cashier))
            assertTrue(late.asked)

            state.refreshShift()
            advanceUntilIdle()
            assertEquals(ShiftIndicator.None, state.shift)

            late.answer(shiftOf(number = "CX-OLD"))
            advanceUntilIdle()

            assertEquals(ShiftIndicator.None, state.shift, "a resposta da leitura cancelada sobrescreveu o topo")
        }

    @Test
    fun lateAnswerIgnoringCancellationIsDroppedAfterReload() =
        runTest {
            val late = LateAnswer<CashierShiftResponse?>()
            val cashier = hanging(late)
            val state = loaded(Connection(cashier = cashier))

            state.reload()
            advanceUntilIdle()
            assertEquals(ShiftIndicator.None, state.shift)

            late.answer(shiftOf(number = "CX-OLD"))
            advanceUntilIdle()

            assertEquals(ShiftIndicator.None, state.shift, "a resposta de antes do reload sobrescreveu o topo")
        }

    @Test
    fun lateAnswerIgnoringCancellationIsDroppedAfterClose() =
        runTest {
            val late = LateAnswer<CashierShiftResponse?>()
            val state = loaded(Connection(cashier = hanging(late)))
            assertEquals(ShiftIndicator.Hidden, state.shift)

            state.close()
            late.answer(shiftOf(number = "CX-OLD"))
            advanceUntilIdle()

            assertEquals(ShiftIndicator.Hidden, state.shift, "a resposta depois do close mudou o topo")
        }

    @Test
    fun lateFailureIgnoringCancellationDoesNotMarkUnknown() =
        runTest {
            val late = LateAnswer<CashierShiftResponse?>()
            val state = loaded(Connection(cashier = hanging(late)))

            state.refreshShift()
            advanceUntilIdle()
            assertEquals(ShiftIndicator.None, state.shift)

            late.fail(ApiError.Network(RuntimeException("sem rede")))
            advanceUntilIdle()

            assertEquals(ShiftIndicator.None, state.shift, "a falha da leitura cancelada virou \"não verificado\"")
        }

    @Test
    fun refreshDuringReloadDoesNotReadWithTheOldSet() =
        runTest {
            val cashier = FakeCashierSource()
            cashier.current = { shiftOf() }
            val connection = Connection(cashier = cashier)
            val state = loaded(connection)
            assertEquals(1, cashier.currentCalls)

            // A sessão seguinte perde o caixa: o `me` em voo ainda não disse isso.
            val gate = CompletableDeferred<Me>()
            connection.me = { gate.await() }
            state.reload()
            state.refreshShift()
            advanceUntilIdle()

            assertEquals(1, cashier.currentCalls, "o refreshShift leu o turno antes do me do reload")
            assertEquals(ShiftIndicator.Hidden, state.shift)

            gate.complete(Me("u-1", "rec", "Recepcionista", setOf("billing.folios.read")))
            advanceUntilIdle()
            state.refreshShift()
            advanceUntilIdle()

            assertEquals(1, cashier.currentCalls, "leu o turno sem billing.cashier.operate no conjunto novo")
            assertEquals(ShiftIndicator.Hidden, state.shift)
        }

    @Test
    fun shiftReadFailureOnEveryRefreshStaysUnknownWithoutError() =
        runTest {
            val cashier = FakeCashierSource()
            cashier.current = { shiftOf() }
            val state = loaded(Connection(cashier = cashier))
            assertEquals(ShiftIndicator.Open("CX-0001", "Recepção"), state.shift)

            cashier.current = { throw ApiError.Problem(503, null, null, emptyList()) }
            state.refreshShift()
            advanceUntilIdle()

            // O topo não afirma o turno velho depois de uma leitura que falhou.
            assertEquals(ShiftIndicator.Unknown, state.shift)
            assertEquals(null, state.error)
            assertEquals(setOf(OPERATE, "billing.folios.read"), state.permissions)
        }
}
