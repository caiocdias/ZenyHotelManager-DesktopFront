package com.zeny.desktop.app.login

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.design.theme.ZenyTheme
import kotlin.test.Test
import kotlin.test.assertEquals

private const val MASK = '•'
private val isPassword = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

/** O que a tela desenha no campo (o `InputText` cru fica só na semântica, como em todo campo do Compose). */
private fun showsOnlyMask(length: Int) =
    SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(MASK.toString().repeat(length)))

@OptIn(ExperimentalTestApi::class)
class LoginScreensUiTest {
    private class RecordingBackend : LoginBackend {
        val passwords = mutableListOf<String>()

        override suspend fun probe(url: ServerUrl): ProbeResult = ProbeResult.Ok("1.0")

        override suspend fun login(
            url: ServerUrl,
            login: String,
            password: String,
        ): LoginOutcome {
            passwords += password
            return LoginOutcome.Failed("Login ou senha inválidos.")
        }
    }

    @Test
    fun loginPasswordIsMasked() =
        runComposeUiTest {
            lateinit var state: LoginState
            setContent {
                val scope = rememberCoroutineScope()
                state = remember { LoginState(RecordingBackend(), "http://localhost:8088", "ana", scope) }
                ZenyTheme { LoginScreen(state, notice = null) }
            }
            onNodeWithTag("login-password").performTextInput("segredo")
            waitForIdle()

            assertEquals("segredo", state.password)
            onNodeWithTag("login-password").assert(isPassword).assert(showsOnlyMask("segredo".length))
        }

    @Test
    fun enterCallsSubmitAndShowsTheMessage() =
        runComposeUiTest {
            val backend = RecordingBackend()
            lateinit var state: LoginState
            setContent {
                val scope = rememberCoroutineScope()
                state = remember { LoginState(backend, "http://localhost:8088", "ana", scope) }
                ZenyTheme { LoginScreen(state, notice = null) }
            }
            onNodeWithTag("login-server").assertTextContains("http://localhost:8088")
            onNodeWithTag("login-user").assertTextContains("ana")
            onNodeWithTag("login-password").performTextInput("segredo")
            onNodeWithText("Entrar").performClick()
            waitForIdle()

            assertEquals(listOf("segredo"), backend.passwords)
            assertEquals("", state.password)
            onNodeWithTag("login-message").assertTextContains("Login ou senha inválidos.")
        }

    @Test
    fun testButtonShowsProbeResultAndNoticeIsShown() =
        runComposeUiTest {
            setContent {
                val scope = rememberCoroutineScope()
                val state = remember { LoginState(RecordingBackend(), "http://localhost:8088", null, scope) }
                ZenyTheme { LoginScreen(state, notice = "Sua sessão foi encerrada. Entre novamente.") }
            }
            onNodeWithTag("login-notice").assertTextContains("Sua sessão foi encerrada. Entre novamente.")
            onNodeWithText("Testar").performClick()
            waitForIdle()

            onNodeWithTag("login-probe-message").assertTextContains("Servidor encontrado (versão 1.0).")
        }

    @Test
    fun passwordChangeFieldsAreMaskedAndSubmitCallsChange() =
        runComposeUiTest {
            val changes = mutableListOf<Pair<String, String>>()
            setContent {
                val scope = rememberCoroutineScope()
                val state =
                    remember {
                        PasswordChangeState(
                            change = { current, new -> changes += current to new },
                            retry = {},
                            awaitingRefresh = { false },
                            scope = scope,
                        )
                    }
                ZenyTheme { PasswordChangeScreen(state) }
            }
            onNodeWithTag("password-current").performTextInput("senha-antiga")
            onNodeWithTag("password-new").performTextInput("uma-senha-bem-longa")
            onNodeWithTag("password-confirm").performTextInput("uma-senha-bem-longa")
            waitForIdle()
            onNodeWithTag("password-current").assert(isPassword).assert(showsOnlyMask("senha-antiga".length))
            listOf("password-new", "password-confirm").forEach {
                onNodeWithTag(it).assert(isPassword).assert(showsOnlyMask("uma-senha-bem-longa".length))
            }

            onNodeWithText("Trocar senha").performClick()
            waitForIdle()

            assertEquals(listOf("senha-antiga" to "uma-senha-bem-longa"), changes)
        }
}
