package com.zeny.desktop.design.approval

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Ataque ao filtro de digitação do PIN (só 0 a 9, até 8) e do motivo (até 500 caracteres) do diálogo. */
@OptIn(ExperimentalTestApi::class)
class ApprovalDialogLimitsAtaqueTest {
    private val base =
        ApprovalDialogModel(
            detail = "Esta ação precisa da aprovação de um superior.",
            login = "joana.gerente",
            pin = "",
            reason = "",
            message = null,
            busy = false,
        )

    private fun hasLoneSurrogate(text: String): Boolean =
        text.indices.any { i ->
            val c = text[i]
            (c.isHighSurrogate() && !(i + 1 < text.length && text[i + 1].isLowSurrogate())) ||
                (c.isLowSurrogate() && !(i > 0 && text[i - 1].isHighSurrogate()))
        }

    @Test
    fun pastedNineDigitsInOneEditAreIgnoredWholeNotTruncated() =
        runComposeUiTest {
            var model by mutableStateOf(base)
            setContent { ZenyTheme { ApprovalDialog(model, {}, { model = model.copy(pin = it) }, {}, {}, {}) } }
            onNodeWithTag("approval-pin").performTextInput("123456789")
            waitForIdle()
            assertEquals("", model.pin)
        }

    @Test
    fun pastedNonAsciiOrControlTextIsIgnoredWholeInThePin() =
        runComposeUiTest {
            var model by mutableStateOf(base.copy(pin = "12"))
            setContent { ZenyTheme { ApprovalDialog(model, {}, { model = model.copy(pin = it) }, {}, {}, {}) } }
            listOf("34\n56", "１２", "3²", "3​4", "3\t4", "𝟙").forEach {
                onNodeWithTag("approval-pin").performTextInput(it)
                waitForIdle()
                assertEquals("12", model.pin, "aceitou ${it.map { c -> c.code.toString(16) }} no PIN")
            }
        }

    @Test
    fun clearingThePinIsAccepted() =
        runComposeUiTest {
            var model by mutableStateOf(base.copy(pin = "1234"))
            setContent { ZenyTheme { ApprovalDialog(model, {}, { model = model.copy(pin = it) }, {}, {}, {}) } }
            onNodeWithTag("approval-pin").performTextClearance()
            waitForIdle()
            assertEquals("", model.pin)
        }

    @Test
    fun pastedSixHundredCharactersKeepTheFirstFiveHundred() =
        runComposeUiTest {
            var model by mutableStateOf(base)
            setContent { ZenyTheme { ApprovalDialog(model, {}, {}, { model = model.copy(reason = it) }, {}, {}) } }
            val pasted = "a".repeat(250) + "b".repeat(250) + "c".repeat(100)
            onNodeWithTag("approval-reason").performTextInput(pasted)
            waitForIdle()
            assertEquals(pasted.take(500), model.reason)
        }

    @Test
    fun reasonCutAtFiveHundredDoesNotLeaveALoneSurrogate() =
        runComposeUiTest {
            var model by mutableStateOf(base)
            setContent { ZenyTheme { ApprovalDialog(model, {}, {}, { model = model.copy(reason = it) }, {}, {}) } }
            // O emoji ocupa os caracteres 500 e 501: cortar em 500 deixa só a metade alta do par.
            onNodeWithTag("approval-reason").performTextInput("x".repeat(499) + "😀" + "fim")
            waitForIdle()
            assertFalse(hasLoneSurrogate(model.reason), "o motivo ficou com meio caractere no corte")
        }
}
