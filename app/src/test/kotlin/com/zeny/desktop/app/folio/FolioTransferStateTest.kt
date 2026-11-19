package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.SplitPart
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransferResponse
import com.zeny.desktop.sdk.model.TransferredPostingResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val TRANSFER = "billing.postings.transfer"
private const val PARTS_TEXT = "Confira as partes da divisão."
private val ITEM = Uuid.parse("50000000-0000-0000-0000-0000000000c1")
private val CHARGE = Uuid.parse("50000000-0000-0000-0000-0000000000c2")
private val COFFEE = Uuid.parse("50000000-0000-0000-0000-0000000000c3")
private val IN_ITEM = Uuid.parse("50000000-0000-0000-0000-0000000000c4")
private val IN_CHARGE = Uuid.parse("50000000-0000-0000-0000-0000000000c5")
private val THIRD_FOLIO_ID = Uuid.parse("60000000-0000-0000-0000-000000000003")

/** "Transferir" e "Dividir" na conta ([TransferState] e [SplitState] no [FolioCorrectionsState]), com portas falsas. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolioTransferStateTest {
    private val item = postingOf(id = ITEM, description = "Frigobar", amount = "20.00").withRemaining("20.00")
    private val charge =
        postingOf(id = CHARGE, description = "Taxa de serviço", amount = "2.00", type = "SERVICE_CHARGE")
            .copy(serviceChargeForPostingId = Option.Some(ITEM))
            .withRemaining("2.00")
    private val coffee = postingOf(id = COFFEE, description = "Café", amount = "8.00").withRemaining("8.00")
    private val own = folioOf()
    private val other = folioOf(id = OTHER_FOLIO_ID, number = "F-0002", balance = "40.00")
    private val third = folioOf(id = THIRD_FOLIO_ID, number = "F-0003", balance = "0.00")

    /** As duas contas no falso: a daqui com [item], a taxa dele e [coffee]; a de destino, vazia até a escrita. */
    private class Ledger(
        here: List<PostingResponse>,
    ) {
        val postings: MutableMap<String, List<PostingResponse>> =
            mutableMapOf(FOLIO_ID.toString() to here, OTHER_FOLIO_ID.toString() to emptyList())
    }

    private fun sourceOf(ledger: Ledger) =
        FakeFolioSource().apply {
            pages = { Page(listOf(own, other), null) }
            folio = { id -> if (id == OTHER_FOLIO_ID.toString()) other else own }
            postings = { id -> ledger.postings[id].orEmpty() }
        }

    /** A transferência no falso: o item sai daqui e entra no destino com a taxa dele logo abaixo. */
    private fun movesTheItem(ledger: Ledger): suspend (TransferCall) -> TransferResponse =
        {
            val outgoing = postingOf(description = "Frigobar", amount = "-20.00", type = "TRANSFER_OUT")
            val incoming =
                postingOf(id = IN_ITEM, description = "Frigobar", amount = "20.00", type = "TRANSFER_IN")
                    .copy(folioId = OTHER_FOLIO_ID)
                    .withRemaining("20.00")
            val incomingCharge =
                postingOf(id = IN_CHARGE, description = "Taxa de serviço", amount = "2.00", type = "TRANSFER_IN")
                    .copy(folioId = OTHER_FOLIO_ID, serviceChargeForPostingId = Option.Some(IN_ITEM))
                    .withRemaining("2.00")
            ledger.postings[FOLIO_ID.toString()] = listOf(item.withRemaining("0.00"), charge, coffee, outgoing)
            ledger.postings[OTHER_FOLIO_ID.toString()] = listOf(incoming, incomingCharge)
            TransferResponse(
                listOf(
                    TransferredPostingResponse(
                        incoming = incoming,
                        originalId = ITEM,
                        outgoing = outgoing,
                        serviceChargeIncoming = Option.Some(incomingCharge),
                    ),
                ),
            )
        }

    private var writes = 0

    private fun TestScope.folio(
        source: FakeFolioSource = sourceOf(Ledger(listOf(item, charge, coffee))),
        corrections: FakeFolioCorrectionSource = FakeFolioCorrectionSource(),
        permissions: Set<String> = setOf(READ, TRANSFER),
        folioId: Uuid = FOLIO_ID,
    ) = FolioState(
        correctionWiringOf(source, corrections, permissions),
        backgroundScope,
        folioId.toString(),
        onWritten = { writes++ },
    ).also {
        it.start()
        runCurrent()
    }

    private fun FolioState.row(id: Uuid) = postings.single { it.id == id.toString() }

    private fun FolioState.fix() = assertNotNull(corrections, "sem o estado das correções")

    private fun TestScope.transferForm(folio: FolioState): TransferState {
        folio.fix().startTransfer(folio.row(ITEM))
        runCurrent()
        return assertNotNull(folio.fix().transfer, "o diálogo de transferência não abriu")
    }

    private fun TestScope.splitForm(folio: FolioState): SplitState {
        folio.fix().startSplit(folio.row(ITEM))
        runCurrent()
        return assertNotNull(folio.fix().split, "o diálogo de divisão não abriu")
    }

    private fun TransferState.chooseOther() = chooseTarget(targets.rows.single { it.id == OTHER_FOLIO_ID.toString() })

    private fun SplitState.addOther() = addPart(targets.rows.single { it.id == OTHER_FOLIO_ID.toString() })

    @Test
    fun transferMovesTheCheckedPostingsAndRereads() =
        runTest {
            val ledger = Ledger(listOf(item, charge, coffee))
            val source = sourceOf(ledger)
            val corrections = FakeFolioCorrectionSource().apply { transferred = movesTheItem(ledger) }
            val folio = folio(source, corrections)
            val form = transferForm(folio)

            assertEquals(listOf(ITEM.toString(), COFFEE.toString()), form.candidates.map { it.id })
            assertEquals(setOf(ITEM.toString()), form.checked, "o lançamento da linha vem marcado")
            form.chooseOther()
            form.reason = "  Hóspede pediu  "
            val reads = source.postingCalls.size
            form.confirm()
            runCurrent()

            val call = corrections.transferCalls.single()
            assertEquals(FOLIO_ID.toString(), call.folioId)
            assertEquals(listOf(ITEM.toString()), call.postingIds)
            assertEquals(OTHER_FOLIO_ID.toString(), call.targetFolioId)
            assertEquals("Hóspede pediu", call.reason)
            assertNull(folio.fix().transfer, "o diálogo não fechou")
            assertEquals("Transferido para a conta F-0002.", folio.notice)
            assertTrue(source.postingCalls.size > reads, "a conta não foi relida")
            assertTrue(writes > 0, "a lista das contas não foi relida")
            val target = ledger.postings.getValue(OTHER_FOLIO_ID.toString())
            assertEquals(listOf(IN_ITEM, IN_CHARGE), target.map { it.id }, "o destino sem a entrada e a taxa do item")
        }

    @Test
    fun serviceChargeFollowsTheItem() =
        runTest {
            val ledger = Ledger(listOf(item, charge, coffee))
            val source = sourceOf(ledger)
            val corrections = FakeFolioCorrectionSource().apply { transferred = movesTheItem(ledger) }
            val folio = folio(source, corrections)
            assertFalse(folio.fix().canTransfer(folio.row(CHARGE)), "a taxa transferível à parte")
            val form = transferForm(folio)

            form.toggle(CHARGE.toString())
            assertEquals(setOf(ITEM.toString()), form.checked, "a taxa foi marcada à parte")
            form.chooseOther()
            form.confirm()
            runCurrent()
            assertEquals(listOf(ITEM.toString()), corrections.transferCalls.single().postingIds)

            val target = folio(source, corrections, folioId = OTHER_FOLIO_ID)
            val rows = target.postings
            assertEquals(listOf(IN_ITEM.toString(), IN_CHARGE.toString()), rows.map { it.id })
            assertTrue(rows[1].serviceCharge, "a taxa recebida não ficou sob o item")
            assertEquals("Transferência (entrada)", rows[1].type)
        }

    @Test
    fun transferWithoutCheckedIsRefusedWithText() =
        runTest {
            val corrections = FakeFolioCorrectionSource()
            val folio = folio(corrections = corrections)
            val form = transferForm(folio)

            form.toggle(ITEM.toString())
            assertTrue(form.checked.isEmpty())
            form.chooseOther()
            form.confirm()
            runCurrent()
            assertEquals("Marque ao menos um lançamento.", form.message)

            folio.fix().finishTransfer()
            val fresh = transferForm(folio)
            assertNull(fresh.target, "o destino veio escolhido")
            fresh.confirm()
            runCurrent()
            assertEquals("Escolha a conta de destino.", fresh.message)
            assertTrue(corrections.transferCalls.isEmpty(), "a entrada fora das regras foi à porta")
        }

    @Test
    fun retryAfterNetworkKeepsTheTransferKey() =
        runTest {
            val corrections =
                FakeFolioCorrectionSource().apply { transferOutcomes += ApiError.Network(IOException("x")) }
            val folio = folio(corrections = corrections)
            val form = transferForm(folio)
            form.chooseOther()

            form.confirm()
            runCurrent()
            assertTrue(form.unconfirmed)
            assertTrue(form.locked, "com o resultado desconhecido, as entradas travam")
            form.toggle(COFFEE.toString())
            assertEquals(setOf(ITEM.toString()), form.checked)

            form.confirm()
            runCurrent()

            val (first, second) = corrections.transferCalls
            assertEquals(first.intent.key, second.intent.key)
            assertEquals(first.postingIds, second.postingIds)
            assertNull(folio.fix().transfer)
        }

    @Test
    fun doubleConfirmTransfersOnce() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val corrections =
                FakeFolioCorrectionSource().apply {
                    transferred = {
                        gate.await()
                        TransferResponse(emptyList())
                    }
                }
            val folio = folio(corrections = corrections)
            val form = transferForm(folio)
            form.chooseOther()

            form.confirm()
            form.confirm()
            runCurrent()
            assertTrue(form.busy)
            gate.complete(Unit)
            runCurrent()

            assertEquals(1, corrections.transferCalls.size)
            assertEquals("Transferido para a conta F-0002.", folio.notice)
        }

    @Test
    fun transferToClosedFolioRereads() =
        runTest {
            val source = sourceOf(Ledger(listOf(item, charge, coffee)))
            val corrections =
                FakeFolioCorrectionSource().apply {
                    transferOutcomes += ApiError.Problem(409, "FOLIO_CLOSED", null, emptyList())
                }
            val folio = folio(source, corrections)
            val form = transferForm(folio)
            form.chooseOther()
            val reads = source.postingCalls.size

            form.confirm()
            runCurrent()

            assertEquals("A conta já está fechada.", form.message)
            assertFalse(form.locked)
            assertTrue(source.postingCalls.size > reads, "a conta encerrada não foi relida")
        }

    @Test
    fun targetListExcludesThisFolio() =
        runTest {
            val source =
                sourceOf(Ledger(listOf(item, charge, coffee))).apply {
                    pages =
                        { cursor -> if (cursor == null) Page(listOf(own, other), "p2") else Page(listOf(third), null) }
                }
            val folio = folio(source)
            val form = transferForm(folio)

            assertEquals(listOf(OTHER_FOLIO_ID.toString()), form.targets.rows.map { it.id })
            val shown = form.targets.rows.single()
            assertEquals("F-0002", shown.number)
            assertEquals("R$ 40,00", shown.balance)
            assertTrue(form.targets.hasMore)
            form.targets.loadMore()
            runCurrent()
            assertEquals(listOf(OTHER_FOLIO_ID, THIRD_FOLIO_ID).map { it.toString() }, form.targets.rows.map { it.id })

            form.chooseTarget(FolioRow(FOLIO_ID.toString(), "F-0001", "Reserva", "R$ 150,00", false))
            assertNull(form.target, "a própria conta virou destino")
        }

    @Test
    fun splitByPercentSendsTheTypedParts() =
        runTest {
            val source = sourceOf(Ledger(listOf(item, charge, coffee)))
            val corrections = FakeFolioCorrectionSource()
            val folio = folio(source, corrections)
            val form = splitForm(folio)

            assertEquals("Resta: R$ 20,00", form.remaining)
            assertEquals(SplitBy.PERCENT, form.by)
            form.addOther()
            form.setPart(0, "33,3333")
            form.setPart(1, "66.6667")
            val reads = source.postingCalls.size
            form.confirm()
            runCurrent()

            val call = corrections.splitCalls.single()
            assertEquals(ITEM.toString(), call.postingId)
            assertEquals(
                listOf(
                    SplitPart.Percent(FOLIO_ID.toString(), BigDecimal("33.3333")),
                    SplitPart.Percent(OTHER_FOLIO_ID.toString(), BigDecimal("66.6667")),
                ),
                call.parts,
            )
            assertNull(call.reason, "o motivo em branco foi")
            assertNull(folio.fix().split, "o diálogo não fechou")
            assertEquals("Dividido entre 2 contas.", folio.notice)
            assertTrue(source.postingCalls.size > reads, "a conta não foi relida")
            assertTrue(writes > 0, "a lista das contas não foi relida")
        }

    @Test
    fun splitByAmountSendsTheTypedParts() =
        runTest {
            val corrections = FakeFolioCorrectionSource()
            val folio = folio(corrections = corrections)
            val form = splitForm(folio)

            form.by = SplitBy.AMOUNT
            form.addOther()
            form.setPart(0, "12,00")
            form.setPart(1, "8,00")
            form.reason = "  Divisão combinada  "
            form.confirm()
            runCurrent()

            val call = corrections.splitCalls.single()
            assertEquals(
                listOf(
                    SplitPart.Amount(FOLIO_ID.toString(), Money.parse("12.00")),
                    SplitPart.Amount(OTHER_FOLIO_ID.toString(), Money.parse("8.00")),
                ),
                call.parts,
            )
            assertEquals("Divisão combinada", call.reason)
            assertEquals("Dividido entre 2 contas.", folio.notice)
        }

    @Test
    fun splitTotalMismatchShowsTheCatalogText() =
        runTest {
            val corrections =
                FakeFolioCorrectionSource().apply {
                    splitOutcomes += ApiError.Problem(422, "SPLIT_TOTAL_MISMATCH", null, emptyList())
                }
            val folio = folio(corrections = corrections)
            val form = splitForm(folio)
            form.addOther()
            form.setPart(0, "50")
            form.setPart(1, "40")

            form.confirm()
            runCurrent()

            assertEquals("A soma das partes não confere com o total.", form.message)
            assertFalse(form.locked)
            assertNotNull(folio.fix().split)
        }

    @Test
    fun splitPartsOutOfShapeAreRefusedWithText() =
        runTest {
            val corrections = FakeFolioCorrectionSource()
            val folio = folio(corrections = corrections)
            val form = splitForm(folio)

            fun refused(case: String) {
                form.confirm()
                runCurrent()
                assertEquals(PARTS_TEXT, form.message, case)
            }

            form.setPart(0, "100")
            refused("uma parte só")
            form.addOther()
            form.setPart(0, "50")
            form.setPart(1, "")
            refused("parte vazia")
            listOf("50,12345", "0", "100,01", "-5", "abc", "1000").forEach {
                form.setPart(1, it)
                refused("percentual $it")
            }
            form.setPart(1, "50")
            form.addOther()
            form.setPart(2, "10")
            refused("conta repetida")
            form.removePart(2)
            form.by = SplitBy.AMOUNT
            form.setPart(0, "10,00")
            form.setPart(1, "0,00")
            refused("valor zero")
            form.setPart(1, "1,234")
            refused("valor com 3 casas")

            repeat(12) { form.addPart(FolioRow(Uuid.random().toString(), "F-9$it", "Reserva", "R$ 0,00", false)) }
            assertEquals(10, form.parts.size, "mais de 10 partes")
            assertTrue(corrections.splitCalls.isEmpty(), "a entrada fora das regras foi à porta")
        }

    @Test
    fun switchingTheModeClearsTheTypedValues() =
        runTest {
            val folio = folio()
            val form = splitForm(folio)
            form.addOther()
            form.setPart(0, "50")
            form.setPart(1, "50")

            form.by = SplitBy.AMOUNT

            assertEquals(listOf("", ""), form.parts.map { it.value }, "o percentual virou valor em reais")
            form.setPart(0, "12,00")
            form.by = SplitBy.PERCENT
            assertEquals(listOf("", ""), form.parts.map { it.value }, "o valor virou percentual")
        }

    @Test
    fun transferOfMoreThanAHundredIsRefusedWithText() =
        runTest {
            val many = (0..100).map { postingOf(description = "Consumo $it", amount = "1.00").withRemaining("1.00") }
            val corrections = FakeFolioCorrectionSource()
            val folio = folio(sourceOf(Ledger(many)), corrections)
            folio.fix().startTransfer(folio.row(many.first().id))
            runCurrent()
            val form = assertNotNull(folio.fix().transfer)
            many.drop(1).forEach { form.toggle(it.id.toString()) }
            form.chooseOther()

            form.confirm()
            runCurrent()

            assertEquals("Marque no máximo 100 lançamentos.", form.message)
            assertFalse(form.locked)
            assertTrue(corrections.transferCalls.isEmpty(), "mais de 100 lançamentos foram à porta")
        }

    @Test
    fun startingACorrectionClearsTheNotice() =
        runTest {
            val ledger = Ledger(listOf(item, charge, coffee))
            val corrections = FakeFolioCorrectionSource().apply { transferred = movesTheItem(ledger) }
            val permissions = setOf(READ, TRANSFER, "billing.postings.create", "billing.servicecharge.remove")
            val folio = folio(sourceOf(ledger), corrections, permissions)
            val holder = folio.fix()

            fun noticeThenStart(
                start: () -> Unit,
                finish: () -> Unit,
            ) {
                transferForm(folio).apply { chooseOther() }.confirm()
                runCurrent()
                assertEquals("Transferido para a conta F-0002.", folio.notice)
                ledger.postings[FOLIO_ID.toString()] = listOf(item, charge, coffee)
                folio.reload()
                runCurrent()
                start()
                assertNull(folio.notice, "abrir a correção deixou o aviso anterior")
                finish()
            }

            noticeThenStart({ holder.startTransfer(folio.row(ITEM)) }, holder::finishTransfer)
            noticeThenStart({ holder.startSplit(folio.row(ITEM)) }, holder::finishSplit)
            noticeThenStart({ holder.startAdjustment(folio.row(ITEM)) }, holder::finishAdjustment)
            noticeThenStart({ holder.startRemoval(folio.row(CHARGE)) }, holder::finishRemoval)
        }

    @Test
    fun splitRetryAfterNetworkKeepsTheKey() =
        runTest {
            val corrections =
                FakeFolioCorrectionSource().apply { splitOutcomes += ApiError.Network(IOException("x")) }
            val folio = folio(corrections = corrections)
            val form = splitForm(folio)
            form.addOther()
            form.setPart(0, "50")
            form.setPart(1, "50")

            form.confirm()
            runCurrent()
            assertTrue(form.unconfirmed)
            assertTrue(form.locked)
            form.setPart(1, "40")
            form.by = SplitBy.AMOUNT
            assertEquals("50", form.parts[1].value)
            assertEquals(SplitBy.PERCENT, form.by)

            form.confirm()
            runCurrent()

            val (first, second) = corrections.splitCalls
            assertEquals(first.intent.key, second.intent.key)
            assertEquals(first.parts, second.parts)
            assertNull(folio.fix().split)
        }

    @Test
    fun doubleConfirmSplitsOnce() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val corrections =
                FakeFolioCorrectionSource().apply {
                    splitDone = {
                        gate.await()
                        TransferResponse(emptyList())
                    }
                }
            val folio = folio(corrections = corrections)
            val form = splitForm(folio)
            form.addOther()
            form.setPart(0, "50")
            form.setPart(1, "50")

            form.confirm()
            form.confirm()
            runCurrent()
            assertTrue(form.busy)
            gate.complete(Unit)
            runCurrent()

            assertEquals(1, corrections.splitCalls.size)
            assertEquals("Dividido entre 2 contas.", folio.notice)
        }

    @Test
    fun closeDropsLateAnswers() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = sourceOf(Ledger(listOf(item, charge, coffee)))
            val corrections =
                FakeFolioCorrectionSource().apply {
                    transferred = {
                        gate.await()
                        TransferResponse(emptyList())
                    }
                }
            val folio = folio(source, corrections)
            val form = transferForm(folio)
            form.chooseOther()
            form.confirm()
            runCurrent()
            val reads = source.postingCalls.size

            folio.close()
            gate.complete(Unit)
            runCurrent()

            assertNull(folio.notice)
            assertEquals(reads, source.postingCalls.size, "a resposta tardia releu a conta")
            assertEquals(0, writes, "a resposta tardia releu a lista das contas")
        }
}
