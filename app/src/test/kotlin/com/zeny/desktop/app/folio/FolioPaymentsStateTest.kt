package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.FolioResponse
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
import kotlin.test.assertTrue

private const val READ = "billing.folios.read"
private const val RECEIVE = "billing.payments.receive"

/** A seção "Pagamentos" do painel da conta. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolioPaymentsStateTest {
    private fun TestScope.started(
        folios: FakeFolioSource,
        payments: FakePaymentSource?,
        permissions: Set<String> = setOf(READ, RECEIVE),
        openShift: (() -> Unit)? = null,
    ) = FolioState(
        wiringOf(folios, permissions, payments = payments, openShift = openShift),
        backgroundScope,
        FOLIO_ID.toString(),
    ).also {
        it.start()
        runCurrent()
    }

    @Test
    fun paymentsLoadWithTheFolio() =
        runTest {
            val payments =
                FakePaymentSource().apply {
                    this.payments = {
                        listOf(
                            paymentOf(
                                amount = "300.00",
                                method = "CREDIT_CARD",
                                cardBrand = "Visa",
                                cardLast4 = "1234",
                            ),
                            paymentOf(amount = "50.00", deposit = true, refunded = "20.00", refundable = "30.00"),
                            paymentOf(amount = "20.00", type = "REFUND", refunded = "0.00", refundable = "5.00"),
                        )
                    }
                }

            val state = started(FakeFolioSource(), payments)

            assertNotNull(state.view)
            assertEquals(listOf(FOLIO_ID.toString()), payments.paymentCalls)
            val (card, cash, refund) = state.payments
            assertEquals("18/11/2026", card.date)
            assertEquals("Recebimento", card.type)
            assertEquals("Cartão de crédito", card.method)
            assertEquals(PaymentMethod.CREDIT_CARD, card.methodCode)
            assertEquals("Visa final 1234", card.card)
            assertEquals("R$ 300,00", card.amount)
            assertNull(card.refunded)
            assertFalse(card.deposit)
            assertFalse("1234" in card.toString(), "o toString não mostra o cartão")
            assertTrue(cash.deposit)
            assertEquals("Dinheiro", cash.method)
            assertNull(cash.card)
            assertEquals("R$ 20,00", cash.refunded)
            assertEquals(Money.parse("30.00"), cash.refundable)
            assertEquals("Reembolso", refund.type)
            assertNull(refund.refunded, "reembolsado zero não aparece")
            assertNull(refund.refundable, "só um recebimento é reembolsável")

            state.reload()
            runCurrent()
            assertEquals(2, payments.paymentCalls.size, "relê os pagamentos junto da conta")
        }

    @Test
    fun noPaymentsSectionWithoutThePort() =
        runTest {
            val state = started(FakeFolioSource(), null)

            assertNotNull(state.view)
            assertTrue(state.payments.isEmpty())
            assertNull(state.paymentsError)
            assertFalse(state.canReceive)
        }

    @Test
    fun paymentsFailureKeepsTheFolio() =
        runTest {
            val payments = FakePaymentSource().apply { this.payments = { throw ApiError.Network(IOException("x")) } }

            val state = started(FakeFolioSource(), payments)

            assertNotNull(state.view)
            assertNull(state.error)
            assertEquals("Não foi possível ler os pagamentos.", state.paymentsError)
            assertTrue(state.payments.isEmpty())

            payments.payments = { listOf(paymentOf()) }
            state.reload()
            runCurrent()
            assertNull(state.paymentsError)
            assertEquals(1, state.payments.size)
        }

    @Test
    fun unknownMethodAndTypeShowAsOther() =
        runTest {
            val payments =
                FakePaymentSource().apply {
                    this.payments = {
                        listOf(paymentOf(method = "CRYPTO", type = "CHARGEBACK"), paymentOf(method = "OTHER"))
                    }
                }

            val state = started(FakeFolioSource(), payments)

            val (unknown, other) = state.payments
            assertEquals("Outro (CRYPTO)", unknown.method)
            assertNull(unknown.methodCode)
            assertEquals("Outro (CHARGEBACK)", unknown.type)
            assertEquals("Outra", other.method)
            assertEquals(PaymentMethod.OTHER, other.methodCode)
        }

    @Test
    fun receiveNeedsPermissionAndOpenFolio() =
        runTest {
            val payments = FakePaymentSource()
            val without = started(FakeFolioSource(), payments, permissions = setOf(READ))
            assertFalse(without.canReceive)
            without.startPayment()
            assertNull(without.payment)

            val open = started(FakeFolioSource(), payments)
            assertTrue(open.canReceive)
            open.startPayment()
            assertNotNull(open.payment)

            val closed = FakeFolioSource().apply { folio = { folioOf(status = FolioResponse.Status.CLOSED) } }
            val shut = started(closed, payments)
            assertFalse(shut.canReceive)
            shut.startPayment()
            assertNull(shut.payment)
        }

    @Test
    fun openShiftClosesTheDialogFirst() =
        runTest {
            val payments =
                FakePaymentSource().apply {
                    receive = { throw ApiError.Problem(422, "CASHIER_SHIFT_REQUIRED", null, emptyList()) }
                }
            var seen = "não chamado"
            lateinit var state: FolioState
            val openShift = { seen = if (state.payment == null) "fechado" else "aberto" }
            state = started(FakeFolioSource(), payments, openShift = openShift)
            state.startPayment()
            val form = assertNotNull(state.payment)
            form.amount = "10,00"
            form.confirm()
            runCurrent()
            assertTrue(form.shiftRequired)

            form.openShiftAndClose()

            assertNull(state.payment)
            assertEquals("fechado", seen, "o diálogo sai antes do atalho")
        }
}
