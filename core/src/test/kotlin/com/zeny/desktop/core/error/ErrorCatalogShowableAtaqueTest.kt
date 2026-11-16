package com.zeny.desktop.core.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ataque ao filtro público de detalhe: bordas do identificador cru e do texto com espaços. */
class ErrorCatalogShowableAtaqueTest {
    @Test
    fun paddedHumanTextIsShowableAndPaddedJsonIsNot() {
        assertTrue(ErrorCatalog.isShowableDetail("  Período inválido.  "))
        assertFalse(ErrorCatalog.isShowableDetail("  {\"a\":1}"))
        assertFalse(ErrorCatalog.isShowableDetail("\n[1]\n"))
        assertFalse(ErrorCatalog.isShowableDetail("  STAY_INVALID  "))
    }

    @Test
    fun identifierLookalikesThatAreProseStayShowable() {
        for (text in listOf("stay_invalid", "STAY INVALID", "Stay_Invalid", "OK.", "A_", "_A", "A__B")) {
            assertTrue(ErrorCatalog.isShowableDetail(text), text)
        }
        for (text in listOf("A", "AB_C1", "X9")) {
            assertFalse(ErrorCatalog.isShowableDetail(text), text)
        }
    }

    @Test
    fun catalogedCodeBeatsDetailAndNullDetailFallsBack() {
        assertEquals(
            ErrorCatalog.message("INVENTORY_PERIOD_TOO_LONG"),
            ErrorCatalog.userMessage(400, "INVENTORY_PERIOD_TOO_LONG", "texto do servidor"),
        )
        assertNull(ErrorCatalog.message("CLOSED_TO_ARRIVAL"))
        assertEquals(ErrorCatalog.GENERIC_MESSAGE, ErrorCatalog.userMessage(422, "CLOSED_TO_ARRIVAL", null))
        val bare = "CLOSED_TO_ARRIVAL"
        assertEquals(ErrorCatalog.GENERIC_MESSAGE, ErrorCatalog.userMessage(422, bare, bare))
    }
}
