package com.zeny.desktop.app.cashier

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CashCount
import com.zeny.desktop.core.facade.CashMovementType
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.CashMovementResponse
import com.zeny.desktop.sdk.model.CashierShiftReportResponse
import com.zeny.desktop.sdk.model.CashierShiftResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val OPERATE = "billing.cashier.operate"
private const val SHIFTS_READ = "billing.shifts.read"

/**
 * Ataque ao `CashierState` pelos riscos da E07: bordas de valor, repetição e duplo clique nas três escritas, o
 * fechamento cego e a resposta atrasada que chega mesmo com o job cancelado ([LateAnswer]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // um teste por risco: valor, repetição, fechamento cego e resposta atrasada
class CashierStateAtaqueTest {
    private var changes = 0

    private fun TestScope.started(
        source: FakeCashierSource,
        permissions: Set<String> = setOf(OPERATE, SHIFTS_READ),
    ) = CashierState(source, backgroundScope, permissions) { changes++ }.also {
        it.start()
        runCurrent()
        changes = 0
    }

    private fun withShift(): FakeCashierSource = FakeCashierSource().also { it.current = { shiftOf() } }

    private fun problem(
        status: Int,
        code: String?,
    ) = ApiError.Problem(status, code, null, emptyList())

    private fun network() = ApiError.Network(RuntimeException("sem rede"))

    // --- Bordas de valor ---

    @Test
    fun openingFloatBordersGoExactAndTheServerDecidesAboveTheCeiling() =
        runTest {
            val source = FakeCashierSource()
            val state = started(source)
            state.cashPointId = FRONT_DESK_POINT

            listOf("0,00" to "0.00", "0,01" to "0.01", "1.000.000,00" to "1000000.00").forEach { (typed, sent) ->
                source.opener = { _, _ -> throw problem(409, "CASHIER_SHIFT_ALREADY_OPEN") }
                source.current = { null }
                state.openingFloat = typed
                assertTrue(state.canOpen, "não abre com $typed")
                state.open()
                runCurrent()
                assertEquals(Money.parse(sent), source.openCalls.last().openingFloat, typed)
            }

            // Acima do teto o front não arredonda nem corta: envia o exato e mostra a recusa do servidor.
            source.opener = { _, _ -> throw problem(400, "CASHIER_SHIFT_INVALID") }
            state.openingFloat = "1.000.000,01"
            state.open()
            runCurrent()

            assertEquals(Money.parse("1000000.01"), source.openCalls.last().openingFloat)
            assertEquals("O turno de caixa está inválido.", state.message)
            assertFalse(state.locked, "a recusa definitiva travou a abertura")
            state.openingFloat = "1.000.000,00"
            source.opener = { id, _ -> shiftOf(cashPointId = id) }
            state.open()
            runCurrent()
            assertTrue(
                source.openCalls.last().key != source.openCalls[source.openCalls.size - 2].key,
                "a abertura corrigida reaproveitou a chave da recusa definitiva",
            )
        }

    @Test
    fun movementBordersGoExactAndAboveTheCeilingShowsTheRefusal() =
        runTest {
            val source = withShift()
            val state = started(source)

            state.startMovement(CashMovementType.SUPPLY)
            val form = assertNotNull(state.movement)
            form.reason = "Troco"
            form.amount = "1.000.000,00"
            source.recorder = { _, _, _ -> throw problem(400, "CASHIER_SHIFT_INVALID") }
            form.confirm()
            runCurrent()
            assertEquals(Money.parse("1000000.00"), source.movementCalls.last().amount)

            form.amount = "1.000.000,01"
            form.confirm()
            runCurrent()

            assertEquals(Money.parse("1000000.01"), source.movementCalls.last().amount)
            assertEquals("O turno de caixa está inválido.", form.message)
            assertSame(form, state.movement, "a recusa definitiva fechou o diálogo")
            assertFalse(form.locked)
        }

    @Test
    fun negativeCountOfNonCashGoesExactAndNegativeCashIsLeftToTheServer() =
        runTest {
            val source = withShift()
            val state = started(source)
            state.startClose()
            val closing = assertNotNull(state.closing)
            source.closer = { throw problem(400, "CASHIER_COUNT_INVALID") }

            closing.setCount(PaymentMethod.CASH, "-0,01")
            closing.setCount(PaymentMethod.CREDIT_CARD, "-1.000.000,00")
            closing.setCount(PaymentMethod.OTHER, "0,00")
            closing.confirm()
            runCurrent()

            assertEquals(
                listOf(
                    CashCount(PaymentMethod.CASH, Money.parse("-0.01")),
                    CashCount(PaymentMethod.CREDIT_CARD, Money.parse("-1000000.00")),
                    CashCount(PaymentMethod.OTHER, Money.parse("0.00")),
                ),
                source.closeCalls.single().counts,
            )
            assertEquals("A contagem do caixa está inválida.", closing.message)
            assertSame(closing, state.closing, "a recusa da contagem fechou o formulário")
            assertFalse(closing.locked, "a recusa da contagem travou as entradas")
            assertNotNull(state.shift, "a recusa da contagem tirou o turno da tela")
        }

    // --- Repetição e duplo clique ---

    @Test
    fun openRetriedThreeTimesKeepsOneKeyAndDoubleRetryCallsOnce() =
        runTest {
            val source = FakeCashierSource()
            source.opener = { _, _ -> throw network() }
            val state = started(source)
            state.cashPointId = FRONT_DESK_POINT
            state.openingFloat = "100,00"

            state.open()
            runCurrent()
            state.open()
            state.open()
            runCurrent()
            state.open()
            runCurrent()

            assertEquals(3, source.openCalls.size, "o duplo clique no \"Tentar de novo\" abriu duas vezes")
            assertEquals(
                1,
                source.openCalls
                    .map { it.key }
                    .toSet()
                    .size,
            )
            assertEquals(Money.parse("100.00"), source.openCalls.last().openingFloat)
        }

    @Test
    fun startingTheDialogAgainDoesNotReplaceAnUnconfirmedMovement() =
        runTest {
            val source = withShift()
            source.recorder = { _, _, _ -> throw network() }
            val state = started(source)
            state.startMovement(CashMovementType.WITHDRAWAL)
            val form = assertNotNull(state.movement)
            form.amount = "50,00"
            form.reason = "Cofre"
            form.confirm()
            runCurrent()
            assertTrue(form.unconfirmed)

            state.startMovement(CashMovementType.WITHDRAWAL)
            state.startMovement(CashMovementType.SUPPLY)
            state.startClose()

            assertSame(form, state.movement, "outro diálogo trocou a intenção sem resultado")
            assertNull(state.closing)
            source.recorder = { type, amount, reason ->
                movementOf(CashMovementResponse.Type.valueOf(type.name), amount.toApi(), reason)
            }
            form.confirm()
            form.confirm()
            runCurrent()

            assertEquals(2, source.movementCalls.size)
            assertEquals(source.movementCalls[0].key, source.movementCalls[1].key)
            assertEquals(source.movementCalls[0].amount, source.movementCalls[1].amount)
            assertEquals(source.movementCalls[0].reason, source.movementCalls[1].reason)
        }

    @Test
    fun movementAfterSuccessIsANewIntent() =
        runTest {
            val source = withShift()
            val state = started(source)

            repeat(2) {
                state.startMovement(CashMovementType.SUPPLY)
                val form = assertNotNull(state.movement)
                form.amount = "10,00"
                form.reason = "Troco"
                form.confirm()
                runCurrent()
            }

            assertEquals(2, source.movementCalls.size)
            assertTrue(
                source.movementCalls[0].key != source.movementCalls[1].key,
                "o segundo suprimento igual reaproveitou a chave do primeiro e seria descartado como repetição",
            )
        }

    @Test
    fun closeDoubleClickAndRepeatedRetryKeepOneKey() =
        runTest {
            val source = withShift()
            source.closer = { throw network() }
            val state = started(source)
            state.startClose()
            val closing = assertNotNull(state.closing)
            closing.setCount(PaymentMethod.CASH, "100,00")

            closing.confirm()
            closing.confirm()
            runCurrent()
            closing.confirm()
            closing.confirm()
            runCurrent()

            assertEquals(2, source.closeCalls.size)
            assertEquals(
                1,
                source.closeCalls
                    .map { it.key }
                    .toSet()
                    .size,
            )
            assertNull(state.report)
            assertEquals(0, changes, "o fechamento sem resultado avisou a casca")
        }

    // --- Fechamento cego ---

    @Test
    fun noReportBeforeTheShiftCloses() =
        runTest {
            val source = FakeCashierSource()
            source.current = {
                shiftOf(
                    expectedVisible = false,
                    expected = listOf(expectedOf("CASH", "987.65")),
                    expectedTotal = "987.65",
                )
            }
            val state = started(source)

            assertFalse(state.canViewReport, "oferece o relatório (com o esperado) do turno aberto")
            state.showReport()
            runCurrent()

            assertEquals(emptyList(), source.reportCalls, "leu o relatório do turno aberto")
            assertNull(state.report)
            assertFalse("987" in state.shift.toString(), "o turno cego expôs o esperado")
        }

    @Test
    fun unconfirmedCloseThatDidNotHappenKeepsTheBlindPanel() =
        runTest {
            val source = FakeCashierSource()
            source.current = { shiftOf(expectedVisible = false, expected = listOf(expectedOf("CASH", "987.65"))) }
            source.closer = { throw network() }
            val state = started(source)
            state.startClose()
            val closing = assertNotNull(state.closing)
            closing.setCount(PaymentMethod.CASH, "10,00")
            closing.confirm()
            runCurrent()

            closing.leave()
            runCurrent()

            assertNull(state.closing)
            assertNull(state.report)
            assertNotNull(state.shift, "a conferência perdeu o turno que continua aberto")
            assertFalse("987" in state.shift.toString(), "a conferência expôs o esperado")
            assertFalse(state.canViewReport)
        }

    // --- Resposta atrasada que ignora o cancelamento ---

    @Test
    fun lateOpenAnswerAfterCloseIsDropped() =
        runTest {
            val source = FakeCashierSource()
            val late = LateAnswer<CashierShiftResponse>()
            source.opener = { _, _ -> late.await() }
            val state = started(source)
            state.cashPointId = FRONT_DESK_POINT
            state.openingFloat = "100,00"

            state.open()
            runCurrent()
            state.close()
            late.answer(shiftOf())
            runCurrent()

            assertNull(state.shift, "a abertura respondida depois do close entrou na tela")
            assertEquals(0, changes, "a abertura respondida depois do close avisou a casca")
        }

    @Test
    fun lateReadAnswersAfterCloseOrANewerReloadAreDropped() =
        runTest {
            val source = withShift()
            val state = started(source)
            val late = LateAnswer<CashierShiftResponse?>()
            source.current = {
                source.current = { shiftOf() }
                late.await()
            }

            state.reload()
            runCurrent()
            state.reload()
            runCurrent()
            late.answer(shiftOf(id = "50000000-0000-0000-0000-000000000099", number = "CX-OLD"))
            runCurrent()

            assertEquals("CX-0001", state.shift?.number, "a leitura velha sobrescreveu a nova")
            assertEquals(0, changes)

            val afterClose = LateAnswer<CashierShiftResponse?>()
            source.current = { afterClose.await() }
            state.reload()
            runCurrent()
            state.close()
            afterClose.answer(null)
            runCurrent()

            assertEquals("CX-0001", state.shift?.number, "a leitura respondida depois do close mudou a tela")
            assertEquals(0, changes)
        }

    @Test
    fun lateMovementAnswerAfterCloseDoesNotRereadNorNotify() =
        runTest {
            val source = withShift()
            val state = started(source)
            val late = LateAnswer<CashMovementResponse>()
            source.recorder = { _, _, _ -> late.await() }
            state.startMovement(CashMovementType.WITHDRAWAL)
            val form = assertNotNull(state.movement)
            form.amount = "50,00"
            form.reason = "Cofre"
            form.confirm()
            runCurrent()
            val reads = source.currentCalls

            state.close()
            late.answer(movementOf())
            runCurrent()

            assertEquals(reads, source.currentCalls, "o movimento respondido depois do close releu o turno")
            assertSame(form, state.movement, "o movimento respondido depois do close mexeu no diálogo")
        }

    @Test
    fun lateCloseAnswerAfterCloseShowsNoReportNorNotifies() =
        runTest {
            val source = withShift()
            val state = started(source)
            val late = LateAnswer<CashierShiftReportResponse>()
            source.closer = { late.await() }
            state.startClose()
            val closing = assertNotNull(state.closing)
            closing.confirm()
            runCurrent()

            state.close()
            late.answer(reportOf())
            runCurrent()

            assertNull(state.report, "o relatório respondido depois do close entrou na tela")
            assertEquals(0, changes, "o fechamento respondido depois do close avisou a casca")
        }

    @Test
    fun lateFailureAfterCloseDoesNotLockTheForm() =
        runTest {
            val source = withShift()
            val state = started(source)
            val late = LateAnswer<CashMovementResponse>()
            source.recorder = { _, _, _ -> late.await() }
            state.startMovement(CashMovementType.SUPPLY)
            val form = assertNotNull(state.movement)
            form.amount = "5,00"
            form.reason = "Troco"
            form.confirm()
            runCurrent()

            state.close()
            late.fail(problem(500, null))
            runCurrent()

            assertFalse(form.closeOnly, "a falha respondida depois do close mexeu no formulário")
            assertNull(form.message)
        }

    // --- Forma desconhecida ---

    @Test
    fun unknownMethodsKeepTheirValueVerbatim() {
        assertEquals("Outro (voucher)", CashierText.method("voucher"))
        assertEquals("Outro (CASH_BACK)", CashierText.method("CASH_BACK"))
        assertEquals("Outro", CashierText.method("OTHER"))
        assertEquals("—", CashierText.money("12,50"), "o decimal fora do contrato virou valor")
        assertEquals("—", CashierText.money("1.005"))
    }
}
