package com.zeny.desktop.seed

import com.zeny.desktop.sdk.model.BookingResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mesma garantia do `design`: o `demo-seed` só chega ao `sdk` pelo `api` do `core` (ADR 0008). */
class CoreSdkVisibilityTest {
    @Test
    fun demoSeedSeesSdkModelsOnlyThroughCoreApi() {
        assertEquals("BookingResponse", BookingResponse::class.simpleName)
    }
}
