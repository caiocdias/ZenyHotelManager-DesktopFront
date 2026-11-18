package com.zeny.desktop.design.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MoneyFieldTest {
    @Test
    fun disabledFieldRefusesTyping() =
        runComposeUiTest {
            var typed = ""
            setContent {
                ZenyTheme {
                    var text by remember { mutableStateOf("") }
                    MoneyField(
                        text,
                        {
                            text = it
                            typed = it
                        },
                        "Valor",
                        enabled = false,
                        modifier = Modifier.testTag("money"),
                    )
                }
            }

            onNodeWithTag("money").assertIsNotEnabled()
            runCatching { onNodeWithTag("money").performTextInput("12,50") }
            waitForIdle()

            assertEquals("", typed)
        }

    @Test
    fun fieldIsEnabledByDefault() =
        runComposeUiTest {
            var typed = ""
            setContent {
                ZenyTheme {
                    var text by remember { mutableStateOf("") }
                    MoneyField(
                        text,
                        {
                            text = it
                            typed = it
                        },
                        "Valor",
                        modifier = Modifier.testTag("money"),
                    )
                }
            }

            onNodeWithTag("money").assertIsEnabled()
            onNodeWithTag("money").performTextInput("12,50")
            waitForIdle()

            assertEquals("12,50", typed)
        }
}
