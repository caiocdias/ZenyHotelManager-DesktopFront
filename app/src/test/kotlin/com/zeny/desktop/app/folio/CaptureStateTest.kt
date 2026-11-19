package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.sdk.model.PreAuthorizationCaptureResponse
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val RECEIVE = "billing.payments.receive"

/** O diálogo "Capturar" da caução ([CaptureState]), aberto pelo [PreAuthorizationsState]. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra do diálogo
class CaptureStateTest {
    private var changed = 0
    private var shortcuts = 0
    private val notices = mutableListOf<String>()

    private fun TestScope.holder(
        source: FakePaymentSource,
        openShift: (() -> Unit)? = { shortcuts++ },
    ) = PreAuthorizationsState(
        wiringOf(FakeFolioSource(), setOf(READ, RECEIVE), payments = source, openShift = openShift),
        backgroundScope,
        FOLIO_ID.toString(),
        folioOpen = { true },
        otherDialogOpen = { false },
        notify = { notices += it },
        onChanged = { changed++ },
    ).also {
        it.reload()
        runCurrent()
    }

    private fun source(
        captured: String = "0.00",
        capturable: String = "300.00",
        status: String = "ACTIVE",
    ) = FakePaymentSource().apply {
        preAuthorizations = {
            listOf(preAuthorizationOf(captured = captured, capturable = capturable, status = status))
        }
    }

    private fun PreAuthorizationsState.opened(): CaptureState {
        startCapture(rows.single())
        return assertNotNull(capture)
    }

    @Test
    fun amountSuggestedIsTheCapturable() =
        runTest {
            val source = source(captured = "100.00", capturable = "200.00", status = "PARTIALLY_CAPTURED")
            val holder = holder(source)
            val id = holder.rows.single().id
            val form = holder.opened()

            assertEquals("R$ 200,00", form.amount)
            assertEquals("Capturável: R$ 200,00", form.capturable)
            form.confirm()
            runCurrent()

            val call = source.captureCalls.single()
            assertEquals(FOLIO_ID.toString(), call.folioId)
            assertEquals(id, call.preAuthorizationId)
            assertEquals("200.00", call.input.amount.toApi())
            assertNull(call.input.nsu)
            assertNull(call.input.authorizationCode)
        }

    @Test
    fun codesGoTrimmedAndCardLikeCodesAreRefused() =
        runTest {
            val source = source()
            val form = holder(source).opened()
            form.nsu = "4111111111111111"
            form.authorizationCode = "4111111111111111"

            form.confirm()
            runCurrent()
            assertTrue(source.captureCalls.isEmpty(), "nada vai à rede")
            assertEquals(setOf(PaymentProblem.NSU, PaymentProblem.AUTHORIZATION_CODE), form.errors.keys)
            assertTrue(form.errors.values.none { "4111" in it }, "o texto não repete o digitado")
            assertFalse("4111" in form.toString())

            form.nsu = " 123456 "
            form.authorizationCode = " AUT9 "
            form.confirm()
            runCurrent()
            val input = source.captureCalls.single().input
            assertEquals("123456", input.nsu)
            assertEquals("AUT9", input.authorizationCode)
        }

    @Test
    fun doubleConfirmCapturesOnce() =
        runTest {
            val gate = CompletableDeferred<PreAuthorizationCaptureResponse>()
            val source = source().apply { capture = { gate.await() } }
            val holder = holder(source)
            val form = holder.opened()

            form.confirm()
            form.confirm()
            runCurrent()
            assertTrue(form.busy)
            form.confirm()
            gate.complete(
                PreAuthorizationCaptureResponse(
                    payment = paymentOf(method = "CREDIT_CARD"),
                    preAuthorization = preAuthorizationOf(status = "CAPTURED"),
                ),
            )
            runCurrent()

            assertEquals(1, source.captureCalls.size)
            assertNull(holder.capture)
        }

    @Test
    fun retryAfterNetworkKeepsTheKey() =
        runTest {
            var failures = 1
            val source = source()
            val fallback = source.capture
            source.capture = {
                if (failures-- > 0) throw ApiError.Network(IOException("caiu"))
                fallback(it)
            }
            val form = holder(source).opened()

            form.confirm()
            runCurrent()
            assertTrue(form.unconfirmed)
            assertEquals(
                "Não foi possível confirmar a captura. Capture de novo sem mudar nada; se fechar, confira a conta " +
                    "antes de capturar de novo.",
                form.message,
            )
            form.confirm()
            runCurrent()

            val (first, second) = source.captureCalls
            assertSame(first.intent, second.intent)
            assertEquals(first.input, second.input, "a repetição leva a mesma entrada")
        }

    @Test
    fun shiftRequiredOffersTheShortcut() =
        runTest {
            val failure = ApiError.Problem(422, "CASHIER_SHIFT_REQUIRED", null, emptyList())
            val source = source().apply { capture = { throw failure } }
            val holder = holder(source)
            val form = holder.opened()

            form.confirm()
            runCurrent()
            assertEquals("É preciso um turno de caixa aberto.", form.message)
            assertTrue(form.offersOpenShift)

            form.openShiftAndClose()
            assertNull(holder.capture, "\"Abrir turno\" fecha o diálogo antes do atalho")
            assertEquals(1, shortcuts)

            val blind = holder(source, openShift = null).opened()
            blind.confirm()
            runCurrent()
            assertEquals("É preciso um turno de caixa aberto.", blind.message)
            assertFalse(blind.offersOpenShift, "sem o atalho, só o texto")
        }

    @Test
    fun expiredBetweenOpenAndConfirmReloads() =
        runTest {
            val failure = ApiError.Problem(422, "PRE_AUTHORIZATION_EXPIRED", null, emptyList())
            val source = source().apply { capture = { throw failure } }
            val holder = holder(source)
            val form = holder.opened()

            form.confirm()
            runCurrent()

            assertEquals(failure.userMessage, form.message)
            assertFalse(form.unconfirmed)
            assertFalse(form.locked, "destrava")
            assertEquals(2, source.preAuthorizationCalls.size, "as cauções são relidas")
            assertSame(form, holder.capture, "o diálogo fica com a mensagem")
        }

    @Test
    fun exceedsAuthorizedShowsMessage() =
        runTest {
            val failure = ApiError.Problem(422, "CAPTURE_EXCEEDS_AUTHORIZED", null, emptyList())
            val source = source().apply { capture = { throw failure } }
            val holder = holder(source)
            val form = holder.opened()

            form.confirm()
            runCurrent()

            assertEquals(failure.userMessage, form.message)
            assertTrue(form.canConfirm)
            assertEquals(2, source.preAuthorizationCalls.size, "as cauções são relidas")
            form.amount = "10,00"
            assertEquals("10,00", form.amount, "a operadora corrige o valor")
        }

    @Test
    fun storedFailureIsCloseOnly() =
        runTest {
            val source =
                source().apply { capture = { throw ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList()) } }
            val holder = holder(source)
            val form = holder.opened()

            form.confirm()
            runCurrent()

            assertTrue(form.closeOnly)
            assertFalse(form.canConfirm)
            assertTrue(form.closesOnEscape)
            assertEquals(
                "O servidor não confirmou a captura. Feche e confira a conta antes de capturar de novo.",
                form.message,
            )
            form.confirm()
            runCurrent()
            assertEquals(1, source.captureCalls.size)

            holder.finishCapture()
            runCurrent()
            assertNull(holder.capture)
            assertEquals(2, source.preAuthorizationCalls.size, "fechar sem resultado relê as cauções")
            assertEquals(1, changed, "e a conta")
        }

    @Test
    fun successReloadsFolioPaymentsAndPreAuthorizations() =
        runTest {
            val folios = FakeFolioSource()
            val source = source()
            val folio =
                FolioState(
                    wiringOf(folios, setOf(READ, RECEIVE), payments = source),
                    backgroundScope,
                    FOLIO_ID.toString(),
                ).also {
                    it.start()
                    runCurrent()
                }
            val holder = assertNotNull(folio.preAuthorizations)
            val folioReads = folios.folioCalls.size
            val paymentReads = source.paymentCalls.size
            val preAuthorizationReads = source.preAuthorizationCalls.size

            holder.opened().confirm()
            runCurrent()

            assertNull(holder.capture)
            assertEquals(folioReads + 1, folios.folioCalls.size, "a conta é relida")
            assertEquals(paymentReads + 1, source.paymentCalls.size, "os pagamentos são relidos")
            assertEquals(preAuthorizationReads + 1, source.preAuthorizationCalls.size, "as cauções são relidas")
            assertEquals("Caução capturada.", folio.notice)
        }

    @Test
    fun fieldsLockWhileBusyAndUnknown() =
        runTest {
            val gate = CompletableDeferred<PreAuthorizationCaptureResponse>()
            val source = source().apply { capture = { gate.await() } }
            val form = holder(source).opened()
            form.amount = "50,00"
            form.nsu = "123"

            form.confirm()
            runCurrent()
            assertTrue(form.locked)
            form.amount = "1,00"
            form.nsu = "999"
            form.authorizationCode = "OUTRA"
            assertEquals("50,00", form.amount)
            assertEquals("123", form.nsu)
            assertEquals("", form.authorizationCode)

            gate.completeExceptionally(ApiError.Network(IOException("caiu")))
            runCurrent()
            assertTrue(form.unconfirmed)
            assertTrue(form.locked)
            form.amount = "2,00"
            form.authorizationCode = "OUTRA"
            assertEquals("50,00", form.amount)
            assertEquals("", form.authorizationCode)
        }

    @Test
    fun capturableFollowsTheReloadAfterExceeds() =
        runTest {
            val id = Uuid.random()
            val failure = ApiError.Problem(422, "CAPTURE_EXCEEDS_AUTHORIZED", null, emptyList())
            val source =
                FakePaymentSource().apply {
                    preAuthorizations = { listOf(preAuthorizationOf(id = id)) }
                    capture = { throw failure }
                }
            val holder = holder(source)
            val form = holder.opened()
            assertEquals("Capturável: R$ 300,00", form.capturable)

            source.preAuthorizations = {
                val partial = "PARTIALLY_CAPTURED"
                listOf(preAuthorizationOf(id = id, captured = "180.00", capturable = "120.00", status = partial))
            }
            form.confirm()
            runCurrent()

            assertEquals(failure.userMessage, form.message)
            assertEquals("Capturável: R$ 120,00", form.capturable, "o diálogo mostra o capturável relido")
        }

    @Test
    fun notFoundReloadsThePreAuthorizations() =
        runTest {
            val failure = ApiError.Problem(404, "PRE_AUTHORIZATION_NOT_FOUND", null, emptyList())
            val source = source().apply { capture = { throw failure } }
            val holder = holder(source)
            val form = holder.opened()

            form.confirm()
            runCurrent()

            assertEquals(failure.userMessage, form.message)
            assertEquals(2, source.preAuthorizationCalls.size, "as cauções são relidas")
        }
}
