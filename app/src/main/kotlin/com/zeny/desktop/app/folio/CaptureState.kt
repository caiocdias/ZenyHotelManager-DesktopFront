package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.facade.CaptureInput
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.core.facade.problems
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import kotlinx.coroutines.CoroutineScope

/**
 * O diálogo "Capturar" da caução [row] da conta [folioId]: o valor (sugerido com o capturável do servidor, copiado sem
 * conta) e, opcionais, o NSU e a autorização da captura. [capturable] mostra o capturável mais recente da caução
 * ([latest], o da tabela relida; sem ele, nada), não o da abertura. As regras de formato são as de
 * `CaptureInput.problems()`, cada uma com o texto dela em [errors]; com problema, nada vai à rede.
 *
 * O envio, a `Idempotency-Key` por intenção, a classificação da falha e o fechamento são do ajudante comum
 * `FolioWrite`: o sucesso vai a [onCaptured]; a recusa definitiva, depois do texto do catálogo, a [onRefused] com o
 * `code`. Com o resultado desconhecido, a repetição reenvia a mesma entrada. A captura exige turno: o
 * `CASHIER_SHIFT_REQUIRED` mostra o texto do catálogo e, com [openShift], o atalho ([offersOpenShift],
 * [openShiftAndClose]). A resposta do servidor nunca fica aqui (ela imprime o cartão no `toString`), e o `toString()`
 * daqui não mostra o digitado.
 */
@Suppress("LongParameterList") // a porta, a conta, a caução, o atalho do turno e os dois avisos para o holder
class CaptureState(
    private val source: PaymentSource,
    scope: CoroutineScope,
    private val folioId: String,
    val row: PreAuthorizationRow,
    private val openShift: (() -> Unit)?,
    private val onCaptured: () -> Unit,
    onRefused: (code: String) -> Unit,
    private val latest: () -> Money? = { row.capturable },
) : FolioWriteForm {
    private val write =
        FolioWrite(scope, FolioText.CAPTURE_UNCONFIRMED, FolioText.CAPTURE_SERVER_UNCONFIRMED, openShift, onRefused)

    private var amountText by mutableStateOf(row.capturable?.let(MoneyInput::format).orEmpty())
    private var nsuText by mutableStateOf("")
    private var authorizationText by mutableStateOf("")

    /** A entrada enviada com a intenção em curso. */
    private var sent: CaptureInput? = null

    /** "Capturável: <valor>", o último que o servidor devolveu para esta caução. */
    val capturable: String? get() = latest()?.let(MoneyInput::format)?.let(FolioText::capturable)

    override val busy: Boolean get() = write.busy

    override val unconfirmed: Boolean get() = write.unconfirmed

    override val closeOnly: Boolean get() = write.closeOnly

    override val message: String? get() = write.message

    override val canConfirm: Boolean get() = write.canSend

    override val closesOnEscape: Boolean get() = write.closesOnEscape

    /** Os problemas de formato do último envio recusado, cada um com o texto ao lado do campo. */
    var errors by mutableStateOf<Map<PaymentProblem, String>>(emptyMap())
        private set

    /** As entradas estão travadas (em voo, resultado desconhecido ou fechado). */
    val locked: Boolean get() = write.locked

    /** O servidor pediu um turno aberto e há o atalho "Abrir turno"; sem [openShift], só o texto aparece. */
    val offersOpenShift: Boolean get() = write.offersOpenShift

    /** O valor da captura (`MoneyField`, `1.234,56`). */
    var amount: String
        get() = amountText
        set(value) = edit(PaymentProblem.AMOUNT) { amountText = value }

    var nsu: String
        get() = nsuText
        set(value) = edit(PaymentProblem.NSU) { nsuText = value }

    var authorizationCode: String
        get() = authorizationText
        set(value) = edit(PaymentProblem.AUTHORIZATION_CODE) { authorizationText = value }

    /** Com o resultado desconhecido, a repetição reenvia a entrada da intenção em curso sem conferi-la de novo. */
    override fun confirm() {
        if (!canConfirm) return
        val input = sent.takeIf { write.unconfirmed } ?: checkedInput() ?: return
        sent = input
        write.send({
            source.capturePreAuthorization(folioId, row.id, input, it)
            WriteOutcome.Done(Unit)
        }) { onCaptured() }
    }

    /** "Abrir turno": quem criou o diálogo o fecha e leva ao caixa. Sem o atalho, nada. */
    fun openShiftAndClose() {
        if (offersOpenShift) openShift?.invoke()
    }

    fun close() = write.close()

    /** A entrada digitada, sem problema de formato; senão `null`, com os textos nos campos. */
    private fun checkedInput(): CaptureInput? {
        val input = typedInput()
        errors = input?.problems().orEmpty().associateWith(FolioText::problem)
        return input?.takeIf { errors.isEmpty() }
    }

    private fun edit(
        problem: PaymentProblem,
        change: () -> Unit,
    ) {
        if (locked) return
        change()
        write.edited()
        errors = errors - problem
    }

    /** A entrada digitada; `null` sem um valor legível, com o texto do valor em [message]. */
    private fun typedInput(): CaptureInput? {
        val amount = MoneyInput.parse(amountText)
        write.message =
            when (amount) {
                is InputResult.Invalid -> amount.message
                is InputResult.Empty -> FolioText.AMOUNT_REQUIRED
                else -> null
            }
        if (amount !is InputResult.Valid) return null
        return CaptureInput(
            amount = amount.value,
            nsu = nsuText.present(),
            authorizationCode = authorizationText.present(),
        )
    }

    override fun toString(): String = "CaptureState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly)"
}

private fun String.present(): String? = trim().takeIf { it.isNotEmpty() }
