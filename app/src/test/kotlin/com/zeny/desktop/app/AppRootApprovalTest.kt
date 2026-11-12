package com.zeny.desktop.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.approval.ApprovalCoordinator
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AppRootApprovalTest {
    private class MemorySettings(
        override var serverUrl: String? = null,
        override var lastLogin: String? = null,
    ) : LocalSettings {
        override val deviceId: String = "device-1"
    }

    private class FakeConnection : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
        val grants = mutableListOf<ApprovalInput>()

        override suspend fun login(
            login: String,
            password: String,
        ) {
            state.value = SessionState.Active("user-1", "session-1", passwordChangeRequired = false)
        }

        override suspend fun changePassword(
            current: String,
            new: String,
        ) = Unit

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
        ): String {
            grants += input
            return "token-1"
        }

        override fun close() = Unit
    }

    private val required =
        ApiError.ApprovalRequired("iam.user.deactivate", "iam.users.manage", "iam.user:1", null, null)

    private class RecordingAction(
        private val required: ApiError.ApprovalRequired,
    ) : ApprovableAction<String> {
        val tokens = mutableListOf<String?>()

        override suspend fun run(approvalToken: String?): ApiResponse<String> {
            tokens += approvalToken
            if (approvalToken == null) throw required
            return ApiResponse("ok", null, 200)
        }
    }

    @Test
    fun hostDrawsTheDialogWhilePendingAndApproves() =
        runComposeUiTest {
            val action = RecordingAction(required)
            val grants = mutableListOf<ApprovalInput>()
            lateinit var result: Deferred<ApiResponse<String>?>
            lateinit var coordinator: ApprovalCoordinator
            setContent {
                val scope = rememberCoroutineScope()
                coordinator =
                    remember {
                        ApprovalCoordinator({ _, input -> "token-1".also { grants += input } }, emptyFlow(), scope)
                    }
                ZenyTheme { ApprovalHost(coordinator) }
                remember { scope.async { coordinator.run(action) }.also { result = it } }
            }
            waitForIdle()

            onNodeWithText("Aprovação de superior").assertExists()
            onNodeWithText(required.userMessage).assertExists()
            onNodeWithTag("approval-login").performTextInput("joana.gerente")
            onNodeWithTag("approval-pin").performTextInput("1234")
            onNodeWithTag("approval-reason").performTextInput("Cortesia")
            onNodeWithTag("approval-confirm").performClick()
            waitForIdle()

            assertEquals(listOf(ApprovalInput("joana.gerente", "1234", "Cortesia")), grants)
            assertEquals(listOf(null, "token-1"), action.tokens)
            assertTrue(result.isCompleted)
            onNodeWithText("Aprovação de superior").assertDoesNotExist()
        }

    @Test
    fun hostCancelClosesTheDialog() =
        runComposeUiTest {
            val action = RecordingAction(required)
            lateinit var result: Deferred<ApiResponse<String>?>
            setContent {
                val scope = rememberCoroutineScope()
                val coordinator = remember { ApprovalCoordinator({ _, _ -> "token-1" }, emptyFlow(), scope) }
                ZenyTheme { ApprovalHost(coordinator) }
                remember { scope.async { coordinator.run(action) }.also { result = it } }
            }
            waitForIdle()

            onNodeWithText("Cancelar").performClick()
            waitForIdle()

            assertTrue(result.isCompleted)
            onNodeWithText("Aprovação de superior").assertDoesNotExist()
            assertEquals(listOf<String?>(null), action.tokens)
        }

    @Test
    fun sessionCoordinatorGrantsThroughTheConnectionAndEndsWhenLeavingTheShell() =
        runTest {
            val connection = FakeConnection()
            val controller =
                AppController(MemorySettings(), { connection }, { ProbeResult.Ok("1.0") }, backgroundScope)
            controller.login(ServerUrl.parse("http://localhost:8088")!!, "ana", "senha")
            runCurrent()
            assertEquals(Screen.Shell, controller.screen.value)

            val approvals = sessionApprovals(controller, backgroundScope)
            val approved = async { approvals.run(RecordingAction(required)) }
            runCurrent()
            approvals.login = "joana.gerente"
            approvals.pin = "1234"
            approvals.reason = "Cortesia"
            approvals.confirm()
            assertEquals("ok", approved.await()?.value)
            assertEquals(listOf(ApprovalInput("joana.gerente", "1234", "Cortesia")), connection.grants)

            val pending = async { approvals.run(RecordingAction(required)) }
            runCurrent()
            approvals.pin = "5678"
            assertNotNull(approvals.dialog)

            controller.logout()
            runCurrent()

            assertNull(pending.await())
            assertNull(approvals.dialog)
            assertEquals("", approvals.pin)
            assertFalse(pending.isCancelled)
        }
}
