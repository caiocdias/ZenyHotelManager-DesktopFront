package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.facade.SplitPart
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import kotlinx.coroutines.CoroutineScope
import java.math.BigDecimal

private const val MAX_SPLIT_PARTS = 10
private val HUNDRED = BigDecimal(100)

/** O percentual digitado: até 3 dígitos inteiros e até 4 casas, com vírgula ou ponto (o padrão do contrato). */
private val PERCENT = Regex("""^\d{1,3}(?:[.,]\d{1,4})?$""")

/**
 * As contas abertas que podem receber da conta [folioId]: a lista paginada do `FolioListState` (com "Carregar mais"),
 * sem a própria conta. Cada linha traz o número, o dono e o saldo do servidor. Lida ao nascer; [close] a cancela.
 */
class FolioTargets(
    source: FolioSource,
    scope: CoroutineScope,
    private val folioId: String,
) {
    private val list = FolioListState(source, scope).also { it.start() }

    val rows: List<FolioRow> get() = list.rows.filter { it.id != folioId }

    val loading: Boolean get() = list.loading

    val error: String? get() = list.error

    val hasMore: Boolean get() = list.hasMore

    fun loadMore() = list.loadMore()

    fun close() = list.close()
}

/**
 * O diálogo "Transferir" a partir do lançamento [row] da conta [folioId]: os lançamentos transferíveis da conta
 * ([candidates], a taxa de serviço nunca à parte: ela segue o item no servidor), com [row] já marcado; a conta de
 * destino escolhida entre as abertas ([targets], sem a própria) e o motivo opcional (até 500, sem os espaços das
 * pontas; em branco não vai). Nenhum marcado ou sem destino é recusado com texto antes da porta.
 *
 * O envio, a `Idempotency-Key` por intenção, a classificação da falha e o fechamento são do ajudante comum
 * `FolioWrite`: o sucesso vai a [onTransferred] com o número da conta de destino; a recusa definitiva, depois do texto
 * do catálogo, a [onRefused] com o `code`.
 */
@Suppress("LongParameterList") // as portas, a conta, a linha, os lançamentos e os dois avisos para o holder
class TransferState(
    private val source: FolioCorrectionSource,
    folios: FolioSource,
    scope: CoroutineScope,
    private val folioId: String,
    row: PostingRow,
    postings: List<PostingRow>,
    private val onTransferred: (number: String) -> Unit,
    onRefused: (code: String) -> Unit,
) : FolioWriteForm {
    private val write =
        FolioWrite(
            scope,
            FolioText.TRANSFER_UNCONFIRMED,
            FolioText.TRANSFER_SERVER_UNCONFIRMED,
            openShift = null,
            onRefused = onRefused,
        )

    /** Os lançamentos que podem ir junto, na ordem da tabela. */
    val candidates: List<PostingRow> = postings.filter { it.transferable }.ifEmpty { listOf(row) }

    val targets = FolioTargets(folios, scope, folioId)

    var checked by mutableStateOf(setOf(row.id))
        private set

    var target by mutableStateOf<FolioRow?>(null)
        private set

    private var reasonText by mutableStateOf("")

    override val busy: Boolean get() = write.busy

    override val unconfirmed: Boolean get() = write.unconfirmed

    override val closeOnly: Boolean get() = write.closeOnly

    override val message: String? get() = write.message

    override val canConfirm: Boolean get() = write.canSend

    override val closesOnEscape: Boolean get() = write.closesOnEscape

    /** As entradas estão travadas (em voo, resultado desconhecido ou fechado). */
    val locked: Boolean get() = write.locked

    /** O motivo opcional, até 500 caracteres; o excesso é recusado. */
    var reason: String
        get() = reasonText
        set(value) {
            if (locked || value == reasonText || value.length > CORRECTION_REASON_MAX) return
            reasonText = value
            write.edited()
        }

    /** Marca ou desmarca o lançamento [postingId]; fora dos [candidates] ou travado, nada. */
    fun toggle(postingId: String) {
        if (locked || candidates.none { it.id == postingId }) return
        checked = if (postingId in checked) checked - postingId else checked + postingId
        write.edited()
    }

    /** Escolhe a conta de destino; a própria conta ou travado, nada. */
    fun chooseTarget(folio: FolioRow) {
        if (locked || folio.id == folioId || folio == target) return
        target = folio
        write.edited()
    }

    override fun confirm() {
        if (!canConfirm) return
        val ids = candidates.map { it.id }.filter { it in checked }
        val chosen = target
        when {
            ids.isEmpty() -> {
                write.message = FolioText.TRANSFER_NOTHING_CHECKED
            }

            chosen == null -> {
                write.message = FolioText.TRANSFER_TARGET_REQUIRED
            }

            else -> {
                val text = reasonText.trim().ifEmpty { null }
                write.send({ WriteOutcome.Done(source.transfer(folioId, ids, chosen.id, text, it)) }) {
                    onTransferred(chosen.number)
                }
            }
        }
    }

    fun close() {
        write.close()
        targets.close()
    }

    override fun toString(): String = "TransferState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly)"
}

/** Como a divisão reparte o lançamento: por percentual ou por valor. */
enum class SplitBy { PERCENT, AMOUNT }

/** Uma parte da divisão: a conta (o [label] na tela, a primeira é a própria) e o percentual ou valor digitado. */
data class SplitPartInput(
    val folioId: String,
    val label: String,
    val value: String,
)

/**
 * O diálogo "Dividir" do lançamento [row] da conta [folioId]: "Por percentual" ou "Por valor" ([by]), a própria conta
 * como primeira parte (a que fica) e de 1 a 9 contas escolhidas entre as abertas ([targets], sem a própria), cada parte
 * com o percentual (até 4 casas, maior que zero e até 100) ou o valor (`Money`, maior que zero) digitado, o
 * [remaining] do servidor e o motivo opcional. O front não soma as partes: a divisão que não fecha é recusada pelo
 * servidor (`SPLIT_TOTAL_MISMATCH`, texto do catálogo). Parte vazia ou fora do formato, menos de 2 partes ou conta
 * repetida são recusadas com texto antes da porta.
 *
 * O envio e a intenção são do `FolioWrite`: o sucesso vai a [onSplit] com o número de partes enviadas; a recusa
 * definitiva, depois do texto do catálogo, a [onRefused] com o `code`.
 */
@Suppress("LongParameterList") // as portas, a conta, a linha e os dois avisos para o holder
class SplitState(
    private val source: FolioCorrectionSource,
    folios: FolioSource,
    scope: CoroutineScope,
    private val folioId: String,
    val row: PostingRow,
    private val onSplit: (parts: Int) -> Unit,
    onRefused: (code: String) -> Unit,
) : FolioWriteForm {
    private val write =
        FolioWrite(
            scope,
            FolioText.SPLIT_UNCONFIRMED,
            FolioText.SPLIT_SERVER_UNCONFIRMED,
            openShift = null,
            onRefused = onRefused,
        )

    /** "Resta: <valor>", o restante que o servidor devolveu para o lançamento. */
    val remaining: String? = row.remaining?.let(MoneyInput::format)?.let(FolioText::remaining)

    val targets = FolioTargets(folios, scope, folioId)

    var parts by mutableStateOf(listOf(SplitPartInput(folioId, FolioText.SPLIT_THIS_FOLIO, "")))
        private set

    private var chosenBy by mutableStateOf(SplitBy.PERCENT)
    private var reasonText by mutableStateOf("")

    override val busy: Boolean get() = write.busy

    override val unconfirmed: Boolean get() = write.unconfirmed

    override val closeOnly: Boolean get() = write.closeOnly

    override val message: String? get() = write.message

    override val canConfirm: Boolean get() = write.canSend

    override val closesOnEscape: Boolean get() = write.closesOnEscape

    /** As entradas estão travadas (em voo, resultado desconhecido ou fechado). */
    val locked: Boolean get() = write.locked

    /** "Por percentual" ou "Por valor"; travado, não muda. */
    var by: SplitBy
        get() = chosenBy
        set(value) {
            if (locked || value == chosenBy) return
            chosenBy = value
            write.edited()
        }

    /** O motivo opcional, até 500 caracteres; o excesso é recusado. */
    var reason: String
        get() = reasonText
        set(value) {
            if (locked || value == reasonText || value.length > CORRECTION_REASON_MAX) return
            reasonText = value
            write.edited()
        }

    /** Acrescenta a conta [folio] como parte, até 10 partes; a própria conta ou travado, nada. */
    fun addPart(folio: FolioRow) {
        if (locked || folio.id == folioId || parts.size >= MAX_SPLIT_PARTS) return
        parts = parts + SplitPartInput(folio.id, "${folio.number} · ${folio.owner}", "")
        write.edited()
    }

    /** Tira a parte [index]; a primeira (a própria conta) fica. */
    fun removePart(index: Int) {
        if (locked || index !in 1 until parts.size) return
        parts = parts.filterIndexed { i, _ -> i != index }
        write.edited()
    }

    /** O percentual ou o valor digitado da parte [index]. */
    fun setPart(
        index: Int,
        value: String,
    ) {
        if (locked || index !in parts.indices || parts[index].value == value) return
        parts = parts.mapIndexed { i, part -> if (i == index) part.copy(value = value) else part }
        write.edited()
    }

    override fun confirm() {
        if (!canConfirm) return
        val checked = checkedParts()
        if (checked == null) {
            write.message = FolioText.SPLIT_PARTS_INVALID
            return
        }
        val text = reasonText.trim().ifEmpty { null }
        write.send({ WriteOutcome.Done(source.split(folioId, row.id, checked, text, it)) }) { onSplit(checked.size) }
    }

    fun close() {
        write.close()
        targets.close()
    }

    /** As partes no formato da fachada; fora das regras, nulo. */
    private fun checkedParts(): List<SplitPart>? {
        val ids = parts.map { it.folioId }
        if (parts.size < 2 || ids.toSet().size != ids.size) return null
        val checked =
            parts.map { part ->
                when (chosenBy) {
                    SplitBy.PERCENT -> percentOf(part.value)?.let { SplitPart.Percent(part.folioId, it) }
                    SplitBy.AMOUNT -> amountOf(part.value)?.let { SplitPart.Amount(part.folioId, it) }
                }
            }
        return checked.filterNotNull().takeIf { it.size == checked.size }
    }

    override fun toString(): String = "SplitState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly)"
}

/** O percentual digitado, maior que zero e até 100, com até 4 casas; senão, nulo. */
private fun percentOf(text: String): BigDecimal? {
    val trimmed = text.trim()
    if (!PERCENT.matches(trimmed)) return null
    val value = BigDecimal(trimmed.replace(',', '.'))
    return value.takeIf { it.signum() > 0 && it <= HUNDRED }
}

/** O valor digitado (`MoneyField`), maior que zero; senão, nulo. */
private fun amountOf(text: String): Money? =
    (MoneyInput.parse(text) as? InputResult.Valid)?.value?.takeIf { it.amount.signum() > 0 }
