package com.zeny.desktop.design.input

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Telefone brasileiro (DDD + número) ou internacional (`+` e código do país), sempre em `[0-9+() -]*`. */
@Suppress("MagicNumber") // Tamanhos fixos de telefone brasileiro e E.164.
object PhoneInput {
    private const val MAX_LENGTH = 30
    private const val MIN_INTERNATIONAL_DIGITS = 8
    private const val MAX_INTERNATIONAL_DIGITS = 15
    private const val LANDLINE_DIGITS = 10
    private const val MOBILE_DIGITS = 11
    private const val INVALID = "Telefone inválido. Use DDD e número, ou + e o código do país."
    private val INTERNATIONAL = Regex("""^\+[0-9() -]*$""")
    private val BRAZILIAN = Regex("""^[0-9() -]*$""")

    fun parse(text: String): InputResult<String> {
        val trimmed = text.trim()
        val digits = trimmed.count { it in '0'..'9' }
        return when {
            trimmed.isEmpty() -> {
                InputResult.Empty
            }

            trimmed.startsWith("+") -> {
                val ok =
                    INTERNATIONAL.matches(trimmed) &&
                        trimmed.length <= MAX_LENGTH &&
                        digits in MIN_INTERNATIONAL_DIGITS..MAX_INTERNATIONAL_DIGITS
                if (ok) InputResult.Valid(trimmed) else InputResult.Invalid(INVALID)
            }

            BRAZILIAN.matches(trimmed) && digits in LANDLINE_DIGITS..MOBILE_DIGITS -> {
                InputResult.Valid(mask(trimmed))
            }

            else -> {
                InputResult.Invalid(INVALID)
            }
        }
    }

    /** Formata o telefone brasileiro enquanto se digita; texto com `+` fica como está. */
    fun mask(raw: String): String {
        if (raw.trimStart().startsWith("+")) return raw
        val digits = raw.filter { it in '0'..'9' }.take(MOBILE_DIGITS)
        val area = digits.take(2)
        val rest = digits.drop(2)
        val split = if (digits.length == MOBILE_DIGITS) 5 else 4
        return when {
            digits.isEmpty() -> ""
            digits.length <= 2 -> "($area"
            rest.length <= split -> "($area) $rest"
            else -> "($area) ${rest.take(split)}-${rest.drop(split)}"
        }
    }
}

@Composable
fun PhoneField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String? = null,
    modifier: Modifier = Modifier,
) = ParsedTextField(
    value,
    onValueChange,
    FieldSpec(label, error, PhoneInput::parse, PhoneInput::mask, { it in '0'..'9' }),
    modifier,
)
