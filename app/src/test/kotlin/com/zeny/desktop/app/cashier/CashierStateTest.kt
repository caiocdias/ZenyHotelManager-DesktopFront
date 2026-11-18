package com.zeny.desktop.app.cashier

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.CashMovementResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OPERATE = "billing.cashier.operate"
private const val FOLIOS_READ = "billing.folios.read"

@OptIn(ExperimentalCoroutinesApi::class)
class CashierStateTest {
    private var changes = 0

    private fun TestScope.started(
        source: FakeCashierSource,
        permissions: Set<String> = setOf(OPERATE, FOLIOS_READ),
    ) = CashierState(source, backgroundScope, permissions) { changes++ }.also {
        it.start()
        runCurrent()
    }

    private fun CashierState.fill(
        point: String = FRONT_DESK_POINT,
        float: String = "100,00",
    ) {
        cashPointId = point
        openingFloat = float
    }

    private fun problem(
        status: Int,
        code: String?,
    ) = ApiError.Problem(status, code, null, emptyList())

    @Test
    fun noShiftShowsOpenForm() =
        runTest {
            val source = FakeCashierSource()
            val state = started(source)

            assertNull(state.shift)
            assertTrue(state.showsOpenForm)
            assertNull(state.notice)
            assertEquals(
                listOf(CashPointOption(FRONT_DESK_POINT, "Recepção"), CashPointOption(BAR_POINT, "Bar")),
                state.cashPoints,
            )
            assertFalse(state.canOpen, "abre sem ponto e sem fundo")
            state.fill()
            assertTrue(state.canOpen)
            assertEquals(1, source.currentCalls)
        }

    @Test
    fun openCreatesShiftAndNotifies() =
        runTest {
            val source = FakeCashierSource()
            val state = started(source)
            state.fill(point = BAR_POINT, float = "1.234,56")

            state.open()
            runCurrent()

            assertEquals(listOf(BAR_POINT), source.openCalls.map { it.cashPointId })
            assertEquals(Money.parse("1234.56"), source.openCalls.single().openingFloat)
            val shift = assertNotNull(state.shift)
            assertEquals("CX-0001", shift.number)
            assertEquals("Bar", shift.cashPointName)
            assertFalse(state.showsOpenForm)
            assertNull(state.message)
            assertEquals(1, changes)
        }

    @Test
    fun openRetryAfterNetworkKeepsTheKey() =
        runTest {
            val source = FakeCashierSource()
            source.opener = { _, _ -> throw ApiError.Network(RuntimeException("sem rede")) }
            val state = started(source)
            state.fill()

            state.open()
            runCurrent()

            assertNull(state.shift)
            assertNotNull(state.message)
            assertFalse(state.closeOnly)
            assertTrue(state.canOpen, "a repetição ficou travada")
            // As entradas da intenção ficam como estavam: a repetição é a mesma intenção.
            state.cashPointId = BAR_POINT
            assertEquals(FRONT_DESK_POINT, state.cashPointId)

            source.opener = { id, _ -> shiftOf(cashPointId = id) }
            state.open()
            runCurrent()

            assertEquals(2, source.openCalls.size)
            assertEquals(source.openCalls[0].key, source.openCalls[1].key)
            assertNotNull(state.shift)
            assertEquals(1, changes)
        }

    @Test
    fun doubleOpenCallsOnce() =
        runTest {
            val source = FakeCashierSource()
            val state = started(source)
            state.fill()

            state.open()
            state.open()
            runCurrent()

            assertEquals(1, source.openCalls.size)
        }

    @Test
    fun definitiveFailureStartsANewIntent() =
        runTest {
            val source = FakeCashierSource()
            source.opener = { _, _ -> throw problem(422, "CASH_POINT_INACTIVE") }
            val state = started(source)
            state.fill()

            state.open()
            runCurrent()
            assertEquals("O ponto de caixa está inativo.", state.message)
            state.open()
            runCurrent()

            assertEquals(2, source.openCalls.size)
            assertTrue(source.openCalls[0].key != source.openCalls[1].key, "a resposta definitiva manteve a chave")
            assertEquals(0, changes)
        }

    @Test
    fun alreadyOpenShowsMessageAndReloads() =
        runTest {
            val source = FakeCashierSource()
            source.opener = { _, _ -> throw problem(409, "CASHIER_SHIFT_ALREADY_OPEN") }
            val state = started(source)
            state.fill()
            source.current = { shiftOf(number = "CX-0007", cashPointName = "Bar") }

            state.open()
            runCurrent()

            assertEquals("Já existe um turno de caixa aberto.", state.message)
            assertEquals(2, source.currentCalls, "não releu o turno corrente")
            assertEquals("CX-0007", state.shift?.number)
            assertFalse(state.busy)
            assertEquals(1, changes, "a releitura que mudou o turno não avisou")
        }

    @Test
    fun withoutOperatePermissionOffersNoOpen() =
        runTest {
            val source = FakeCashierSource()
            val state = started(source, permissions = setOf(FOLIOS_READ))

            assertEquals("Seu perfil não opera o caixa.", state.notice)
            assertEquals(0, source.currentCalls)
            assertEquals(0, source.cashPointsCalls)
            assertFalse(state.showsOpenForm)
            state.fill()
            state.open()
            runCurrent()
            assertFalse(state.canOpen)
            assertEquals(0, source.openCalls.size)
        }

    @Test
    fun cashPointsForbiddenShowsText() =
        runTest {
            val source = FakeCashierSource()
            source.points = { throw problem(403, "FORBIDDEN") }
            val state = started(source)

            assertTrue(state.showsOpenForm)
            assertEquals("Seu perfil não pode listar os pontos de caixa.", state.cashPointsError)
            assertEquals(emptyList(), state.cashPoints)
            assertFalse(state.canOpen)
        }

    @Test
    fun openStoredFailureIsCloseOnly() =
        runTest {
            val source = FakeCashierSource()
            source.opener = { _, _ -> throw problem(500, null) }
            val state = started(source)
            state.fill()

            state.open()
            runCurrent()

            assertTrue(state.closeOnly)
            assertEquals(
                "O servidor não confirmou a abertura do turno. Feche e confira o turno corrente antes de refazer.",
                state.message,
            )
            assertFalse(state.canOpen)
            state.open()
            runCurrent()
            assertEquals(1, source.openCalls.size, "repetiu com a resposta guardada sob a chave")

            // "Fechar" confere: relê o turno corrente, e a próxima abertura é outra intenção.
            source.current = { shiftOf() }
            state.dismiss()
            runCurrent()

            assertEquals(2, source.currentCalls)
            assertFalse(state.closeOnly)
            assertNull(state.message)
            assertNotNull(state.shift)
            assertEquals(1, changes)
        }

    @Test
    fun currentShiftShowsThePanel() =
        runTest {
            val source = FakeCashierSource()
            source.current = {
                shiftOf(
                    openingFloat = "100.00",
                    movements =
                        listOf(
                            movementOf(CashMovementResponse.Type.WITHDRAWAL, "50.00", "Cofre"),
                            movementOf(CashMovementResponse.Type.SUPPLY, "1234.5", "Troco"),
                        ),
                )
            }
            val state = started(source)

            val shift = assertNotNull(state.shift)
            assertEquals(SHIFT_ID, shift.id)
            assertEquals("18/11/2026", shift.openedOn)
            assertEquals("R$ 100,00", shift.openingFloat)
            assertEquals(
                listOf(
                    MovementLine("Sangria", "R$ 50,00", "Cofre"),
                    MovementLine("Suprimento", "R$ 1.234,50", "Troco"),
                ),
                shift.movements,
            )
            assertEquals(0, source.cashPointsCalls, "leu os pontos de caixa com turno aberto")
            assertEquals(0, changes, "a primeira leitura avisou a casca")
        }

    @Test
    fun expectedOnlyWhenVisible() =
        runTest {
            val hidden = FakeCashierSource()
            hidden.current = {
                shiftOf(expectedVisible = false, expected = listOf(expectedOf()), expectedTotal = "150.00")
            }
            val blind = assertNotNull(started(hidden).shift)

            assertEquals(emptyList(), blind.expected)
            assertNull(blind.expectedTotal)

            val visible = FakeCashierSource()
            visible.current = {
                shiftOf(
                    expectedVisible = true,
                    expected = listOf(expectedOf("CASH", "150.00"), expectedOf("VOUCHER", "10.00")),
                    expectedTotal = "160.00",
                )
            }
            val open = assertNotNull(started(visible).shift)

            assertEquals(
                listOf(ExpectedLine("Dinheiro", "R$ 150,00"), ExpectedLine("Outro (VOUCHER)", "R$ 10,00")),
                open.expected,
            )
            assertEquals("R$ 160,00", open.expectedTotal)
        }

    @Test
    fun currentShiftFailureOffersRetry() =
        runTest {
            val source = FakeCashierSource()
            source.current = { throw ApiError.Network(RuntimeException("sem rede")) }
            val state = started(source)

            assertNotNull(state.loadError)
            assertFalse(state.showsOpenForm)

            source.current = { null }
            state.reload()
            runCurrent()

            assertNull(state.loadError)
            assertTrue(state.showsOpenForm)
        }

    @Test
    fun closeDropsTheLateAnswer() =
        runTest {
            val source = FakeCashierSource()
            val gate = CompletableDeferred<Unit>()
            val state = started(source)
            state.fill()
            source.opener = { id, _ ->
                gate.await()
                shiftOf(cashPointId = id)
            }

            state.open()
            runCurrent()
            state.close()
            gate.complete(Unit)
            runCurrent()

            assertNull(state.shift)
            assertEquals(0, changes)
        }
}
