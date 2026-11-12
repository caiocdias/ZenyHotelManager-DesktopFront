package com.zeny.desktop.design.input

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DocumentFieldAttackTest {
    @Test
    fun documentFieldShowsMaskAndErrorOnlyAfterFocusLeaves() =
        runComposeUiTest {
            var state = ""
            setContent {
                ZenyTheme {
                    var text by remember { mutableStateOf("") }
                    Column {
                        DocumentField(
                            text,
                            {
                                text = it
                                state = it
                            },
                            "Documento",
                            modifier = Modifier.testTag("doc"),
                        )
                        BasicTextField("", {}, modifier = Modifier.testTag("other"))
                    }
                }
            }
            onNodeWithTag("doc").performClick()
            onNodeWithTag("doc").performTextInput("11122233344")
            waitForIdle()
            assertEquals("111.222.333-44", state)
            onNodeWithText("CPF inválido.").assertDoesNotExist()
            onNodeWithTag("other").performClick()
            waitForIdle()
            onNodeWithText("CPF inválido.").assertIsDisplayed()
        }

    @Test
    fun phoneFieldShowsMaskAndErrorOnlyAfterFocusLeaves() =
        runComposeUiTest {
            var state = ""
            val error = "Telefone inválido. Use DDD e número, ou + e o código do país."
            setContent {
                ZenyTheme {
                    var text by remember { mutableStateOf("") }
                    Column {
                        PhoneField(
                            text,
                            {
                                text = it
                                state = it
                            },
                            "Telefone",
                            modifier = Modifier.testTag("phone"),
                        )
                        BasicTextField("", {}, modifier = Modifier.testTag("other"))
                    }
                }
            }
            onNodeWithTag("phone").performClick()
            onNodeWithTag("phone").performTextInput("119876")
            waitForIdle()
            assertEquals("(11) 9876", state)
            onNodeWithText(error).assertDoesNotExist()
            onNodeWithTag("other").performClick()
            waitForIdle()
            onNodeWithText(error).assertIsDisplayed()
        }
}
