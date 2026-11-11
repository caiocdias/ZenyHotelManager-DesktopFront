package com.zeny.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleGraphTest {
    @Test
    fun coreExposesSdkModels() {
        val model = Class.forName("com.zeny.desktop.sdk.model.BookingResponse")
        assertEquals("BookingResponse", model.simpleName)
    }
}
