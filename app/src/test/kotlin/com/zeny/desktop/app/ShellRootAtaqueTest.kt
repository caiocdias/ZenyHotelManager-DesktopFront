package com.zeny.desktop.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ataques à casca ligada na raiz (Task 7): carga em voo quando a sessão termina. */
@OptIn(ExperimentalTestApi::class)
class ShellRootAtaqueTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    private class Fake : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
        val meStarted = AtomicInteger()
        val meCancelled = AtomicInteger()
        val gate = CompletableDeferred<Unit>()

        override suspend fun login(
            login: String,
            password: String,
        ) {
            state.value = SessionState.Active("u-1", "s-1", false)
        }

        override suspend fun changePassword(
            current: String,
            new: String,
        ) = error("não usado")

        override suspend fun retryPasswordChangeRefresh() = Unit

        override suspend fun logout() {
            state.value = SessionState.LoggedOut
        }

        override suspend fun me(): Me {
            meStarted.incrementAndGet()
            try {
                gate.await()
            } catch (e: CancellationException) {
                meCancelled.incrementAndGet()
                throw e
            }
            return Me("u-1", "ana", "Ana Souza", setOf("rooms.status.read"))
        }

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 3, 7))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun close() = Unit
    }

    private fun logIn(
        fake: Fake,
        after: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember { AppController(Settings(), { fake }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        onNodeWithText("Entrar").performClick()
        waitForIdle()
        after()
    }

    @Test
    fun logoutWithMeInFlightCancelsTheShellLoad() {
        val fake = Fake()
        logIn(fake) {
            assertEquals(1, fake.meStarted.get())

            onNodeWithText("Sair").performClick()
            waitForIdle()

            onNodeWithText("Entrar").assertExists()
            assertEquals(1, fake.meCancelled.get(), "a leitura de me() seguiu viva depois do logout")
        }
    }

    @Test
    fun sessionEndedWithMeInFlightCancelsTheShellLoad() {
        val fake = Fake()
        logIn(fake) {
            fake.state.value = SessionState.Ended("expirou")
            waitForIdle()

            onNodeWithText("Entrar").assertExists()
            assertEquals(1, fake.meCancelled.get(), "a leitura de me() seguiu viva depois do fim da sessão")
        }
    }
}
