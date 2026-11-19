package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.PostingResponse
import kotlinx.coroutines.CoroutineScope

/** O limite do motivo das correções da conta (o do contrato), nos estados e nos diálogos. */
internal const val CORRECTION_REASON_MAX = 500

/**
 * O diálogo "Ajustar" do lançamento [row] da conta [folioId]: o valor (`MoneyField`, sem sugestão, maior que zero; o
 * servidor aplica o sinal oposto ao do original) e o motivo (obrigatório, até 500 caracteres; a edição que passaria
 * disso é recusada), enviado sem os espaços das pontas. [remaining] mostra o restante que o servidor devolveu. A
 * entrada fora das regras é recusada com texto antes da porta.
 *
 * O ajuste passa pelo [approve] (o coordenador da sessão): a aprovação cancelada deixa "Aprovação cancelada. O ajuste
 * não foi feito." e destrava, com a mesma intenção. O envio, a `Idempotency-Key` por intenção, a classificação da
 * falha e o fechamento são do ajudante comum `FolioWrite`: o sucesso vai a [onAdjusted]; a recusa definitiva, depois do
 * texto do catálogo, a [onRefused] com o `code`. O `toString()` não mostra o digitado.
 */
@Suppress("LongParameterList") // a porta, a conta, a linha, a aprovação e os dois avisos para o holder
class AdjustmentState(
    private val source: FolioCorrectionSource,
    scope: CoroutineScope,
    private val folioId: String,
    val row: PostingRow,
    private val approve: suspend (ApprovableAction<PostingResponse>) -> ApiResponse<PostingResponse>?,
    private val onAdjusted: () -> Unit,
    onRefused: (code: String) -> Unit,
) : FolioWriteForm {
    private val write =
        FolioWrite(
            scope,
            FolioText.ADJUSTMENT_UNCONFIRMED,
            FolioText.ADJUSTMENT_SERVER_UNCONFIRMED,
            openShift = null,
            onRefused = onRefused,
        )

    private var amountText by mutableStateOf("")
    private var reasonText by mutableStateOf("")

    /** "Resta: <valor>", o restante que o servidor devolveu para o lançamento. */
    val remaining: String? = row.remaining?.let(MoneyInput::format)?.let(FolioText::remaining)

    override val busy: Boolean get() = write.busy

    override val unconfirmed: Boolean get() = write.unconfirmed

    override val closeOnly: Boolean get() = write.closeOnly

    override val message: String? get() = write.message

    override val canConfirm: Boolean get() = write.canSend

    override val closesOnEscape: Boolean get() = write.closesOnEscape

    /** As entradas estão travadas (em voo, resultado desconhecido ou fechado). */
    val locked: Boolean get() = write.locked

    /** O valor do ajuste (`MoneyField`, `1.234,56`). */
    var amount: String
        get() = amountText
        set(value) = edit(amountText, value) { amountText = it }

    /** O motivo, até 500 caracteres; o excesso é recusado. */
    var reason: String
        get() = reasonText
        set(value) {
            if (value.length <= CORRECTION_REASON_MAX) edit(reasonText, value) { reasonText = it }
        }

    override fun confirm() {
        if (!canConfirm) return
        val amount = checkedAmount() ?: return
        val text = reasonText.trim()
        if (text.isEmpty()) {
            write.message = FolioText.ADJUSTMENT_REASON_REQUIRED
        } else {
            write.send({ adjust(amount, text, it) }) { onAdjusted() }
        }
    }

    fun close() = write.close()

    private fun edit(
        current: String,
        value: String,
        change: (String) -> Unit,
    ) {
        if (locked || value == current) return
        change(value)
        write.edited()
    }

    /** O valor digitado, maior que zero; senão `null`, com o texto em [message]. */
    private fun checkedAmount(): Money? {
        val parsed = MoneyInput.parse(amountText)
        val value = (parsed as? InputResult.Valid)?.value
        write.message =
            when {
                parsed is InputResult.Invalid -> parsed.message
                value == null || value.amount.signum() == 0 -> FolioText.AMOUNT_REQUIRED
                value.amount.signum() < 0 -> FolioText.problem(PaymentProblem.AMOUNT)
                else -> null
            }
        return value?.takeIf { it.amount.signum() > 0 }
    }

    /**
     * Pede o ajuste pelo [approve]. Aprovação cancelada: o servidor respondeu `APPROVAL_REQUIRED` na mesma chave,
     * então nada foi feito; o ajudante destrava as entradas e mantém a intenção.
     */
    private suspend fun adjust(
        amount: Money,
        reason: String,
        intent: Intent,
    ): WriteOutcome<Unit> =
        approve(source.adjust(folioId, row.id, amount, reason, intent))
            ?.let { WriteOutcome.Done(Unit) }
            ?: WriteOutcome.Cancelled(FolioText.ADJUSTMENT_APPROVAL_CANCELLED)

    override fun toString(): String = "AdjustmentState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly)"
}

/**
 * A confirmação "Remover a taxa" da taxa de serviço [row] da conta [folioId]: só diz que o servidor registra o motivo
 * ([text]) e confirma, sem campo; a chamada vai sem corpo. O envio, a `Idempotency-Key` por intenção, a classificação
 * da falha e o fechamento são do ajudante comum `FolioWrite`: o sucesso vai a [onRemoved] com o lançamento devolvido
 * (o motivo dele vai ao aviso); a recusa definitiva, depois do texto do catálogo, a [onRefused] com o `code`.
 */
class ServiceChargeRemovalState(
    private val source: FolioCorrectionSource,
    scope: CoroutineScope,
    private val folioId: String,
    val row: PostingRow,
    private val onRemoved: (PostingResponse) -> Unit,
    onRefused: (code: String) -> Unit,
) : FolioWriteForm {
    private val write =
        FolioWrite(
            scope,
            FolioText.REMOVAL_UNCONFIRMED,
            FolioText.REMOVAL_SERVER_UNCONFIRMED,
            openShift = null,
            onRefused = onRefused,
        )

    /** "O servidor registra o motivo: pedido do cliente." */
    val text: String = FolioText.REMOVAL_TEXT

    override val busy: Boolean get() = write.busy

    override val unconfirmed: Boolean get() = write.unconfirmed

    override val closeOnly: Boolean get() = write.closeOnly

    override val message: String? get() = write.message

    override val canConfirm: Boolean get() = write.canSend

    override val closesOnEscape: Boolean get() = write.closesOnEscape

    override fun confirm() {
        write.send({ WriteOutcome.Done(source.removeServiceCharge(folioId, row.id, it)) }, onRemoved)
    }

    fun close() = write.close()

    override fun toString(): String =
        "ServiceChargeRemovalState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly)"
}
