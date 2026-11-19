package com.zeny.desktop.app.folio

import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ExpiringUntilErrorTest {
    private val source =
        ExpiringPreAuthorizationSource {
            _,
            _,
            _,
            ->
            Page<CardPreAuthorizationResponse>(emptyList(), null)
        }

    @Test
    fun typingClearsTheInvalidDateWarning() =
        runTest {
            val state = ExpiringPreAuthorizationsState(source, FakePaymentSource(), backgroundScope)
            state.enter()
            runCurrent()
            state.untilText = "31/02/2026"
            state.search()
            assertEquals("Data inválida.", state.untilError)

            state.untilText = "30/11/202"

            assertNull(state.untilError)
        }
}
