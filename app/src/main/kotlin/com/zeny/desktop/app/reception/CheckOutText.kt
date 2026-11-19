package com.zeny.desktop.app.reception

import com.zeny.desktop.core.facade.LateCheckOutQuote
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.MoneyInput

/** Os textos do assistente de check-out. Os valores vêm do servidor e só são formatados em BRL, nunca calculados. */
object CheckOutText {
    const val CHECK_FAILED = "Não foi possível conferir as contas e as cauções."
    const val UNREADABLE = "Seu perfil não pode ler as contas desta reserva."
    const val PRE_AUTHORIZATION_PENDING = "Caução a capturar ou liberar"
    const val ASK_RECEIVER = "Peça a quem pode receber pagamentos para capturar ou liberar a caução."
    const val CAPTURE_FIRST = "Capture ou libere a caução antes de concluir."
    const val DUE_RECEIVED = "Recebido. Conclua o check-out."
    const val NOTHING_PENDING = "Nada pendente nas contas e nas cauções."
    const val CHARGE_LATE = "Cobrar o late check-out"
    const val DONE = "Check-out concluído."
    const val PAID_NOT_CHECKED_OUT = "O pagamento foi registrado, mas o check-out não foi concluído."
    const val UNCONFIRMED =
        "Não foi possível confirmar o check-out. Conclua de novo sem mudar nada; " +
            "se fechar, confira a reserva antes de tentar de novo."
    const val SERVER_UNCONFIRMED =
        "O servidor não confirmou o check-out. Feche e confira a reserva antes de tentar de novo."

    /** O saldo da conta: devedora, "Falta receber"; credora, o valor a devolver (o saldo sem o sinal). */
    fun balance(balance: Money): String =
        if (balance.amount.signum() > 0) {
            "Falta receber ${MoneyInput.format(balance)}"
        } else {
            "Conta credora: ${MoneyInput.format(Money(balance.amount.abs()))} a devolver"
        }

    /** O botão do recebimento do devido de uma conta. */
    fun receive(due: Money): String = "Receber ${MoneyInput.format(due)}"

    /** O total devido que o servidor mandou. */
    fun total(total: Money): String = "Total devido: ${MoneyInput.format(total)}"

    /** O late check-out oferecido pela cotação. */
    fun lateOffer(quote: LateCheckOutQuote): String =
        "Late check-out até ${quote.limitTime}: ${MoneyInput.format(quote.fee)}"

    /** A linha do late que o check-out vai lançar, acima do total. */
    fun lateLine(fee: Money): String = "Late check-out: ${MoneyInput.format(fee)}"
}
