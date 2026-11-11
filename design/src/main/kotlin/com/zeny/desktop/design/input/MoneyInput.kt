package com.zeny.desktop.design.input

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zeny.desktop.core.types.Money
import java.math.BigDecimal
import java.math.RoundingMode

/** Dinheiro no formato brasileiro (`1.234,56`), sempre em [BigDecimal] com escala 2. */
object MoneyInput {
    private const val SCALE = 2
    private const val INVALID = "Valor inválido. Use o formato 1.234,56."
    private const val TOO_MANY_DECIMALS = "Use no máximo 2 casas decimais."
    private val PATTERN =
        Regex("""^(-)?[\s\u00A0\u202F]*(?:R\$[\s\u00A0\u202F]*)?(-)?(\d+|\d{1,3}(?:\.\d{3})+)(?:,(\d+))?$""")

    fun parse(text: String): InputResult<Money> {
        val trimmed = text.trim()
        val match = PATTERN.matchEntire(trimmed)
        return when {
            trimmed.isEmpty() -> InputResult.Empty
            match == null -> InputResult.Invalid(INVALID)
            else -> parseMatch(match.groupValues)
        }
    }

    // groupValues: [0] texto, [1] sinal antes do R$, [2] sinal depois, [3] inteiro, [4] decimais.
    private fun parseMatch(groups: List<String>): InputResult<Money> {
        val negative = groups[1].isNotEmpty() || groups[2].isNotEmpty()
        val decimals = groups[4]
        return when {
            groups[1].isNotEmpty() && groups[2].isNotEmpty() -> {
                InputResult.Invalid(INVALID)
            }

            decimals.length > SCALE -> {
                InputResult.Invalid(TOO_MANY_DECIMALS)
            }

            else -> {
                val sign = if (negative) "-" else ""
                val fraction = if (decimals.isEmpty()) "" else ".$decimals"
                val amount = BigDecimal(sign + groups[3].replace(".", "") + fraction).setScale(SCALE)
                InputResult.Valid(Money(amount))
            }
        }
    }

    fun format(money: Money): String {
        val scaled = money.amount.setScale(SCALE, RoundingMode.HALF_UP)
        val digits = scaled.abs().toPlainString()
        val integer = digits.substringBefore('.')
        val fraction = digits.substringAfter('.')
        val grouped =
            integer
                .reversed()
                .chunked(3)
                .joinToString(".")
                .reversed()
        val sign = if (scaled.signum() < 0) "-" else ""
        return "${sign}R$ $grouped,$fraction"
    }
}

@Composable
fun MoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String? = null,
    modifier: Modifier = Modifier,
) = ParsedTextField(value, onValueChange, label, error, modifier, MoneyInput::parse)
