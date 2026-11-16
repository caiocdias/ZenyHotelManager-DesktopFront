package com.zeny.desktop.core.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorCatalogShowableTest {
    @Test
    fun isShowableDetailMatchesUserMessage() {
        val showable = "20/11/2026 está fechada para chegada."
        assertTrue(ErrorCatalog.isShowableDetail(showable))
        assertEquals(showable, ErrorCatalog.userMessage(422, null, showable))
        val fallback = ErrorCatalog.userMessage(422, null, null)
        for (hidden in listOf("", "   ", "{\"a\":1}", "[1]", "CLOSED_TO_ARRIVAL", "STAY_INVALID")) {
            assertFalse(ErrorCatalog.isShowableDetail(hidden), hidden)
            assertEquals(fallback, ErrorCatalog.userMessage(422, null, hidden), hidden)
        }
    }

    @Test
    fun serverDetailStillWinsForUncataloguedCode() {
        val detail = "20/11/2026 está fechada para chegada."
        assertEquals(detail, ErrorCatalog.userMessage(422, "CLOSED_TO_ARRIVAL", detail))
    }
}
