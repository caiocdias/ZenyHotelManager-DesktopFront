package com.zeny.desktop.design.form

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FormUiAttackTest {
    private fun problem(vararg errors: FieldError) = ApiError.Problem(422, "VALIDATION_FAILED", null, errors.toList())

    private val required: (String) -> String? = { if (it.isBlank()) "Obrigatório." else null }

    @Test
    fun bannerShowsUnmatchedMessagesOnSeparateLinesWithoutPath() =
        runComposeUiTest {
            val state = FormState(listOf(FormField("name", "Nome")))
            setContent { ZenyTheme { Column { FormErrorBanner(state) } } }
            state.applyServerErrors(
                problem(FieldError("a.b", "CODE_A", "primeira"), FieldError("c", "CODE_B", "segunda")),
            )
            waitForIdle()
            onNodeWithTag("form-error").assertTextEquals("primeira\nsegunda")
        }

    @Test
    fun bannerDisappearsAfterClearServerErrors() =
        runComposeUiTest {
            val state = FormState(listOf(FormField("name", "Nome")))
            setContent { ZenyTheme { FormErrorBanner(state) } }
            state.applyServerErrors(ApiError.Network(RuntimeException("x")))
            waitForIdle()
            onNodeWithTag("form-error").assertExists()
            state.clearServerErrors()
            waitForIdle()
            onNodeWithTag("form-error").assertDoesNotExist()
        }

    @Test
    fun secondApplyReplacesErrorShownUnderFields() =
        runComposeUiTest {
            val name = FormField("name", "Nome")
            val price = FormField("price", "Preço")
            val state = FormState(listOf(name, price))
            setContent {
                ZenyTheme {
                    Column {
                        FormTextField(name)
                        FormTextField(price)
                    }
                }
            }
            state.applyServerErrors(problem(FieldError("price", "A", "erro preço")))
            waitForIdle()
            onNodeWithTag("error-price", useUnmergedTree = true).assertTextEquals("erro preço")
            state.applyServerErrors(problem(FieldError("name", "A", "erro nome")))
            waitForIdle()
            onNodeWithTag("error-name", useUnmergedTree = true).assertTextEquals("erro nome")
            onNodeWithTag("error-price", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun typingInOneFieldKeepsServerErrorOfTheOther() =
        runComposeUiTest {
            val name = FormField("name", "Nome")
            val price = FormField("price", "Preço")
            val state = FormState(listOf(name, price))
            setContent {
                ZenyTheme {
                    Column {
                        FormTextField(name)
                        FormTextField(price)
                    }
                }
            }
            state.applyServerErrors(
                problem(FieldError("price", "A", "erro preço"), FieldError("name", "A", "erro nome")),
            )
            waitForIdle()
            onNodeWithTag("field-name").performTextInput("Ana")
            waitForIdle()
            onNodeWithTag("error-name", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("error-price", useUnmergedTree = true).assertTextEquals("erro preço")
        }

    @Test
    fun localErrorAppearsOnlyAfterFieldLosesFocus() =
        runComposeUiTest {
            val name = FormField("name", "Nome", validate = required)
            val price = FormField("price", "Preço")
            setContent {
                ZenyTheme {
                    Column {
                        FormTextField(name)
                        FormTextField(price)
                    }
                }
            }
            onNodeWithTag("field-name").performClick()
            waitForIdle()
            onNodeWithTag("error-name", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("field-price").performClick()
            waitForIdle()
            onNodeWithTag("error-name", useUnmergedTree = true).assertTextEquals("Obrigatório.")
        }

    @Test
    fun validateShowsLocalErrorsOfUntouchedFieldsInUi() =
        runComposeUiTest {
            val name = FormField("name", "Nome", validate = required)
            val state = FormState(listOf(name))
            setContent { ZenyTheme { FormTextField(name) } }
            onNodeWithTag("error-name", useUnmergedTree = true).assertDoesNotExist()
            state.validate()
            waitForIdle()
            onNodeWithTag("error-name", useUnmergedTree = true).assertTextEquals("Obrigatório.")
        }
}
