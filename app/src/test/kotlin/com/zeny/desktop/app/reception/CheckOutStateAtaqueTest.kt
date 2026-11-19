package com.zeny.desktop.app.reception

import com.zeny.desktop.app.folio.FOLIO_ID
import com.zeny.desktop.app.folio.FakeFolioSource
import com.zeny.desktop.app.folio.FakePaymentSource
import com.zeny.desktop.app.folio.OTHER_FOLIO_ID
import com.zeny.desktop.app.folio.folioOf
import com.zeny.desktop.app.folio.preAuthorizationOf
import com.zeny.desktop.app.folio.wiringOf
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CheckOutResult
import com.zeny.desktop.core.facade.LateCheckOutQuote
import com.zeny.desktop.core.types.Money
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
import kotlin.test.assertTrue

private const val READ = "billing.folios.read"
private const val RECEIVE = "billing.payments.receive"
private const val RESERVATION = "20000000-0000-0000-0000-0000000000e1"
private const val CHECK_FAILED = "Não foi possível conferir as contas e as cauções."
private const val PAID_NOT_CHECKED_OUT = "O pagamento foi registrado, mas o check-out não foi concluído."
private val A = FOLIO_ID.toString()
private val B = OTHER_FOLIO_ID.toString()
private val LATE = LateCheckOutQuote("14:00", Money.parse("80.00"))

/** Ataque ao assistente de check-out ([CheckOutState]): as bordas que os testes da fatia não fecham. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // um teste por ataque
class CheckOutStateAtaqueTest {
    private val closings = mutableListOf<Pair<String?, Boolean>>()

    private fun settled() = FakeFolioSource().apply { byReservation = { listOf(folioOf(balance = "0.00")) } }

    private fun problem(
        status: Int,
        code: String,
    ) = ApiError.Problem(status, code, null, emptyList())

    private fun dueOn(
        total: String,
        vararg lines: Triple<String, String, String>,
    ) = CheckOutResult.BalanceDue(lines.map { (id, number, balance) -> dueOf(id, number, balance) }, Money.parse(total))

    @Suppress("LongParameterList") // as portas e o que cada teste varia, com padrão
    private fun TestScope.started(
        source: FakeCheckOutSource = FakeCheckOutSource(),
        folios: FakeFolioSource = settled(),
        payments: FakePaymentSource? = FakePaymentSource(),
        permissions: Set<String> = setOf(READ, RECEIVE),
        openShift: (() -> Unit)? = null,
    ): CheckOutState {
        val wiring = wiringOf(folios, permissions, payments = payments, openShift = openShift)
        return CheckOutState(source, wiring, backgroundScope, RESERVATION) { notice, reload ->
            closings += notice to reload
        }.also {
            it.reload()
            runCurrent()
        }
    }

    @Test
    fun feeChangedLeavesConcludeEnabledAndTheNextSendCarriesTheRereadFeeWithANewKey() =
        runTest {
            val source =
                FakeCheckOutSource().apply {
                    quote = { LATE }
                    checkOut = { throw problem(409, "EARLY_LATE_FEE_CHANGED") }
                }
            val state = started(source)
            state.chargeLate = true
            source.quote = { LateCheckOutQuote("14:00", Money.parse("90.00")) }
            state.conclude()
            runCurrent()

            assertTrue(state.canConclude, "a cotação relida libera um novo envio pela operadora")
            assertTrue(state.chargeLate, "a caixa continua ligada")
            assertEquals("Late check-out: R$ 90,00", state.lateLine)

            source.checkOut = { CheckOutResult.Done }
            state.conclude()
            runCurrent()

            assertEquals(2, source.calls.size)
            assertEquals(Money.parse("80.00"), source.calls[0].lateFee)
            assertEquals(Money.parse("90.00"), source.calls[1].lateFee, "o valor da cotação relida, não o antigo")
            assertNotEquals(source.calls[0].intent.key, source.calls[1].intent.key)
        }

    @Test
    fun shiftShortcutClosesThePaymentAndTheAssistantBeforeOpeningTheShift() =
        runTest {
            var seenAtOpen: Pair<Int, Boolean>? = null
            lateinit var state: CheckOutState
            val source = FakeCheckOutSource().apply { checkOut = { dueOn("100.00", Triple(A, "F-0001", "100.00")) } }
            val payments = FakePaymentSource().apply { receive = { throw problem(422, "CASHIER_SHIFT_REQUIRED") } }
            state =
                started(source, payments = payments, openShift = {
                    seenAtOpen = closings.size to (state.payment == null)
                })
            state.conclude()
            runCurrent()
            state.receiveDue(A)
            val form = assertNotNull(state.payment)
            form.confirm()
            runCurrent()

            form.openShiftAndClose()

            assertEquals(1 to true, seenAtOpen, "no atalho, o recebimento e o assistente já tinham fechado")
        }

    @Test
    fun folioReadFailureBlocksConcludeAndRetryUnblocks() =
        runTest {
            val folios = FakeFolioSource().apply { byReservation = { throw IOException("rede") } }
            val source = FakeCheckOutSource()
            val state = started(source, folios = folios)

            assertEquals(CHECK_FAILED, state.error)
            assertFalse(state.canConclude)
            state.conclude()
            runCurrent()
            assertTrue(source.calls.isEmpty(), "a conferência falhou: nada é enviado")

            folios.byReservation = { listOf(folioOf(balance = "0.00")) }
            state.reload()
            runCurrent()

            assertNull(state.error)
            assertTrue(state.canConclude)
        }

    @Test
    fun preAuthorizationReadFailureOnTheSecondFolioBlocksConclude() =
        runTest {
            val folios =
                FakeFolioSource().apply {
                    byReservation = {
                        listOf(folioOf(id = FOLIO_ID, balance = "0.00"), folioOf(id = OTHER_FOLIO_ID, balance = "0.00"))
                    }
                }
            val payments =
                FakePaymentSource().apply {
                    preAuthorizations = { if (it == B) throw IOException("rede") else emptyList() }
                }
            val state = started(folios = folios, payments = payments)

            assertEquals(CHECK_FAILED, state.error)
            assertFalse(state.canConclude)
        }

    @Test
    fun malformedFolioBalanceIsAReadFailure() =
        runTest {
            val folios = FakeFolioSource().apply { byReservation = { listOf(folioOf(balance = "150,00")) } }
            val state = started(folios = folios)

            assertEquals(CHECK_FAILED, state.error)
            assertTrue(state.pending.isEmpty(), "nenhum valor inventado")
            assertFalse(state.canConclude)
        }

    @Test
    fun partiallyCapturedWithNothingLeftIsNotPending() =
        runTest {
            val payments =
                FakePaymentSource().apply {
                    preAuthorizations = {
                        listOf(
                            preAuthorizationOf(status = "PARTIALLY_CAPTURED", captured = "300.00", capturable = "0.00"),
                            preAuthorizationOf(status = "RELEASED", capturable = "0.00"),
                        )
                    }
                }
            val state = started(payments = payments)

            assertTrue(state.pending.isEmpty())
            assertNull(state.blocked)
            assertTrue(state.canConclude)
        }

    @Test
    fun reservationNotFoundClosesAndReloadsThePanel() =
        runTest {
            val refusal = problem(404, "RESERVATION_NOT_FOUND")
            val source = FakeCheckOutSource().apply { checkOut = { throw refusal } }
            val state = started(source)

            state.conclude()
            runCurrent()

            assertEquals(listOf<Pair<String?, Boolean>>(refusal.userMessage to true), closings)
            state.conclude()
            runCurrent()
            assertEquals(1, source.calls.size, "o assistente fechou")
        }

    @Test
    fun alreadyCheckedOutWithADueOnScreenStillClosesAndReloads() =
        runTest {
            val refusal = problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
            val source = FakeCheckOutSource().apply { checkOut = { dueOn("100.00", Triple(A, "F-0001", "100.00")) } }
            val state = started(source)
            state.conclude()
            runCurrent()
            assertNotNull(state.due)

            source.checkOut = { throw refusal }
            state.conclude()
            runCurrent()

            assertEquals(listOf<Pair<String?, Boolean>>(refusal.userMessage to true), closings)
        }

    @Test
    fun quoteThatStopsBeingOfferedUnchecksTheBoxAndTheSendGoesWithoutLate() =
        runTest {
            val source = FakeCheckOutSource().apply { quote = { LATE } }
            val state = started(source)
            state.chargeLate = true
            source.quote = { null }

            state.reload()
            runCurrent()

            assertFalse(state.chargeLate)
            assertNull(state.lateOffer)
            assertNull(state.lateLine)
            state.chargeLate = true
            assertFalse(state.chargeLate, "sem oferta, a caixa não liga")
            state.conclude()
            runCurrent()
            assertNull(source.calls.single().lateFee)
        }

    @Test
    fun retryAfterNetworkWithTheQuoteRereadKeepsTheStoredFeeAndKey() =
        runTest {
            val source =
                FakeCheckOutSource().apply {
                    quote = { LATE }
                    checkOut = { throw ApiError.Network(IOException("x")) }
                }
            val state = started(source)
            state.chargeLate = true
            state.conclude()
            runCurrent()
            source.quote = { LateCheckOutQuote("15:00", Money.parse("95.00")) }
            state.reload()
            runCurrent()

            state.conclude()
            runCurrent()

            assertEquals(2, source.calls.size)
            assertEquals(source.calls[0].intent.key, source.calls[1].intent.key)
            assertEquals(Money.parse("80.00"), source.calls[1].lateFee, "o valor guardado quando a intenção nasceu")
        }

    @Test
    fun dueWithOnlyACreditFolioOffersNoReceive() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { dueOn("-30.00", Triple(B, "F-0002", "-30.00")) } }
            val state = started(source)
            state.conclude()
            runCurrent()

            assertEquals(
                listOf(CheckOutDueLine(B, "F-0002", "Conta credora: R$ 30,00 a devolver", null)),
                state.dueLines,
            )
            state.receiveDue(B)
            assertNull(state.payment, "conta credora não recebe")
            assertTrue(state.canConclude)
        }

    @Test
    fun debtorAndCreditFoliosKeepTheServerTotalWithoutNetting() =
        runTest {
            // O servidor não anula as contas: o total que ele manda é mostrado como veio, nunca recalculado.
            val source =
                FakeCheckOutSource().apply {
                    checkOut = { dueOn("130.00", Triple(A, "F-0001", "150.00"), Triple(B, "F-0002", "-20.00")) }
                }
            val state = started(source)
            state.conclude()
            runCurrent()

            assertEquals("Total devido: R$ 130,00", state.dueTotal)
            assertEquals(listOf("Receber R$ 150,00", null), state.dueLines.map { it.receive })
            state.receiveDue(A)
            assertEquals("R$ 150,00", assertNotNull(state.payment).amount, "o devido da conta, não o total")
        }

    @Test
    fun keyAfterDueIsNewAndItsRetryAfterNetworkKeepsIt() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { dueOn("100.00", Triple(A, "F-0001", "100.00")) } }
            val state = started(source)
            state.conclude()
            runCurrent()
            source.checkOut = { throw ApiError.Network(IOException("x")) }
            state.conclude()
            runCurrent()
            source.checkOut = { CheckOutResult.Done }
            state.conclude()
            runCurrent()

            assertEquals(3, source.calls.size)
            assertNotEquals(source.calls[0].intent.key, source.calls[1].intent.key, "o devido gira a intenção")
            assertEquals(source.calls[1].intent.key, source.calls[2].intent.key, "a repetição leva a mesma chave")
            assertEquals(listOf<Pair<String?, Boolean>>("Check-out concluído." to true), closings)
        }

    @Test
    fun withUnknownResultReceiveDueDoesNotOpen() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { dueOn("100.00", Triple(A, "F-0001", "100.00")) } }
            val state = started(source)
            state.conclude()
            runCurrent()
            source.checkOut = { throw ApiError.Network(IOException("x")) }
            state.conclude()
            runCurrent()
            assertTrue(state.unconfirmed)

            state.receiveDue(A)

            assertNull(state.payment, "com o resultado desconhecido, nada de recebimento")
        }

    @Test
    fun refusalAfterADueReceivedEndsTheReceivedMarkAndTheLateLock() =
        runTest {
            val source =
                FakeCheckOutSource().apply {
                    quote = { LATE }
                    checkOut = { dueOn("180.00", Triple(A, "F-0001", "180.00")) }
                }
            val state = started(source)
            state.chargeLate = true
            state.conclude()
            runCurrent()
            state.receiveDue(A)
            assertNotNull(state.payment).confirm()
            runCurrent()
            assertTrue(state.lateLocked)

            source.checkOut = { throw problem(409, "RESERVATION_CHANGED") }
            state.conclude()
            runCurrent()

            assertTrue(state.dueReceived.isEmpty())
            assertNull(state.due)
            assertFalse(state.lateLocked)
            state.chargeLate = false
            assertFalse(state.chargeLate, "a caixa destravou com a resposta do check-out")
        }

    @Test
    fun withoutReceiveTheDueShowsNoReceiveAndStillRetries() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { dueOn("100.00", Triple(A, "F-0001", "100.00")) } }
            val state = started(source, permissions = setOf(READ))
            state.conclude()
            runCurrent()

            assertEquals(listOf<String?>(null), state.dueLines.map { it.receive })
            state.receiveDue(A)
            assertNull(state.payment)
            assertTrue(state.canConclude, "o perfil ainda conclui (outra pessoa recebe)")
        }

    @Test
    fun reopenedAfterADueReceivedConcludesWithAFreshKey() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { dueOn("100.00", Triple(A, "F-0001", "100.00")) } }
            val folios = settled()
            val first = started(source, folios = folios)
            first.conclude()
            runCurrent()
            first.receiveDue(A)
            assertNotNull(first.payment).confirm()
            runCurrent()
            first.close()
            assertEquals(listOf<Pair<String?, Boolean>>(PAID_NOT_CHECKED_OUT to false), closings)

            // Reaberto: a conta relida está credora do que foi recebido (o 409 desfez o que o servidor lançou).
            folios.byReservation = { listOf(folioOf(id = FOLIO_ID, number = "F-0001", balance = "-20.00")) }
            source.checkOut = { CheckOutResult.Done }
            val second = started(source, folios = folios)
            assertTrue(second.canConclude, "a conta credora não bloqueia: o servidor confere")
            second.conclude()
            runCurrent()

            assertEquals(2, source.calls.size)
            assertNotEquals(source.calls[0].intent.key, source.calls[1].intent.key)
            assertEquals("Check-out concluído." to true, closings.last())
        }

    @Test
    fun toStringShowsNoAmountNorId() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { dueOn("1234.56", Triple(A, "F-0001", "1234.56")) } }
            val state = started(source)
            state.conclude()
            runCurrent()

            val text = state.toString()
            assertFalse("1234" in text || "1.234" in text, "sem valor")
            assertFalse(A in text || RESERVATION in text, "sem id")
            assertFalse("F-0001" in text, "sem número de conta")
        }
}
