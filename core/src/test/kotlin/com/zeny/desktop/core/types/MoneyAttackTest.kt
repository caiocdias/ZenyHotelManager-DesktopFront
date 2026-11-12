package com.zeny.desktop.core.types

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MoneyAttackTest {
    private fun assertRejectedWithoutEcho(text: String) {
        val error = assertFailsWith<IllegalArgumentException>("aceitou entrada inválida") { Money.parse(text) }
        val message = error.message.orEmpty()
        if (text.isNotBlank()) assertFalse(message.contains(text.trim()), "mensagem ecoa a entrada")
    }

    @Test
    fun rejectsNonAsciiDigits() {
        assertRejectedWithoutEcho("١٠.٥٠")
        assertRejectedWithoutEcho("１０.50")
    }

    @Test
    fun rejectsInternalAndTrailingWhitespace() {
        listOf(
            "10 .50",
            "10. 50",
            "1 0",
            "10.50 ",
            "10.50\n",
            "\n10.50",
            "10.50\r\n",
            "10.50\u0000",
            "\t10.50",
            "10.50 ",
        ).forEach { assertRejectedWithoutEcho(it) }
    }

    @Test
    fun rejectsMultilineInjection() {
        assertRejectedWithoutEcho("10.50\n20.00")
    }

    @Test
    fun rejectsMoreThanTwoDecimalsEvenWithTrailingZeros() {
        assertRejectedWithoutEcho("1.000")
        assertRejectedWithoutEcho("1.500")
    }

    @Test
    fun rejectsLoneMinusAndOddTokens() {
        listOf("-", "-.5", "1.2.3", "0x10", "1_000", "Infinity", "-Infinity", "10.5e1", "1E2", "--", "-+1")
            .forEach { assertRejectedWithoutEcho(it) }
    }

    @Test
    fun negativeZeroIsAcceptedAndPreservesScale() {
        assertEquals("0", Money.parse("-0").toApi())
        assertEquals("0.00", Money.parse("-0.00").toApi())
        assertEquals(0, Money.parse("-0.00").amount.signum())
    }

    @Test
    fun preservesLeadingZerosValueAndScale() {
        assertEquals(BigDecimal("7.00"), Money.parse("007.00").amount)
        assertEquals(2, Money.parse("0.10").amount.scale())
        assertEquals(1, Money.parse("0.1").amount.scale())
        assertEquals(0, Money.parse("5").amount.scale())
    }

    @Test
    fun hugeInputIsHandledWithoutHang() {
        val big = "9".repeat(100_000)
        assertEquals(big, Money.parse(big).toApi())
        assertRejectedWithoutEcho("$big.001")
        assertRejectedWithoutEcho(big + "x")
    }

    @Test
    fun toApiNeverUsesScientificNotation() {
        val text = "1" + "0".repeat(30) + ".00"
        assertEquals(text, Money.parse(text).toApi())
    }
}
