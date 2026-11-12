package com.zeny.desktop.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
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

private const val ENDED_NOTICE = "Sua sessão foi encerrada. Entre novamente."
private const val SECRET = "segredo-que-nao-pode-vazar"
private const val NEW_SECRET = "nova-senha-bem-longa-123"

/** Texto desenhado (Text ou EditableText); o `InputText` cru fica na semântica de todo campo do Compose. */
private fun drawsText(secret: String) =
    SemanticsMatcher("desenha '$secret'") { node ->
        val texts =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty() +
                listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText))
        texts.any { it.text.contains(secret) }
    }

/** Ataques às telas de login e de troca de senha e à raiz (Task 6). Só com conexão falsa; nada de Ktor. */
@OptIn(ExperimentalTestApi::class)
class AppRootAtaqueTest {
    private class MemorySettings(
        override var serverUrl: String? = null,
        override var lastLogin: String? = null,
    ) : LocalSettings {
        override val deviceId: String = "device-1"
    }

    private class Fake : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
        var passwordChangeRequired = false
        var loginError: Exception? = null
        var changeError: Exception? = null
        var loginGate: CompletableDeferred<Unit>? = null
        val logins = mutableListOf<Pair<String, String>>()
        val changes = mutableListOf<Pair<String, String>>()

        override suspend fun login(
            login: String,
            password: String,
        ) {
            logins += login to password
            loginGate?.await()
            loginError?.let { throw it }
            state.value = SessionState.Active("user-1", "session-1", passwordChangeRequired)
        }

        override suspend fun changePassword(
            current: String,
            new: String,
        ) {
            changes += current to new
            changeError?.let { throw it }
            state.value = SessionState.Active("user-1", "session-1", passwordChangeRequired = false)
        }

        override suspend fun retryPasswordChangeRefresh() = Unit

        override suspend fun logout() {
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

    private fun ComposeUiTest.root(
        fake: Fake,
        settings: MemorySettings = MemorySettings(),
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { AppController(settings, { fake }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
    }

    private fun ComposeUiTest.typeCredentials(
        login: String = "ana",
        password: String = SECRET,
    ) {
        if (login.isNotEmpty()) onNodeWithTag("login-user").performTextInput(login)
        onNodeWithTag("login-password").performTextInput(password)
    }

    private fun ComposeUiTest.loginThenShowChange(fake: Fake) {
        root(fake)
        typeCredentials()
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        onNodeWithTag("password-current").assertExists()
    }

    @Test
    fun passwordNeverAppearsAsVisibleTextAndFieldIsEmptyAfterSubmit() =
        runComposeUiTest {
            val fake = Fake().apply { loginError = ApiError.Network(RuntimeException("boom")) }
            root(fake)
            typeCredentials()
            waitForIdle()
            onNode(drawsText(SECRET)).assertDoesNotExist()

            onNodeWithText("Entrar").performClick()
            waitForIdle()

            assertEquals(listOf("ana" to SECRET), fake.logins)
            onNode(drawsText(SECRET)).assertDoesNotExist()
            onNodeWithTag("login-message").assertExists()
        }

    @Test
    fun enterIsDisabledWhileLoginIsInFlightAndOnlyOneLoginReachesTheServer() =
        runComposeUiTest {
            val gate = CompletableDeferred<Unit>()
            val fake = Fake().apply { loginGate = gate }
            root(fake)
            typeCredentials()
            onNodeWithText("Entrar").performClick()
            waitForIdle()

            onNodeWithText("Entrar").assertIsNotEnabled()
            onNodeWithText("Entrar").performClick()
            waitForIdle()
            assertEquals(1, fake.logins.size)

            gate.complete(Unit)
            waitForIdle()
            assertEquals(1, fake.logins.size)
            onNodeWithText("Sessão iniciada.").assertExists()
        }

    @Test
    fun failedLoginStaysOnLoginShowsMessageAndReenablesEnter() =
        runComposeUiTest {
            val fake = Fake().apply { loginError = ApiError.Network(RuntimeException("boom")) }
            root(fake)
            typeCredentials()
            onNodeWithText("Entrar").performClick()
            waitForIdle()

            onNodeWithTag("login-message").assertExists()
            onNodeWithText("Entrar").assertIsEnabled()
            onNodeWithText("Sessão iniciada.").assertDoesNotExist()
            onNodeWithTag("login-user").assertTextContains("ana")
        }

    @Test
    fun blankPasswordShowsMessageAndNeverReachesTheServer() =
        runComposeUiTest {
            val fake = Fake()
            root(fake)
            onNodeWithTag("login-user").performTextInput("ana")
            onNodeWithText("Entrar").performClick()
            waitForIdle()

            onNodeWithTag("login-message").assertTextContains("Informe a senha.")
            assertTrue(fake.logins.isEmpty())
        }

    @Test
    fun loginGoesStraightToShellAndLogoutReturnsWithoutNoticeKeepingTheLastLogin() =
        runComposeUiTest {
            val fake = Fake()
            val settings = MemorySettings()
            root(fake, settings)
            typeCredentials()
            onNodeWithText("Entrar").performClick()
            waitForIdle()

            onNodeWithText("Sessão iniciada.").assertExists()
            onNodeWithTag("password-current").assertDoesNotExist()
            assertEquals("ana", settings.lastLogin)

            onNodeWithText("Sair").performClick()
            waitForIdle()

            onNodeWithText("Entrar").assertExists()
            onNodeWithTag("login-notice").assertDoesNotExist()
            onNodeWithTag("login-user").assertTextContains("ana")
            onNode(drawsText(SECRET)).assertDoesNotExist()
        }

    @Test
    fun sessionEndedInShellReturnsToLoginWithNoticeAndNoticeGoesAwayOnNextLogin() =
        runComposeUiTest {
            val fake = Fake()
            root(fake)
            typeCredentials()
            onNodeWithText("Entrar").performClick()
            waitForIdle()
            onNodeWithText("Sessão iniciada.").assertExists()

            fake.state.value = SessionState.Ended("expirou")
            waitForIdle()

            onNodeWithTag("login-notice").assertTextContains(ENDED_NOTICE)
            onNodeWithText("Sessão iniciada.").assertDoesNotExist()

            typeCredentials(login = "", password = NEW_SECRET)
            onNodeWithText("Entrar").performClick()
            waitForIdle()
            onNodeWithText("Sessão iniciada.").assertExists()
            onNodeWithTag("login-notice").assertDoesNotExist()
        }

    @Test
    fun sessionEndedOnPasswordChangeScreenReturnsToLoginWithNotice() =
        runComposeUiTest {
            val fake = Fake().apply { passwordChangeRequired = true }
            loginThenShowChange(fake)

            fake.state.value = SessionState.Ended("expirou")
            waitForIdle()

            onNodeWithTag("login-notice").assertTextContains(ENDED_NOTICE)
            onNodeWithTag("password-current").assertDoesNotExist()
        }

    @Test
    fun failedPasswordChangeStaysShowsMessageClearsFieldsAndAllowsRetry() =
        runComposeUiTest {
            val fake = Fake().apply { passwordChangeRequired = true }
            loginThenShowChange(fake)
            fake.changeError = ApiError.Network(RuntimeException("boom"))

            onNodeWithTag("password-current").performTextInput(SECRET)
            onNodeWithTag("password-new").performTextInput(NEW_SECRET)
            onNodeWithTag("password-confirm").performTextInput(NEW_SECRET)
            onNodeWithText("Trocar senha").performClick()
            waitForIdle()

            assertEquals(listOf(SECRET to NEW_SECRET), fake.changes)
            onNodeWithTag("password-message").assertExists()
            onNodeWithText("Trocar senha").assertIsEnabled()
            onNode(drawsText(SECRET)).assertDoesNotExist()
            onNode(drawsText(NEW_SECRET)).assertDoesNotExist()

            fake.changeError = null
            onNodeWithTag("password-current").performTextInput(SECRET)
            onNodeWithTag("password-new").performTextInput(NEW_SECRET)
            onNodeWithTag("password-confirm").performTextInput(NEW_SECRET)
            onNodeWithText("Trocar senha").performClick()
            waitForIdle()

            assertEquals(2, fake.changes.size)
            onNodeWithText("Sessão iniciada.").assertExists()
        }

    @Test
    fun invalidPasswordChangeNeverReachesTheServer() =
        runComposeUiTest {
            val fake = Fake().apply { passwordChangeRequired = true }
            loginThenShowChange(fake)

            onNodeWithTag("password-current").performTextInput(SECRET)
            onNodeWithTag("password-new").performTextInput("curta")
            onNodeWithTag("password-confirm").performTextInput("curta")
            onNodeWithText("Trocar senha").performClick()
            waitForIdle()

            assertTrue(fake.changes.isEmpty())
            onNodeWithTag("password-message").assertExists()
        }
}
