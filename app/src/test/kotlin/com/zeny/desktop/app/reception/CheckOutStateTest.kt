package com.zeny.desktop.app.reception

import com.zeny.desktop.app.folio.FOLIO_ID
import com.zeny.desktop.app.folio.FakeFolioSource
import com.zeny.desktop.app.folio.FakePaymentSource
import com.zeny.desktop.app.folio.OTHER_FOLIO_ID
import com.zeny.desktop.app.folio.folioOf
import com.zeny.desktop.app.folio.paymentOf
import com.zeny.desktop.app.folio.preAuthorizationOf
import com.zeny.desktop.app.folio.wiringOf
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.CheckOutResult
import com.zeny.desktop.core.facade.LateCheckOutQuote
import com.zeny.desktop.core.types.Money
import kotlinx.coroutines.CompletableDeferred
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
private const val CAPTURE_FIRST = "Capture ou libere a caução antes de concluir."
private const val CHECK_FAILED = "Não foi possível conferir as contas e as cauções."
private const val PAID_NOT_CHECKED_OUT = "O pagamento foi registrado, mas o check-out não foi concluído."
private val A = FOLIO_ID.toString()
private val B = OTHER_FOLIO_ID.toString()
private val LATE = LateCheckOutQuote("14:00", Money.parse("80.00"))

/** O assistente de check-out ([CheckOutState]), com as portas falsas. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // um teste por regra do assistente
class CheckOutStateTest {
    private val closings = mutableListOf<Pair<String?, Boolean>>()

    private fun settled() = FakeFolioSource().apply { byReservation = { listOf(folioOf(balance = "0.00")) } }

    private fun problem(
        status: Int,
        code: String,
    ) = ApiError.Problem(status, code, null, emptyList())

    private fun due(vararg lines: Triple<String, String, String>) =
        CheckOutResult.BalanceDue(
            lines.map { (id, number, balance) -> dueOf(id, number, balance) },
            Money.parse("70.00"),
        )

    @Suppress("LongParameterList") // as portas e o que cada teste varia, com padrão
    private fun TestScope.started(
        source: FakeCheckOutSource = FakeCheckOutSource(),
        folios: FakeFolioSource = settled(),
        payments: FakePaymentSource? = FakePaymentSource(),
        permissions: Set<String> = setOf(READ, RECEIVE),
        openShift: (() -> Unit)? = null,
        wired: Boolean = true,
    ): CheckOutState {
        val wiring = if (wired) wiringOf(folios, permissions, payments = payments, openShift = openShift) else null
        return CheckOutState(source, wiring, backgroundScope, RESERVATION) { notice, reload ->
            closings += notice to reload
        }.also {
            it.reload()
            runCurrent()
        }
    }

    @Test
    fun pendingShowsBalancesAndCapturablePreAuthorizations() =
        runTest {
            val folios =
                FakeFolioSource().apply {
                    byReservation = {
                        listOf(
                            folioOf(id = FOLIO_ID, number = "F-0001", balance = "150.00"),
                            folioOf(id = OTHER_FOLIO_ID, number = "F-0002", balance = "-30.00"),
                        )
                    }
                }
            val payments =
                FakePaymentSource().apply {
                    preAuthorizations = { folioId ->
                        if (folioId == A) {
                            listOf(
                                preAuthorizationOf(status = "ACTIVE", capturable = "300.00"),
                                preAuthorizationOf(status = "CAPTURED", capturable = "0.00"),
                                preAuthorizationOf(status = "EXPIRED", capturable = "300.00"),
                            )
                        } else {
                            emptyList()
                        }
                    }
                }

            val state = started(folios = folios, payments = payments)

            assertTrue(state.loaded)
            assertNull(state.error)
            assertEquals(listOf(RESERVATION), folios.reservationCalls)
            assertEquals(listOf(A, B), payments.preAuthorizationCalls)
            assertEquals(
                listOf(
                    CheckOutPending.Balance("F-0001", "Falta receber R$ 150,00", A),
                    CheckOutPending.Balance("F-0002", "Conta credora: R$ 30,00 a devolver", B),
                    CheckOutPending.PreAuthorization("F-0001", "Caução a capturar ou liberar", A),
                ),
                state.pending,
            )
        }

    @Test
    fun capturablePreAuthorizationBlocksConclude() =
        runTest {
            val source = FakeCheckOutSource()
            val payments = FakePaymentSource().apply { preAuthorizations = { listOf(preAuthorizationOf()) } }

            val state = started(source, payments = payments)
            state.conclude()
            runCurrent()

            assertFalse(state.canConclude)
            assertEquals(CAPTURE_FIRST, state.blocked)
            assertTrue(source.calls.isEmpty(), "com caução capturável, nada vai ao servidor")
        }

    @Test
    fun releasingTheCautionUnblocks() =
        runTest {
            val payments = FakePaymentSource().apply { preAuthorizations = { listOf(preAuthorizationOf()) } }
            val state = started(payments = payments)
            assertFalse(state.canConclude)

            payments.preAuthorizations = { listOf(preAuthorizationOf(status = "RELEASED", capturable = "0.00")) }
            state.reload()
            runCurrent()

            assertTrue(state.canConclude)
            assertNull(state.blocked)
            assertTrue(state.pending.isEmpty())
        }

    @Test
    fun preAuthorizationReadFailureBlocksConclude() =
        runTest {
            val source = FakeCheckOutSource()
            val payments =
                FakePaymentSource().apply { preAuthorizations = { throw ApiError.Network(IOException("x")) } }

            val state = started(source, payments = payments)
            state.conclude()
            runCurrent()

            assertEquals(CHECK_FAILED, state.error)
            assertFalse(state.canConclude)
            assertTrue(source.calls.isEmpty())

            payments.preAuthorizations = { emptyList() }
            state.reload()
            runCurrent()
            assertNull(state.error)
            assertTrue(state.canConclude)

            val folios = FakeFolioSource().apply { byReservation = { throw ApiError.Network(IOException("x")) } }
            val unread = started(folios = folios)
            assertEquals(CHECK_FAILED, unread.error)
            assertFalse(unread.canConclude, "a falha da leitura das contas também bloqueia")
        }

    @Test
    fun malformedCapturableBlocksConclude() =
        runTest {
            val source = FakeCheckOutSource()
            val payments =
                FakePaymentSource().apply {
                    preAuthorizations = { listOf(preAuthorizationOf(status = "ACTIVE", capturable = "trezentos")) }
                }

            val state = started(source, payments = payments)
            state.conclude()
            runCurrent()

            assertEquals(CHECK_FAILED, state.error, "capturável ilegível numa caução ativa não some da conferência")
            assertFalse(state.canConclude)
            assertTrue(source.calls.isEmpty())

            payments.preAuthorizations = { listOf(preAuthorizationOf(status = "PARTIALLY_CAPTURED", capturable = "")) }
            state.reload()
            runCurrent()
            assertEquals(CHECK_FAILED, state.error)

            payments.preAuthorizations = { listOf(preAuthorizationOf(status = "RELEASED", capturable = "x")) }
            state.reload()
            runCurrent()
            assertNull(state.error, "fora das situações capturáveis, o capturável não importa")
            assertTrue(state.canConclude)
        }

    @Test
    fun expiredIsNotPending() =
        runTest {
            val payments =
                FakePaymentSource().apply {
                    preAuthorizations = { listOf(preAuthorizationOf(status = "EXPIRED", capturable = "300.00")) }
                }

            val state = started(payments = payments)

            assertTrue(state.pending.isEmpty(), "o servidor libera a vencida")
            assertTrue(state.canConclude)
        }

    @Test
    fun withoutReceiveTheCautionSaysWhoCan() =
        runTest {
            val payments = FakePaymentSource().apply { preAuthorizations = { listOf(preAuthorizationOf()) } }

            val state = started(payments = payments, permissions = setOf(READ))

            assertEquals(
                listOf(
                    CheckOutPending.PreAuthorization(
                        "F-0001",
                        "Peça a quem pode receber pagamentos para capturar ou liberar a caução.",
                        A,
                    ),
                ),
                state.pending,
            )
            assertFalse(state.canConclude)
        }

    @Test
    fun doubleConfirmChecksOutOnce() =
        runTest {
            val gate = CompletableDeferred<CheckOutResult>()
            val source = FakeCheckOutSource().apply { checkOut = { gate.await() } }
            val state = started(source)

            state.conclude()
            state.conclude()
            runCurrent()
            state.conclude()
            runCurrent()

            assertEquals(1, source.calls.size)
            assertTrue(state.busy)
            assertFalse(state.canConclude)
        }

    @Test
    fun retryAfterNetworkKeepsTheKey() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { throw ApiError.Network(IOException("x")) } }
            val state = started(source)

            state.conclude()
            runCurrent()

            assertTrue(state.unconfirmed)
            assertEquals(
                "Não foi possível confirmar o check-out. Conclua de novo sem mudar nada; " +
                    "se fechar, confira a reserva antes de tentar de novo.",
                state.message,
            )
            source.checkOut = { CheckOutResult.Done }
            state.conclude()
            runCurrent()

            assertEquals(2, source.calls.size)
            assertEquals(source.calls[0].intent.key, source.calls[1].intent.key)
        }

    @Test
    fun storedFailureIsCloseOnly() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { throw problem(500, "INTERNAL_ERROR") } }
            val state = started(source)

            state.conclude()
            runCurrent()
            state.conclude()
            runCurrent()

            assertTrue(state.closeOnly)
            assertFalse(state.canConclude)
            assertEquals(1, source.calls.size)
            assertEquals(
                "O servidor não confirmou o check-out. Feche e confira a reserva antes de tentar de novo.",
                state.message,
            )
        }

    @Test
    fun balanceDueShowsEachFolioAndRetriesWithANewKey() =
        runTest {
            val balanceDue = due(Triple(A, "F-0001", "100.00"), Triple(B, "F-0002", "-30.00"))
            val source = FakeCheckOutSource().apply { checkOut = { balanceDue } }
            val state = started(source)

            state.conclude()
            runCurrent()

            assertEquals(balanceDue, state.due)
            assertEquals(
                listOf(
                    CheckOutDueLine(A, "F-0001", "Falta receber R$ 100,00", "Receber R$ 100,00"),
                    CheckOutDueLine(B, "F-0002", "Conta credora: R$ 30,00 a devolver", null),
                ),
                state.dueLines,
            )
            assertEquals("Total devido: R$ 70,00", state.dueTotal)
            assertNull(state.message, "o saldo devido não é uma falha")
            assertTrue(closings.isEmpty())

            state.conclude()
            runCurrent()
            assertEquals(2, source.calls.size)
            assertNotEquals(source.calls[0].intent.key, source.calls[1].intent.key)
        }

    @Test
    fun zeroBalanceFoliosInTheDueAreNotOffered() =
        runTest {
            val balanceDue = due(Triple(A, "F-0001", "100.00"), Triple(B, "F-0002", "0.00"))
            val source = FakeCheckOutSource().apply { checkOut = { balanceDue } }
            val state = started(source)

            state.conclude()
            runCurrent()

            assertEquals(listOf("F-0001"), state.dueLines.map { it.number })
            state.receiveDue(B)
            assertNull(state.payment, "nunca \"Receber R$ 0,00\"")
        }

    @Test
    fun receiveDueSuggestsTheServerAmount() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { due(Triple(A, "F-0001", "1234.56")) } }
            val state = started(source)
            state.conclude()
            runCurrent()

            state.receiveDue(A)

            assertEquals("R$ 1.234,56", assertNotNull(state.payment).amount)
            assertFalse(state.canConclude, "um diálogo por vez")
        }

    @Test
    fun receiveDueNeedsPermission() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { due(Triple(A, "F-0001", "100.00")) } }
            val state = started(source, permissions = setOf(READ))
            state.conclude()
            runCurrent()

            state.receiveDue(A)

            assertNull(state.payment)
            assertNull(state.dueLines.single().receive)

            val unwired = started(source, payments = null)
            unwired.conclude()
            runCurrent()
            unwired.receiveDue(A)
            assertNull(unwired.payment, "sem a porta dos pagamentos, nada")
        }

    @Test
    fun receiveDueShowsTheChange() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { due(Triple(A, "F-0001", "95.00")) } }
            val payments = FakePaymentSource().apply { receive = { paymentOf(amount = "95.00", change = "5.00") } }
            val folios = settled()
            val state = started(source, folios = folios, payments = payments)
            state.conclude()
            runCurrent()

            state.receiveDue(A)
            assertNotNull(state.payment).confirm()
            runCurrent()

            assertNull(state.payment)
            assertEquals(A, payments.receiveCalls.single().folioId)
            assertEquals("Pagamento registrado. Troco: R$ 5,00.", state.notice)
            assertEquals(setOf(A), state.dueReceived)
            assertEquals(2, folios.reservationCalls.size, "relê o assistente depois de receber")
            assertEquals("Recebido. Conclua o check-out.", state.dueLines.single().text)
            assertNull(state.dueLines.single().receive, "não oferece receber de novo")
        }

    @Test
    fun receiveDueShiftRequiredClosesAndOpensTheShift() =
        runTest {
            var opened = 0
            val source = FakeCheckOutSource().apply { checkOut = { due(Triple(A, "F-0001", "100.00")) } }
            val payments =
                FakePaymentSource().apply { receive = { throw problem(422, "CASHIER_SHIFT_REQUIRED") } }
            val state = started(source, payments = payments, openShift = { opened++ })
            state.conclude()
            runCurrent()
            state.receiveDue(A)
            val form = assertNotNull(state.payment)
            form.confirm()
            runCurrent()

            assertTrue(form.offersOpenShift)
            form.openShiftAndClose()

            assertNull(state.payment)
            assertEquals(1, opened)
            assertEquals(listOf<Pair<String?, Boolean>>(null to false), closings)
            state.conclude()
            runCurrent()
            assertEquals(1, source.calls.size, "o assistente fechou")
        }

    @Test
    fun receiveDueFolioClosedReloads() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { due(Triple(A, "F-0001", "100.00")) } }
            val payments = FakePaymentSource().apply { receive = { throw problem(409, "FOLIO_CLOSED") } }
            val folios = settled()
            val state = started(source, folios = folios, payments = payments)
            state.conclude()
            runCurrent()
            state.receiveDue(A)

            assertNotNull(state.payment).confirm()
            runCurrent()

            assertEquals(2, folios.reservationCalls.size)
            assertEquals("A conta já está fechada.", assertNotNull(state.payment).message)
        }

    @Test
    fun afterReceivingDueDoesNotSayRefund() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { due(Triple(A, "F-0001", "100.00")) } }
            val folios = settled()
            val state = started(source, folios = folios)
            state.conclude()
            runCurrent()
            state.receiveDue(A)
            // O 409 desfez o late e a noite da chegada: a conta relida fica credora do que foi recebido a mais.
            folios.byReservation = { listOf(folioOf(id = FOLIO_ID, number = "F-0001", balance = "-20.00")) }

            assertNotNull(state.payment).confirm()
            runCurrent()

            assertEquals(
                listOf(CheckOutPending.Balance("F-0001", "Recebido. Conclua o check-out.", A)),
                state.pending,
            )

            source.checkOut = { CheckOutResult.Done }
            state.conclude()
            runCurrent()
            assertTrue(state.dueReceived.isEmpty(), "até a próxima resposta do check-out")
        }

    @Test
    fun closingWithDuePaidWarns() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { due(Triple(A, "F-0001", "100.00")) } }
            val state = started(source)
            state.conclude()
            runCurrent()
            state.receiveDue(A)
            assertNotNull(state.payment).confirm()
            runCurrent()

            state.close()

            assertEquals(listOf<Pair<String?, Boolean>>(PAID_NOT_CHECKED_OUT to false), closings)
        }

    @Test
    fun closingWhileUnknownReloadsThePanel() =
        runTest {
            val source = FakeCheckOutSource().apply { checkOut = { throw ApiError.Network(IOException("x")) } }
            val state = started(source)
            state.conclude()
            runCurrent()
            assertTrue(state.unconfirmed)

            state.close()

            val expected = listOf<Pair<String?, Boolean>>(null to true)
            assertEquals(expected, closings, "o servidor pode ter feito o check-out")

            val stored = FakeCheckOutSource().apply { checkOut = { throw problem(500, "INTERNAL_ERROR") } }
            val closeOnly = started(stored)
            closeOnly.conclude()
            runCurrent()
            assertTrue(closeOnly.closeOnly)
            closeOnly.close()
            assertEquals(null to true, closings.last())

            started().close()
            assertEquals(null to false, closings.last(), "sem envio, fechar não relê o painel")
        }

    @Test
    fun lateIsSentOnlyWhenChecked() =
        runTest {
            val source = FakeCheckOutSource().apply { quote = { LATE } }
            val state = started(source)
            assertEquals(LATE, state.quote)
            assertEquals("Late check-out até 14:00: R$ 80,00", state.lateOffer)
            assertFalse(state.chargeLate, "desligada por padrão")

            state.conclude()
            runCurrent()
            assertNull(source.calls.single().lateFee)

            val charged = started(source)
            charged.chargeLate = true
            charged.conclude()
            runCurrent()
            assertEquals(Money.parse("80.00"), source.calls.last().lateFee)
        }

    @Test
    fun retryAfterNetworkResendsTheStoredFee() =
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
            source.checkOut = { CheckOutResult.Done }
            state.conclude()
            runCurrent()

            assertEquals(2, source.calls.size)
            assertEquals(Money.parse("80.00"), source.calls[1].lateFee, "o valor guardado quando a intenção nasceu")
            assertEquals(source.calls[0].intent.key, source.calls[1].intent.key)
        }

    @Test
    fun lateBoxChangeRotatesTheIntent() =
        runTest {
            val source =
                FakeCheckOutSource().apply {
                    quote = { LATE }
                    checkOut = { throw ApiError.Network(IOException("x")) }
                }
            val state = started(source)
            state.conclude()
            runCurrent()

            state.chargeLate = true
            assertFalse(state.chargeLate, "com o resultado desconhecido, a caixa trava")

            source.checkOut = { throw problem(409, "RESERVATION_CHANGED") }
            state.conclude()
            runCurrent()
            source.checkOut = { CheckOutResult.Done }
            state.chargeLate = true
            state.conclude()
            runCurrent()

            assertEquals(3, source.calls.size)
            assertEquals(source.calls[0].intent.key, source.calls[1].intent.key)
            assertNotEquals(source.calls[1].intent.key, source.calls[2].intent.key)
            assertEquals(Money.parse("80.00"), source.calls[2].lateFee)
        }

    @Test
    fun lateBoxLocksAfterReceivingADueWithLate() =
        runTest {
            val source =
                FakeCheckOutSource().apply {
                    quote = { LATE }
                    checkOut = { due(Triple(A, "F-0001", "180.00")) }
                }
            val state = started(source)
            state.chargeLate = true
            state.conclude()
            runCurrent()
            state.receiveDue(A)
            assertNotNull(state.payment).confirm()
            runCurrent()

            state.chargeLate = false
            assertTrue(state.chargeLate, "a caixa trava ligada até a próxima resposta")
            source.quote = { null }
            state.reload()
            runCurrent()
            assertTrue(state.chargeLate)

            source.checkOut = { CheckOutResult.Done }
            state.conclude()
            runCurrent()
            assertEquals(Money.parse("80.00"), source.calls.last().lateFee)
        }

    @Test
    fun lateLineShowsAboveTheTotal() =
        runTest {
            val state = started(FakeCheckOutSource().apply { quote = { LATE } })
            assertNull(state.lateLine)

            state.chargeLate = true

            assertEquals("Late check-out: R$ 80,00", state.lateLine)
        }

    @Test
    fun feeChangedRereadsTheQuoteAndWaits() =
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

            assertEquals("A tarifa de entrada antecipada ou saída tardia mudou. Confira.", state.message)
            assertEquals(Money.parse("90.00"), state.quote?.fee)
            assertEquals(2, source.quoteCalls)
            assertEquals(1, source.calls.size, "nenhum envio automático")
            assertTrue(closings.isEmpty())
        }

    @Test
    fun lateNotOfferedUnchecksAndRereads() =
        runTest {
            val source =
                FakeCheckOutSource().apply {
                    quote = { LATE }
                    checkOut = { throw problem(422, "EARLY_LATE_NOT_OFFERED") }
                }
            val state = started(source)
            state.chargeLate = true
            source.quote = { null }

            state.conclude()
            runCurrent()

            assertFalse(state.chargeLate)
            assertNull(state.quote)
            assertNull(state.lateOffer)
            assertEquals(2, source.quoteCalls)
            assertEquals(1, source.calls.size)
        }

    @Test
    fun quoteFailureDoesNotBlock() =
        runTest {
            val source = FakeCheckOutSource().apply { quote = { throw ApiError.Network(IOException("x")) } }
            val state = started(source)

            assertNull(state.quote)
            assertNull(state.lateOffer)
            assertNull(state.error)
            assertTrue(state.canConclude)
            state.chargeLate = true
            assertFalse(state.chargeLate, "sem late oferecido, a caixa não liga")
        }

    @Test
    fun concurrentChangeShowsTextAndReloads() =
        runTest {
            val failure = problem(409, "RESERVATION_CHANGED")
            val source = FakeCheckOutSource().apply { checkOut = { throw failure } }
            val folios = settled()
            val payments = FakePaymentSource()
            val state = started(source, folios = folios, payments = payments)

            state.conclude()
            runCurrent()

            assertEquals("A reserva mudou enquanto você concluía. Confira e tente de novo.", state.message)
            assertEquals(2, folios.reservationCalls.size)
            assertEquals(2, payments.preAuthorizationCalls.size)
            assertEquals(2, source.quoteCalls)
            assertTrue(closings.isEmpty(), "o assistente fica aberto")
            assertTrue(state.canConclude)
        }

    @Test
    fun alreadyCheckedOutClosesAndReloadsThePanel() =
        runTest {
            val source =
                FakeCheckOutSource().apply { checkOut = { throw problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED") } }
            val state = started(source)

            state.conclude()
            runCurrent()

            assertEquals(
                listOf<Pair<String?, Boolean>>("Esta ação não é permitida na situação atual da reserva." to true),
                closings,
            )
            assertFalse(state.canConclude)

            val gone = FakeCheckOutSource().apply { checkOut = { throw problem(404, "RESERVATION_NOT_FOUND") } }
            started(gone).conclude()
            runCurrent()
            assertEquals("A reserva não foi encontrada." to true, closings.last())
        }

    @Test
    fun successCallsOnDoneAndCloses() =
        runTest {
            val source = FakeCheckOutSource()
            val state = started(source)

            state.conclude()
            runCurrent()

            assertEquals(RESERVATION, source.calls.single().reservationId)
            assertEquals(listOf<Pair<String?, Boolean>>("Check-out concluído." to true), closings)
            assertFalse(state.canConclude)
            state.close()
            assertEquals(1, closings.size, "fecha uma vez só")
        }

    @Test
    fun withoutFolioReadSaysSoAndStillConcludes() =
        runTest {
            val source = FakeCheckOutSource()
            val folios = settled()
            val state = started(source, folios = folios, wired = false)

            assertEquals("Seu perfil não pode ler as contas desta reserva.", state.unreadable)
            assertTrue(state.pending.isEmpty())
            assertNull(state.error)
            assertTrue(state.canConclude)
            state.conclude()
            runCurrent()
            assertEquals(1, source.calls.size)

            val noPayments = started(FakeCheckOutSource(), folios = folios, payments = null)
            assertEquals("Seu perfil não pode ler as contas desta reserva.", noPayments.unreadable)
            assertTrue(noPayments.canConclude)
            assertTrue(folios.reservationCalls.isEmpty(), "sem a porta dos pagamentos, não lê pendências")
        }

    @Test
    fun closeDropsLateAnswers() =
        runTest {
            val lateQuote = CompletableDeferred<LateCheckOutQuote?>()
            val result = CompletableDeferred<CheckOutResult>()
            val source = FakeCheckOutSource().apply { checkOut = { result.await() } }
            val state = started(source)
            state.conclude()
            runCurrent()
            source.quote = { lateQuote.await() }
            state.reload()
            runCurrent()

            state.close()
            lateQuote.complete(LATE)
            result.complete(CheckOutResult.Done)
            runCurrent()

            assertEquals(listOf<Pair<String?, Boolean>>(null to false), closings)
            assertNull(state.quote)
            state.reload()
            state.conclude()
            runCurrent()
            assertEquals(1, source.calls.size)
        }
}
