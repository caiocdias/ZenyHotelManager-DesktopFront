package com.zeny.desktop.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppRootTest {
    private class MemorySettings(
        override var serverUrl: String? = null,
        override var lastLogin: String? = null,
    ) : LocalSettings {
        override val deviceId: String = "device-1"
    }

    /** Pede a troca de senha no login; a troca devolve a sessão sem pendência. */
    private class FakeConnection : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
        val changes = mutableListOf<Pair<String, String>>()

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

    @Test
    fun isShellDevModeOnlyWithFlag() {
        val none: (String) -> String? = { null }

        fun env(value: String?): (String) -> String? = { if (it == "ZENY_DEV_SHELL") value else null }

        fun prop(value: String?): (String) -> String? = { if (it == "zeny.devShell") value else null }

        assertFalse(isShellDevMode(none, none))
        assertTrue(isShellDevMode(env("1"), none))
        assertTrue(isShellDevMode(none, prop("true")))
        listOf("0", "true", "TRUE", "yes", "", " 1", "1 ").forEach {
            assertFalse(isShellDevMode(env(it), none), "ZENY_DEV_SHELL='$it'")
        }
        listOf("1", "TRUE", "True", "false", "", " true").forEach {
            assertFalse(isShellDevMode(none, prop(it)), "zeny.devShell='$it'")
        }
        // A flag do catálogo não liga a casca de desenvolvimento.
        assertFalse(isShellDevMode({ if (it == "ZENY_DEV") "1" else null }, { if (it == "zeny.dev") "true" else null }))
    }

    @Test
    fun startsOnLoginWithSavedServerAndLastLogin() =
        runComposeUiTest {
            val settings = MemorySettings(serverUrl = "http://salvo:9000", lastLogin = "ana")
            setContent {
                val scope = rememberCoroutineScope()
                val controller =
                    remember {
                        AppController(settings, { error("sem conexão") }, { ProbeResult.Ok("1.0") }, scope)
                    }
                ZenyTheme { AppRoot(controller, devMode = false) }
            }
            onNodeWithTag("login-server").assertTextContains("http://salvo:9000")
            onNodeWithTag("login-user").assertTextContains("ana")
            onNodeWithText("Entrar").assertExists()
        }

    @Test
    fun followsTheControllerFromLoginToPasswordChangeAndOn() =
        runComposeUiTest {
            val connection = FakeConnection()
            setContent {
                val scope = rememberCoroutineScope()
                val controller =
                    remember {
                        AppController(MemorySettings(), { connection }, { ProbeResult.Ok("1.0") }, scope)
                    }
                ZenyTheme { AppRoot(controller, devMode = false) }
            }
            onNodeWithTag("login-user").performTextInput("ana")
            onNodeWithTag("login-password").performTextInput("senha-antiga")
            onNodeWithText("Entrar").performClick()
            waitForIdle()

            onNodeWithTag("password-current").performTextInput("senha-antiga")
            onNodeWithTag("password-new").performTextInput("uma-senha-bem-longa")
            onNodeWithTag("password-confirm").performTextInput("uma-senha-bem-longa")
            onNodeWithText("Trocar senha").performClick()
            waitForIdle()

            assertEquals(listOf("senha-antiga" to "uma-senha-bem-longa"), connection.changes)
            onNodeWithText("Entrar").assertDoesNotExist()
            onNodeWithText("Trocar senha").assertDoesNotExist()
        }
}
