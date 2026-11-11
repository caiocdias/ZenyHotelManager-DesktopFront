package com.zeny.desktop.design.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhoneInputTest {
    private val message = "Telefone inválido. Use DDD e número, ou + e o código do país."
    private val pattern = Regex("[0-9+() -]*")

    private fun valid(text: String): String {
        val result = PhoneInput.parse(text)
        assertIs<InputResult.Valid<String>>(result, "esperava Valid para '$text'")
        assertTrue(pattern.matches(result.value) && result.value.length <= 30)
        return result.value
    }

    @Test
    fun brazilianMobileAndLandline() {
        listOf("11987654321", "(11) 98765-4321", " 11 98765 4321 ").forEach {
            assertEquals("(11) 98765-4321", valid(it))
        }
        listOf("1134567890", "(11) 3456-7890").forEach {
            assertEquals("(11) 3456-7890", valid(it))
        }
    }

    @Test
    fun internationalPhone() {
        assertEquals("+55 11 98765-4321", valid("+55 11 98765-4321"))
        assertEquals("+1 (415) 555-0100", valid(" +1 (415) 555-0100 "))
    }

    @Test
    fun rejectsInvalid() {
        listOf(
            "123456789",
            "123456789012",
            "+55 11 abc 98765",
            "+" + "1".repeat(30),
            "+()()()()",
            "++5511987654321",
            "+1234567",
            "+1234567890123456",
            "11 9876-abcd",
            "55+11987654321",
        ).forEach {
            assertEquals(InputResult.Invalid(message), PhoneInput.parse(it), "para '$it'")
        }
        assertEquals(InputResult.Empty, PhoneInput.parse(" "))
    }

    @Test
    fun masksWhileTyping() {
        assertEquals("", PhoneInput.mask(""))
        assertEquals("(1", PhoneInput.mask("1"))
        assertEquals("(11", PhoneInput.mask("11"))
        assertEquals("(11) 9", PhoneInput.mask("119"))
        assertEquals("(11) 3456", PhoneInput.mask("113456"))
        assertEquals("(11) 3456-7", PhoneInput.mask("1134567"))
        assertEquals("(11) 3456-7890", PhoneInput.mask("1134567890"))
        assertEquals("(11) 98765-4321", PhoneInput.mask("11987654321"))
        assertEquals("(11) 98765-4321", PhoneInput.mask("(11) 98765-4321"))
        assertEquals("(11) 98765-4321", PhoneInput.mask("119876543219"))
        assertEquals("+55 11 98", PhoneInput.mask("+55 11 98"))
    }
}
