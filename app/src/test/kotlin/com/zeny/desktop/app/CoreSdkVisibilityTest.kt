package com.zeny.desktop.app

import com.zeny.desktop.sdk.model.BookingResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mesma garantia do `design`: o `app` só chega ao `sdk` pelo `api` do `core` (ADR 0008). */
class CoreSdkVisibilityTest {
    @Test
    fun appSeesSdkModelsOnlyThroughCoreApi() {
        assertEquals("BookingResponse", BookingResponse::class.simpleName)
    }
}
