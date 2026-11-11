package com.zeny.desktop.design.input

import com.zeny.desktop.core.types.Money
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class MoneyInputAttackTest {
    private fun api(text: String): String {
        val result = MoneyInput.parse(text)
        assertIs<InputResult.Valid<Money>>(result, "esperava Valid para '$text' mas veio $result")
        return result.value.toApi()
    }

    private fun assertInvalid(text: String) {
        val result = MoneyInput.parse(text)
        assertIs<InputResult.Invalid>(result, "esperava Invalid para '$text' mas veio $result")
        assertFalse(result.message.isBlank())
    }

    @Test
    fun rejectsNonAsciiDigits() {
        listOf("١٢", "１２,００", "१२", "1٢").forEach { assertInvalid(it) }
    }

    @Test
    fun rejectsMalformedSignsAndSeparators() {
        listOf("+5", "5-", "R$ 5-", "-R$ -5", "--R$ 5", "-,50", ",50", "5,", "R$ ,50", "12.3456,00")
            .forEach { assertInvalid(it) }
    }

    @Test
    fun rejectsBadThousandsGrouping() {
        listOf("1.23", "1.2345", "12.345.67", ".123", "1..234", "1.234.", "1234.567,00", "1.234.56")
            .forEach { assertInvalid(it) }
    }

    @Test
    fun thousandsWithoutDecimalsIsAcceptedAsThousands() {
        assertEquals("1234.00", api("1.234"))
        assertEquals("1234567.00", api("1.234.567"))
    }

    @Test
    fun acceptsCurrencyPrefixVariants() {
        assertEquals("5.00", api("R$5,00"))
        assertEquals("5.00", api("R$  5,00"))
        assertEquals("-5.00", api("-R$5,00"))
    }

    @Test
    fun trimsNonBreakingAndThinSpacesAtEnds() {
        assertEquals("12.00", api(" 12 "))
        assertEquals("12.00", api(" 12 "))
    }

    @Test
    fun acceptsNonBreakingSpaceAfterCurrencySymbol() {
        // O NumberFormat pt-BR do JDK separa "R$" do número com NBSP; colar isso deve funcionar.
        assertEquals("1234.56", api("R$ 1.234,56"))
    }

    @Test
    fun negativeZeroNeverShowsMinus() {
        assertEquals("0.00", api("-0,00"))
        assertEquals("0.00", api("-R$ 0,00"))
        assertEquals("R$ 0,00", MoneyInput.format(Money(BigDecimal("-0.00"))))
        assertEquals("R$ 0,00", MoneyInput.format(Money(BigDecimal("-0.001"))))
    }

    @Test
    fun hugeValuesStayExact() {
        val digits = "123456789012345678901234567890"
        assertEquals("$digits.07", api("123.456.789.012.345.678.901.234.567.890,07"))
        assertEquals("-$digits.00", api("-$digits"))
    }

    @Test
    fun formatHandlesAnyInputScale() {
        assertEquals("R$ 5,00", MoneyInput.format(Money(BigDecimal("5"))))
        assertEquals("R$ 5,10", MoneyInput.format(Money(BigDecimal("5.1"))))
        assertEquals("R$ 5,12", MoneyInput.format(Money(BigDecimal("5.123"))))
        assertEquals("R$ 5,13", MoneyInput.format(Money(BigDecimal("5.125"))))
        assertEquals("R$ 1.000.000,00", MoneyInput.format(Money(BigDecimal("1E+6"))))
        assertEquals("R$ 100,00", MoneyInput.format(Money(BigDecimal("1E+2"))))
        assertEquals("-R$ 1.000,00", MoneyInput.format(Money(BigDecimal("-1000"))))
    }

    @Test
    fun formatUsesPlainAsciiSpaceAndNoNbsp() {
        val text = MoneyInput.format(Money(BigDecimal("1234.5")))
        assertFalse(text.contains(' '))
        assertFalse(text.contains(' '))
    }

    @Test
    fun formatAndParseIgnoreDefaultLocale() {
        val original = Locale.getDefault()
        try {
            listOf(Locale.US, Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN-u-nu-deva")).forEach {
                Locale.setDefault(it)
                assertEquals("R$ 1.234,56", MoneyInput.format(Money(BigDecimal("1234.56"))), "locale $it")
                assertEquals("1234.56", api("1.234,56"), "locale $it")
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun roundTripsAcrossScalesAndSigns() {
        listOf("0.00", "-0.01", "1", "1E+3", "12.3", "-999999999999.99", "100000", "1234567.89").forEach {
            val money = Money(BigDecimal(it))
            val back = MoneyInput.parse(MoneyInput.format(money))
            assertIs<InputResult.Valid<Money>>(back, "ida e volta de $it")
            assertEquals(0, back.value.amount.compareTo(money.amount), "ida e volta de $it")
            assertEquals(2, back.value.amount.scale())
        }
    }
}
