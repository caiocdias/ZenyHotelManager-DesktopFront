package com.zeny.desktop.design.approval

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val MASK = '•'
private val isPassword = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

private fun showsOnlyMask(length: Int) =
    SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(MASK.toString().repeat(length)))

@OptIn(ExperimentalTestApi::class)
class ApprovalDialogUiTest {
    private val valid =
        ApprovalDialogModel(
            detail = "Esta ação precisa da aprovação de um superior.",
            login = "joana.gerente",
            pin = "1234",
            reason = "Cortesia da gerência",
            message = null,
            busy = false,
        )

    @Test
    fun pinIsMasked() =
        runComposeUiTest {
            var model by mutableStateOf(valid.copy(pin = ""))
            setContent {
                ZenyTheme {
                    ApprovalDialog(model, {}, { model = model.copy(pin = it) }, {}, {}, {})
                }
            }
            onNodeWithText("Aprovação de superior").assertExists()
            onNodeWithText(valid.detail).assertExists()
            onNodeWithTag("approval-pin").performTextInput("987654")
            waitForIdle()

            assertEquals("987654", model.pin)
            onNodeWithTag("approval-pin").assert(isPassword).assert(showsOnlyMask(6))
        }

    @Test
    fun approveDisabledOutsideTheRules() =
        runComposeUiTest {
            var model by mutableStateOf(valid)
            var confirms = 0
            setContent {
                ZenyTheme { ApprovalDialog(model, {}, {}, {}, { confirms++ }, {}) }
            }
            onNodeWithTag("approval-confirm").assertIsEnabled().performClick()
            assertEquals(1, confirms)

            val invalid =
                listOf("", "123", "123456789", "12a4", "12 34").map { valid.copy(pin = it) } +
                    listOf("", "   ", "x".repeat(501)).map { valid.copy(reason = it) } +
                    valid.copy(login = " ") +
                    valid.copy(busy = true)
            invalid.forEach {
                model = it
                waitForIdle()
                assertFalse(it.canConfirm, it.toString())
                onNodeWithTag("approval-confirm").assertIsNotEnabled()
            }
            onNodeWithTag("approval-confirm").performClick()
            assertEquals(1, confirms)

            model = valid.copy(pin = "12345678", reason = "x".repeat(500))
            waitForIdle()
            onNodeWithTag("approval-confirm").assertIsEnabled()
        }

    @Test
    fun showsTheRefusalMessage() =
        runComposeUiTest {
            setContent {
                ZenyTheme {
                    ApprovalDialog(
                        valid.copy(pin = "", message = "Login ou PIN do aprovador inválidos."),
                        {},
                        {},
                        {},
                        {},
                        {},
                    )
                }
            }
            onNodeWithTag("approval-message").assertExists()
            onNodeWithText("Login ou PIN do aprovador inválidos.").assertExists()
        }

    @Test
    fun typingKeepsThePinToEightAsciiDigitsAndTheReasonTo500Characters() =
        runComposeUiTest {
            var model by mutableStateOf(valid.copy(pin = "", reason = ""))
            setContent {
                ZenyTheme {
                    ApprovalDialog(
                        model,
                        {},
                        { model = model.copy(pin = it) },
                        { model = model.copy(reason = it) },
                        {},
                        {},
                    )
                }
            }
            onNodeWithTag("approval-pin").performTextInput("12")
            listOf("a", " ", "-", "\u0663", "\uFF14").forEach {
                onNodeWithTag("approval-pin").performTextInput(it)
                waitForIdle()
                assertEquals("12", model.pin, "aceitou '$it' no PIN")
            }
            onNodeWithTag("approval-pin").performTextInput("345678")
            onNodeWithTag("approval-pin").performTextInput("9")
            waitForIdle()
            assertEquals("12345678", model.pin)

            onNodeWithTag("approval-reason").performTextInput("x".repeat(499))
            onNodeWithTag("approval-reason").performTextInput("yz")
            waitForIdle()
            assertEquals("x".repeat(499) + "y", model.reason)
            onNodeWithTag("approval-reason").performTextInput("w")
            waitForIdle()
            assertEquals(500, model.reason.length)
        }

    @Test
    fun toStringHidesThePin() {
        assertFalse(valid.copy(pin = "987654").toString().contains("987654"))
    }
}
