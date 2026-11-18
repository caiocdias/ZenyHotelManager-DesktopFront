package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.PaymentInput
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.core.facade.problems
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
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

/**
 * O diálogo "Receber" da conta [folioId]: a forma (padrão "Dinheiro"), o valor (sugerido com o saldo [suggested] da
 * conta, só quando positivo), "Depósito (sinal)" (padrão desligado), o valor entregue (só no dinheiro), a bandeira e
 * os 4 últimos dígitos (só no cartão) e o NSU e a autorização (fora do dinheiro), todos opcionais. Não há campo de
 * número de cartão nem de CVV. O que está escondido pela forma escolhida nunca vai no pedido. As regras de formato
 * são as de `PaymentInput.problems()`, cada uma com o texto dela em [errors]; com problema, nada vai à rede.
 *
 * O recebimento leva uma `Idempotency-Key` por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido
 * (`classifyWriteFailure`), com as entradas travadas; resposta definitiva gera outra. O `5xx` guardado sob a chave
 * deixa o estado [closeOnly]. O sucesso vai a [onChanged] com a resposta (o troco vem dela); o `FOLIO_CLOSED` mostra a
 * mensagem e chama [onFolioClosed]. O `CASHIER_SHIFT_REQUIRED` mostra a mensagem do catálogo e, com [openShift], o
 * atalho ([shiftRequired], [openShiftAndClose]). [close] cancela o que é daqui. O `toString()` não mostra o digitado.
 */
@Suppress("LongParameterList") // a porta, a conta, a sugestão do servidor e os três avisos para o painel
class PaymentFormState(
    private val source: PaymentSource,
    scope: CoroutineScope,
    private val folioId: String,
    suggested: Money?,
    private val openShift: (() -> Unit)?,
    private val onChanged: (PaymentResponse) -> Unit,
    private val onFolioClosed: () -> Unit,
) : FolioWriteForm {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    private var methodValue by mutableStateOf(PaymentMethod.CASH)
    private var amountText by mutableStateOf(
        suggested?.takeIf { it.amount.signum() > 0 }?.let(MoneyInput::format).orEmpty(),
    )
    private var depositValue by mutableStateOf(false)
    private var tenderedText by mutableStateOf("")
    private var brandText by mutableStateOf("")
    private var last4Text by mutableStateOf("")
    private var nsuText by mutableStateOf("")
    private var authorizationText by mutableStateOf("")

    override var busy by mutableStateOf(false)
        private set

    override var unconfirmed by mutableStateOf(false)
        private set

    override var closeOnly by mutableStateOf(false)
        private set

    override var message by mutableStateOf<String?>(null)
        private set

    /** Os problemas de formato do último envio recusado, cada um com o texto ao lado do campo. */
    var errors by mutableStateOf<Map<PaymentProblem, String>>(emptyMap())
        private set

    private var shiftRefused by mutableStateOf(false)

    // `closed` é estado observável: `canConfirm` o lê, e o Compose só recompõe o que leu estado.
    private var closed by mutableStateOf(false)
    private var intent: Intent? = null

    /** As entradas estão travadas (em voo, resultado desconhecido ou fechado). */
    val locked: Boolean get() = closed || busy || unconfirmed || closeOnly

    /** O servidor pediu um turno aberto e há o atalho "Abrir turno"; sem [openShift], só o texto aparece. */
    val shiftRequired: Boolean get() = shiftRefused && openShift != null

    override val canConfirm: Boolean get() = !closed && !busy && !closeOnly

    override val closesOnEscape: Boolean get() = !busy && (!unconfirmed || closeOnly)

    val showsTendered: Boolean get() = methodValue == PaymentMethod.CASH

    val showsCard: Boolean get() = methodValue == PaymentMethod.DEBIT_CARD || methodValue == PaymentMethod.CREDIT_CARD

    val showsCodes: Boolean get() = methodValue != PaymentMethod.CASH

    var method: PaymentMethod
        get() = methodValue
        set(value) {
            if (!locked) methodValue = value
        }

    /** O valor digitado (`MoneyField`, `1.234,56`). */
    var amount: String
        get() = amountText
        set(value) = edit(PaymentProblem.AMOUNT) { amountText = value }

    var deposit: Boolean
        get() = depositValue
        set(value) {
            if (!locked) depositValue = value
        }

    /** O valor entregue em dinheiro, opcional; o troco é do servidor. */
    var tendered: String
        get() = tenderedText
        set(value) = edit(PaymentProblem.TENDERED) { tenderedText = value }

    var cardBrand: String
        get() = brandText
        set(value) = edit(PaymentProblem.CARD_BRAND) { brandText = value }

    var cardLast4: String
        get() = last4Text
        set(value) = edit(PaymentProblem.CARD_LAST4) { last4Text = value }

    var nsu: String
        get() = nsuText
        set(value) = edit(PaymentProblem.NSU) { nsuText = value }

    var authorizationCode: String
        get() = authorizationText
        set(value) = edit(PaymentProblem.AUTHORIZATION_CODE) { authorizationText = value }

    override fun confirm() {
        if (!canConfirm) return
        val input = typedInput()
        errors = input?.problems().orEmpty().associateWith(FolioText::problem)
        if (input != null && errors.isEmpty()) send(input)
    }

    /** "Abrir turno": quem criou o diálogo o fecha e leva ao caixa. Sem o atalho, nada. */
    fun openShiftAndClose() {
        if (shiftRequired) openShift?.invoke()
    }

    fun close() {
        closed = true
        jobs.cancel()
    }

    private fun send(input: PaymentInput) {
        busy = true
        message = null
        shiftRefused = false
        val current = intent ?: Intent().also { intent = it }
        ownScope.launch {
            try {
                val response = source.receive(folioId, input, current)
                currentCoroutineContext().ensureActive()
                intent = null
                unconfirmed = false
                onChanged(response)
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

    private fun edit(
        problem: PaymentProblem,
        change: () -> Unit,
    ) {
        if (locked) return
        change()
        errors = errors - problem
    }

    /**
     * A entrada com só o que a forma escolhida mostra; `null` sem um valor legível, com o texto em [message] (o mesmo
     * que o `MoneyField` mostra embaixo do campo).
     */
    private fun typedInput(): PaymentInput? {
        val amount = MoneyInput.parse(amountText)
        val tendered = if (showsTendered) MoneyInput.parse(tenderedText) else InputResult.Empty
        message =
            when {
                amount is InputResult.Invalid -> amount.message
                amount is InputResult.Empty -> FolioText.AMOUNT_REQUIRED
                tendered is InputResult.Invalid -> tendered.message
                else -> null
            }
        if (amount !is InputResult.Valid || tendered is InputResult.Invalid) return null
        return PaymentInput(
            method = methodValue,
            amount = amount.value,
            deposit = depositValue,
            tendered = (tendered as? InputResult.Valid)?.value,
            cardBrand = brandText.takeIf { showsCard }?.present(),
            cardLast4 = last4Text.takeIf { showsCard }?.present(),
            nsu = nsuText.takeIf { showsCodes }?.present(),
            authorizationCode = authorizationText.takeIf { showsCodes }?.present(),
        )
    }

    private fun fail(failure: ApiError) {
        val outcome = classifyWriteFailure(failure)
        if (outcome != WriteFailure.DEFINITIVE) {
            // A repetição leva a mesma chave; com a resposta guardada, repetir não adianta: feche e confira.
            unconfirmed = true
            closeOnly = outcome == WriteFailure.UNKNOWN_STORED
            message = if (closeOnly) FolioText.PAYMENT_SERVER_UNCONFIRMED else FolioText.PAYMENT_UNCONFIRMED
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

    override fun toString(): String = "PaymentFormState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly)"
}

private fun String.present(): String? = trim().takeIf { it.isNotEmpty() }
