package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransferResponse
import com.zeny.desktop.sdk.model.TransferredPostingResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
private const val TRANSFER = "billing.postings.transfer"
private const val PARTS_TEXT = "Confira as partes da divisão."
private const val TRANSFER_LIMIT = 100
private val ITEM = Uuid.parse("50000000-0000-0000-0000-0000000000e1")
private val CHARGE = Uuid.parse("50000000-0000-0000-0000-0000000000e2")
private val OUT_ITEM = Uuid.parse("50000000-0000-0000-0000-0000000000e3")
private val OUT_CHARGE = Uuid.parse("50000000-0000-0000-0000-0000000000e4")
private val IN_ITEM = Uuid.parse("50000000-0000-0000-0000-0000000000e5")
private val IN_CHARGE = Uuid.parse("50000000-0000-0000-0000-0000000000e6")

/**
 * Ataque do testador à M4 E08 sobre o [FolioCorrectionsState]: as bordas que as fatias não cobriram (valor negativo
 * no ajuste, linhas que não são taxa, cada parte fora do formato a partir de um diálogo limpo, a saída na conta de
 * origem relida, mais de 100 marcados, aprovação expirada, conta inexistente, remoção concorrente, duplo clique na
 * remoção e a permissão da transferência), com portas falsas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolioCorrectionsAttackTest {
    private val item = postingOf(id = ITEM, description = "Frigobar", amount = "20.00").withRemaining("20.00")
    private val charge =
        postingOf(id = CHARGE, description = "Taxa de serviço", amount = "2.00", type = "SERVICE_CHARGE")
            .copy(serviceChargeForPostingId = Option.Some(ITEM))
            .withRemaining("2.00")
    private val own = folioOf()
    private val other = folioOf(id = OTHER_FOLIO_ID, number = "F-0002", balance = "40.00")

    private fun sourceOf(ledger: MutableMap<String, List<PostingResponse>>) =
        FakeFolioSource().apply {
            pages = { Page(listOf(own, other), null) }
            folio = { id -> if (id == OTHER_FOLIO_ID.toString()) other else own }
            postings = { id -> ledger[id].orEmpty() }
        }

    private fun ledgerOf(vararg here: PostingResponse): MutableMap<String, List<PostingResponse>> =
        mutableMapOf(
            FOLIO_ID.toString() to here.toList().ifEmpty { listOf(item, charge) },
            OTHER_FOLIO_ID.toString() to emptyList(),
        )

    @Suppress("LongParameterList") // fixture: cada porta e regra que os testes variam, com padrão
    private fun TestScope.folio(
        source: FakeFolioSource = sourceOf(ledgerOf()),
        corrections: FakeFolioCorrectionSource = FakeFolioCorrectionSource(),
        permissions: Set<String> = setOf(READ, CREATE, ADJUST, REMOVE, TRANSFER),
        approve: suspend (ApprovableAction<PostingResponse>) -> ApiResponse<PostingResponse>? = { it.run(null) },
        folioId: Uuid = FOLIO_ID,
    ) = FolioState(
        correctionWiringOf(source, corrections, permissions, approve),
        backgroundScope,
        folioId.toString(),
    ).also {
        it.start()
        runCurrent()
    }

    private fun FolioState.row(id: Uuid) = postings.single { it.id == id.toString() }

    private fun FolioState.fix() = assertNotNull(corrections, "sem o estado das correções")

    /** O aprovador da casca que sempre aprova: pede no `APPROVAL_REQUIRED` e repete com o token. */
    private suspend fun alwaysApproves(action: ApprovableAction<PostingResponse>): ApiResponse<PostingResponse>? =
        try {
            action.run(null)
        } catch (
            // O coordenador falso só repete com o token.
            @Suppress("SwallowedException") e: ApiError.ApprovalRequired,
        ) {
            action.run("tok")
        }

    @Test
    fun negativeAdjustmentAmountIsRefusedWithText() =
        runTest {
            val corrections = FakeFolioCorrectionSource()
            val folio = folio(corrections = corrections)
            folio.fix().startAdjustment(folio.row(ITEM))
            val form = assertNotNull(folio.fix().adjustment, "o diálogo de ajuste não abriu")
            form.reason = "Cobrado a mais"
            form.amount = "-5,00"
            assertNull(form.message, "o diálogo já abriu com uma mensagem")

            form.confirm()
            runCurrent()

            assertEquals("O valor deve ser maior que zero.", form.message)
            assertFalse(form.locked, "a recusa local travou as entradas")
            assertTrue(corrections.adjustCalls.isEmpty(), "o valor negativo foi à porta")
        }

    @Test
    fun removalNeverOnTransferInWithoutChargeLinkNorOnCorrections() =
        runTest {
            val incoming =
                postingOf(description = "Recebido", amount = "5.00", type = "TRANSFER_IN").withRemaining("5.00")
            val adjustment =
                postingOf(description = "Ajuste", amount = "-1.00", type = "ADJUSTMENT")
                    .correcting(ITEM)
                    .withRemaining("-1.00")
            val reversal =
                postingOf(description = "Estorno", amount = "-3.00", type = "REVERSAL")
                    .correcting(ITEM)
                    .withRemaining("-3.00")
            val folio = folio(sourceOf(ledgerOf(item, charge, incoming, adjustment, reversal)))
            val holder = folio.fix()

            listOf(incoming, adjustment, reversal).forEach {
                val row = folio.row(it.id)
                assertFalse(row.serviceChargeRemovable, "a linha ${it.description} virou taxa removível")
                assertFalse(holder.canRemoveServiceCharge(row), "a remoção apareceu em ${it.description}")
                holder.startRemoval(row)
                assertNull(holder.removal, "a remoção abriu em ${it.description}")
                assertFalse(holder.canAdjust(row), "o ajuste apareceu em ${it.description}")
            }
            assertTrue(holder.canTransfer(folio.row(incoming.id)), "a entrada transferida ficou sem transferência")
            assertFalse(holder.canTransfer(folio.row(adjustment.id)), "a transferência apareceu num ajuste")
            assertFalse(holder.canTransfer(folio.row(reversal.id)), "a transferência apareceu num estorno")
            assertTrue(holder.canRemoveServiceCharge(folio.row(CHARGE)), "a taxa real ficou sem a remoção")
        }

    @Test
    fun eachOutOfShapeSplitIsRefusedWithItsOwnText() =
        runTest {
            val corrections = FakeFolioCorrectionSource()
            val folio = folio(corrections = corrections)
            val holder = folio.fix()

            fun refusedFromAFreshDialog(
                case: String,
                setup: SplitState.() -> Unit,
            ) {
                holder.finishSplit()
                holder.startSplit(folio.row(ITEM))
                runCurrent()
                val form = assertNotNull(holder.split, "o diálogo de divisão não abriu ($case)")
                assertNull(form.message, "o diálogo novo já veio com mensagem ($case)")
                form.setup()
                form.confirm()
                runCurrent()
                assertEquals(PARTS_TEXT, form.message, case)
                assertFalse(form.locked, "a recusa local travou as entradas ($case)")
                assertTrue(corrections.splitCalls.isEmpty(), "a entrada fora das regras foi à porta ($case)")
            }

            fun SplitState.withOther(
                first: String,
                second: String,
            ) {
                addPart(targets.rows.single { it.id == OTHER_FOLIO_ID.toString() })
                setPart(0, first)
                setPart(1, second)
            }

            refusedFromAFreshDialog("uma parte só") { setPart(0, "100") }
            refusedFromAFreshDialog("primeira parte vazia") { withOther("", "50") }
            refusedFromAFreshDialog("segunda parte vazia") { withOther("50", "") }
            listOf("50,12345", "0", "0,0000", "100,0001", "-5", "abc", "1000", "50,", ",5").forEach {
                refusedFromAFreshDialog("percentual $it") { withOther("50", it) }
            }
            refusedFromAFreshDialog("conta repetida") {
                withOther("50", "25")
                addPart(targets.rows.single { it.id == OTHER_FOLIO_ID.toString() })
                setPart(2, "25")
            }
            listOf("0,00", "1,234", "-1,00", "abc").forEach {
                refusedFromAFreshDialog("valor $it") {
                    by = SplitBy.AMOUNT
                    withOther("10,00", it)
                }
            }
        }

    /** A transferência no falso: a saída do item e da taxa ficam aqui; a entrada dos dois vai à outra conta. */
    private fun movesTheItemOut(
        ledger: MutableMap<String, List<PostingResponse>>,
    ): suspend (TransferCall) -> TransferResponse =
        {
            val outgoing = postingOf(id = OUT_ITEM, description = "Frigobar", amount = "-20.00", type = "TRANSFER_OUT")
            val outgoingCharge =
                postingOf(id = OUT_CHARGE, description = "Taxa de serviço", amount = "-2.00", type = "TRANSFER_OUT")
            val incoming =
                postingOf(id = IN_ITEM, description = "Frigobar", amount = "20.00", type = "TRANSFER_IN")
                    .copy(folioId = OTHER_FOLIO_ID)
                    .withRemaining("20.00")
            val incomingCharge =
                postingOf(id = IN_CHARGE, description = "Taxa de serviço", amount = "2.00", type = "TRANSFER_IN")
                    .copy(folioId = OTHER_FOLIO_ID, serviceChargeForPostingId = Option.Some(IN_ITEM))
                    .withRemaining("2.00")
            ledger[FOLIO_ID.toString()] =
                listOf(item.withRemaining("0.00"), charge.withRemaining("0.00"), outgoing, outgoingCharge)
            ledger[OTHER_FOLIO_ID.toString()] = listOf(incoming, incomingCharge)
            TransferResponse(
                listOf(
                    TransferredPostingResponse(
                        incoming = incoming,
                        originalId = ITEM,
                        outgoing = outgoing,
                        serviceChargeIncoming = Option.Some(incomingCharge),
                        serviceChargeOutgoing = Option.Some(outgoingCharge),
                    ),
                ),
            )
        }

    @Test
    fun transferShowsTheOutgoingInThisFolioAfterTheReread() =
        runTest {
            val ledger = ledgerOf()
            val source = sourceOf(ledger)
            val corrections = FakeFolioCorrectionSource().apply { transferred = movesTheItemOut(ledger) }
            val folio = folio(source, corrections)
            folio.fix().startTransfer(folio.row(ITEM))
            runCurrent()
            val form = assertNotNull(folio.fix().transfer, "o diálogo de transferência não abriu")
            form.chooseTarget(form.targets.rows.single { it.id == OTHER_FOLIO_ID.toString() })
            form.confirm()
            runCurrent()

            val here = folio.postings
            assertEquals(
                listOf("Transferência (saída)", "Transferência (saída)"),
                here.filter { it.id == OUT_ITEM.toString() || it.id == OUT_CHARGE.toString() }.map { it.type },
                "a saída do item e da taxa não estão na conta de origem relida",
            )
            assertEquals("-R$ 20,00", folio.row(OUT_ITEM).amount)
            assertFalse(folio.fix().canTransfer(folio.row(ITEM)), "o item transferido continua transferível")
            assertFalse(folio.fix().canAdjust(folio.row(ITEM)), "o item transferido continua ajustável")

            val target = folio(source, corrections, folioId = OTHER_FOLIO_ID)
            assertEquals(listOf(IN_ITEM.toString(), IN_CHARGE.toString()), target.postings.map { it.id })
            assertTrue(target.row(IN_CHARGE).serviceCharge, "a taxa recebida não ficou sob o item")
        }

    @Test
    fun moreThanAHundredCheckedIsRefusedBeforeThePort() =
        runTest {
            val many =
                (0..TRANSFER_LIMIT).map {
                    postingOf(description = "Consumo $it", amount = "1.00").withRemaining("1.00")
                }
            val corrections =
                FakeFolioCorrectionSource().apply {
                    // Como a fachada real: mais de 100 ids lança IllegalArgumentException antes da rede.
                    transferred = { call ->
                        require(call.postingIds.size <= TRANSFER_LIMIT) { "postingIds fora do limite" }
                        TransferResponse(emptyList())
                    }
                }
            val folio = folio(sourceOf(ledgerOf(*many.toTypedArray())), corrections)
            folio.fix().startTransfer(folio.row(many.first().id))
            runCurrent()
            val form = assertNotNull(folio.fix().transfer, "o diálogo de transferência não abriu")
            many.drop(1).forEach { form.toggle(it.id.toString()) }
            assertEquals(TRANSFER_LIMIT + 1, form.checked.size)
            form.chooseTarget(form.targets.rows.single { it.id == OTHER_FOLIO_ID.toString() })

            form.confirm()
            runCurrent()

            assertTrue(corrections.transferCalls.isEmpty(), "mais de 100 lançamentos foram à porta")
            assertFalse(form.unconfirmed, "a entrada fora das regras virou resultado desconhecido")
            assertFalse(form.locked, "a recusa local travou as entradas")
            assertNotNull(form.message, "a recusa veio sem texto")
        }

    @Test
    fun expiredApprovalTokenShowsTheCatalogTextAndUnlocks() =
        runTest {
            val corrections =
                FakeFolioCorrectionSource().apply {
                    requiresApproval = ApiError.ApprovalRequired("POSTING_ADJUST", ADJUST, "posting", null, null)
                    adjustOutcomes += ApiError.Problem(403, "APPROVAL_TOKEN_EXPIRED", null, emptyList())
                }
            val folio = folio(corrections = corrections, approve = ::alwaysApproves)
            folio.fix().startAdjustment(folio.row(ITEM))
            val form = assertNotNull(folio.fix().adjustment, "o diálogo de ajuste não abriu")
            form.amount = "5,00"
            form.reason = "Cobrado a mais"

            form.confirm()
            runCurrent()

            assertEquals("A aprovação expirou. Peça uma nova aprovação.", form.message)
            assertFalse(form.locked, "a aprovação expirada travou as entradas")
            assertFalse(form.unconfirmed, "a aprovação expirada virou resultado desconhecido")
            assertNotNull(folio.fix().adjustment, "o diálogo fechou com a aprovação expirada")
            assertNull(folio.notice)

            form.confirm()
            runCurrent()

            assertEquals(2, corrections.adjustCalls.size, "o segundo pedido não foi à porta")
            assertNull(folio.fix().adjustment, "o ajuste aprovado de novo não fechou o diálogo")
            assertEquals("Ajuste lançado.", folio.notice)
        }

    @Test
    fun transferToUnknownFolioRereads() =
        runTest {
            val source = sourceOf(ledgerOf())
            val corrections =
                FakeFolioCorrectionSource().apply {
                    transferOutcomes += ApiError.Problem(404, "UNKNOWN_FOLIO", null, emptyList())
                }
            val folio = folio(source, corrections)
            folio.fix().startTransfer(folio.row(ITEM))
            runCurrent()
            val form = assertNotNull(folio.fix().transfer, "o diálogo de transferência não abriu")
            form.chooseTarget(form.targets.rows.single { it.id == OTHER_FOLIO_ID.toString() })
            val reads = source.postingCalls.size

            form.confirm()
            runCurrent()

            assertEquals("A conta informada não existe.", form.message)
            assertFalse(form.locked)
            assertFalse(form.unconfirmed)
            assertTrue(source.postingCalls.size > reads, "a conta não foi relida")
        }

    @Test
    fun removalAlreadyReversedElsewhereShowsTextAndRereads() =
        runTest {
            val source = sourceOf(ledgerOf())
            val corrections =
                FakeFolioCorrectionSource().apply {
                    removalOutcomes += ApiError.Problem(409, "POSTING_ALREADY_REVERSED", null, emptyList())
                }
            val folio = folio(source, corrections)
            folio.fix().startRemoval(folio.row(CHARGE))
            val form = assertNotNull(folio.fix().removal, "a confirmação da remoção não abriu")
            val reads = source.postingCalls.size

            form.confirm()
            runCurrent()

            assertEquals("O lançamento já foi estornado.", form.message)
            assertFalse(form.unconfirmed)
            assertTrue(source.postingCalls.size > reads, "a conta não foi relida")
            assertNull(folio.notice, "a recusa avisou como sucesso")
        }

    @Test
    fun doubleConfirmRemovesOnce() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val corrections =
                FakeFolioCorrectionSource().apply {
                    val answer = removed
                    removed = {
                        gate.await()
                        answer()
                    }
                }
            val folio = folio(corrections = corrections)
            folio.fix().startRemoval(folio.row(CHARGE))
            val form = assertNotNull(folio.fix().removal, "a confirmação da remoção não abriu")

            form.confirm()
            runCurrent()
            assertTrue(form.busy)
            form.confirm()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(1, corrections.removalCalls.size, "o duplo clique removeu duas vezes")
            assertNull(folio.fix().removal)
        }

    @Test
    fun transferAndSplitNeedTheTransferPermission() =
        runTest {
            val without = folio(permissions = setOf(READ, CREATE, ADJUST, REMOVE))
            val holder = without.fix()
            assertFalse(holder.canTransfer(without.row(ITEM)), "a transferência sem billing.postings.transfer")
            holder.startTransfer(without.row(ITEM))
            holder.startSplit(without.row(ITEM))
            assertNull(holder.transfer, "a transferência abriu sem a permissão")
            assertNull(holder.split, "a divisão abriu sem a permissão")

            val with = folio(permissions = setOf(READ, TRANSFER))
            assertTrue(with.fix().canTransfer(with.row(ITEM)), "a permissão não liberou a transferência")
            assertFalse(with.fix().canAdjust(with.row(ITEM)), "o ajuste só com a transferência")
            assertFalse(with.fix().canRemoveServiceCharge(with.row(CHARGE)), "a remoção só com a transferência")
        }

    @Test
    fun editingTheSplitAfterARefusalRotatesTheKey() =
        runTest {
            val corrections =
                FakeFolioCorrectionSource().apply {
                    splitOutcomes += ApiError.Problem(422, "SPLIT_TOTAL_MISMATCH", null, emptyList())
                }
            val folio = folio(corrections = corrections)
            folio.fix().startSplit(folio.row(ITEM))
            runCurrent()
            val form = assertNotNull(folio.fix().split, "o diálogo de divisão não abriu")
            form.addPart(form.targets.rows.single { it.id == OTHER_FOLIO_ID.toString() })
            form.setPart(0, "50")
            form.setPart(1, "40")
            form.confirm()
            runCurrent()
            assertEquals("A soma das partes não confere com o total.", form.message)

            form.setPart(1, "50")
            form.confirm()
            runCurrent()

            val (first, second) = corrections.splitCalls
            assertNotEquals(first.intent.key, second.intent.key, "a divisão corrigida reusou a chave da recusada")
            assertEquals("Dividido entre 2 contas.", folio.notice)
        }
}
