package com.zeny.desktop.design.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextRange
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MaskedFieldSelectionTest {
    private fun run(
        initial: String,
        block: androidx.compose.ui.test.ComposeUiTest.(() -> String) -> Unit,
    ) = runComposeUiTest {
        var state = initial
        setContent {
            ZenyTheme {
                var text by remember { mutableStateOf(initial) }
                DocumentField(text, {
                    text = it
                    state = it
                }, "Documento", modifier = Modifier.testTag("f"))
            }
        }
        block { state }
    }

    @Test
    fun selectAllThenTypingAnotherDocumentReplacesTheValue() =
        run("111.222.333-44") { state ->
            val node = onNodeWithTag("f")
            node.performClick()
            node.performTextInputSelection(TextRange(0, 14))
            "52998224725".forEach { node.performTextInput(it.toString()) }
            waitForIdle()
            assertEquals("529.982.247-25", state())
            node.assertTextEquals("Documento", "529.982.247-25", includeEditableText = true)
        }

    @Test
    fun typingInTheMiddleKeepsTheOtherDigitsInOrder() =
        run("123.456.789-0") { state ->
            val node = onNodeWithTag("f")
            node.performClick()
            node.performTextInputSelection(TextRange(2))
            node.performTextInput("9")
            waitForIdle()
            assertEquals("129.345.678-90", state())
        }
}
