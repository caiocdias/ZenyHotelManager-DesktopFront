package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val FOLIO_CLOSED = "FOLIO_CLOSED"
private const val SHIFT_REQUIRED = "CASHIER_SHIFT_REQUIRED"
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
 * vale enquanto o resultado é desconhecido (`classifyWriteFailure`), com as entradas travadas; resposta definitiva
 * gera outra. O `5xx` guardado sob a chave deixa o estado [closeOnly]. O sucesso ([done]) vai a [onChanged]; o
 * `FOLIO_CLOSED` mostra a mensagem e chama [onFolioClosed]; o `CASHIER_SHIFT_REQUIRED` mostra a mensagem do catálogo
 * e, com [openShift], o atalho ([offersOpenShift], [openShiftAndClose]). [close] cancela o que é daqui. O
 * `toString()` não mostra o digitado.
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
    private val onFolioClosed: () -> Unit,
) : FolioWriteForm {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    private val suggested = payment.refundable?.let(MoneyInput::format)
    private var amountText by mutableStateOf(suggested.orEmpty())
    private var reasonText by mutableStateOf("")
    private var nsuText by mutableStateOf("")
    private var authorizationText by mutableStateOf("")

    override var busy by mutableStateOf(false)
        private set

    override var unconfirmed by mutableStateOf(false)
        private set

    override var closeOnly by mutableStateOf(false)
        private set

    /** A validação recusada, a aprovação cancelada ou o resultado do último envio que não deu certo. */
    override var message by mutableStateOf<String?>(null)
        private set

    /** Os problemas de formato do último envio recusado, cada um com o texto ao lado do campo. */
    var errors by mutableStateOf<Map<PaymentProblem, String>>(emptyMap())
        private set

    /** O reembolso foi feito. */
    var done by mutableStateOf(false)
        private set

    private var shiftRefused by mutableStateOf(false)

    // `closed` é estado observável: `canConfirm` o lê, e o Compose só recompõe o que leu estado.
    private var closed by mutableStateOf(false)
    private var intent: Intent? = null

    /** As entradas estão travadas (em voo, resultado desconhecido, fechado ou feito). */
    val locked: Boolean get() = closed || busy || unconfirmed || closeOnly || done

    /** O servidor pediu um turno aberto e há o atalho "Abrir turno"; sem [openShift], só o texto aparece. */
    val offersOpenShift: Boolean get() = shiftRefused && openShift != null

    override val canConfirm: Boolean get() = !closed && !busy && !closeOnly && !done

    override val closesOnEscape: Boolean get() = !busy && (!unconfirmed || closeOnly)

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
        if (input != null && errors.isEmpty()) send(input)
    }

    /** "Abrir turno": quem criou o diálogo o fecha e leva ao caixa. Sem o atalho, nada. */
    fun openShiftAndClose() {
        if (offersOpenShift) openShift?.invoke()
    }

    fun close() {
        closed = true
        jobs.cancel()
    }

    private fun send(input: RefundInput) {
        busy = true
        message = null
        shiftRefused = false
        val current = intent ?: Intent().also { intent = it }
        ownScope.launch {
            try {
                val approved = approve(source.refund(folioId, payment.id, input, current))
                currentCoroutineContext().ensureActive()
                if (approved == null) approvalCancelled() else finish()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                fail(e as? ApiError ?: ApiError.Network(e))
            } finally {
                busy = false
            }
        }
    }

    /** Uma edição destravada que muda o texto é outra intenção e tira o aviso do campo. */
    private fun edit(
        problem: PaymentProblem,
        current: String,
        value: String,
        change: (String) -> Unit,
    ) {
        if (locked || value == current) return
        change(value)
        intent = null
        errors = errors - problem
    }

    /** A entrada com só o que a forma mostra; `null` sem um valor legível, com o texto em [message]. */
    private fun typedInput(): RefundInput? {
        val amount = MoneyInput.parse(amountText)
        message =
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

    private fun finish() {
        intent = null
        unconfirmed = false
        message = null
        done = true
        onChanged()
    }

    /**
     * Aprovação cancelada: o servidor respondeu `APPROVAL_REQUIRED` na mesma chave, então nada foi feito. Destrava as
     * entradas (inclusive depois de um resultado desconhecido) e mantém a intenção (a chave não muda).
     */
    private fun approvalCancelled() {
        unconfirmed = false
        message = FolioText.REFUND_APPROVAL_CANCELLED
    }

    private fun fail(failure: ApiError) {
        val outcome = classifyWriteFailure(failure)
        if (outcome != WriteFailure.DEFINITIVE) {
            // A repetição leva a mesma chave; com a resposta guardada, repetir não adianta: feche e confira.
            unconfirmed = true
            closeOnly = outcome == WriteFailure.UNKNOWN_STORED
            message = if (closeOnly) FolioText.REFUND_SERVER_UNCONFIRMED else FolioText.REFUND_UNCONFIRMED
            return
        }
        unconfirmed = false
        intent = null
        message = failure.userMessage
        when ((failure as? ApiError.Problem)?.code) {
            FOLIO_CLOSED -> onFolioClosed()
            SHIFT_REQUIRED -> shiftRefused = true
        }
    }

    override fun toString(): String =
        "RefundState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly, done=$done)"
}

private fun String.present(): String? = trim().takeIf { it.isNotEmpty() }
