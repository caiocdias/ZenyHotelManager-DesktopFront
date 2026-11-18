package com.zeny.desktop.design.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

private const val SIGNAL = "Texto colado com mais de 1000 caracteres. Ele não foi aceito."
private const val LIMIT = 1000

private typealias FieldContent =
    @Composable (String, (String) -> Unit, Boolean, Modifier) -> Unit

private val money: FieldContent = { text, change, enabled, modifier ->
    MoneyField(text, change, "Valor", enabled = enabled, modifier = modifier)
}
private val date: FieldContent = { text, change, enabled, modifier ->
    DateField(text, change, "Data", enabled = enabled, modifier = modifier)
}

/** `enabled` e o limite de 1000 caracteres por edição do `MoneyField` e do `DateField` (A-M7). */
@OptIn(ExperimentalTestApi::class)
class FieldLimitsTest {
    private class Probe {
        val received = mutableListOf<String>()
    }

    private fun ComposeUiTest.show(
        field: FieldContent,
        initial: String = "",
        enabled: Boolean = true,
    ): Probe {
        val probe = Probe()
        setContent {
            ZenyTheme {
                var text by remember { mutableStateOf(initial) }
                field(
                    text,
                    {
                        text = it
                        probe.received += it
                    },
                    enabled,
                    Modifier.testTag("field"),
                )
            }
        }
        return probe
    }

    private fun refusesTypingAndPaste(field: FieldContent) =
        runComposeUiTest {
            val probe = show(field, enabled = false)
            onNodeWithTag("field").assertIsNotEnabled()
            runCatching { onNodeWithTag("field").performTextInput("12/05/2026") }
            runCatching { onNodeWithTag("field").performTextInput("1".repeat(LIMIT + 1)) }
            waitForIdle()
            assertEquals(emptyList(), probe.received)
        }

    @Test
    fun disabledMoneyFieldRefusesTypingAndPaste() = refusesTypingAndPaste(money)

    @Test
    fun disabledDateFieldRefusesTypingAndPaste() = refusesTypingAndPaste(date)

    private fun pasteOverLimit(field: FieldContent) =
        runComposeUiTest {
            val probe = show(field)
            onNodeWithTag("field").performTextInput("12")
            waitForIdle()
            onNodeWithTag("field").performTextInput("1".repeat(LIMIT + 1))
            waitForIdle()
            assertEquals(listOf("12"), probe.received)
            onNodeWithTag("field").assertTextContains("12")
            onNodeWithText(SIGNAL).assertExists()
            onNodeWithTag("field").performTextInput("3")
            waitForIdle()
            assertEquals(listOf("12", "123"), probe.received)
            onNodeWithText(SIGNAL).assertDoesNotExist()
        }

    @Test
    fun pasteOverLimitShowsSignalAndKeepsText() {
        pasteOverLimit(money)
        pasteOverLimit(date)
    }

    @Test
    fun pasteAtLimitIsAccepted() {
        listOf(money, date).forEach { field ->
            runComposeUiTest {
                val probe = show(field)
                onNodeWithTag("field").performTextInput("1".repeat(LIMIT))
                waitForIdle()
                assertEquals(listOf("1".repeat(LIMIT)), probe.received)
                onNodeWithText(SIGNAL).assertDoesNotExist()
            }
        }
    }
}
