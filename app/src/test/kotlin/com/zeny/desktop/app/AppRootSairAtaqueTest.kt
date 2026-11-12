package com.zeny.desktop.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val emptyText =
    SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))

/** Ataque ao "Sair" da troca de senha obrigatória, pela raiz do app e com conexão falsa (nada de Ktor). */
@OptIn(ExperimentalTestApi::class)
class AppRootSairAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    /** Todo login pede a troca de senha; o logout pode ficar preso em `logoutGate`. */
    private class Fake : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
        val changes = mutableListOf<Pair<String, String>>()
        var logouts = 0
        var logoutGate: CompletableDeferred<Unit>? = null

        override suspend fun login(
            login: String,
            password: String,
        ) {
            state.value = SessionState.Active("user-1", "session-1", passwordChangeRequired = true)
        }

        override suspend fun changePassword(
            current: String,
            new: String,
        ) {
            changes += current to new
            state.value = SessionState.Active("user-1", "session-1", passwordChangeRequired = false)
        }

        override suspend fun retryPasswordChangeRefresh() = Unit

        override suspend fun logout() {
            logouts++
            logoutGate?.await()
            state.value = SessionState.LoggedOut
        }

        override suspend fun me(): Me = error("não usado")

        override suspend fun hotel(): HotelInfo = error("não usado")

        override suspend fun businessDate(): BusinessDate = error("não usado")

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun close() = Unit
    }

    private fun ComposeUiTest.root(fake: Fake) {
        setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember { AppController(MemorySettings(), { fake }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
    }

    private fun ComposeUiTest.loginToPasswordChange() {
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("senha-antiga")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        onNodeWithTag("password-current").assertExists()
    }

    @Test
    fun typedPasswordsDoNotSurviveSairIntoTheNextPasswordChange() =
        runComposeUiTest {
            val fake = Fake()
            root(fake)
            loginToPasswordChange()
            onNodeWithTag("password-current").performTextInput("senha-antiga")
            onNodeWithTag("password-new").performTextInput("nova-senha-bem-longa-1")
            onNodeWithTag("password-confirm").performTextInput("nova-senha-bem-longa-1")

            onNodeWithText("Sair").performClick()
            waitForIdle()
            onNodeWithText("Entrar").assertExists()
            loginToPasswordChange()

            onNodeWithTag("password-current").assert(emptyText)
            onNodeWithTag("password-new").assert(emptyText)
            onNodeWithTag("password-confirm").assert(emptyText)
            assertTrue(fake.changes.isEmpty())
            assertEquals(1, fake.logouts)
        }

    @Test
    fun sairReturnsToLoginAtOnceEvenIfTheServerLogoutNeverAnswers() =
        runComposeUiTest {
            val fake = Fake().apply { logoutGate = CompletableDeferred() }
            root(fake)
            loginToPasswordChange()

            onNodeWithText("Sair").performClick()
            waitForIdle()

            assertEquals(1, fake.logouts)
            onNodeWithTag("password-current").assertDoesNotExist()
            onNodeWithText("Entrar").assertExists()
            onNodeWithTag("login-notice").assertDoesNotExist()
        }

    @Test
    fun sairDoesNotSendTheTypedPasswordsToTheServer() =
        runComposeUiTest {
            val fake = Fake()
            root(fake)
            loginToPasswordChange()
            onNodeWithTag("password-current").performTextInput("senha-antiga")
            onNodeWithTag("password-new").performTextInput("nova-senha-bem-longa-1")
            onNodeWithTag("password-confirm").performTextInput("nova-senha-bem-longa-1")

            onNodeWithText("Sair").performClick()
            waitForIdle()

            assertTrue(fake.changes.isEmpty(), "o Sair enviou uma troca de senha")
        }
}
