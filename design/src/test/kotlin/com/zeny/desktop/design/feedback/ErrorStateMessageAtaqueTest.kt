package com.zeny.desktop.design.feedback

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.theme.ZenyTheme
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ErrorStateMessageAtaqueTest {
    @Test
    fun apiErrorOverloadShowsUserMessageAndRetries() =
        runComposeUiTest {
            var retries = 0
            val error = ApiError.Network(IOException("boom"))
            setContent { ZenyTheme { ErrorState(error, onRetry = { retries++ }) } }
            onNodeWithText(error.userMessage).assertExists()
            onNodeWithText("boom", substring = true).assertDoesNotExist()
            onNodeWithText("Tentar de novo").performClick()
            assertEquals(1, retries)
        }

    @Test
    fun messageOverloadShowsGivenTextAndRetries() =
        runComposeUiTest {
            var retries = 0
            setContent { ZenyTheme { ErrorState("Texto pronto", onRetry = { retries++ }) } }
            onNodeWithText("Texto pronto").assertExists()
            onNodeWithText("Tentar de novo").performClick()
            assertEquals(1, retries)
        }
}
