package com.zeny.desktop.app.cashier

import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.CashMovementResponse

/** Os textos do caixa. Os valores vêm do servidor e só são formatados em BRL, nunca calculados. */
object CashierText {
    const val NO_OPERATE = "Seu perfil não opera o caixa."
    const val CASH_POINTS_FORBIDDEN = "Seu perfil não pode listar os pontos de caixa."
    const val OPEN_UNCONFIRMED =
        "Não foi possível confirmar a abertura do turno. Abra de novo sem mudar nada; " +
            "se sair, confira o turno corrente antes de refazer."
    const val OPEN_SERVER_UNCONFIRMED =
        "O servidor não confirmou a abertura do turno. Feche e confira o turno corrente antes de refazer."
    private const val UNKNOWN = "—"

    private val methods =
        mapOf(
            "CASH" to "Dinheiro",
            "DEBIT_CARD" to "Cartão de débito",
            "CREDIT_CARD" to "Cartão de crédito",
            "PIX" to "Pix",
            "BANK_TRANSFER" to "Transferência bancária",
            "OTHER" to "Outro",
        )

    /** O decimal do servidor em BRL (`R$ 1.234,56`); um valor fora do formato do contrato vira "—". */
    fun money(decimal: String): String =
        try {
            MoneyInput.format(Money.parse(decimal))
        } catch (_: IllegalArgumentException) {
            UNKNOWN
        }

    /** A data de negócio do servidor em `dd/MM/aaaa`; fora do formato, "—". */
    fun date(iso: String): String =
        try {
            DateInput.format(BusinessDate.parse(iso))
        } catch (_: IllegalArgumentException) {
            UNKNOWN
        }

    fun movementType(type: CashMovementResponse.Type): String =
        when (type) {
            CashMovementResponse.Type.WITHDRAWAL -> "Sangria"
            CashMovementResponse.Type.SUPPLY -> "Suprimento"
        }

    /** A forma de pagamento (`x-extensible-enum`): a desconhecida aparece como "Outro (VALOR)". */
    fun method(value: String): String = methods[value] ?: "Outro ($value)"
}
