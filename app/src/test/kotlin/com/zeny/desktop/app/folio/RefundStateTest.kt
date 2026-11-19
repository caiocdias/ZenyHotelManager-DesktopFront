package com.zeny.desktop.app.folio

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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val REFUND = "billing.payments.refund"
private val PAID = Uuid.parse("60000000-0000-0000-0000-000000000001")
private const val UNCONFIRMED =
    "Não foi possível confirmar o reembolso. Reembolse de novo sem mudar nada; " +
        "se fechar, confira a conta antes de refazer."
private const val SERVER_UNCONFIRMED =
    "O servidor não confirmou o reembolso. Feche e confira a conta antes de refazer."
private const val REASON_TEXT = "Informe o motivo, com até 200 caracteres."

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class RefundApprover(
    var cancel: Boolean = false,
) {
    val received = mutableListOf<ApprovableAction<PaymentResponse>>()

    suspend fun approve(action: ApprovableAction<PaymentResponse>): ApiResponse<PaymentResponse>? {
        received += action
        return try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
    }
}

private fun rowOf(
    method: PaymentMethod? = PaymentMethod.CASH,
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

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra do brief
class RefundStateTest {
    private val approver = RefundApprover()
    private var changes = 0
    private var closedCalls = 0

    private fun TestScope.refund(
        source: FakePaymentSource,
        row: PaymentRow = rowOf(),
        openShift: (() -> Unit)? = null,
    ) = RefundState(
        source,
        backgroundScope,
        FOLIO_ID.toString(),
        row,
        approver::approve,
        openShift,
        { changes++ },
        { closedCalls++ },
    )

    private fun approval() = ApiError.ApprovalRequired("PAYMENT_REFUND", REFUND, "payment", null, null)

    @Test
    fun refundNeedsReason() =
        runTest {
            val source = FakePaymentSource()
            val state = refund(source)

            state.reason = "   "
            state.confirm()
            runCurrent()

            assertEquals(REASON_TEXT, state.errors[PaymentProblem.REASON])
            assertTrue(source.refundCalls.isEmpty())

            state.reason = "a".repeat(200)
            state.reason = "a".repeat(201)
            assertEquals(200, state.reason.length, "o motivo passa de 200 caracteres")
            assertNull(state.errors[PaymentProblem.REASON], "editar o motivo tira o aviso")

            state.reason = "  Cobrado em dobro  "
            state.confirm()
            runCurrent()
            val call = source.refundCalls.single()
            assertEquals("Cobrado em dobro", call.input.reason)
            assertEquals(PAID.toString(), call.paymentId)
            assertEquals(FOLIO_ID.toString(), call.folioId)
        }

    @Test
    fun amountSuggestedIsTheRefundable() =
        runTest {
            val source = FakePaymentSource()
            val state = refund(source, rowOf(refundable = "1234.56"))

            assertEquals("R$ 1.234,56", state.amount)
            assertEquals("Reembolsável: R$ 1.234,56", state.refundable)

            state.reason = "Cobrado em dobro"
            state.amount = "1.000,05"
            state.confirm()
            runCurrent()
            assertEquals(
                Money.parse("1000.05"),
                source.refundCalls
                    .single()
                    .input.amount,
            )
        }

    @Test
    fun approvalRequiredGoesThroughApprove() =
        runTest {
            val source = FakePaymentSource().apply { requiresApproval = approval() }
            val state = refund(source)
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertSame(source.refundActions.single(), approver.received.single())
            val key =
                source.refundCalls
                    .single()
                    .intent.key
            assertEquals(listOf(RefundRun(key, null), RefundRun(key, "tok")), source.refundRuns)
            assertTrue(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun cancelledApprovalLeavesTextAndUnlocks() =
        runTest {
            approver.cancel = true
            val source = FakePaymentSource().apply { requiresApproval = approval() }
            val state = refund(source)
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertEquals("Aprovação cancelada. O reembolso não foi feito.", state.message)
            assertFalse(state.busy)
            assertFalse(state.done)
            assertFalse(state.locked)
            assertTrue(state.canConfirm)
            state.reason = "Outro motivo"
            assertEquals("Outro motivo", state.reason, "as entradas destravam")
            assertEquals(1, source.refundRuns.size)
            assertEquals(0, changes)
        }

    @Test
    fun retryAfterNetworkKeepsTheKey() =
        runTest {
            val source = FakePaymentSource()
            source.refundOutcomes += ApiError.Network(IOException("sem rede"))
            val state = refund(source)
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertTrue(state.unconfirmed)
            assertEquals(UNCONFIRMED, state.message)
            state.reason = "Outro"
            state.amount = "1,00"
            assertEquals("Cobrado em dobro", state.reason)
            assertEquals("R$ 80,00", state.amount)

            state.confirm()
            runCurrent()

            assertEquals(2, source.refundCalls.size)
            assertSame(source.refundCalls[0].intent, source.refundCalls[1].intent)
            assertEquals(source.refundRuns[0].key, source.refundRuns[1].key)
            assertTrue(state.done)
            assertEquals(1, changes)
        }

    @Test
    fun storedFailureIsCloseOnly() =
        runTest {
            val source = FakePaymentSource()
            source.refundOutcomes += ApiError.Problem(502, "BAD_GATEWAY", null, emptyList())
            val state = refund(source)
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertTrue(state.closeOnly)
            assertEquals(SERVER_UNCONFIRMED, state.message)
            assertFalse(state.canConfirm)
            state.confirm()
            runCurrent()
            assertEquals(1, source.refundRuns.size)
            assertEquals(0, changes)
        }

    @Test
    fun refundOverRefundableShowsMessage() =
        runTest {
            val source = FakePaymentSource().apply { requiresApproval = approval() }
            source.refundOutcomes += ApiError.Problem(422, "REFUND_EXCEEDS_REFUNDABLE", "detalhe", emptyList())
            val state = refund(source)
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertEquals("O reembolso é maior que o valor reembolsável.", state.message)
            assertEquals(listOf<String?>(null, "tok"), source.refundRuns.map { it.token }, "depois da aprovação")
            assertFalse(state.busy)
            assertFalse(state.locked, "a recusa definitiva destrava")
            assertFalse(state.unconfirmed)
            assertFalse(state.done)

            state.confirm()
            runCurrent()
            assertNotEquals(source.refundCalls[0].intent.key, source.refundCalls[1].intent.key, "outra intenção")
            assertTrue(state.done)
        }

    @Test
    fun cashRefundSendsNoNsu() =
        runTest {
            val cash = FakePaymentSource()
            val inCash = refund(cash)
            assertFalse(inCash.showsCodes)
            inCash.nsu = "123456"
            inCash.authorizationCode = "AB12"
            inCash.reason = "Cobrado em dobro"
            inCash.confirm()
            runCurrent()
            val cashInput = cash.refundCalls.single().input
            assertNull(cashInput.nsu)
            assertNull(cashInput.authorizationCode)

            val unknown = FakePaymentSource()
            val inUnknown = refund(unknown, rowOf(method = null))
            assertFalse(inUnknown.showsCodes, "forma desconhecida: o servidor decide")
            inUnknown.nsu = "123456"
            inUnknown.reason = "Cobrado em dobro"
            inUnknown.confirm()
            runCurrent()
            assertNull(
                unknown.refundCalls
                    .single()
                    .input.nsu,
            )

            val card = FakePaymentSource()
            val inCard = refund(card, rowOf(method = PaymentMethod.CREDIT_CARD))
            assertTrue(inCard.showsCodes)
            inCard.nsu = " 123456 "
            inCard.authorizationCode = "AB12"
            inCard.reason = "Cobrado em dobro"
            inCard.confirm()
            runCurrent()
            val cardInput = card.refundCalls.single().input
            assertEquals("123456", cardInput.nsu)
            assertEquals("AB12", cardInput.authorizationCode)
        }

    @Test
    fun cardLikeNsuIsRefusedWithText() =
        runTest {
            val source = FakePaymentSource()
            val state = refund(source, rowOf(method = PaymentMethod.DEBIT_CARD))
            state.nsu = "4111 1111 1111 1111"
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertEquals("O NSU tem até 20 letras e números, no máximo 12 dígitos.", state.errors[PaymentProblem.NSU])
            assertTrue(source.refundCalls.isEmpty())
            assertFalse("4111" in state.toString())
        }

    @Test
    fun shiftRequiredOffersOpenShiftOnlyWithTheShortcut() =
        runTest {
            val shift = ApiError.Problem(422, "CASHIER_SHIFT_REQUIRED", null, emptyList())
            var opened = 0
            val source = FakePaymentSource().apply { refundOutcomes += shift }
            val state = refund(source, openShift = { opened++ })
            state.reason = "Cobrado em dobro"
            state.confirm()
            runCurrent()

            assertEquals("É preciso um turno de caixa aberto.", state.message)
            assertTrue(state.offersOpenShift)
            assertFalse(state.locked)
            state.openShiftAndClose()
            assertEquals(1, opened)

            val alone = refund(FakePaymentSource().apply { refundOutcomes += shift })
            alone.reason = "Cobrado em dobro"
            alone.confirm()
            runCurrent()
            assertEquals("É preciso um turno de caixa aberto.", alone.message)
            assertFalse(alone.offersOpenShift, "sem openShift, só o texto")
        }

    @Test
    fun closedFolioShowsMessageAndCallsBack() =
        runTest {
            val source = FakePaymentSource()
            source.refundOutcomes += ApiError.Problem(409, "FOLIO_CLOSED", null, emptyList())
            val state = refund(source)
            state.reason = "Cobrado em dobro"

            state.confirm()
            runCurrent()

            assertEquals("A conta já está fechada.", state.message)
            assertEquals(1, closedCalls)
            assertEquals(0, changes)
        }

    @Test
    fun successReloadsTheFolio() =
        runTest {
            val folios = FakeFolioSource()
            val payments =
                FakePaymentSource().apply {
                    this.payments = { listOf(paymentOf(PAID, amount = "100.00", refundable = "100.00")) }
                }
            var written = 0
            val folio =
                FolioState(
                    wiringOf(folios, setOf(READ, REFUND), payments = payments).withApprover(approver),
                    backgroundScope,
                    FOLIO_ID.toString(),
                    onWritten = { written++ },
                ).also {
                    it.start()
                    runCurrent()
                }
            val row = folio.payments.single()
            assertTrue(folio.canRefund(row))
            payments.payments = {
                listOf(
                    paymentOf(PAID, amount = "100.00", refunded = "100.00", refundable = "0.00"),
                    paymentOf(type = "REFUND", amount = "100.00"),
                )
            }

            folio.startRefund(row)
            val state = assertNotNull(folio.refund)
            state.reason = "Cobrado em dobro"
            state.confirm()
            runCurrent()

            assertNull(folio.refund, "o diálogo fecha depois do reembolso")
            assertEquals("Reembolso registrado.", folio.notice)
            assertEquals(2, folios.folioCalls.size)
            assertEquals(2, payments.paymentCalls.size)
            assertEquals(1, written)
            assertEquals(listOf("Recebimento", "Reembolso"), folio.payments.map { it.type })
            assertTrue(folio.payments.none { folio.canRefund(it) })
        }
}

/** O mesmo portador, com o reembolso aprovado pelo [approver] de teste. */
private fun FolioWiring.withApprover(approver: RefundApprover) =
    FolioWiring(
        source,
        permissions,
        approve,
        guestPdf,
        internalPdf,
        payments = payments,
        approveRefund = approver::approve,
        openShift = openShift,
    )
