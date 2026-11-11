package com.zeny.desktop.core.types

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MoneyTest {
    @Test
    fun parseKeepsExactValue() {
        listOf("10.50", "0.01", "-3.20", "0", "7", "123456789012345678.99").forEach {
            assertEquals(it, Money.parse(it).toApi())
        }
        assertEquals(2, Money.parse("10.50").amount.scale())
    }

    @Test
    fun rejectsInvalid() {
        listOf("0.001", "1e3", "10,50", " 10.50", "", "NaN", ".5", "+1", "10.", "--1").forEach {
            val error = assertFailsWith<IllegalArgumentException>("aceitou '$it'") { Money.parse(it) }
            assertFalse(it.isNotEmpty() && error.message.orEmpty().contains(it), "mensagem ecoa '$it'")
        }
    }

    @Test
    fun neverUsesDouble() {
        val source = File("src/main/kotlin/com/zeny/desktop/core/types/Money.kt").readText()
        assertFalse(source.contains("Double"))
        assertFalse(source.contains("toDouble"))
    }
}
