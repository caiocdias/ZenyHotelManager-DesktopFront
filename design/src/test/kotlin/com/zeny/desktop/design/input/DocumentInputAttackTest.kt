package com.zeny.desktop.design.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocumentInputAttackTest {
    private val weights = listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)

    private fun checkDigit(
        base: String,
        w: List<Int>,
    ): Char {
        val remainder = base.toList().zip(w).sumOf { (c, p) -> (c.code - '0'.code) * p } % 11
        return if (remainder < 2) '0' else ('0' + (11 - remainder))
    }

    private fun cnpj(base: String): String {
        val first = checkDigit(base, weights)
        return base + first + checkDigit(base + first, listOf(6) + weights)
    }

    private fun cpf(base: String): String {
        val first = checkDigit(base, (10 downTo 2).toList())
        return base + first + checkDigit(base + first, (11 downTo 2).toList())
    }

    private fun assertInvalid(
        text: String,
        message: String? = null,
    ) {
        val result = DocumentInput.parse(text)
        assertIs<InputResult.Invalid>(result, "esperava Invalid para '$text' mas veio $result")
        if (message != null) assertEquals(message, result.message, "para '$text'")
    }

    @Test
    fun cnpjLettersInCheckDigitPositionsAreRejected() {
        val base = cnpj("A1B2C3D4E5F6")
        assertInvalid(base.dropLast(1) + "A", "CNPJ inválido.")
        assertInvalid(base.dropLast(2) + "AB", "CNPJ inválido.")
        assertInvalid(base.take(12) + "X" + base.last(), "CNPJ inválido.")
    }

    @Test
    fun cnpjWithAccentsOrCedillaIsRejected() {
        val base = cnpj("A1B2C3D4E5F6")
        listOf("Ç", "É", "Á", "Ñ").forEach { accent ->
            assertInvalid(accent + base.drop(1), "CNPJ inválido.")
            assertInvalid(base.take(5) + accent + base.drop(6), "CNPJ inválido.")
        }
    }

    @Test
    fun cnpjWithPunctuationAndLowercaseIsNormalized() {
        val value = cnpj("A1B2C3D4E5F6")
        val t = value.lowercase()
        val typed = " ${t.take(2)}.${t.substring(2, 5)}.${t.substring(5, 8)}/${t.substring(8, 12)}-${t.substring(12)} "
        val result = DocumentInput.parse(typed)
        assertIs<InputResult.Valid<Document>>(result)
        assertEquals(value, result.value.value)
        assertEquals(DocumentKind.CNPJ, result.value.kind)
    }

    @Test
    fun cpfWithLettersIsRejected() {
        val base = cpf("123456789")
        assertInvalid("A" + base.drop(1), "CPF inválido.")
        assertInvalid(base.dropLast(1) + "X", "CPF inválido.")
        assertInvalid("ABCDEFGHIJK", "CPF inválido.")
    }

    private fun singleCharacterChanges(doc: String): List<String> =
        doc.indices.flatMap { i ->
            (('0'..'9') + ('A'..'Z')).filter { it != doc[i] }.map { doc.replaceRange(i, i + 1, it.toString()) }
        }

    @Test
    fun anySingleCharacterChangeNeverAcceptsAWrongCheckDigit() {
        val documents = listOf(cpf("123456789"), cnpj("112223330001"), cnpj("A1B2C3D4E5F6"), "12ABC34501DE35")
        documents.flatMap(::singleCharacterChanges).forEach { changed ->
            val result = DocumentInput.parse(changed)
            if (result is InputResult.Valid<Document>) {
                val v = result.value.value
                val recomputed = if (v.length == 11) cpf(v.take(9)) else cnpj(v.take(12))
                assertEquals(recomputed, v, "aceitou '$changed' com DV errado")
            }
        }
    }

    @Test
    fun repeatedCharactersAreRejected() {
        listOf("00000000000", "99999999999", "11111111111").forEach { assertInvalid(it, "CPF inválido.") }
        listOf("00000000000000", "11111111111111", "AAAAAAAAAAAAAA", "00.000.000/0000-00")
            .forEach { assertInvalid(it, "CNPJ inválido.") }
    }

    @Test
    fun nonAsciiDigitsAreRejected() {
        assertInvalid("١٢٣٤٥٦٧٨٩٠١", "CPF inválido.")
        assertInvalid("１２３４５６７８９０１", "CPF inválido.")
        assertInvalid("１１２２２３３３００００１１", "CNPJ inválido.")
    }

    // uppercase() de ß, ı e ſ dá SS, I e S: letras não ASCII não podem virar caracteres válidos.
    @Test
    fun eszettIsNotAcceptedAsCnpjCharacters() {
        assertInvalid("ß" + cnpj("SSBC34501DE3").drop(2), "CNPJ inválido.")
    }

    @Test
    fun dotlessIIsNotAcceptedAsCnpjCharacter() {
        assertInvalid("1ı" + cnpj("1IBC34501DE3").drop(2), "CNPJ inválido.")
    }

    @Test
    fun longSIsNotAcceptedAsCnpjCharacter() {
        assertInvalid("1ſ" + cnpj("1SBC34501DE3").drop(2), "CNPJ inválido.")
    }

    @Test
    fun invalidMessagesAndToStringNeverContainTheNumber() {
        val digits = cpf("987654321")
        val alpha = cnpj("A1B2C3D4E5F6")
        val inputs =
            listOf(
                digits,
                digits.dropLast(1) + "9",
                alpha,
                alpha.dropLast(1) + "A",
                alpha + "9",
                "ABC123",
                "111.111.111-11",
            )
        inputs.forEach { text ->
            val result = DocumentInput.parse(text)
            val rendered =
                when (result) {
                    is InputResult.Invalid -> result.message
                    is InputResult.Valid<Document> -> result.value.toString() + result.toString()
                    else -> result.toString()
                }
            val raw = text.filter(Char::isLetterOrDigit)
            assertFalse(rendered.contains(raw), "vazou '$raw' em '$rendered'")
            raw.windowed(4).forEach { assertFalse(rendered.contains(it), "vazou trecho '$it' em '$rendered'") }
        }
    }

    @Test
    fun documentEqualsAndHashCodeUseKindAndValue() {
        val a = Document(DocumentKind.CNPJ, "12ABC34501DE35")
        assertEquals(a, Document(DocumentKind.CNPJ, "12ABC34501DE35"))
        assertEquals(a.hashCode(), Document(DocumentKind.CNPJ, "12ABC34501DE35").hashCode())
        assertFalse(a == Document(DocumentKind.CPF, "12ABC34501DE35"))
        assertFalse(a == Document(DocumentKind.CNPJ, "12ABC34501DE36"))
        val none: Any? = null
        assertFalse(a.equals(none))
        assertFalse(a.equals("12ABC34501DE35"))
        assertEquals("Document(CNPJ, ***)", a.toString())
    }

    @Test
    fun partialMasksForEveryLengthFromZeroToFifteen() {
        val digits = "123456789012345"
        val expected =
            listOf(
                "",
                "1",
                "12",
                "123",
                "123.4",
                "123.45",
                "123.456",
                "123.456.7",
                "123.456.78",
                "123.456.789",
                "123.456.789-0",
                "123.456.789-01",
                "12.345.678/9012",
                "12.345.678/9012-3",
                "12.345.678/9012-34",
                "12.345.678/9012-34",
            )
        (0..15).forEach { n -> assertEquals(expected[n], DocumentInput.mask(digits.take(n)), "com $n caracteres") }
    }

    @Test
    fun maskIsIdempotentAndBounded() {
        listOf("", "1", "12abc", "12ABC34501DE35", "12.abc.345/01de-35999", "ç1é2", "  1 2 3 ", "111.222.333-44")
            .forEach {
                val once = DocumentInput.mask(it)
                assertEquals(once, DocumentInput.mask(once), "não idempotente para '$it'")
                assertTrue(once.length <= 18)
                assertTrue(once.all { c -> c in '0'..'9' || c in 'A'..'Z' || c in ".-/" }, "fora do conjunto: '$once'")
            }
    }

    @Test
    fun maskDropsNonAsciiLettersInsteadOfKeepingThem() {
        assertEquals("12", DocumentInput.mask("1Ç2"))
        assertEquals("12", DocumentInput.mask("١1٢2"))
    }
}
