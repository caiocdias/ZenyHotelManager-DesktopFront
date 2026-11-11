package com.zeny.desktop.design.input

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zeny.desktop.core.types.BusinessDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** Data de negócio no formato `dd/MM/aaaa`, sem fuso. */
object DateInput {
    private const val INVALID = "Data inválida. Use dd/mm/aaaa."
    private val SHAPE = Regex("""^\d{2}/\d{2}/\d{4}$""")
    private val FORMATTER = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)

    fun parse(text: String): InputResult<BusinessDate> {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> InputResult.Empty
            !SHAPE.matches(trimmed) -> InputResult.Invalid(INVALID)
            else -> parseStrict(trimmed)
        }
    }

    private fun parseStrict(text: String): InputResult<BusinessDate> =
        try {
            InputResult.Valid(BusinessDate(LocalDate.parse(text, FORMATTER)))
        } catch (_: DateTimeParseException) {
            InputResult.Invalid(INVALID)
        }

    fun format(date: BusinessDate): String = FORMATTER.format(date.value)
}

@Composable
fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String? = null,
    modifier: Modifier = Modifier,
) = ParsedTextField(value, onValueChange, FieldSpec(label, error, DateInput::parse), modifier)
