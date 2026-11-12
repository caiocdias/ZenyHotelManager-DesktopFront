package com.zeny.desktop.design.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class AppScaffoldTest {
    private val items = listOf(NavItem("rooms", "Quartos"), NavItem("guests", "Hóspedes"), NavItem("stays", "Estadias"))

    @Test
    fun marksSelected() {
        val state = navState(items, "guests")

        assertEquals(listOf(false, true, false), state.map { it.second })
    }

    @Test
    fun unknownSelectedMarksNone() {
        assertEquals(listOf(false, false, false), navState(items, "ghost").map { it.second })
        assertEquals(listOf(false, false, false), navState(items, null).map { it.second })
    }

    @Test
    fun keepsOrder() {
        assertEquals(items, navState(items, "stays").map { it.first })
    }
}
