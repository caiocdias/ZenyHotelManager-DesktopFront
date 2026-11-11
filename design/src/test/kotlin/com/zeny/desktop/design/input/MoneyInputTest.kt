package com.zeny.desktop.design.input

import com.zeny.desktop.core.types.Money
import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MoneyInputTest {
    private fun api(text: String): String {
        val result = MoneyInput.parse(text)
        assertIs<InputResult.Valid<Money>>(result, "esperava Valid para '$text' mas veio $result")
        return result.value.toApi()
    }

    @Test
    fun parsesBrazilianFormatWithoutLoss() {
        assertEquals("1234.56", api("1.234,56"))
        assertEquals("1234.56", api("1234,56"))
        assertEquals("1234.56", api("R$ 1.234,56"))
        assertEquals("12.00", api("12"))
        assertEquals("12.50", api("12,5"))
        assertEquals("0.01", api("R$ 0,01"))
        assertEquals("-5.00", api("-5"))
        assertEquals("-5.00", api("-R$ 5,00"))
        assertEquals("-5.00", api("R$ -5,00"))
        assertEquals("999999999999.99", api("999.999.999.999,99"))
    }

    @Test
    fun rejectsInvalid() {
        listOf("abc", "1234.56", "1,234", "12,345", "12.34,00", "1.2.3", "--1", "1e3", "R$", "-").forEach {
            val result = MoneyInput.parse(it)
            assertIs<InputResult.Invalid>(result, "esperava Invalid para '$it'")
            assertTrue(result.message.isNotBlank())
        }
        assertEquals(
            "Valor inválido. Use o formato 1.234,56.",
            (MoneyInput.parse("abc") as InputResult.Invalid).message,
        )
        assertEquals(
            "Use no máximo 2 casas decimais.",
            (MoneyInput.parse("12,345") as InputResult.Invalid).message,
        )
        assertEquals(InputResult.Empty, MoneyInput.parse("   "))
        assertEquals(InputResult.Empty, MoneyInput.parse(""))
    }

    @Test
    fun pastedValues() {
        assertEquals("12.00", api(" 12 "))
        assertEquals("10.50", api("R$ 10,5"))
    }

    @Test
    fun formatsBrazilianMoney() {
        assertEquals("R$ 1.234,50", MoneyInput.format(Money(BigDecimal("1234.5"))))
        assertEquals("-R$ 5,00", MoneyInput.format(Money(BigDecimal("-5"))))
        assertEquals("R$ 0,00", MoneyInput.format(Money(BigDecimal.ZERO)))
        assertEquals("R$ 999.999.999.999,99", MoneyInput.format(Money(BigDecimal("999999999999.99"))))
    }

    @Test
    fun formatRoundTrips() {
        listOf("0", "0.01", "-5", "1234.5", "999999999999.99").forEach {
            val money = Money(BigDecimal(it))
            val result = MoneyInput.parse(MoneyInput.format(money))
            assertIs<InputResult.Valid<Money>>(result)
            assertEquals(0, result.value.amount.compareTo(money.amount), "ida e volta de $it")
        }
    }

    @Test
    fun neverUsesDouble() {
        val source = File("src/main/kotlin/com/zeny/desktop/design/input/MoneyInput.kt").readText()
        listOf("Double", "toDouble", "Float").forEach {
            assertFalse(source.contains(it), "MoneyInput.kt não pode usar $it")
        }
    }
}
