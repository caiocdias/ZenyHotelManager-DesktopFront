package com.zeny.desktop.core.types

import java.math.BigDecimal

private val MONEY_FORMAT = Regex("""^-?\d+(\.\d{1,2})?$""")

/** Valor em BRL no formato do contrato (`^-?\d+(\.\d{1,2})?$`); a escala recebida é preservada. */
@JvmInline
value class Money(
    val amount: BigDecimal,
) {
    fun toApi(): String = amount.toPlainString()

    companion object {
        fun parse(text: String): Money {
            require(MONEY_FORMAT.matches(text)) { "Valor monetário inválido." }
            return Money(BigDecimal(text))
        }
    }
}
