package com.zeny.desktop.design

import com.zeny.desktop.sdk.model.BookingResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A ADR 0008 manda o `core` expor o `sdk` por `api`. Este módulo só depende do `core`: a referência tipada
 * abaixo deixa de compilar se o `core` trocar `api` por `implementation`, o que o carregamento por nome de
 * classe (`ModuleGraphTest` do `core`) não percebe.
 */
class CoreSdkVisibilityTest {
    @Test
    fun designSeesSdkModelsOnlyThroughCoreApi() {
        assertEquals("BookingResponse", BookingResponse::class.simpleName)
    }
}
