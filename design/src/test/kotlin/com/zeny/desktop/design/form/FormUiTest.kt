package com.zeny.desktop.design.form

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FormUiTest {
    @Test
    fun serverErrorShowsUnderFieldAndTypingClearsIt() =
        runComposeUiTest {
            val name = FormField("name", "Nome")
            val price = FormField("price", "Preço")
            val state = FormState(listOf(name, price))
            setContent {
                ZenyTheme {
                    Column {
                        FormErrorBanner(state)
                        FormTextField(name)
                        FormTextField(price)
                    }
                }
            }
            state.applyServerErrors(
                ApiError.Problem(
                    422,
                    "VALIDATION_FAILED",
                    null,
                    listOf(FieldError("price", "NOT_BLANK", "não deve estar em branco")),
                ),
            )
            waitForIdle()
            onNodeWithTag("error-price", useUnmergedTree = true).assertTextEquals("não deve estar em branco")
            onNodeWithTag("error-name", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("form-error").assertDoesNotExist()
            onNodeWithTag("field-price").performTextInput("10")
            waitForIdle()
            onNodeWithTag("error-price", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun formErrorBannerShowsFormError() =
        runComposeUiTest {
            val state = FormState(listOf(FormField("name", "Nome")))
            setContent { ZenyTheme { FormErrorBanner(state) } }
            state.applyServerErrors(ApiError.Network(RuntimeException("x")))
            waitForIdle()
            onNodeWithTag("form-error").assertTextEquals(
                "Não foi possível falar com o servidor. Confira o endereço e a rede.",
            )
        }
}
