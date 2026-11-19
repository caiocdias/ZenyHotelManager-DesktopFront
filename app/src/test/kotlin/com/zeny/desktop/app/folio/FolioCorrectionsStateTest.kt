package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.PostingResponse
import de.quati.kotlin.util.Option
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
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val CREATE = "billing.postings.create"
private const val ADJUST = "billing.postings.adjust"
private const val REMOVE = "billing.servicecharge.remove"
private const val RECEIVE = "billing.payments.receive"
private val ITEM = Uuid.parse("50000000-0000-0000-0000-0000000000a1")
private val CHARGE = Uuid.parse("50000000-0000-0000-0000-0000000000a2")
private val ADJUSTMENT = Uuid.parse("50000000-0000-0000-0000-0000000000a3")

/** O ajuste e a remoção da taxa na conta ([FolioCorrectionsState]), com portas falsas. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolioCorrectionsStateTest {
    private val item = postingOf(id = ITEM, description = "Frigobar", amount = "20.00").withRemaining("20.00")
    private val charge =
        postingOf(id = CHARGE, description = "Taxa de serviço", amount = "2.00", type = "SERVICE_CHARGE")
            .copy(serviceChargeForPostingId = Option.Some(ITEM))
            .withRemaining("2.00")

    private fun folios(vararg postings: PostingResponse) =
        FakeFolioSource().apply {
            val list = postings.toList().ifEmpty { listOf(item, charge) }
            this.postings = { list }
        }

    /** O aprovador da casca: pede a aprovação no `APPROVAL_REQUIRED` e, sem [cancel], repete com o token. */
    private class Approver(
        var cancel: Boolean = false,
    ) {
        var asked = 0

        suspend fun approve(action: ApprovableAction<PostingResponse>): ApiResponse<PostingResponse>? =
            try {
                action.run(null)
            } catch (
                // O coordenador falso só conta o pedido e repete com o token.
                @Suppress("SwallowedException") e: ApiError.ApprovalRequired,
            ) {
                asked++
                if (cancel) null else action.run("tok")
            }
    }

    @Suppress("LongParameterList") // fixture: cada porta e regra que os testes variam, com padrão
    private fun TestScope.folio(
        source: FakeFolioSource = folios(),
        corrections: FakeFolioCorrectionSource? = FakeFolioCorrectionSource(),
        permissions: Set<String> = setOf(READ, CREATE, REMOVE),
        approver: Approver = Approver(),
        payments: FakePaymentSource? = null,
    ) = FolioState(
        correctionWiringOf(source, corrections, permissions, approver::approve, payments),
        backgroundScope,
        FOLIO_ID.toString(),
    ).also {
        it.start()
        runCurrent()
    }

    private fun FolioState.row(id: Uuid) = postings.single { it.id == id.toString() }

    private fun FolioState.fix() = assertNotNull(corrections, "sem o estado das correções")

    /** Abre "Ajustar" no item e digita o valor e o motivo. */
    private fun FolioState.adjust(
        amount: String = "5,00",
        reason: String = "Cobrado a mais",
    ): AdjustmentState {
        fix().startAdjustment(row(ITEM))
        val form = assertNotNull(fix().adjustment, "o diálogo de ajuste não abriu")
        form.amount = amount
        form.reason = reason
        return form
    }

    @Test
    fun adjustOverTheLimitAsksApprovalAndShowsTheLinkedPosting() =
        runTest {
            val adjustment =
                postingOf(id = ADJUSTMENT, description = "Ajuste", amount = "-5.00", type = "ADJUSTMENT")
                    .correcting(ITEM)
            val source = folios()
            val corrections =
                FakeFolioCorrectionSource().apply {
                    requiresApproval = ApiError.ApprovalRequired("POSTING_ADJUST", ADJUST, "posting", null, null)
                    adjusted = {
                        source.postings = { listOf(item, charge, adjustment) }
                        adjustment
                    }
                }
            val approver = Approver()
            val folio = folio(source, corrections, approver = approver)
            val form = folio.adjust(reason = "  Cobrado a mais  ")

            assertEquals("Resta: R$ 20,00", form.remaining)
            form.confirm()
            runCurrent()

            assertEquals(1, approver.asked, "a aprovação não foi pedida pelo approve do wiring")
            val call = corrections.adjustCalls.single()
            assertEquals(ITEM.toString(), call.postingId)
            assertEquals(Money.parse("5.00"), call.amount)
            assertEquals("Cobrado a mais", call.reason, "o motivo vai sem os espaços das pontas")
            assertNull(folio.fix().adjustment, "o diálogo não fechou")
            assertEquals("Ajuste lançado.", folio.notice)
            val linked = folio.row(ADJUSTMENT)
            assertEquals("Frigobar", linked.corrects)
            assertEquals("R$ 20,00", folio.row(ITEM).amount, "o original fica com o valor dele")
            assertNull(folio.row(ITEM).corrects)
        }

    @Test
    fun approvalCancelledKeepsTheIntent() =
        runTest {
            val corrections =
                FakeFolioCorrectionSource().apply {
                    requiresApproval = ApiError.ApprovalRequired("POSTING_ADJUST", ADJUST, "posting", null, null)
                }
            val approver = Approver(cancel = true)
            val folio = folio(corrections = corrections, approver = approver)
            val form = folio.adjust()

            form.confirm()
            runCurrent()

            assertEquals("Aprovação cancelada. O ajuste não foi feito.", form.message)
            assertFalse(form.locked, "as entradas continuam travadas")
            assertFalse(form.busy)
            assertNotNull(folio.fix().adjustment)
            assertNull(folio.notice)

            approver.cancel = false
            form.confirm()
            runCurrent()
            val (first, second) = corrections.adjustCalls
            assertEquals(first.intent.key, second.intent.key, "a aprovação cancelada trocou a intenção")
            assertNull(folio.fix().adjustment)
        }

    @Test
    fun doubleConfirmAdjustsOnce() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val corrections =
                FakeFolioCorrectionSource().apply {
                    val done = adjusted
                    adjusted = {
                        gate.await()
                        done()
                    }
                }
            val folio = folio(corrections = corrections)
            val form = folio.adjust()

            form.confirm()
            form.confirm()
            runCurrent()
            assertTrue(form.busy)
            assertTrue(form.locked)
            gate.complete(Unit)
            runCurrent()

            assertEquals(1, corrections.adjustCalls.size)
            assertEquals("Ajuste lançado.", folio.notice)
        }

    @Test
    fun retryAfterNetworkKeepsTheKey() =
        runTest {
            val corrections =
                FakeFolioCorrectionSource().apply { adjustOutcomes += ApiError.Network(IOException("x")) }
            val folio = folio(corrections = corrections)
            val form = folio.adjust()

            form.confirm()
            runCurrent()
            assertTrue(form.unconfirmed)
            assertTrue(form.locked, "com o resultado desconhecido, as entradas travam")
            form.amount = "9,00"
            assertEquals("5,00", form.amount)

            form.confirm()
            runCurrent()

            val (first, second) = corrections.adjustCalls
            assertEquals(first.intent.key, second.intent.key)
            assertEquals(first.amount, second.amount)
            assertNull(folio.fix().adjustment)
        }

    @Test
    fun storedFailureIsCloseOnly() =
        runTest {
            val source = folios()
            val corrections =
                FakeFolioCorrectionSource().apply {
                    adjustOutcomes += ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList())
                }
            val folio = folio(source, corrections)
            val form = folio.adjust()

            form.confirm()
            runCurrent()
            assertTrue(form.closeOnly)
            assertFalse(form.canConfirm)
            form.confirm()
            runCurrent()
            assertEquals(1, corrections.adjustCalls.size)

            val reads = source.postingCalls.size
            folio.fix().finishAdjustment()
            runCurrent()
            assertNull(folio.fix().adjustment)
            assertTrue(source.postingCalls.size > reads, "fechar sem resultado conhecido não releu a conta")
        }

    @Test
    fun editingAfterADefinitiveAnswerRotatesTheIntent() =
        runTest {
            val corrections =
                FakeFolioCorrectionSource().apply {
                    adjustOutcomes += ApiError.Problem(422, "ADJUSTMENT_EXCEEDS_REMAINING", null, emptyList())
                }
            val folio = folio(corrections = corrections)
            val form = folio.adjust(amount = "50,00")

            form.confirm()
            runCurrent()
            assertEquals("O ajuste é maior que o valor restante.", form.message)
            assertFalse(form.locked)

            form.amount = "5,00"
            form.confirm()
            runCurrent()

            val (first, second) = corrections.adjustCalls
            assertNotEquals(first.intent.key, second.intent.key)
            assertNull(folio.fix().adjustment)
        }

    @Test
    fun emptyAmountOrReasonIsRefusedWithText() =
        runTest {
            val corrections = FakeFolioCorrectionSource()
            val folio = folio(corrections = corrections)
            val form = folio.adjust(amount = "", reason = "Cobrado a mais")
            assertEquals("", form.amount, "o valor não é sugerido")

            form.confirm()
            runCurrent()
            assertEquals("Informe o valor.", form.message)

            form.amount = "0,00"
            form.confirm()
            runCurrent()
            assertEquals("Informe o valor.", form.message)

            form.amount = "5,00"
            form.reason = "   "
            form.confirm()
            runCurrent()
            assertEquals("Informe o motivo.", form.message)

            form.reason = "x".repeat(501)
            assertEquals("   ", form.reason, "o motivo passa de 500 caracteres")
            assertTrue(corrections.adjustCalls.isEmpty(), "a entrada fora das regras foi à porta")
        }

    @Test
    fun alreadyReversedShowsTextAndRereads() =
        runTest {
            val source = folios()
            val corrections =
                FakeFolioCorrectionSource().apply {
                    adjustOutcomes += ApiError.Problem(409, "POSTING_ALREADY_REVERSED", null, emptyList())
                }
            val folio = folio(source, corrections)
            val form = folio.adjust()
            val reads = source.postingCalls.size

            form.confirm()
            runCurrent()

            assertEquals("O lançamento já foi estornado.", form.message)
            assertTrue(source.postingCalls.size > reads, "a recusa não releu a conta")
        }

    @Test
    fun folioClosedRereads() =
        runTest {
            val source = folios()
            val corrections =
                FakeFolioCorrectionSource().apply {
                    removalOutcomes += ApiError.Problem(409, "FOLIO_CLOSED", null, emptyList())
                }
            val folio = folio(source, corrections)
            folio.fix().startRemoval(folio.row(CHARGE))
            val form = assertNotNull(folio.fix().removal)
            val reads = source.postingCalls.size

            form.confirm()
            runCurrent()

            assertEquals("A conta já está fechada.", form.message)
            assertTrue(source.postingCalls.size > reads, "a conta encerrada não foi relida")
        }

    @Test
    fun removalShowsTheServerReason() =
        runTest {
            val corrections =
                FakeFolioCorrectionSource().apply {
                    removed = {
                        postingOf(type = "REVERSAL", amount = "-2.00").copy(reason = Option.Some("Pedido do hóspede."))
                    }
                }
            val folio = folio(corrections = corrections)
            folio.fix().startRemoval(folio.row(CHARGE))
            val form = assertNotNull(folio.fix().removal)
            assertEquals("O servidor registra o motivo: pedido do cliente.", form.text)

            form.confirm()
            runCurrent()

            assertEquals(CHARGE.toString(), corrections.removalCalls.single().postingId)
            assertNull(folio.fix().removal)
            assertEquals("Taxa de serviço removida: Pedido do hóspede.", folio.notice)
        }

    @Test
    fun removalWithoutAReasonSaysOnlyItWasRemoved() =
        runTest {
            val corrections =
                FakeFolioCorrectionSource().apply { removed = { postingOf(type = "REVERSAL", amount = "-2.00") } }
            val folio = folio(corrections = corrections)
            folio.fix().startRemoval(folio.row(CHARGE))

            assertNotNull(folio.fix().removal).confirm()
            runCurrent()

            assertEquals("Taxa de serviço removida.", folio.notice)
        }

    @Test
    fun removalOnlyOnServiceChargeRows() =
        runTest {
            val reversedCharge =
                charge.copy(id = Uuid.random(), reversedBy = Option.Some(Uuid.random()))
            val folio =
                folio(folios(item, charge, reversedCharge), permissions = setOf(READ, CREATE, ADJUST, REMOVE))
            val holder = folio.fix()

            assertFalse(folio.row(ITEM).serviceChargeRemovable)
            assertFalse(holder.canRemoveServiceCharge(folio.row(ITEM)), "a remoção apareceu num item")
            holder.startRemoval(folio.row(ITEM))
            assertNull(holder.removal)
            assertFalse(holder.canRemoveServiceCharge(folio.row(reversedCharge.id)), "a remoção numa taxa estornada")
            assertTrue(holder.canRemoveServiceCharge(folio.row(CHARGE)))
            assertFalse(holder.canAdjust(folio.row(CHARGE)), "o ajuste numa taxa")
        }

    @Test
    fun removalRetryKeepsTheKey() =
        runTest {
            val corrections =
                FakeFolioCorrectionSource().apply { removalOutcomes += ApiError.Network(IOException("x")) }
            val folio = folio(corrections = corrections)
            folio.fix().startRemoval(folio.row(CHARGE))
            val form = assertNotNull(folio.fix().removal)

            form.confirm()
            runCurrent()
            assertTrue(form.unconfirmed)
            form.confirm()
            runCurrent()

            val (first, second) = corrections.removalCalls
            assertEquals(first.intent.key, second.intent.key)
            assertNull(folio.fix().removal)
        }

    @Test
    fun actionsNeedARemainingFromTheServer() =
        runTest {
            val absent = postingOf(description = "Sem restante")
            val zero = postingOf(description = "Zerado").withRemaining("0.00")
            val garbage = postingOf(description = "Fora do formato").withRemaining("1,5")
            val credit = postingOf(description = "Crédito", amount = "-20.00").withRemaining("-20.00")
            val reversed = item.copy(id = Uuid.random(), reversedBy = Option.Some(Uuid.random()))
            val zeroCharge = charge.copy(id = Uuid.random()).withRemaining("0.00")
            val folio =
                folio(
                    folios(item, charge, absent, zero, garbage, credit, reversed, zeroCharge),
                    permissions = setOf(READ, CREATE, REMOVE),
                )
            val holder = folio.fix()

            fun row(posting: PostingResponse) = folio.row(posting.id)

            listOf(absent, zero, garbage, reversed).forEach {
                assertFalse(holder.canAdjust(row(it)), "o ajuste apareceu em ${it.description}")
            }
            assertNull(row(absent).remaining)
            assertNull(row(garbage).remaining)
            assertTrue(holder.canAdjust(row(credit)), "o crédito ficou sem ajuste")
            assertEquals(Money.parse("-20.00"), row(credit).remaining)
            assertFalse(holder.canRemoveServiceCharge(row(zeroCharge)), "a remoção numa taxa zerada")
            holder.startAdjustment(row(zero))
            assertNull(holder.adjustment)
        }

    @Test
    fun actionsFollowThePermissions() =
        runTest {
            fun TestScope.holderWith(permissions: Set<String>) = folio(permissions = permissions).let { it to it.fix() }

            val (byCreate, createHolder) = holderWith(setOf(READ, CREATE))
            assertTrue(createHolder.canAdjust(byCreate.row(ITEM)))
            assertFalse(createHolder.canRemoveServiceCharge(byCreate.row(CHARGE)))

            val (byAdjust, adjustHolder) = holderWith(setOf(READ, ADJUST))
            assertTrue(adjustHolder.canAdjust(byAdjust.row(ITEM)))
            assertFalse(adjustHolder.canRemoveServiceCharge(byAdjust.row(CHARGE)))

            val (byRemove, removeHolder) = holderWith(setOf(READ, REMOVE))
            assertFalse(removeHolder.canAdjust(byRemove.row(ITEM)))
            assertTrue(removeHolder.canRemoveServiceCharge(byRemove.row(CHARGE)))
            removeHolder.startAdjustment(byRemove.row(ITEM))
            assertNull(removeHolder.adjustment)

            val (readOnly, readHolder) = holderWith(setOf(READ))
            assertFalse(readHolder.canAdjust(readOnly.row(ITEM)))
            assertFalse(readHolder.canRemoveServiceCharge(readOnly.row(CHARGE)))

            val noPort = folio(corrections = null, permissions = setOf(READ, CREATE, ADJUST, REMOVE))
            assertNull(noPort.corrections)

            val closedSource = folios().apply { folio = { folioOf(status = FolioResponse.Status.CLOSED) } }
            val closed = folio(closedSource, permissions = setOf(READ, CREATE, ADJUST, REMOVE))
            assertFalse(closed.fix().canAdjust(closed.row(ITEM)))
            assertFalse(closed.fix().canRemoveServiceCharge(closed.row(CHARGE)))
        }

    @Test
    fun oneDialogAtATime() =
        runTest {
            val payments = FakePaymentSource()
            val folio = folio(permissions = setOf(READ, CREATE, REMOVE, RECEIVE), payments = payments)
            val holder = folio.fix()

            folio.startPayment()
            assertNotNull(folio.payment)
            holder.startAdjustment(folio.row(ITEM))
            holder.startRemoval(folio.row(CHARGE))
            assertNull(holder.adjustment, "o ajuste abriu com \"Receber\" aberto")
            assertNull(holder.removal)
            folio.finishPayment()

            holder.startAdjustment(folio.row(ITEM))
            assertNotNull(holder.adjustment)
            assertTrue(holder.dialogOpen)
            folio.startPayment()
            folio.startPosting()
            holder.startRemoval(folio.row(CHARGE))
            assertNull(folio.payment, "\"Receber\" abriu com o ajuste aberto")
            assertNull(folio.posting)
            assertNull(holder.removal)
            val preAuthorizations = assertNotNull(folio.preAuthorizations)
            preAuthorizations.startRegister()
            assertNull(preAuthorizations.register, "a caução abriu com o ajuste aberto")

            holder.finishAdjustment()
            assertFalse(holder.dialogOpen)
        }

    @Test
    fun closeDropsLateAnswers() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = folios()
            val corrections =
                FakeFolioCorrectionSource().apply {
                    val done = adjusted
                    adjusted = {
                        gate.await()
                        done()
                    }
                }
            val folio = folio(source, corrections)
            folio.adjust().confirm()
            runCurrent()
            val reads = source.postingCalls.size

            folio.close()
            gate.complete(Unit)
            runCurrent()

            assertNull(folio.notice)
            assertEquals(reads, source.postingCalls.size, "a resposta tardia releu a conta")
        }
}
