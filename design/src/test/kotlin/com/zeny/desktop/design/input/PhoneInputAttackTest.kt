package com.zeny.desktop.design.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhoneInputAttackTest {
    private val message = "Telefone inválido. Use DDD e número, ou + e o código do país."
    private val pattern = Regex("[0-9+() -]*")

    private fun assertInvalid(text: String) {
        val result = PhoneInput.parse(text)
        assertIs<InputResult.Invalid>(result, "esperava Invalid para '$text' mas veio $result")
        assertEquals(message, result.message)
    }

    private fun assertValid(text: String): String {
        val result = PhoneInput.parse(text)
        assertIs<InputResult.Valid<String>>(result, "esperava Valid para '$text' mas veio $result")
        return result.value
    }

    @Test
    fun plusInTheMiddleOrDoubledIsRejected() {
        listOf("+55 11+98765432", "5511+987654321", "++5511987654321", "+ +5511987654321", "11 +987654321").forEach {
            assertInvalid(it)
        }
    }

    @Test
    fun onlyPunctuationIsRejected() {
        listOf("+", "()", "- - -", "+()-() -", "(  )").forEach { assertInvalid(it) }
        assertEquals(InputResult.Empty, PhoneInput.parse("   "))
        assertEquals(InputResult.Empty, PhoneInput.parse(""))
    }

    @Test
    fun internationalDigitBoundaries() {
        assertInvalid("+1234567")
        assertValid("+12345678")
        assertValid("+123456789012345")
        assertInvalid("+1234567890123456")
    }

    @Test
    fun internationalLengthBoundaries() {
        val thirty = "+" + "2".repeat(14) + "-".repeat(15)
        assertEquals(30, thirty.length)
        assertEquals(thirty, assertValid(thirty))
        assertInvalid(thirty + "-")
    }

    @Test
    fun brazilianDigitBoundaries() {
        assertInvalid("119876543")
        assertInvalid("123456789012")
        assertValid("1134567890")
        assertValid("11987654321")
    }

    @Test
    fun doubledSpacesAndPunctuationAreNormalizedForBrazilianNumbers() {
        assertEquals("(11) 98765-4321", assertValid("(11)   98765  -  4321"))
        assertEquals("(11) 3456-7890", assertValid("11  3456   7890"))
    }

    @Test
    fun lettersAndSymbolsAreRejected() {
        listOf(
            "11 98765-432a",
            "(11) 9876x-4321",
            "+55 11 9876543A",
            "11.98765.4321",
            "11/98765-4321",
            "tel 11987654321",
        ).forEach { assertInvalid(it) }
    }

    @Test
    fun nonAsciiDigitsAndUnicodeAreRejected() {
        listOf(
            "١١٩٨٧٦٥٤٣٢١",
            "+٥٥١١٩٨٧٦٥٤٣٢١",
            "１１９８７６５４３２１",
            "+５５１１９８７６５４３２１",
            "11 98765-4321​",
            "11−987654321",
        ).forEach { assertInvalid(it) }
    }

    @Test
    fun embeddedNewlineOrTabIsRejected() {
        listOf("11\n987654321", "11\t987654321", "+55 11\n987654321", "+5511987654321\n+5511987654321")
            .forEach { assertInvalid(it) }
    }

    @Test
    fun validResultAlwaysMatchesPatternAndLimit() {
        val samples =
            listOf(
                "11987654321",
                "(11) 3456-7890",
                "+55 11 98765-4321",
                "+1 (415) 555-0100",
                " +44 20 7946 0958 ",
                " +5511987654321 ",
                "(((11)))98765-4321",
                "--11 98765-4321--",
                "+(55)(11)987654321",
            )
        samples.forEach { text ->
            val value = assertValid(text)
            assertTrue(pattern.matches(value), "valor '$value' foge do pattern")
            assertTrue(value.length <= 30, "valor '$value' passa de 30")
        }
    }

    // Até 10 dígitos o corte é 4-4 (fixo); no 11º dígito o número reflui para 5-4 (celular). Comportamento aceito.
    @Test
    fun maskPartialAndBounded() {
        val digits = "119876543210123"
        val expected =
            listOf(
                "",
                "(1",
                "(11",
                "(11) 9",
                "(11) 98",
                "(11) 987",
                "(11) 9876",
                "(11) 9876-5",
                "(11) 9876-54",
                "(11) 9876-543",
                "(11) 9876-5432",
                "(11) 98765-4321",
                "(11) 98765-4321",
            )
        (0..12).forEach { n -> assertEquals(expected[n], PhoneInput.mask(digits.take(n)), "com $n dígitos") }
    }

    @Test
    fun maskLeavesInternationalAsIsAndIsIdempotentForBrazilian() {
        assertEquals("+55 11 98765-4321", PhoneInput.mask("+55 11 98765-4321"))
        assertEquals("+", PhoneInput.mask("+"))
        listOf("1", "11", "11987", "119876543", "1198765432", "11987654321").forEach {
            val once = PhoneInput.mask(it)
            assertEquals(once, PhoneInput.mask(once))
            assertFalse(once.contains(Regex("[^0-9() -]")))
        }
    }
}
