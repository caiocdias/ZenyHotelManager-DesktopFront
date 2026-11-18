package com.zeny.desktop.app.folio

import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.FolioResponse
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val READ = "billing.folios.read"
private const val RECEIVE = "billing.payments.receive"
private const val SHIFT_TEXT = "É preciso um turno de caixa aberto."
private const val SERVER_UNCONFIRMED =
    "O servidor não confirmou o recebimento. Feche e confira a conta antes de receber de novo."
private const val CARD_LIKE = "4111 1111 1111 1111"

/** As propriedades públicas previstas do formulário: nenhuma de número de cartão nem de CVV. */
private val PAYMENT_CODES = setOf("nsu", "authorizationCode")
private val FIELDS = setOf("method", "amount", "deposit", "tendered", "cardBrand", "cardLast4") + PAYMENT_CODES
private val SHOWN = setOf("showsTendered", "showsCard", "showsCodes", "errors", "locked", "shiftRequired")
private val FORM_STATUS = setOf("busy", "unconfirmed", "closeOnly", "message", "canConfirm", "closesOnEscape")

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra do brief
class PaymentFormStateTest {
    private val received = mutableListOf<PaymentResponse>()
    private var closedCalls = 0

    private fun TestScope.form(
        source: FakePaymentSource,
        suggested: Money? = null,
        openShift: (() -> Unit)? = null,
    ) = PaymentFormState(
        source,
        backgroundScope,
        FOLIO_ID.toString(),
        suggested,
        openShift,
        { received += it },
        { closedCalls++ },
    )

    /** A conta aberta com a permissão de receber e o diálogo de recebimento aberto por ela. */
    private fun TestScope.panel(
        folios: FakeFolioSource,
        payments: FakePaymentSource,
    ): FolioState =
        FolioState(wiringOf(folios, setOf(READ, RECEIVE), payments = payments), backgroundScope, FOLIO_ID.toString())
            .also {
                it.start()
                runCurrent()
                it.startPayment()
                runCurrent()
            }

    @Test
    fun doubleConfirmReceivesOnce() =
        runTest {
            val source = FakePaymentSource()
            val late = LateAnswer<PaymentResponse>()
            source.receive = { late.await() }
            val form = form(source)
            form.amount = "50,00"

            form.confirm()
            form.confirm()
            runCurrent()
            form.confirm()
            runCurrent()

            assertEquals(1, source.receiveCalls.size)
            assertTrue(form.busy)
            assertTrue(form.locked)
            late.answer(paymentOf())
            runCurrent()
            assertEquals(1, received.size)
            assertEquals(1, source.receiveCalls.size)
        }

    @Test
    fun retryAfterNetworkKeepsTheKey() =
        runTest {
            val source = FakePaymentSource()
            var failures = 1
            source.receive = {
                if (failures-- > 0) throw ApiError.Network(IOException("sem rede"))
                paymentOf()
            }
            val form = form(source)
            form.amount = "50,00"

            form.confirm()
            runCurrent()

            assertTrue(form.unconfirmed)
            assertFalse(form.closeOnly)
            assertNotNull(form.message)
            form.amount = "60,00"
            assertEquals("50,00", form.amount, "as entradas ficam travadas sem resultado")

            form.confirm()
            runCurrent()

            assertEquals(2, source.receiveCalls.size)
            assertSame(source.receiveCalls[0].intent, source.receiveCalls[1].intent)
            assertEquals(1, received.size)
            assertFalse(form.unconfirmed)
        }

    @Test
    fun amountGoesAsExactDecimal() =
        runTest {
            val source = FakePaymentSource()
            val form = form(source)
            form.amount = "1,234"

            form.confirm()
            runCurrent()

            assertTrue(source.receiveCalls.isEmpty(), "três casas não vão ao servidor")
            assertEquals("Use no máximo 2 casas decimais.", form.message)

            form.amount = "1.234,56"
            form.confirm()
            runCurrent()

            val input = source.receiveCalls.single().input
            assertEquals(Money.parse("1234.56"), input.amount)
            assertEquals("1234.56", input.amount.toApi())
            assertEquals(PaymentMethod.CASH, input.method, "a forma padrão é o dinheiro")
            assertFalse(input.deposit, "o depósito começa desligado")
        }

    @Test
    fun suggestedAmountIsTheServerBalance() =
        runTest {
            assertEquals("R$ 1.234,56", form(FakePaymentSource(), Money.parse("1234.56")).amount)
            assertEquals("", form(FakePaymentSource(), Money.parse("0.00")).amount)
            assertEquals("", form(FakePaymentSource(), Money.parse("-20.00")).amount)
            assertEquals("", form(FakePaymentSource(), null).amount)

            val folios = FakeFolioSource().apply { folio = { folioOf(balance = "150.00") } }
            assertEquals("R$ 150,00", assertNotNull(panel(folios, FakePaymentSource()).payment).amount)
            val credit = FakeFolioSource().apply { folio = { folioOf(balance = "-30.00") } }
            assertEquals("", assertNotNull(panel(credit, FakePaymentSource()).payment).amount)
        }

    @Test
    fun cashHidesCardFieldsAndCardHidesTendered() =
        runTest {
            val source = FakePaymentSource()
            val form = form(source)
            assertTrue(form.showsTendered)
            assertFalse(form.showsCard)
            assertFalse(form.showsCodes)

            form.method = PaymentMethod.CREDIT_CARD
            assertFalse(form.showsTendered)
            assertTrue(form.showsCard)
            assertTrue(form.showsCodes)
            form.cardBrand = "Visa"
            form.cardLast4 = "1234"
            form.nsu = "123456"
            form.authorizationCode = "AB12"
            form.method = PaymentMethod.PIX
            assertFalse(form.showsCard)
            assertTrue(form.showsCodes)
            form.method = PaymentMethod.CASH
            form.amount = "80,00"
            form.tendered = "100,00"

            form.confirm()
            runCurrent()

            val cash = source.receiveCalls.single().input
            assertEquals(PaymentMethod.CASH, cash.method)
            assertEquals(Money.parse("100.00"), cash.tendered)
            assertNull(cash.cardBrand)
            assertNull(cash.cardLast4)
            assertNull(cash.nsu)
            assertNull(cash.authorizationCode)

            val card = form(source)
            card.tendered = "100,00"
            card.method = PaymentMethod.DEBIT_CARD
            card.amount = "80,00"
            card.cardBrand = "Master Card"
            card.cardLast4 = "9876"
            card.confirm()
            runCurrent()
            val debit = source.receiveCalls.last().input
            assertNull(debit.tendered)
            assertEquals("Master Card", debit.cardBrand)
            assertEquals("9876", debit.cardLast4)
        }

    @Test
    fun cardLikeNsuIsRefusedWithText() =
        runTest {
            val source = FakePaymentSource()
            val form = form(source)
            form.method = PaymentMethod.CREDIT_CARD
            form.amount = "80,00"

            listOf(CARD_LIKE, "4111111111111111", "1234567890123").forEach { typed ->
                form.nsu = typed
                form.confirm()
                runCurrent()
                val text = assertNotNull(form.errors[PaymentProblem.NSU], "o NSU \"$typed\" foi aceito")
                assertFalse(typed in text)
                assertFalse(typed in form.message.orEmpty())
                assertFalse(typed in form.toString())
            }
            form.authorizationCode = CARD_LIKE
            form.confirm()
            runCurrent()
            assertNotNull(form.errors[PaymentProblem.AUTHORIZATION_CODE])
            assertTrue(source.receiveCalls.isEmpty(), "nada com cara de cartão vai à rede")

            form.nsu = "123456789012"
            form.authorizationCode = "AB1234"
            form.confirm()
            runCurrent()
            assertTrue(form.errors.isEmpty())
            assertEquals(
                "123456789012",
                source.receiveCalls
                    .single()
                    .input.nsu,
            )
        }

    @Test
    fun formatProblemsShowTextNextToTheField() =
        runTest {
            val source = FakePaymentSource()
            val form = form(source)
            form.amount = "100,00"
            form.tendered = "50,00"
            form.confirm()
            runCurrent()
            assertEquals(setOf(PaymentProblem.TENDERED), form.errors.keys)

            form.tendered = ""
            form.method = PaymentMethod.CREDIT_CARD
            form.cardBrand = "V1sa"
            form.cardLast4 = "12"
            form.confirm()
            runCurrent()
            assertEquals(setOf(PaymentProblem.CARD_BRAND, PaymentProblem.CARD_LAST4), form.errors.keys)
            assertTrue(form.errors.values.all { it.isNotBlank() })
            assertTrue(source.receiveCalls.isEmpty())

            form.amount = "0,00"
            form.cardBrand = ""
            form.cardLast4 = ""
            form.confirm()
            runCurrent()
            assertEquals(setOf(PaymentProblem.AMOUNT), form.errors.keys)
            form.amount = ""
            form.confirm()
            runCurrent()
            assertEquals("Informe o valor.", form.message)
            assertTrue(source.receiveCalls.isEmpty())
        }

    @Test
    fun formOffersNoCardNumberField() {
        val properties =
            PaymentFormState::class.java.methods
                .filter { it.declaringClass == PaymentFormState::class.java && it.parameterCount == 0 }
                .mapNotNull { method ->
                    val name = method.name
                    when {
                        name.startsWith("get") -> name.removePrefix("get")
                        name.startsWith("is") -> name.removePrefix("is")
                        else -> null
                    }
                }.map { it.replaceFirstChar(Char::lowercase) }
                .toSet()

        assertEquals(
            FIELDS + SHOWN + FORM_STATUS,
            properties,
        )
        assertTrue(properties.none { "number" in it.lowercase() || "cvv" in it.lowercase() || "pan" == it })
    }

    @Test
    fun shiftRequiredShowsMessageAndShortcut() =
        runTest {
            val source = FakePaymentSource()
            source.receive = { throw ApiError.Problem(422, "CASHIER_SHIFT_REQUIRED", "detalhe", emptyList()) }
            var opened = 0
            val form = form(source, openShift = { opened++ })
            form.amount = "50,00"

            form.confirm()
            runCurrent()

            assertEquals(SHIFT_TEXT, form.message)
            assertTrue(form.shiftRequired)
            assertFalse(form.locked, "a recusa definitiva destrava o formulário")
            assertFalse(form.unconfirmed)
            form.openShiftAndClose()
            assertEquals(1, opened)

            source.receive = { paymentOf() }
            form.confirm()
            runCurrent()
            assertFalse(form.shiftRequired)
            assertTrue(
                source.receiveCalls[0].intent.key != source.receiveCalls[1].intent.key,
                "a recusa descarta a intenção",
            )

            val alone = form(FakePaymentSource().apply { receive = { throw shift() } })
            alone.amount = "50,00"
            alone.confirm()
            runCurrent()
            assertEquals(SHIFT_TEXT, alone.message)
            assertFalse(alone.shiftRequired, "sem openShift, só o texto")
            alone.openShiftAndClose()
        }

    @Test
    fun fieldsLockWhileBusyAndUnknown() =
        runTest {
            val source = FakePaymentSource()
            val late = LateAnswer<PaymentResponse>()
            source.receive = { late.await() }
            val form = form(source)
            form.amount = "50,00"
            assertFalse(form.locked)

            form.confirm()
            runCurrent()
            assertTrue(form.locked)
            form.method = PaymentMethod.PIX
            form.deposit = true
            assertEquals(PaymentMethod.CASH, form.method)
            assertFalse(form.deposit)

            late.fail(ApiError.Network(IOException("sem rede")))
            runCurrent()
            assertFalse(form.busy)
            assertTrue(form.unconfirmed)
            assertTrue(form.locked)
            form.amount = "70,00"
            assertEquals("50,00", form.amount)
        }

    @Test
    fun storedFailureIsCloseOnly() =
        runTest {
            val source = FakePaymentSource()
            source.receive = { throw ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList()) }
            val form = form(source)
            form.amount = "50,00"

            form.confirm()
            runCurrent()

            assertTrue(form.closeOnly)
            assertTrue(form.locked)
            assertEquals(SERVER_UNCONFIRMED, form.message)
            assertFalse(form.canConfirm)
            form.confirm()
            runCurrent()
            assertEquals(1, source.receiveCalls.size)
        }

    @Test
    fun storedFailureRereadsTheFolioOnClose() =
        runTest {
            val folios = FakeFolioSource()
            val payments = FakePaymentSource()
            payments.receive = { throw ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList()) }
            val state = panel(folios, payments)
            val form = assertNotNull(state.payment)
            form.amount = "50,00"
            form.confirm()
            runCurrent()
            assertTrue(form.closeOnly)

            state.finishPayment()
            runCurrent()

            assertNull(state.payment)
            assertEquals(2, folios.folioCalls.size, "fechar relê a conta para conferir")
            assertEquals(2, payments.paymentCalls.size)
        }

    @Test
    fun closedFolioShowsMessageAndReloads() =
        runTest {
            val folios = FakeFolioSource()
            val payments = FakePaymentSource()
            payments.receive = { throw ApiError.Problem(409, "FOLIO_CLOSED", "detalhe", emptyList()) }
            val state = panel(folios, payments)
            val form = assertNotNull(state.payment)
            form.amount = "50,00"
            folios.folio = { folioOf(status = FolioResponse.Status.CLOSED) }

            form.confirm()
            runCurrent()

            assertEquals("A conta já está fechada.", form.message)
            assertFalse(form.busy)
            assertFalse(form.unconfirmed)
            assertEquals(2, folios.folioCalls.size)
            assertEquals(true, state.view?.closed)
            assertFalse(state.canReceive)

            val alone = form(FakePaymentSource().apply { receive = { throw folioClosed() } })
            alone.amount = "10,00"
            alone.confirm()
            runCurrent()
            assertEquals(1, closedCalls)
        }

    @Test
    fun successReloadsAndShowsChange() =
        runTest {
            val folios = FakeFolioSource()
            val payments = FakePaymentSource()
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
            form.amount = "80,00"
            form.tendered = "100,00"
            payments.receive = { paymentOf(amount = "80.00", change = "20.00") }
            folios.folio = { folioOf(balance = "70.00") }
            payments.payments = { listOf(paymentOf(amount = "80.00")) }

            form.confirm()
            runCurrent()

            assertNull(state.payment, "o diálogo fecha depois do recebimento")
            assertEquals("R$ 70,00", state.view?.balance)
            assertEquals(listOf("R$ 80,00"), state.payments.map { it.amount })
            assertEquals("Pagamento registrado. Troco: R$ 20,00.", state.notice)
            assertEquals(1, written)

            payments.receive = { paymentOf(amount = "10.00", change = "0.00", method = "PIX") }
            state.startPayment()
            assertNull(state.notice, "um recebimento novo apaga o aviso anterior")
            assertNotNull(state.payment).amount = "10,00"
            assertNotNull(state.payment).confirm()
            runCurrent()
            assertEquals("Pagamento registrado.", state.notice)
        }

    private fun shift() = ApiError.Problem(422, "CASHIER_SHIFT_REQUIRED", null, emptyList())

    private fun folioClosed() = ApiError.Problem(409, "FOLIO_CLOSED", null, emptyList())
}
