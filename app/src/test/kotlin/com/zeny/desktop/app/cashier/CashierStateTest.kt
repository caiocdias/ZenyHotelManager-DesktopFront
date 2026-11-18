package com.zeny.desktop.app.cashier

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CashCount
import com.zeny.desktop.core.facade.CashMovementType
import com.zeny.desktop.core.facade.PaymentMethod
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
private const val SHIFTS_READ = "billing.shifts.read"

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // um teste por regra: leitura, abertura, movimentos, fechamento cego e relatório
class CashierStateTest {
    private var changes = 0

    private fun TestScope.started(
        source: FakeCashierSource,
        permissions: Set<String> = setOf(OPERATE, FOLIOS_READ),
    ) = CashierState(source, backgroundScope, permissions) { changes++ }.also {
        it.start()
        runCurrent()
        // A primeira leitura avisa a casca (firstReadNotifiesTheShell); os testes contam o que vem depois dela.
        changes = 0
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
        }

    @Test
    fun firstReadNotifiesTheShell() =
        runTest {
            val source = FakeCashierSource()
            source.current = { shiftOf() }
            val state = CashierState(source, backgroundScope, setOf(OPERATE)) { changes++ }

            state.start()
            runCurrent()

            assertEquals(1, changes, "a primeira leitura não avisou a casca (o topo pode estar velho)")
            state.reload()
            runCurrent()
            assertEquals(1, changes, "a releitura sem troca de turno avisou de novo")
        }

    @Test
    fun firstReadFailureDoesNotNotify() =
        runTest {
            val source = FakeCashierSource()
            source.current = { throw ApiError.Network(RuntimeException("sem rede")) }
            val state = CashierState(source, backgroundScope, setOf(OPERATE)) { changes++ }

            state.start()
            runCurrent()

            assertEquals(0, changes)
            source.current = { null }
            state.reload()
            runCurrent()
            assertEquals(1, changes, "a primeira leitura que deu certo não avisou a casca")
        }

    @Test
    fun negativeOpeningFloatIsRefused() =
        runTest {
            val source = FakeCashierSource()
            val state = started(source)
            state.fill(float = "-10,00")

            assertFalse(state.canOpen, "abre com fundo negativo")
            assertEquals("O fundo de troco não pode ser negativo.", state.floatError)
            state.open()
            runCurrent()
            assertEquals(0, source.openCalls.size)

            state.openingFloat = "0,00"
            assertNull(state.floatError)
            assertTrue(state.canOpen, "o fundo zero é aceito pelo contrato")
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

    private fun TestScope.withShift(
        source: FakeCashierSource = FakeCashierSource().also { it.current = { shiftOf() } },
        permissions: Set<String> = setOf(OPERATE, SHIFTS_READ),
    ): Pair<FakeCashierSource, CashierState> = source to started(source, permissions)

    private fun CashierState.withdrawal(
        amount: String = "50,00",
        reason: String = "Cofre",
    ): MovementForm {
        startMovement(CashMovementType.WITHDRAWAL)
        val form = assertNotNull(movement, "o diálogo de sangria não abriu")
        form.amount = amount
        form.reason = reason
        return form
    }

    private fun CashierState.closingForm(): CloseShiftForm {
        startClose()
        return assertNotNull(closing, "o formulário de fechamento não abriu")
    }

    @Test
    fun doubleSubmitRecordsOnce() =
        runTest {
            val (source, state) = withShift()
            val form = state.withdrawal()

            form.confirm()
            form.confirm()
            runCurrent()

            assertEquals(1, source.movementCalls.size)

            val closing = state.closingForm()
            closing.confirm()
            closing.confirm()
            runCurrent()

            assertEquals(1, source.closeCalls.size)
        }

    @Test
    fun movementRecordedClosesTheDialogAndRereadsTheShift() =
        runTest {
            val (source, state) = withShift()
            val form = state.withdrawal(amount = "50,00", reason = "  Depósito no cofre  ")
            source.current = {
                shiftOf(
                    movements = listOf(movementOf(CashMovementResponse.Type.WITHDRAWAL, "50.00", "Depósito no cofre")),
                )
            }

            form.confirm()
            runCurrent()

            val call = source.movementCalls.single()
            assertEquals(SHIFT_ID, call.shiftId)
            assertEquals(CashMovementType.WITHDRAWAL, call.type)
            assertEquals("Depósito no cofre", call.reason)
            assertNull(state.movement)
            assertEquals(listOf(MovementLine("Sangria", "R$ 50,00", "Depósito no cofre")), state.shift?.movements)
            assertEquals(0, changes, "o movimento não troca o turno")
        }

    @Test
    fun retryAfterNetworkKeepsTheKey() =
        runTest {
            val (source, state) = withShift()
            source.recorder = { _, _, _ -> throw ApiError.Network(RuntimeException("sem rede")) }
            val form = state.withdrawal()

            form.confirm()
            runCurrent()

            assertTrue(form.unconfirmed)
            assertFalse(form.closeOnly)
            assertEquals(
                "Não foi possível confirmar o movimento. Tente de novo sem mudar nada; " +
                    "se sair, confira o turno antes de refazer.",
                form.message,
            )
            form.amount = "60,00"
            assertEquals("50,00", form.amount, "a entrada mudou com o resultado desconhecido")
            source.recorder = { _, amount, reason -> movementOf(amount = amount.toApi(), reason = reason) }
            form.confirm()
            runCurrent()

            assertEquals(2, source.movementCalls.size)
            assertEquals(source.movementCalls[0].key, source.movementCalls[1].key)
            assertNull(state.movement)

            source.closer = { throw ApiError.Network(RuntimeException("sem rede")) }
            val closing = state.closingForm()
            closing.setCount(PaymentMethod.CASH, "100,00")
            closing.confirm()
            runCurrent()

            assertTrue(closing.unconfirmed)
            assertEquals(
                "Não foi possível confirmar o fechamento. Tente de novo sem mudar nada; " +
                    "se sair, confira o turno antes de refazer.",
                closing.message,
            )
            closing.setCount(PaymentMethod.CASH, "90,00")
            assertEquals("100,00", closing.entries.first { it.method == PaymentMethod.CASH }.text)
            source.closer = { reportOf() }
            closing.confirm()
            runCurrent()

            assertEquals(2, source.closeCalls.size)
            assertEquals(source.closeCalls[0].key, source.closeCalls[1].key)
            assertEquals(source.closeCalls[0].counts, source.closeCalls[1].counts)
            assertNotNull(state.report)
        }

    @Test
    fun definitiveMovementFailureStartsANewIntent() =
        runTest {
            val (source, state) = withShift()
            source.recorder = { _, _, _ -> throw problem(400, "CASHIER_SHIFT_INVALID") }
            val form = state.withdrawal()

            form.confirm()
            runCurrent()
            assertEquals("O turno de caixa está inválido.", form.message)
            assertFalse(form.unconfirmed)
            form.confirm()
            runCurrent()

            assertEquals(2, source.movementCalls.size)
            assertTrue(source.movementCalls[0].key != source.movementCalls[1].key)
        }

    @Test
    fun movementAmountGoesAsExactDecimal() =
        runTest {
            val (source, state) = withShift()
            val form = state.withdrawal(amount = "1.234,56")

            form.confirm()
            runCurrent()

            assertEquals(Money.parse("1234.56"), source.movementCalls.single().amount)

            // Mais de duas casas não arredonda em silêncio: não envia.
            state.startMovement(CashMovementType.SUPPLY)
            val supply = assertNotNull(state.movement)
            supply.amount = "1,005"
            supply.reason = "Troco"
            supply.confirm()
            runCurrent()
            assertEquals(1, source.movementCalls.size)
            assertEquals("Informe um valor maior que zero.", supply.message)
        }

    @Test
    fun movementAmountMustBePositive() =
        runTest {
            val (source, state) = withShift()
            val form = state.withdrawal(amount = "0,00")

            assertEquals("Informe um valor maior que zero.", form.amountError)
            form.confirm()
            runCurrent()
            form.amount = "-5,00"
            form.confirm()
            runCurrent()

            assertEquals(0, source.movementCalls.size)
            form.amount = "0,01"
            assertNull(form.amountError)
            form.confirm()
            runCurrent()
            assertEquals(Money.parse("0.01"), source.movementCalls.single().amount)
        }

    @Test
    fun movementNeedsReason() =
        runTest {
            val (source, state) = withShift()
            val form = state.withdrawal(reason = "   ")

            form.confirm()
            runCurrent()

            assertEquals(0, source.movementCalls.size)
            assertEquals("Informe o motivo.", form.message)

            form.reason = "x".repeat(201)
            form.confirm()
            runCurrent()
            assertEquals(0, source.movementCalls.size)
            assertEquals("O motivo tem no máximo 200 caracteres.", form.message)

            form.reason = "x".repeat(200)
            form.confirm()
            runCurrent()
            assertEquals(1, source.movementCalls.size)
        }

    @Test
    fun closedShiftRefusesMovement() =
        runTest {
            val (source, state) = withShift()
            source.recorder = { _, _, _ -> throw problem(409, "CASHIER_SHIFT_CLOSED") }
            val form = state.withdrawal()
            source.current = { null }

            form.confirm()
            runCurrent()

            assertNull(state.movement)
            assertEquals("O turno de caixa já está fechado.", state.message)
            assertEquals(2, source.currentCalls, "não releu o turno corrente")
            assertNull(state.shift)
            assertEquals(1, changes, "a releitura que tirou o turno não avisou a casca")
        }

    @Test
    fun closedShiftRefusesClose() =
        runTest {
            val (source, state) = withShift()
            source.closer = { throw problem(409, "CASHIER_SHIFT_CLOSED") }
            val closing = state.closingForm()
            source.current = { null }

            closing.confirm()
            runCurrent()

            assertNull(state.closing)
            assertEquals("O turno de caixa já está fechado.", state.message)
            assertNull(state.shift)
            assertEquals(1, changes)
        }

    @Test
    fun movementStoredFailureIsCloseOnlyAndRereads() =
        runTest {
            val (source, state) = withShift()
            source.recorder = { _, _, _ -> throw problem(500, null) }
            val form = state.withdrawal()

            form.confirm()
            runCurrent()

            assertTrue(form.closeOnly)
            assertEquals(
                "O servidor não confirmou o movimento. Feche e confira o turno antes de refazer.",
                form.message,
            )
            form.confirm()
            runCurrent()
            assertEquals(1, source.movementCalls.size, "repetiu com a resposta guardada sob a chave")

            form.leave()
            runCurrent()

            assertNull(state.movement)
            assertEquals(2, source.currentCalls, "o \"Fechar\" não conferiu o turno")
        }

    @Test
    fun closeStoredFailureIsCloseOnlyAndRereads() =
        runTest {
            val (source, state) = withShift()
            source.closer = { throw problem(503, null) }
            val closing = state.closingForm()

            closing.confirm()
            runCurrent()

            assertTrue(closing.closeOnly)
            assertEquals(
                "O servidor não confirmou o fechamento. Feche e confira o turno antes de refazer.",
                closing.message,
            )
            source.current = { null }
            closing.leave()
            runCurrent()

            assertNull(state.closing)
            assertNull(state.shift)
            assertEquals(1, changes)
            assertTrue(state.canViewReport, "o turno que sumiu na conferência não oferece o relatório")
        }

    @Test
    fun closeFormNeverExposesExpected() =
        runTest {
            val source = FakeCashierSource()
            source.current = {
                shiftOf(
                    expectedVisible = true,
                    expected = listOf(expectedOf("CASH", "987.65")),
                    expectedTotal = "987.65",
                )
            }
            val (_, state) = withShift(source)
            assertEquals("R$ 987,65", state.shift?.expectedTotal, "o painel deveria mostrar o esperado visível")
            val closing = state.closingForm()

            assertEquals(
                listOf("Dinheiro", "Cartão de débito", "Cartão de crédito", "Pix", "Transferência bancária", "Outro"),
                closing.entries.map { it.label },
            )
            assertTrue(closing.entries.all { it.text.isEmpty() }, "a contagem veio preenchida")
            assertFalse("987" in closing.entries.toString(), "o formulário de fechamento carrega o esperado")
            assertNull(closing.message)
        }

    @Test
    fun closeShowsReportWithDifferences() =
        runTest {
            val (source, state) = withShift()
            // Valores que não fecham entre si: a tela mostra o que o servidor mandou, sem refazer a conta.
            source.closer = {
                reportOf(
                    counts =
                        listOf(
                            countOf("CASH", expected = "150.00", declared = "140.00", difference = "-12.34"),
                            countOf("PIX", expected = "50.00", declared = "-5.00", difference = "0.00"),
                        ),
                    expectedTotal = "200.00",
                    declaredTotal = "1234.56",
                    differenceTotal = "-7.00",
                )
            }
            val closing = state.closingForm()
            closing.setCount(PaymentMethod.CASH, "140,00")
            closing.setCount(PaymentMethod.PIX, "-5,00")

            closing.confirm()
            runCurrent()

            assertEquals(
                listOf(
                    CashCount(PaymentMethod.CASH, Money.parse("140.00")),
                    CashCount(PaymentMethod.PIX, Money.parse("-5.00")),
                ),
                source.closeCalls.single().counts,
                "a forma vazia foi, ou a contagem não foi exata",
            )
            assertEquals(SHIFT_ID, source.closeCalls.single().shiftId)
            assertNull(state.closing)
            assertNull(state.shift)
            val report = assertNotNull(state.report)
            assertEquals("CX-0001", report.number)
            assertEquals(
                listOf(
                    CountLine("Dinheiro", "R$ 150,00", "R$ 140,00", "-R$ 12,34"),
                    CountLine("Pix", "R$ 50,00", "-R$ 5,00", "R$ 0,00"),
                ),
                report.counts,
            )
            assertEquals("R$ 200,00", report.expectedTotal)
            assertEquals("R$ 1.234,56", report.declaredTotal)
            assertEquals("-R$ 7,00", report.differenceTotal)
            assertEquals(true, report.hasDifference)
        }

    @Test
    fun invalidCountIsNotSent() =
        runTest {
            val (source, state) = withShift()
            val closing = state.closingForm()
            closing.setCount(PaymentMethod.CASH, "12,345")

            closing.confirm()
            runCurrent()

            assertEquals(0, source.closeCalls.size)
            assertEquals("Confira os valores contados: use o formato 1.234,56.", closing.message)
        }

    @Test
    fun unknownMethodShowsAsOther() =
        runTest {
            val (source, state) = withShift()
            source.closer = {
                reportOf(
                    counts =
                        listOf(
                            countOf("VOUCHER", expected = "10.00", declared = "0.00", difference = "-10.00"),
                            countOf("CASH", expected = "150.00", declared = "150.00", difference = "0.00"),
                        ),
                )
            }

            state.closingForm().confirm()
            runCurrent()

            val report = assertNotNull(state.report)
            assertEquals(
                listOf(
                    CountLine("Outro (VOUCHER)", "R$ 10,00", "R$ 0,00", "-R$ 10,00"),
                    CountLine("Dinheiro", "R$ 150,00", "R$ 150,00", "R$ 0,00"),
                ),
                report.counts,
            )
        }

    @Test
    fun closeNotifiesShiftChanged() =
        runTest {
            val (_, state) = withShift()

            state.closingForm().confirm()
            runCurrent()

            assertEquals(1, changes)
        }

    @Test
    fun viewReportRereadsTheClosedShift() =
        runTest {
            val (source, state) = withShift()
            state.closingForm().confirm()
            runCurrent()
            assertFalse(state.canViewReport, "oferece reler o relatório que está na tela")

            state.dismissReport()
            assertNull(state.report)
            assertTrue(state.canViewReport)
            source.reporter = { reportOf(differenceTotal = "-3.00") }
            state.showReport()
            runCurrent()

            assertEquals(listOf(SHIFT_ID), source.reportCalls)
            assertEquals("-R$ 3,00", state.report?.differenceTotal)
        }

    @Test
    fun viewReportNeedsShiftsRead() =
        runTest {
            val (source, state) = withShift(permissions = setOf(OPERATE))
            state.closingForm().confirm()
            runCurrent()
            assertNotNull(state.report, "o relatório do próprio fechamento não apareceu")

            state.dismissReport()
            state.showReport()
            runCurrent()

            assertFalse(state.canViewReport)
            assertEquals(emptyList(), source.reportCalls)
        }

    @Test
    fun noDialogsWithoutAShift() =
        runTest {
            val state = started(FakeCashierSource())

            state.startMovement(CashMovementType.SUPPLY)
            state.startClose()

            assertNull(state.movement)
            assertNull(state.closing)
        }
}
