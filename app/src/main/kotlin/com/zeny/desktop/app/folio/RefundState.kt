package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.core.facade.RefundInput
import com.zeny.desktop.core.facade.problems
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.PaymentResponse
import kotlinx.coroutines.CoroutineScope

private const val FOLIO_CLOSED = "FOLIO_CLOSED"
private const val REASON_MAX = 200

/**
 * O diálogo "Reembolsar" do recebimento [payment] da conta [folioId]: o valor (sugerido com o reembolsável que o
 * servidor devolveu, copiado sem conta), o motivo (obrigatório, até 200 caracteres; a edição que passaria disso é
 * recusada) e, só fora do dinheiro, o NSU e a autorização da devolução. A forma vem da linha: a desconhecida esconde
 * o NSU e a autorização e deixa o servidor decidir. As regras de formato são as de `RefundInput.problems()`, cada uma
 * com o texto dela em [errors]; com problema, nada vai à rede.
 *
 * O reembolso passa pelo [approve] (o coordenador da sessão): a aprovação cancelada deixa "Aprovação cancelada. O
 * reembolso não foi feito." e destrava, com a mesma intenção. A `Idempotency-Key` é por intenção: a mesma `Intent`
 * vale enquanto o resultado é desconhecido (pelo `FolioWrite`), com as entradas travadas; resposta definitiva
 * gera outra. O `5xx` guardado sob a chave deixa o estado [closeOnly]. O sucesso ([done]) vai a [onChanged]; o
 * `FOLIO_CLOSED` mostra a mensagem e chama [onFolioClosed]; o `CASHIER_SHIFT_REQUIRED` mostra a mensagem do catálogo
 * e, com [openShift], o atalho ([offersOpenShift], [openShiftAndClose]). [close] cancela o que é daqui. O
 * `toString()` não mostra o digitado. O envio, a classificação da falha e o fechamento são do ajudante comum
 * `FolioWrite`.
 */
@Suppress("LongParameterList") // a porta, a conta, o pagamento, a aprovação e os três avisos para o painel
class RefundState(
    private val source: PaymentSource,
    scope: CoroutineScope,
    private val folioId: String,
    val payment: PaymentRow,
    private val approve: suspend (ApprovableAction<PaymentResponse>) -> ApiResponse<PaymentResponse>?,
    private val openShift: (() -> Unit)?,
    private val onChanged: () -> Unit,
    onFolioClosed: () -> Unit,
) : FolioWriteForm {
    private val write =
        FolioWrite(scope, FolioText.REFUND_UNCONFIRMED, FolioText.REFUND_SERVER_UNCONFIRMED, openShift) { code ->
            if (code == FOLIO_CLOSED) onFolioClosed()
        }

    private val suggested = payment.refundable?.let(MoneyInput::format)
    private var amountText by mutableStateOf(suggested.orEmpty())
    private var reasonText by mutableStateOf("")
    private var nsuText by mutableStateOf("")
    private var authorizationText by mutableStateOf("")

    override val busy: Boolean get() = write.busy

    override val unconfirmed: Boolean get() = write.unconfirmed

    override val closeOnly: Boolean get() = write.closeOnly

    /** A validação recusada, a aprovação cancelada ou o resultado do último envio que não deu certo. */
    override val message: String? get() = write.message

    /** Os problemas de formato do último envio recusado, cada um com o texto ao lado do campo. */
    var errors by mutableStateOf<Map<PaymentProblem, String>>(emptyMap())
        private set

    /** O reembolso foi feito. */
    var done by mutableStateOf(false)
        private set

    /** As entradas estão travadas (em voo, resultado desconhecido, fechado ou feito). */
    val locked: Boolean get() = write.locked || done

    /** O servidor pediu um turno aberto e há o atalho "Abrir turno"; sem [openShift], só o texto aparece. */
    val offersOpenShift: Boolean get() = write.offersOpenShift

    override val canConfirm: Boolean get() = write.canSend && !done

    override val closesOnEscape: Boolean get() = write.closesOnEscape

    /** NSU e autorização só numa forma reconhecida que não é dinheiro. */
    val showsCodes: Boolean get() = payment.methodCode != null && payment.methodCode != PaymentMethod.CASH

    /** "Reembolsável: <valor>", como o servidor o devolveu; nulo sem o reembolsável na linha. */
    val refundable: String? = suggested?.let(FolioText::refundable)

    /** O valor digitado (`MoneyField`, `1.234,56`). */
    var amount: String
        get() = amountText
        set(value) = edit(PaymentProblem.AMOUNT, amountText, value) { amountText = it }

    /** O motivo, até 200 caracteres; o excesso é recusado. */
    var reason: String
        get() = reasonText
        set(value) {
            if (value.length <= REASON_MAX) edit(PaymentProblem.REASON, reasonText, value) { reasonText = it }
        }

    var nsu: String
        get() = nsuText
        set(value) = edit(PaymentProblem.NSU, nsuText, value) { nsuText = it }

    var authorizationCode: String
        get() = authorizationText
        set(value) = edit(PaymentProblem.AUTHORIZATION_CODE, authorizationText, value) { authorizationText = it }

    override fun confirm() {
        if (!canConfirm) return
        val input = typedInput()
        errors = input?.problems().orEmpty().associateWith(FolioText::problem)
        if (input != null && errors.isEmpty()) {
            write.send({ refund(input, it) }) { finish() }
        }
    }

    /** "Abrir turno": quem criou o diálogo o fecha e leva ao caixa. Sem o atalho, nada. */
    fun openShiftAndClose() {
        if (offersOpenShift) openShift?.invoke()
    }

    fun close() = write.close()

    /** Uma edição destravada que muda o texto é outra intenção e tira o aviso do campo. */
    private fun edit(
        problem: PaymentProblem,
        current: String,
        value: String,
        change: (String) -> Unit,
    ) {
        if (locked || value == current) return
        change(value)
        write.edited()
        errors = errors - problem
    }

    /** A entrada com só o que a forma mostra; `null` sem um valor legível, com o texto em [message]. */
    private fun typedInput(): RefundInput? {
        val amount = MoneyInput.parse(amountText)
        write.message =
            when (amount) {
                is InputResult.Invalid -> amount.message
                is InputResult.Empty -> FolioText.AMOUNT_REQUIRED
                else -> null
            }
        if (amount !is InputResult.Valid) return null
        return RefundInput(
            amount = amount.value,
            reason = reasonText.trim(),
            nsu = nsuText.takeIf { showsCodes }?.present(),
            authorizationCode = authorizationText.takeIf { showsCodes }?.present(),
        )
    }

    /**
     * Pede o reembolso pelo [approve]. Aprovação cancelada: o servidor respondeu `APPROVAL_REQUIRED` na mesma chave,
     * então nada foi feito; o ajudante destrava as entradas (inclusive depois de um resultado desconhecido) e mantém a
     * intenção (a chave não muda).
     */
    private suspend fun refund(
        input: RefundInput,
        intent: Intent,
    ): WriteOutcome<Unit> =
        approve(source.refund(folioId, payment.id, input, intent))
            ?.let { WriteOutcome.Done(Unit) }
            ?: WriteOutcome.Cancelled(FolioText.REFUND_APPROVAL_CANCELLED)

    /** O reembolso foi feito; a intenção já acabou no ajudante. */
    private fun finish() {
        write.message = null
        done = true
        onChanged()
    }

    override fun toString(): String =
        "RefundState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly, done=$done)"
}

private fun String.present(): String? = trim().takeIf { it.isNotEmpty() }
