package com.zeny.desktop.core.types

import kotlin.test.Test
import kotlin.test.assertEquals

class ExtensibleEnumTest {
    private val labels = ExtensibleEnumLabels(mapOf("DIRECT" to "Direto"))

    @Test
    fun knownValueUsesItsLabel() {
        assertEquals("Direto", labels.label("DIRECT"))
    }

    @Test
    fun unknownValueFallsBackToOutro() {
        assertEquals("Outro (TELEPATHY)", labels.label("TELEPATHY"))
    }
}
