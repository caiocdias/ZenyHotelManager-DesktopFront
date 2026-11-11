package com.zeny.desktop.design.input

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class DocumentKind { CPF, CNPJ }

/** CPF ou CNPJ sem pontuação e em maiúsculas. O número nunca aparece em [toString]. */
class Document(
    val kind: DocumentKind,
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is Document && other.kind == kind && other.value == value

    override fun hashCode(): Int = 31 * kind.hashCode() + value.hashCode()

    override fun toString(): String = "Document($kind, ***)"
}

/** CPF e CNPJ (numérico ou alfanumérico), com dígitos verificadores conferidos. */
@Suppress("MagicNumber") // Pesos do módulo 11 e tamanhos fixos dos documentos.
object DocumentInput {
    private const val CPF_LENGTH = 11
    private const val CNPJ_LENGTH = 14
    private const val MODULUS = 11
    private const val ASCII_ZERO = 48
    private const val INVALID_CPF = "CPF inválido."
    private const val INVALID_CNPJ = "CNPJ inválido."
    private const val INVALID_LENGTH = "Documento deve ter 11 (CPF) ou 14 (CNPJ) caracteres."
    private val PUNCTUATION = Regex("""[.\-/\s]""")
    private val CNPJ_SHAPE = Regex("""^[0-9A-Z]{12}\d{2}$""")
    private val CPF_SHAPE = Regex("""^\d{11}$""")
    private val CNPJ_WEIGHTS = listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)

    fun parse(text: String): InputResult<Document> {
        if (text.isBlank()) return InputResult.Empty
        val raw = asciiUpper(text.replace(PUNCTUATION, ""))
        return when (raw.length) {
            CPF_LENGTH -> {
                if (isValidCpf(raw)) {
                    InputResult.Valid(Document(DocumentKind.CPF, raw))
                } else {
                    InputResult.Invalid(INVALID_CPF)
                }
            }

            CNPJ_LENGTH -> {
                if (isValidCnpj(raw)) {
                    InputResult.Valid(Document(DocumentKind.CNPJ, raw))
                } else {
                    InputResult.Invalid(INVALID_CNPJ)
                }
            }

            else -> {
                // Caractere fora de [0-9A-Za-z] não é "tamanho errado": o documento tem lixo, não falta dígito.
                val garbage = raw.any { it !in '0'..'9' && it !in 'A'..'Z' }
                InputResult.Invalid(if (garbage) INVALID_CNPJ else INVALID_LENGTH)
            }
        }
    }

    /** Formata enquanto se digita: CPF `000.000.000-00` (até 11 dígitos) ou CNPJ `AA.AAA.AAA/AAAA-00`. */
    fun mask(raw: String): String {
        val chars = asciiUpper(raw.replace(PUNCTUATION, "")).filter { it in '0'..'9' || it in 'A'..'Z' }
        val cnpj = chars.length > CPF_LENGTH || chars.any { it in 'A'..'Z' }
        return if (cnpj) maskWith(chars.take(CNPJ_LENGTH), CNPJ_SEPARATORS) else maskWith(chars, CPF_SEPARATORS)
    }

    // Separador inserido antes do caractere de cada posição.
    private val CPF_SEPARATORS = mapOf(3 to '.', 6 to '.', 9 to '-')
    private val CNPJ_SEPARATORS = mapOf(2 to '.', 5 to '.', 8 to '/', 12 to '-')

    private fun maskWith(
        chars: String,
        separators: Map<Int, Char>,
    ): String =
        buildString {
            chars.forEachIndexed { index, char ->
                separators[index]?.let { append(it) }
                append(char)
            }
        }

    // Só a-z vira maiúscula: uppercase() de Unicode transformaria ß em SS, ı em I e ſ em S.
    private fun asciiUpper(text: String): String =
        text
            .map {
                if (it in
                    'a'..'z'
                ) {
                    it - ('a' - 'A')
                } else {
                    it
                }
            }.joinToString("")

    private fun isValidCpf(raw: String): Boolean {
        if (!CPF_SHAPE.matches(raw) || raw.all { it == raw[0] }) return false
        val first = checkDigit(raw.take(9), (10 downTo 2).toList())
        val second = checkDigit(raw.take(9) + first, (11 downTo 2).toList())
        return raw[9] == first && raw[10] == second
    }

    private fun isValidCnpj(raw: String): Boolean {
        if (!CNPJ_SHAPE.matches(raw) || raw.all { it == raw[0] }) return false
        val first = checkDigit(raw.take(12), CNPJ_WEIGHTS)
        val second = checkDigit(raw.take(12) + first, listOf(6) + CNPJ_WEIGHTS)
        return raw[12] == first && raw[13] == second
    }

    // Valor do caractere = código ASCII - 48 (dígitos 0-9, letras 17-42).
    private fun checkDigit(
        base: String,
        weights: List<Int>,
    ): Char {
        val remainder = base.toList().zip(weights).sumOf { (c, w) -> (c.code - ASCII_ZERO) * w } % MODULUS
        return if (remainder < 2) '0' else '0' + (MODULUS - remainder)
    }
}

@Composable
fun DocumentField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String? = null,
    modifier: Modifier = Modifier,
) = ParsedTextField(
    DocumentInput.mask(value),
    { onValueChange(DocumentInput.mask(it)) },
    FieldSpec(label, error, DocumentInput::parse),
    modifier,
)
