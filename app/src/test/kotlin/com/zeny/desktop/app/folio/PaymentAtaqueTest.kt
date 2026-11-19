package com.zeny.desktop.app.folio

import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.PaymentResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val RECEIVE = "billing.payments.receive"
private const val REFUND_PERMISSION = "billing.payments.refund"
private val PAID = Uuid.parse("60000000-0000-0000-0000-000000000002")

/** Números de cartão de teste, como a operadora colaria no NSU ou na autorização. */
private val CARD_LIKE = listOf("4111 1111 1111 1111", "4111-1111-1111-1111", "5555555555554444", "41111111111111")

private fun refundRow(
    method: PaymentMethod? = PaymentMethod.CREDIT_CARD,
    refundable: String = "80.00",
) = PaymentRow(
    id = PAID.toString(),
    date = "18/11/2026",
    type = "Recebimento",
    method = method?.let(FolioText::method) ?: "Outro (VOUCHER)",
    methodCode = method,
    card = null,
    amount = "R$ 100,00",
    refunded = null,
    refundable = Money.parse(refundable),
    deposit = false,
)

/**
 * Ataque da etapa aos estados do recebimento e do reembolso: dado de cartão com espaços e traços, bordas do valor,
 * duplo clique e aprovação perdida no reembolso, a troca de sessão com o diálogo aberto e o filtro do reembolsável.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por ataque
class PaymentAtaqueTest {
    private val received = mutableListOf<PaymentResponse>()
    private var refunds = 0

    private fun TestScope.form(source: FakePaymentSource) =
        PaymentFormState(source, backgroundScope, FOLIO_ID.toString(), null, null, { received += it }, {})

    private fun TestScope.refund(
        source: FakePaymentSource,
        approve: suspend (ApprovableAction<PaymentResponse>) -> ApiResponse<PaymentResponse>?,
        row: PaymentRow = refundRow(),
    ) = RefundState(source, backgroundScope, FOLIO_ID.toString(), row, approve, null, { refunds++ }, {})

    @Test
    fun cardNumbersInNsuOrAuthorizationNeverLeaveTheForm() =
        runTest {
            CARD_LIKE.forEach { code ->
                listOf(PaymentMethod.CREDIT_CARD, PaymentMethod.PIX, PaymentMethod.OTHER).forEach { method ->
                    val source = FakePaymentSource()
                    val form = form(source)
                    form.method = method
                    form.amount = "10,00"
                    form.nsu = code
                    form.authorizationCode = code
                    form.confirm()
                    runCurrent()

                    assertTrue(source.receiveCalls.isEmpty(), "$method: o número de cartão não vai à rede")
                    assertEquals(setOf(PaymentProblem.NSU, PaymentProblem.AUTHORIZATION_CODE), form.errors.keys)
                    val shown = form.errors.values.joinToString() + form.message.orEmpty() + form.toString()
                    assertFalse(code.filter(Char::isDigit).take(12) in shown.filter(Char::isDigit), shown)
                }
            }
        }

    @Test
    fun cardNumberInRefundCodesNeverLeavesTheForm() =
        runTest {
            CARD_LIKE.forEach { code ->
                val source = FakePaymentSource()
                val state = refund(source, { it.run(null) })
                state.reason = "Cobrado em dobro"
                state.nsu = code
                state.authorizationCode = code
                state.confirm()
                runCurrent()

                assertTrue(source.refundCalls.isEmpty(), "o número de cartão não vai à rede")
                assertEquals(setOf(PaymentProblem.NSU, PaymentProblem.AUTHORIZATION_CODE), state.errors.keys)
                val shown = state.errors.values.joinToString() + state.message.orEmpty() + state.toString()
                assertFalse(code.filter(Char::isDigit).take(12) in shown.filter(Char::isDigit), shown)
            }
        }

    @Test
    fun amountEdgesAreExactOrRefusedBeforeTheNetwork() =
        runTest {
            val accepted = mapOf("0,01" to "0.01", "1.000.000,00" to "1000000.00", "1000000" to "1000000.00")
            accepted.forEach { (typed, exact) ->
                val source = FakePaymentSource()
                val form = form(source)
                form.amount = typed
                form.confirm()
                runCurrent()
                assertEquals(
                    Money.parse(exact),
                    source.receiveCalls
                        .single()
                        .input.amount,
                    typed,
                )
            }
            listOf("0,00", "-1,00", "-0,01", "12,345", "1,5,0", "abc").forEach { typed ->
                val source = FakePaymentSource()
                val form = form(source)
                form.amount = typed
                form.confirm()
                runCurrent()
                assertTrue(source.receiveCalls.isEmpty(), "$typed não vai à rede")
                assertTrue(form.message != null || form.errors.isNotEmpty(), "$typed é recusado com texto")
            }
        }

    @Test
    fun tenderedEdgesFollowTheAmount() =
        runTest {
            val ok = FakePaymentSource()
            form(ok).apply {
                amount = "10,00"
                tendered = "10,00"
                confirm()
            }
            runCurrent()
            assertEquals(
                Money.parse("10.00"),
                ok.receiveCalls
                    .single()
                    .input.tendered,
            )

            listOf("9,99", "0,00", "-10,00", "10,001").forEach { typed ->
                val source = FakePaymentSource()
                val form = form(source)
                form.amount = "10,00"
                form.tendered = typed
                form.confirm()
                runCurrent()
                assertTrue(source.receiveCalls.isEmpty(), "entregue $typed não vai à rede")
                assertTrue(form.message != null || PaymentProblem.TENDERED in form.errors, "entregue $typed tem texto")
            }
        }

    @Test
    fun doubleConfirmRefundsOnce() =
        runTest {
            val source = FakePaymentSource()
            val late = LateAnswer<ApiResponse<PaymentResponse>?>()
            var asked = 0
            val state =
                refund(source, {
                    asked++
                    late.await()
                })
            state.reason = "Cobrado em dobro"

            state.confirm()
            state.confirm()
            runCurrent()
            state.confirm()
            runCurrent()

            assertEquals(1, source.refundCalls.size)
            assertEquals(1, asked)
            assertTrue(state.locked)
            late.answer(ApiResponse(paymentOf(type = "REFUND"), null, 201))
            runCurrent()
            assertTrue(state.done)
            assertEquals(1, refunds)
            state.confirm()
            runCurrent()
            assertEquals(1, source.refundCalls.size, "depois de feito, confirmar não reembolsa de novo")
        }

    /** O Gerente aprovou e a resposta se perdeu: o estado trava e a repetição leva a mesma intenção. */
    @Test
    fun lostApprovalKeepsTheIntentAndLocks() =
        runTest {
            val source = FakePaymentSource()
            source.requiresApproval =
                ApiError.ApprovalRequired("PAYMENT_REFUND", REFUND_PERMISSION, "payment", null, null)
            var lose = true
            val state =
                refund(source, { action ->
                    try {
                        action.run(null)
                    } catch (_: ApiError.ApprovalRequired) {
                        val done = action.run("tok")
                        if (lose) {
                            lose = false
                            throw ApiError.Network(IOException("resposta perdida"))
                        }
                        done
                    }
                })
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertTrue(state.unconfirmed)
            assertTrue(state.locked)
            assertFalse(state.done)
            state.amount = "1,00"
            assertEquals("R$ 80,00", state.amount, "resultado desconhecido trava o valor")

            state.confirm()
            runCurrent()

            assertEquals(2, source.refundCalls.size)
            assertSame(source.refundCalls[0].intent, source.refundCalls[1].intent)
            assertTrue(state.done)
            assertEquals(1, refunds)
        }

    @Test
    fun cancelledApprovalKeepsTheIntentUntilAnEdit() =
        runTest {
            val source = FakePaymentSource()
            source.requiresApproval =
                ApiError.ApprovalRequired("PAYMENT_REFUND", REFUND_PERMISSION, "payment", null, null)
            val state =
                refund(source, { action ->
                    try {
                        action.run(null)
                    } catch (_: ApiError.ApprovalRequired) {
                        null
                    }
                })
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()
            state.confirm()
            runCurrent()
            assertSame(source.refundCalls[0].intent, source.refundCalls[1].intent, "sem edição, a mesma intenção")
            assertFalse(state.locked)

            state.amount = "10,00"
            state.confirm()
            runCurrent()
            assertNotSame(source.refundCalls[1].intent, source.refundCalls[2].intent, "editar é outra intenção")
            assertEquals(Money.parse("10.00"), source.refundCalls[2].input.amount)
            assertEquals(0, refunds)
        }

    @Test
    fun unknownMethodRefundNeverSendsCodes() =
        runTest {
            val source = FakePaymentSource()
            val state = refund(source, { it.run(null) }, refundRow(method = null))
            assertFalse(state.showsCodes)
            state.reason = "Cobrado em dobro"
            state.nsu = "ABC123"
            state.authorizationCode = "XYZ789"
            state.confirm()
            runCurrent()

            val input = source.refundCalls.single().input
            assertNull(input.nsu)
            assertNull(input.authorizationCode)
        }

    /** Troca de sessão com "Receber" em voo: a resposta tardia não relê a conta nem avisa a lista. */
    @Test
    fun sessionSwitchWithPaymentInFlightDropsTheLateAnswer() =
        runTest {
            val folios = FakeFolioSource()
            val payments = FakePaymentSource()
            val late = LateAnswer<PaymentResponse>()
            payments.receive = { late.await() }
            var written = 0
            val state =
                FolioState(
                    wiringOf(folios, setOf(READ, RECEIVE), payments = payments),
                    backgroundScope,
                    FOLIO_ID.toString(),
                    onWritten = { written++ },
                ).also {
                    it.start()
                    runCurrent()
                }
            state.startPayment()
            val form = assertNotNull(state.payment)
            form.amount = "10,00"
            form.confirm()
            runCurrent()
            assertTrue(late.asked)
            val folioReads = folios.folioCalls.size
            val paymentReads = payments.paymentCalls.size

            state.close()
            late.answer(paymentOf())
            runCurrent()

            assertEquals(folioReads, folios.folioCalls.size, "a conta não é relida depois da troca")
            assertEquals(paymentReads, payments.paymentCalls.size, "os pagamentos não são relidos depois da troca")
            assertEquals(0, written, "a lista não é avisada depois da troca")
            assertNull(state.notice)
        }

    @Test
    fun onlyAKnownPaymentWithPositiveRefundableIsRefundable() =
        runTest {
            val payments =
                FakePaymentSource().apply {
                    this.payments = {
                        listOf(
                            paymentOf(amount = "10.00", type = "REFUND", refundable = "10.00"),
                            paymentOf(amount = "10.00", type = "CHARGEBACK", method = "VOUCHER", refundable = "10.00"),
                            paymentOf(amount = "10.00", refundable = "0.00"),
                            paymentOf(amount = "10.00", refundable = "-1.00"),
                            paymentOf(amount = "10.00", refundable = "dez reais"),
                            paymentOf(amount = "10.00", method = "VOUCHER", refundable = "0.01"),
                        )
                    }
                }
            val state =
                FolioState(
                    wiringOf(FakeFolioSource(), setOf(READ, RECEIVE, REFUND_PERMISSION), payments = payments),
                    backgroundScope,
                    FOLIO_ID.toString(),
                ).also {
                    it.start()
                    runCurrent()
                }

            val rows = state.payments
            assertEquals(6, rows.size)
            assertEquals("Outro (CHARGEBACK)", rows[1].type)
            assertEquals("Outro (VOUCHER)", rows[1].method)
            assertNull(rows[1].methodCode)
            assertEquals(listOf(false, false, false, false, false, true), rows.map { state.canRefund(it) })
            assertEquals(Money.parse("0.01"), rows[5].refundable)
        }
}
