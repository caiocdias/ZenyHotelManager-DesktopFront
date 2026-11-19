package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.facade.PaymentInput
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.facade.PaymentProblem
import com.zeny.desktop.core.facade.problems
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.PaymentResponse
import kotlinx.coroutines.CoroutineScope

private const val FOLIO_CLOSED = "FOLIO_CLOSED"

/**
 * O diálogo "Receber" da conta [folioId]: a forma (padrão "Dinheiro"), o valor (sugerido com o saldo [suggested] da
 * conta, só quando positivo), "Depósito (sinal)" (padrão desligado), o valor entregue (só no dinheiro), a bandeira e
 * os 4 últimos dígitos (só no cartão) e o NSU e a autorização (fora do dinheiro), todos opcionais. Não há campo de
 * número de cartão nem de CVV. O que está escondido pela forma escolhida nunca vai no pedido. As regras de formato
 * são as de `PaymentInput.problems()`, cada uma com o texto dela em [errors]; com problema, nada vai à rede.
 *
 * O recebimento leva uma `Idempotency-Key` por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido
 * (pelo `FolioWrite`), com as entradas travadas; resposta definitiva gera outra. O `5xx` guardado sob a chave
 * deixa o estado [closeOnly]. O sucesso vai a [onChanged] com a resposta (o troco vem dela); o `FOLIO_CLOSED` mostra a
 * mensagem e chama [onFolioClosed]. O `CASHIER_SHIFT_REQUIRED` mostra a mensagem do catálogo e, com [openShift], o
 * atalho ([offersOpenShift], [openShiftAndClose]). [close] cancela o que é daqui. O `toString()` não mostra o digitado.
 * O envio, a classificação da falha e o fechamento são do ajudante comum `FolioWrite`.
 */
@Suppress("LongParameterList") // a porta, a conta, a sugestão do servidor e os três avisos para o painel
class PaymentFormState(
    private val source: PaymentSource,
    scope: CoroutineScope,
    private val folioId: String,
    suggested: Money?,
    private val openShift: (() -> Unit)?,
    private val onChanged: (PaymentResponse) -> Unit,
    onFolioClosed: () -> Unit,
) : FolioWriteForm {
    private val write =
        FolioWrite(scope, FolioText.PAYMENT_UNCONFIRMED, FolioText.PAYMENT_SERVER_UNCONFIRMED, openShift) { code ->
            if (code == FOLIO_CLOSED) onFolioClosed()
        }

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

    override val busy: Boolean get() = write.busy

    override val unconfirmed: Boolean get() = write.unconfirmed

    override val closeOnly: Boolean get() = write.closeOnly

    override val message: String? get() = write.message

    /** Os problemas de formato do último envio recusado, cada um com o texto ao lado do campo. */
    var errors by mutableStateOf<Map<PaymentProblem, String>>(emptyMap())
        private set

    /** As entradas estão travadas (em voo, resultado desconhecido ou fechado). */
    val locked: Boolean get() = write.locked

    /** O servidor pediu um turno aberto e há o atalho "Abrir turno"; sem [openShift], só o texto aparece. */
    val offersOpenShift: Boolean get() = write.offersOpenShift

    override val canConfirm: Boolean get() = write.canSend

    override val closesOnEscape: Boolean get() = write.closesOnEscape

    val showsTendered: Boolean get() = methodValue == PaymentMethod.CASH

    val showsCard: Boolean get() = methodValue == PaymentMethod.DEBIT_CARD || methodValue == PaymentMethod.CREDIT_CARD

    val showsCodes: Boolean get() = methodValue != PaymentMethod.CASH

    var method: PaymentMethod
        get() = methodValue
        set(value) {
            if (locked) return
            methodValue = value
            write.edited()
        }

    /** O valor digitado (`MoneyField`, `1.234,56`). */
    var amount: String
        get() = amountText
        set(value) = edit(PaymentProblem.AMOUNT) { amountText = value }

    var deposit: Boolean
        get() = depositValue
        set(value) {
            if (locked) return
            depositValue = value
            write.edited()
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
        if (input != null && errors.isEmpty()) {
            write.send({ WriteOutcome.Done(source.receive(folioId, input, it)) }, onChanged)
        }
    }

    /** "Abrir turno": quem criou o diálogo o fecha e leva ao caixa. Sem o atalho, nada. */
    fun openShiftAndClose() {
        if (offersOpenShift) openShift?.invoke()
    }

    fun close() = write.close()

    private fun edit(
        problem: PaymentProblem,
        change: () -> Unit,
    ) {
        if (locked) return
        change()
        write.edited()
        errors = errors - problem
    }

    /**
     * A entrada com só o que a forma escolhida mostra; `null` sem um valor legível, com o texto em [message] (o mesmo
     * que o `MoneyField` mostra embaixo do campo).
     */
    private fun typedInput(): PaymentInput? {
        val amount = MoneyInput.parse(amountText)
        val tendered = if (showsTendered) MoneyInput.parse(tenderedText) else InputResult.Empty
        write.message =
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

    override fun toString(): String = "PaymentFormState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly)"
}

private fun String.present(): String? = trim().takeIf { it.isNotEmpty() }
