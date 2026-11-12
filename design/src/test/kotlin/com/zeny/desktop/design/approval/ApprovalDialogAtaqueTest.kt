package com.zeny.desktop.design.approval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApprovalDialogAtaqueTest {
    private val ok = ApprovalDialogModel("d", "joana", "1234", "motivo", null, false)

    @Test
    fun pinRuleRejectsUnicodeDigitsSignsAndSpaces() {
        listOf("٣٣٣٣", "１２３４", " 1234", "1234 ", "1234\n", "+1234", "-1234", "12.34", "123", "123456789", "")
            .forEach { assertFalse(ok.copy(pin = it).canConfirm, "pin '$it'") }
        listOf("1234", "123456", "12345678").forEach { assertEquals(true, ok.copy(pin = it).canConfirm, it) }
    }

    @Test
    fun reasonRuleTrimsAndBoundsAt500() {
        assertFalse(ok.copy(reason = " \n\t ").canConfirm)
        assertFalse(ok.copy(reason = "x".repeat(501)).canConfirm)
        assertEquals(true, ok.copy(reason = "x".repeat(500)).canConfirm)
        assertEquals(true, ok.copy(reason = " " + "x".repeat(500) + " ").canConfirm)
    }

    @Test
    fun toStringNeverShowsThePin() {
        assertFalse(ok.copy(pin = "987654").toString().contains("987654"))
    }
}
