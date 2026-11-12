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
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class InputFieldsTest {
    @Test
    fun formErrorShowsImmediately() =
        runComposeUiTest {
            setContent { ZenyTheme { MoneyField("1", {}, "Valor", error = "Valor negativo não permitido.") } }
            onNodeWithText("Valor negativo não permitido.").assertIsDisplayed()
        }

    @Test
    fun localErrorAppearsOnlyAfterFocusLeaves() =
        runComposeUiTest {
            setContent {
                ZenyTheme {
                    var text by remember { mutableStateOf("abc") }
                    Column {
                        DateField(text, { text = it }, "Data", modifier = Modifier.testTag("date"))
                        BasicTextField("", {}, modifier = Modifier.testTag("other"))
                    }
                }
            }
            onNodeWithText("Data inválida. Use dd/mm/aaaa.").assertDoesNotExist()
            onNodeWithTag("date").performClick()
            waitForIdle()
            onNodeWithText("Data inválida. Use dd/mm/aaaa.").assertDoesNotExist()
            onNodeWithTag("other").performClick()
            waitForIdle()
            onNodeWithText("Data inválida. Use dd/mm/aaaa.").assertIsDisplayed()
        }
}
