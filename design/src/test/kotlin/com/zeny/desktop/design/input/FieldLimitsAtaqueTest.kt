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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val SIGNAL = "Texto colado com mais de 1000 caracteres. Ele não foi aceito."
private const val LIMIT = 1000
private const val FORM_ERROR = "Erro do formulário."

private typealias LimitedField =
    @Composable (String, (String) -> Unit, String?, Modifier) -> Unit

private val moneyField: LimitedField = { text, change, error, modifier ->
    MoneyField(text, change, "Valor", error = error, modifier = modifier)
}
private val dateField: LimitedField = { text, change, error, modifier ->
    DateField(text, change, "Data", error = error, modifier = modifier)
}

/** Ataque da etapa ao limite de 1000 caracteres (A-M7): foco, prioridade do sinal, a borda somada e o `require`. */
@OptIn(ExperimentalTestApi::class)
class FieldLimitsAtaqueTest {
    private fun ComposeUiTest.show(
        field: LimitedField,
        initial: String,
        error: String? = null,
    ): MutableList<String> {
        val received = mutableListOf<String>()
        setContent {
            ZenyTheme {
                var text by remember { mutableStateOf(initial) }
                field(
                    text,
                    {
                        text = it
                        received += it
                    },
                    error,
                    Modifier.testTag("field"),
                )
            }
        }
        return received
    }

    /** O limite é do texto que a edição deixaria: 999 + 1 entra; 1000 + 1 é recusado, com o sinal e o foco. */
    @Test
    fun appendCrossingTheLimitIsRefusedAndKeepsFocus() {
        listOf(moneyField, dateField).forEach { field ->
            runComposeUiTest {
                val received = show(field, "1".repeat(LIMIT - 1))
                onNodeWithTag("field").performTextInput("2")
                waitForIdle()
                assertEquals(LIMIT, received.single().length)
                onNodeWithText(SIGNAL).assertDoesNotExist()

                onNodeWithTag("field").performTextInput("3")
                waitForIdle()
                assertEquals(1, received.size, "a edição que passaria de 1000 não chega ao onValueChange")
                onNodeWithText(SIGNAL).assertExists()
                onNodeWithTag("field").assertIsFocused()
            }
        }
    }

    /** O sinal tem prioridade sobre o erro do formulário; a edição aceita devolve o erro do formulário. */
    @Test
    fun signalWinsOverTheFormErrorUntilTheNextAcceptedEdit() {
        listOf(moneyField, dateField).forEach { field ->
            runComposeUiTest {
                show(field, "", error = FORM_ERROR)
                onNodeWithText(FORM_ERROR).assertExists()

                onNodeWithTag("field").performTextInput("1".repeat(LIMIT + 1))
                waitForIdle()
                onNodeWithText(SIGNAL).assertExists()
                onNodeWithText(FORM_ERROR).assertDoesNotExist()
                onNodeWithTag("field").assertIsFocused()

                onNodeWithTag("field").performTextInput("1")
                waitForIdle()
                onNodeWithText(SIGNAL).assertDoesNotExist()
                onNodeWithText(FORM_ERROR).assertExists()
            }
        }
    }

    @Test
    fun maxLengthIsRefusedWithAMask() {
        assertFailsWith<IllegalArgumentException> {
            FieldSpec("Telefone", null, { InputResult.Empty }, mask = { it }, maxLength = LIMIT)
        }
        FieldSpec("Telefone", null, { InputResult.Empty }, mask = { it })
        FieldSpec("Valor", null, { InputResult.Empty }, maxLength = LIMIT)
    }
}
