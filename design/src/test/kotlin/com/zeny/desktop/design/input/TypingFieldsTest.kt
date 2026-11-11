package com.zeny.desktop.design.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextRange
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Digitação real, um caractere por vez, sem `performTextReplacement`. */
@OptIn(ExperimentalTestApi::class)
class TypingFieldsTest {
    private fun digitsOf(text: String) = text.filter { it.isDigit() }

    private fun SemanticsNodeInteraction.typeEach(text: String) {
        performClick()
        text.forEach { performTextInput(it.toString()) }
    }

    private fun ComposeUiTest.mount(
        initial: String,
        field: @Composable (String, (String) -> Unit, Modifier) -> Unit,
        sink: (String) -> Unit,
    ) = setContent {
        ZenyTheme {
            var text by remember { mutableStateOf(initial) }
            field(
                text,
                {
                    text = it
                    sink(it)
                },
                Modifier.testTag("f"),
            )
        }
    }

    @Test
    fun documentFieldKeepsDigitOrderWhenTypedOneKeyAtATime() =
        runComposeUiTest {
            var state = ""
            mount("", { v, c, m -> DocumentField(v, c, "Documento", modifier = m) }) { state = it }
            onNodeWithTag("f").typeEach("11122233344")
            waitForIdle()
            assertEquals("11122233344", digitsOf(state))
            assertEquals("111.222.333-44", state)
            onNodeWithTag("f").assertTextEquals("Documento", "111.222.333-44", includeEditableText = true)
        }

    @Test
    fun documentFieldWithDistinctDigitsKeepsOrderWhenTypedOneKeyAtATime() =
        runComposeUiTest {
            var state = ""
            mount("", { v, c, m -> DocumentField(v, c, "Documento", modifier = m) }) { state = it }
            onNodeWithTag("f").typeEach("12345678901")
            waitForIdle()
            assertEquals("12345678901", digitsOf(state))
        }

    @Test
    fun phoneFieldKeepsDigitOrderWhenTypedOneKeyAtATime() =
        runComposeUiTest {
            var state = ""
            mount("", { v, c, m -> PhoneField(v, c, "Telefone", modifier = m) }) { state = it }
            onNodeWithTag("f").typeEach("11987654321")
            waitForIdle()
            assertEquals("11987654321", digitsOf(state))
            assertEquals("(11) 98765-4321", state)
            onNodeWithTag("f").assertTextEquals("Telefone", "(11) 98765-4321", includeEditableText = true)
        }

    @Test
    fun documentFieldBackspaceOverSeparatorNeverReordersOrInventsDigits() =
        runComposeUiTest {
            var state = "111.222.333-44"
            mount(state, { v, c, m -> DocumentField(v, c, "Documento", modifier = m) }) { state = it }
            val node = onNodeWithTag("f")
            node.performClick()
            // Cursor logo depois do primeiro ponto: "111.|222.333-44".
            node.performTextInputSelection(TextRange(4))
            node.performKeyInput { pressKey(Key.Backspace) }
            waitForIdle()
            val original = "11122233344"
            val digits = digitsOf(state)
            // Os dígitos que sobram são os originais, na ordem, com no máximo um a menos.
            assertTrue(digits.length in (original.length - 1)..original.length, "estado=$state")
            assertTrue(
                original.indices.any { original.removeRange(it, it + 1) == digits } || digits == original,
                "dígitos fora de ordem: estado=$state",
            )
        }
}
