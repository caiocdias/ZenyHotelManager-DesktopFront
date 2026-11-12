package com.zeny.desktop.core.types

import kotlin.test.Test
import kotlin.test.assertEquals

class ExtensibleEnumAttackTest {
    @Test
    fun emptyMapAndEmptyValueNeverThrow() {
        assertEquals("Outro ()", ExtensibleEnumLabels(emptyMap()).label(""))
    }

    @Test
    fun lookupIsCaseSensitive() {
        assertEquals("Outro (direct)", ExtensibleEnumLabels(mapOf("DIRECT" to "Direto")).label("direct"))
    }

    @Test
    fun unknownValueIsEchoedVerbatimInsideFallback() {
        assertEquals("Outro ( X\n)", ExtensibleEnumLabels(mapOf("A" to "a")).label(" X\n"))
    }

    @Test
    fun knownEmptyLabelIsKeptNotReplacedByFallback() {
        assertEquals("", ExtensibleEnumLabels(mapOf("A" to "")).label("A"))
    }
}
