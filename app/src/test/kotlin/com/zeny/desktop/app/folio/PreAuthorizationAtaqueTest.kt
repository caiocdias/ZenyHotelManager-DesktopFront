package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.PreAuthorizationCaptureResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val READ = "billing.folios.read"
private const val CREATE = "billing.postings.create"
private const val RECEIVE = "billing.payments.receive"
private const val REFUND = "billing.payments.refund"
private const val VALIDITY_RANGE = "A validade vai de hoje até 89 dias."

/**
 * Ataque à M4 E09 (cauções) no `app`: as lacunas que as revisões de fatia deixaram ao testador e os `Riscos:` da etapa
 * (permissão, um diálogo por vez, troca de sessão com o diálogo aberto, capturável nas bordas, a ordem do atalho do
 * turno, o reenvio depois da recusa definitiva e a validade num fuso com horário de verão).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por ataque
class PreAuthorizationAtaqueTest {
    private fun TestScope.holder(
        source: FakePaymentSource,
        permissions: Set<String> = setOf(READ, RECEIVE),
        folioOpen: () -> Boolean = { true },
        otherDialogOpen: () -> Boolean = { false },
        openShift: (() -> Unit)? = null,
    ) = PreAuthorizationsState(
        wiringOf(FakeFolioSource(), permissions, payments = source, openShift = openShift),
        backgroundScope,
        FOLIO_ID.toString(),
        folioOpen = folioOpen,
        otherDialogOpen = otherDialogOpen,
        notify = {},
        onChanged = {},
    ).also {
        it.reload()
        runCurrent()
    }

    private fun TestScope.folio(
        source: FakePaymentSource,
        folios: FakeFolioSource = FakeFolioSource(),
        permissions: Set<String> = setOf(READ, CREATE, RECEIVE),
    ) = FolioState(
        wiringOf(folios, permissions, payments = source),
        backgroundScope,
        FOLIO_ID.toString(),
    ).also {
        it.start()
        runCurrent()
    }

    private fun sourceWith(vararg statuses: Pair<String, String>) =
        FakePaymentSource().apply {
            preAuthorizations = {
                statuses.map { (status, capturable) -> preAuthorizationOf(status = status, capturable = capturable) }
            }
        }

    @Test
    fun shortcutClosesTheCaptureBeforeOpeningTheShift() =
        runTest {
            val source =
                sourceWith("ACTIVE" to "300.00").apply {
                    capture = { throw ApiError.Problem(422, "CASHIER_SHIFT_REQUIRED", null, emptyList()) }
                }
            val seenAtShortcut = mutableListOf<CaptureState?>()
            lateinit var holder: PreAuthorizationsState
            holder = holder(source, openShift = { seenAtShortcut += holder.capture })
            holder.startCapture(holder.rows.single())
            val form = assertNotNull(holder.capture)
            form.confirm()
            runCurrent()
            assertTrue(form.offersOpenShift)

            form.openShiftAndClose()

            assertEquals(listOf<CaptureState?>(null), seenAtShortcut, "o diálogo fechou antes de o atalho rodar")
            assertTrue(form.locked, "o diálogo fechado não envia mais")
            form.confirm()
            runCurrent()
            assertEquals(1, source.captureCalls.size)
        }

    @Test
    fun resendAfterDefinitiveRefusalTakesTheCorrectedValueAndANewIntent() =
        runTest {
            var refusals = 1
            val source = sourceWith("ACTIVE" to "300.00")
            val accept = source.capture
            source.capture = {
                if (refusals-- > 0) throw ApiError.Problem(422, "CAPTURE_EXCEEDS_AUTHORIZED", null, emptyList())
                accept(it)
            }
            val holder = holder(source)
            holder.startCapture(holder.rows.single())
            val form = assertNotNull(holder.capture)

            form.confirm()
            runCurrent()
            assertFalse(form.unconfirmed)
            form.amount = "120,00"
            form.confirm()
            runCurrent()

            val (refused, resent) = source.captureCalls
            assertEquals("300.00", refused.input.amount.toApi())
            assertEquals("120.00", resent.input.amount.toApi(), "o reenvio leva o valor corrigido")
            assertNotSame(refused.intent, resent.intent)
            assertNotEquals(refused.intent.key, resent.intent.key, "a recusa definitiva acabou com a intenção")
            assertNull(holder.capture, "a captura corrigida deu certo")
        }

    @Test
    fun resendAfterDefinitiveRefusalWithoutEditingIsANewIntent() =
        runTest {
            var refusals = 1
            val source = sourceWith("ACTIVE" to "300.00")
            val accept = source.capture
            source.capture = {
                if (refusals-- > 0) throw ApiError.Problem(422, "PRE_AUTHORIZATION_EXPIRED", null, emptyList())
                accept(it)
            }
            val holder = holder(source)
            holder.startCapture(holder.rows.single())
            val form = assertNotNull(holder.capture)

            form.confirm()
            runCurrent()
            form.confirm()
            runCurrent()

            val (refused, resent) = source.captureCalls
            assertNotEquals(refused.intent.key, resent.intent.key)
        }

    @Test
    fun activeWithZeroCapturableOffersNoCapture() =
        runTest {
            val source = sourceWith("ACTIVE" to "0.00", "PARTIALLY_CAPTURED" to "0.00", "ACTIVE" to "-1.00")
            val holder = holder(source)

            holder.rows.forEach { row ->
                assertNull(row.capturable, "capturável zero ou negativo não aparece")
                assertFalse(holder.canCapture(row), "sem capturável, sem \"Capturar\"")
                holder.startCapture(row)
                assertNull(holder.capture)
                assertTrue(holder.canRelease(row), "liberar continua")
            }
            holder.startRelease(holder.rows.first())
            assertEquals("A sobra será liberada na maquininha.", assertNotNull(holder.release).text)
            assertTrue(source.captureCalls.isEmpty())
        }

    @Test
    fun otherDialogOpenBlocksEveryPreAuthorizationDialog() =
        runTest {
            var other = true
            val holder = holder(sourceWith("ACTIVE" to "300.00"), otherDialogOpen = { other })
            val row = holder.rows.single()
            assertTrue(holder.canCapture(row))

            holder.startCapture(row)
            holder.startRegister()
            holder.startRelease(row)
            assertNull(holder.capture)
            assertNull(holder.register)
            assertNull(holder.release)
            assertFalse(holder.dialogOpen)

            other = false
            holder.startCapture(row)
            assertNotNull(holder.capture)
        }

    @Test
    fun refundPermissionAloneWritesNoPreAuthorization() =
        runTest {
            listOf(setOf(READ), setOf(READ, REFUND), setOf(READ, CREATE, REFUND)).forEach { permissions ->
                val source = sourceWith("ACTIVE" to "300.00", "EXPIRED" to "300.00")
                val holder = holder(source, permissions = permissions)
                assertEquals(2, holder.rows.size, "ler as cauções só pede billing.folios.read")
                assertFalse(holder.canRegister, "$permissions")
                holder.rows.forEach { row ->
                    assertFalse(holder.canCapture(row), "$permissions")
                    assertFalse(holder.canRelease(row), "$permissions")
                    holder.startCapture(row)
                    holder.startRelease(row)
                }
                holder.startRegister()
                assertFalse(holder.dialogOpen, "$permissions")
                assertTrue(source.registerCalls.isEmpty() && source.captureCalls.isEmpty())
                assertTrue(source.releaseCalls.isEmpty())
            }
        }

    @Test
    fun closedFolioOffersOnlyRelease() =
        runTest {
            val source = sourceWith("ACTIVE" to "300.00", "PARTIALLY_CAPTURED" to "200.00", "EXPIRED" to "300.00")
            val holder = holder(source, folioOpen = { false })

            assertFalse(holder.canRegister)
            holder.startRegister()
            assertNull(holder.register)
            holder.rows.forEach { row ->
                assertFalse(holder.canCapture(row), row.statusCode)
                holder.startCapture(row)
                assertNull(holder.capture)
                assertTrue(holder.canRelease(row), row.statusCode)
            }
        }

    @Test
    fun unknownAndSettledStatusesOfferNoWrite() =
        runTest {
            val source =
                sourceWith("SUSPENDED" to "300.00", "CAPTURED" to "0.00", "RELEASED" to "0.00", "CAPTURED" to "10.00")
            val holder = holder(source)

            assertEquals("Outro (SUSPENDED)", holder.rows.first().status)
            holder.rows.forEach { row ->
                assertFalse(holder.canCapture(row), row.statusCode)
                assertFalse(holder.canRelease(row), row.statusCode)
            }
        }

    @Test
    fun oneDialogAtATimeWithPostingReversalAndRefund() =
        runTest {
            val posting = postingOf()
            val folios = FakeFolioSource().apply { postings = { listOf(posting) } }
            val source =
                sourceWith("ACTIVE" to "300.00").apply {
                    payments = { listOf(paymentOf(refundable = "100.00")) }
                }
            val folio = folio(source, folios)
            val holder = assertNotNull(folio.preAuthorizations)
            val row = holder.rows.single()

            fun holderOpensNothing(what: String) {
                holder.startRegister()
                holder.startCapture(row)
                holder.startRelease(row)
                assertFalse(holder.dialogOpen, "com $what aberto, a caução não abre diálogo")
            }

            folio.startPosting()
            assertNotNull(folio.posting)
            holderOpensNothing("o lançamento")
            folio.dismissPosting()

            folio.startReversal(posting.id.toString())
            assertNotNull(folio.reversal)
            holderOpensNothing("o estorno")
            folio.dismissReversal()

            folio.startRefund(folio.payments.single())
            assertNotNull(folio.refund)
            holderOpensNothing("o reembolso")
            folio.finishRefund()

            holder.startCapture(row)
            assertNotNull(holder.capture)
            folio.startPosting()
            folio.startReversal(posting.id.toString())
            folio.startRefund(folio.payments.single())
            folio.startPayment()
            assertNull(folio.posting, "com a captura aberta, o painel não abre o lançamento")
            assertNull(folio.reversal, "nem o estorno")
            assertNull(folio.refund, "nem o reembolso")
            assertNull(folio.payment, "nem o recebimento")
        }

    @Test
    fun sessionCloseWithCaptureInFlightDropsTheAnswer() =
        runTest {
            val gate = CompletableDeferred<PreAuthorizationCaptureResponse>()
            val folios = FakeFolioSource()
            val source = sourceWith("ACTIVE" to "300.00").apply { capture = { gate.await() } }
            val folio = folio(source, folios)
            val holder = assertNotNull(folio.preAuthorizations)
            holder.startCapture(holder.rows.single())
            val form = assertNotNull(holder.capture)
            form.confirm()
            runCurrent()
            assertTrue(form.busy)
            val folioReads = folios.folioCalls.size
            val preAuthorizationReads = source.preAuthorizationCalls.size

            folio.close()
            gate.complete(
                PreAuthorizationCaptureResponse(
                    payment = paymentOf(method = "CREDIT_CARD"),
                    preAuthorization = preAuthorizationOf(status = "CAPTURED"),
                ),
            )
            runCurrent()

            assertNull(folio.notice, "a resposta tardia não avisa o painel fechado")
            assertEquals(folioReads, folios.folioCalls.size, "nem relê a conta")
            assertEquals(preAuthorizationReads, source.preAuthorizationCalls.size, "nem as cauções")
            assertFalse(holder.canRegister, "o holder fechado não escreve")
            form.confirm()
            runCurrent()
            assertEquals(1, source.captureCalls.size)
        }

    @Test
    fun sessionCloseWithRegisterUnknownSendsNothingMore() =
        runTest {
            val source =
                FakePaymentSource().apply {
                    register = { throw ApiError.Network(IOException("caiu")) }
                }
            val folio = folio(source)
            val holder = assertNotNull(folio.preAuthorizations)
            holder.startRegister()
            val form = assertNotNull(holder.register)
            form.amount = "100,00"
            // O holder pelo FolioState usa o relógio real: a validade é amanhã no fuso do hotel do falso.
            form.expiresOn = DateInput.format(BusinessDate(LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(1)))
            form.authorizationCode = "AUT1"
            form.confirm()
            runCurrent()
            val sent = source.registerCalls.size
            assertEquals(1, sent, "o primeiro registro foi à rede")
            assertTrue(form.unconfirmed, "e ficou sem confirmação")

            folio.close()
            form.confirm()
            runCurrent()
            holder.finishRegister()
            runCurrent()

            assertEquals(sent, source.registerCalls.size, "fechada a sessão, a repetição não vai à rede")
        }

    @Test
    fun validityAcrossDaylightSavingUsesTheOffsetOfThatDay() =
        runTest {
            // 10/08/2026, 22:00 em Nova York (EDT, UTC-4), já 11/08 em UTC; o horário de verão acaba em 01/11/2026.
            val clock = Clock.fixed(Instant.parse("2026-08-11T02:00:00Z"), ZoneOffset.UTC)
            val zone = ZoneId.of("America/New_York")
            val source = FakePaymentSource()

            fun form(date: String) =
                PreAuthorizationFormState(source, backgroundScope, FOLIO_ID.toString(), zone, clock, {}, {}).apply {
                    amount = "100,00"
                    expiresOn = date
                    authorizationCode = "AUT1"
                    confirm()
                }

            form("10/08/2026")
            runCurrent()
            assertEquals(
                Instant.parse("2026-08-11T03:59:59Z"),
                source.registerCalls
                    .last()
                    .input.expiresAt,
                "hoje, ainda em EDT",
            )
            form("07/11/2026")
            runCurrent()
            assertEquals(
                Instant.parse("2026-11-08T04:59:59Z"),
                source.registerCalls
                    .last()
                    .input.expiresAt,
                "89 dias, já em EST (UTC-5): o fim do dia no fuso daquele dia",
            )
            listOf("09/08/2026", "08/11/2026").forEach { date ->
                val out = form(date)
                runCurrent()
                assertEquals(VALIDITY_RANGE, out.validityError, date)
            }
            assertEquals(2, source.registerCalls.size, "fora do intervalo, nada vai à rede")
        }

    @Test
    fun closedFolioRefusalOnReleaseRereadsTheFolio() =
        runTest {
            val folios = FakeFolioSource()
            val source =
                sourceWith("ACTIVE" to "300.00").apply {
                    release = { throw ApiError.Problem(409, "FOLIO_CLOSED", null, emptyList()) }
                }
            val folio = folio(source, folios)
            val holder = assertNotNull(folio.preAuthorizations)
            holder.startRelease(holder.rows.single())
            val form = assertNotNull(holder.release)
            val folioReads = folios.folioCalls.size
            folios.folio = { folioOf(status = FolioResponse.Status.CLOSED) }

            form.confirm()
            runCurrent()

            assertEquals(folioReads + 1, folios.folioCalls.size, "FOLIO_CLOSED relê a conta")
            assertSame(form, holder.release, "o diálogo fica com o texto")
            assertFalse(form.unconfirmed)
        }
}
