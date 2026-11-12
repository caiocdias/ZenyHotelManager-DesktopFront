package com.zeny.desktop.app.approval

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.http.ApiResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalCoordinatorTest {
    private fun required() =
        ApiError.ApprovalRequired(
            action = "iam.user.deactivate",
            permission = "iam.users.manage",
            resourceRef = "iam.user:0199d6b8",
            limit = null,
            detail = "Requer aprovação",
        )

    /** Recusa na primeira tentativa (sem token) e aceita a repetição com token; guarda os tokens recebidos. */
    private class RecordingAction(
        val required: ApiError.ApprovalRequired,
        private val onRetry: (String) -> ApiResponse<String> = { ApiResponse("ok", "\"v2\"", 200) },
    ) : ApprovableAction<String> {
        val tokens = mutableListOf<String?>()

        override suspend fun run(approvalToken: String?): ApiResponse<String> {
            tokens += approvalToken
            if (approvalToken == null) throw required
            return onRetry(approvalToken)
        }
    }

    private class Harness(
        scope: CoroutineScope,
        val grantResult: suspend (ApprovalInput) -> String = { "token-1" },
    ) {
        val ended = MutableSharedFlow<Unit>()
        val grants = mutableListOf<Pair<ApiError.ApprovalRequired, ApprovalInput>>()
        val coordinator =
            ApprovalCoordinator(
                grant = { required, input ->
                    grants += required to input
                    grantResult(input)
                },
                sessionEnded = ended,
                scope = scope,
            )

        fun fill(
            login: String = "joana.gerente",
            pin: String = "1234",
            reason: String = "Cortesia da gerência",
        ) {
            coordinator.login = login
            coordinator.pin = pin
            coordinator.reason = reason
        }
    }

    private fun TestScope.harness(grantResult: suspend (ApprovalInput) -> String = { "token-1" }) =
        Harness(backgroundScope, grantResult)

    private fun Harness.assertCleared() {
        assertNull(coordinator.pending)
        assertNull(coordinator.dialog)
        assertEquals("", coordinator.login)
        assertEquals("", coordinator.pin)
        assertEquals("", coordinator.reason)
    }

    @Test
    fun approvalRequiredOpensDialogAndRetriesOnceWithToken() =
        runTest {
            val h = harness()
            val action = RecordingAction(required())
            val result = async { h.coordinator.run(action) }
            runCurrent()

            assertSame(action.required, h.coordinator.pending)
            val dialog = assertNotNull(h.coordinator.dialog)
            assertEquals(action.required.userMessage, dialog.detail)
            listOf("iam.user.deactivate", "iam.users.manage", "iam.user:0199d6b8").forEach {
                assertFalse(dialog.detail.contains(it), "detail cru: $it")
            }
            assertFalse(dialog.busy)
            assertNull(dialog.message)

            h.fill(login = " joana.gerente ", reason = " Cortesia da gerência ")
            h.coordinator.confirm()
            val response = assertNotNull(result.await())

            assertEquals("ok", response.value)
            assertEquals("\"v2\"", response.etag)
            assertEquals(listOf(null, "token-1"), action.tokens)
            assertEquals(1, h.grants.size)
            assertSame(action.required, h.grants.single().first)
            assertEquals(ApprovalInput("joana.gerente", "1234", "Cortesia da gerência"), h.grants.single().second)
            h.assertCleared()
        }

    @Test
    fun runWithoutApprovalReturnsTheResponseDirectly() =
        runTest {
            val h = harness()
            val tokens = mutableListOf<String?>()
            val response =
                h.coordinator.run { token ->
                    tokens += token
                    ApiResponse(42, null, 201)
                }

            assertEquals(42, response?.value)
            assertEquals(listOf<String?>(null), tokens)
            assertNull(h.coordinator.dialog)
        }

    @Test
    fun cancelReturnsNullWithoutRetry() =
        runTest {
            val h = harness()
            val action = RecordingAction(required())
            val result = async { h.coordinator.run(action) }
            runCurrent()
            h.fill()

            h.coordinator.cancel()
            // O diálogo some e o PIN sai do estado na hora, sem esperar a corrotina do `run`.
            h.assertCleared()

            assertNull(result.await())
            assertEquals(listOf<String?>(null), action.tokens)
            assertTrue(h.grants.isEmpty())
            h.assertCleared()
        }

    @Test
    fun confirmIgnoredOutsideTheRules() =
        runTest {
            val h = harness()
            val result = async { h.coordinator.run(RecordingAction(required())) }
            runCurrent()

            listOf(
                Triple("", "1234", "motivo"),
                Triple("joana", "123", "motivo"),
                Triple("joana", "123456789", "motivo"),
                Triple("joana", "12a4", "motivo"),
                Triple("joana", "1234", "   "),
                Triple("joana", "1234", "x".repeat(501)),
            ).forEach { (login, pin, reason) ->
                h.fill(login, pin, reason)
                assertFalse(assertNotNull(h.coordinator.dialog).canConfirm, "login=$login reason=${reason.length}")
                h.coordinator.confirm()
                runCurrent()
            }

            assertTrue(h.grants.isEmpty())
            assertTrue(result.isActive)
            h.coordinator.cancel()
            assertNull(result.await())
        }

    @Test
    fun rejectedApprovalKeepsDialogAndDoesNotRetry() =
        runTest {
            val rejection = ApiError.Problem(403, "APPROVAL_CREDENTIALS_INVALID", null, emptyList())
            var attempts = 0
            val h =
                harness { input ->
                    attempts++
                    if (attempts == 1) throw rejection
                    assertEquals("5678", input.approverPin)
                    "token-2"
                }
            val action = RecordingAction(required())
            val result = async { h.coordinator.run(action) }
            runCurrent()
            h.fill(pin = "987654")

            h.coordinator.confirm()
            runCurrent()

            assertSame(action.required, h.coordinator.pending)
            val dialog = assertNotNull(h.coordinator.dialog)
            assertEquals(rejection.userMessage, dialog.message)
            assertFalse(dialog.message!!.contains("987654"))
            assertFalse(dialog.busy)
            assertEquals("", h.coordinator.pin)
            assertEquals("joana.gerente", h.coordinator.login)
            assertEquals("Cortesia da gerência", h.coordinator.reason)
            assertEquals(listOf<String?>(null), action.tokens)
            assertTrue(result.isActive)

            // Corrigido o PIN, a aprovação segue e a ação é repetida uma vez.
            h.coordinator.pin = "5678"
            h.coordinator.confirm()
            assertEquals("ok", result.await()?.value)
            assertEquals(listOf(null, "token-2"), action.tokens)
            h.assertCleared()
        }

    @Test
    fun sessionEndedDuringGrantClosesAndPropagates() =
        runTest {
            val h = harness { throw ApiError.SessionEnded("SESSION_REVOKED") }
            val action = RecordingAction(required())
            val result = async { runCatching { h.coordinator.run(action) } }
            runCurrent()
            h.fill()

            h.coordinator.confirm()

            assertFailsWith<ApiError.SessionEnded> { result.await().getOrThrow() }
            assertEquals(listOf<String?>(null), action.tokens)
            h.assertCleared()
        }

    @Test
    fun sessionEndCancelsPendingAndClearsPin() =
        runTest {
            val h = harness()
            val action = RecordingAction(required())
            val result = async { h.coordinator.run(action) }
            runCurrent()
            h.fill()

            h.ended.emit(Unit)
            h.assertCleared()

            assertNull(result.await())
            assertEquals(listOf<String?>(null), action.tokens)
            assertTrue(h.grants.isEmpty())
            h.assertCleared()
        }

    @Test
    fun sessionEndDuringGrantCancelsTheGrantWithoutRetry() =
        runTest {
            val gate = CompletableDeferred<String>()
            val h = harness { gate.await() }
            val action = RecordingAction(required())
            val result = async { h.coordinator.run(action) }
            runCurrent()
            h.fill()
            h.coordinator.confirm()
            runCurrent()
            assertTrue(assertNotNull(h.coordinator.dialog).busy)

            h.ended.emit(Unit)
            h.assertCleared()
            gate.complete("token-tarde")

            assertNull(result.await())
            assertEquals(listOf<String?>(null), action.tokens)
            h.assertCleared()
        }

    /** Documenta o KDoc do `run`: a repetição aprovada é da feature; o fim da sessão não a cancela. */
    @Test
    fun sessionEndDuringTheApprovedRetryDoesNotCancelIt() =
        runTest {
            val retryGate = CompletableDeferred<ApiResponse<String>>()
            val tokens = mutableListOf<String?>()
            val action =
                ApprovableAction { approvalToken ->
                    tokens += approvalToken
                    if (approvalToken == null) throw required()
                    retryGate.await()
                }
            val h = harness()
            val result = async { h.coordinator.run(action) }
            runCurrent()
            h.fill()
            h.coordinator.confirm()
            runCurrent()
            assertEquals(listOf(null, "token-1"), tokens)

            h.ended.emit(Unit)
            runCurrent()
            assertFalse(result.isCancelled)
            retryGate.complete(ApiResponse("ok", null, 200))

            assertEquals("ok", assertNotNull(result.await()).value)
            h.assertCleared()
        }

    @Test
    fun coordinatorWorksAgainAfterASessionEnd() =
        runTest {
            val h = harness()
            val first = async { h.coordinator.run(RecordingAction(required())) }
            runCurrent()
            h.ended.emit(Unit)
            assertNull(first.await())

            val action = RecordingAction(required())
            val second = async { h.coordinator.run(action) }
            runCurrent()
            h.fill()
            h.coordinator.confirm()

            assertEquals("ok", second.await()?.value)
            assertEquals(listOf(null, "token-1"), action.tokens)
        }

    @Test
    fun secondApprovalRequiredPropagates() =
        runTest {
            val h = harness()
            val again = required()
            val action = RecordingAction(required()) { throw again }
            val result = async { runCatching { h.coordinator.run(action) } }
            runCurrent()
            h.fill()

            h.coordinator.confirm()

            assertSame(again, result.await().exceptionOrNull())
            assertEquals(listOf(null, "token-1"), action.tokens)
            assertEquals(1, h.grants.size)
            h.assertCleared()
        }

    @Test
    fun concurrentRunRejected() =
        runTest {
            val h = harness()
            val first = RecordingAction(required())
            val result = async { h.coordinator.run(first) }
            runCurrent()

            val second = RecordingAction(required())
            assertFailsWith<IllegalStateException> { h.coordinator.run(second) }

            assertTrue(second.tokens.isEmpty())
            assertSame(first.required, h.coordinator.pending)
            h.fill()
            h.coordinator.confirm()
            assertEquals("ok", result.await()?.value)
        }

    @Test
    fun runsThatMeetApprovalAtTheSameTimeRejectTheSecond() =
        runTest {
            val h = harness()
            val gate = CompletableDeferred<Unit>()
            val required = required()
            val slow =
                ApprovableAction { token ->
                    if (token == null) {
                        gate.await()
                        throw required
                    }
                    ApiResponse("lento", null, 200)
                }
            // A lenta parte antes e só recebe o APPROVAL_REQUIRED depois que a outra abriu o diálogo.
            val second = async { runCatching { h.coordinator.run(slow) } }
            val first = async { h.coordinator.run(RecordingAction(required())) }
            runCurrent()
            assertNotNull(h.coordinator.dialog)
            gate.complete(Unit)
            runCurrent()

            assertTrue(second.await().exceptionOrNull() is IllegalStateException)
            h.fill()
            h.coordinator.confirm()
            assertEquals("ok", first.await()?.value)
        }

    @Test
    fun doubleConfirmGrantsOnce() =
        runTest {
            // O primeiro pedido é recusado só depois dos cliques extras: um clique aceito no meio já teria
            // virado um segundo pedido logo depois da recusa.
            val gate = CompletableDeferred<Unit>()
            var attempts = 0
            val h =
                harness {
                    attempts++
                    if (attempts == 1) {
                        gate.await()
                        throw ApiError.Problem(403, "APPROVAL_CREDENTIALS_INVALID", null, emptyList())
                    }
                    "token-1"
                }
            val action = RecordingAction(required())
            val result = async { h.coordinator.run(action) }
            runCurrent()
            h.fill()

            h.coordinator.confirm()
            h.coordinator.fill()
            h.coordinator.confirm()
            runCurrent()
            h.coordinator.confirm()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(1, h.grants.size)
            assertNotNull(assertNotNull(h.coordinator.dialog).message)
            assertEquals(listOf<String?>(null), action.tokens)
            assertTrue(result.isActive)

            h.fill()
            h.coordinator.confirm()
            assertEquals("ok", result.await()?.value)
            assertEquals(2, h.grants.size)
            assertEquals(listOf(null, "token-1"), action.tokens)
        }

    @Test
    fun dismissIgnoredWhileGrantInFlight() =
        runTest {
            val gate = CompletableDeferred<String>()
            val h = harness { gate.await() }
            val action = RecordingAction(required())
            val result = async { h.coordinator.run(action) }
            runCurrent()
            h.fill()
            h.coordinator.confirm()
            runCurrent()

            h.coordinator.cancel()
            assertNotNull(h.coordinator.dialog)
            gate.complete("token-1")

            assertEquals("ok", result.await()?.value)
        }

    @Test
    fun callerCancellationClosesTheDialog() =
        runTest {
            val h = harness()
            val result = async { h.coordinator.run(RecordingAction(required())) }
            runCurrent()
            h.fill()

            result.cancel()
            runCurrent()

            h.assertCleared()
        }

    @Test
    fun otherErrorsPropagate() =
        runTest {
            val h = harness()
            val conflict = ApiError.Problem(409, "ROOM_OCCUPIED", null, emptyList())
            val failed = runCatching { h.coordinator.run<String> { throw conflict } }
            assertSame(conflict, failed.exceptionOrNull())
            assertNull(h.coordinator.dialog)

            val network = ApiError.Network(java.io.IOException("caiu"))
            val action = RecordingAction(required()) { throw network }
            val result = async { runCatching { h.coordinator.run(action) } }
            runCurrent()
            h.fill()
            h.coordinator.confirm()

            assertSame(network, result.await().exceptionOrNull())
            assertEquals(listOf(null, "token-1"), action.tokens)
            h.assertCleared()
        }

    @Test
    fun pinNeverInToString() =
        runTest {
            val h = harness()
            val result = async { h.coordinator.run(RecordingAction(required())) }
            runCurrent()
            h.fill(pin = "987654")

            val dialog = assertNotNull(h.coordinator.dialog)
            assertEquals("987654", dialog.pin)
            assertFalse(dialog.toString().contains("987654"), dialog.toString())
            assertFalse(h.coordinator.toString().contains("987654"), h.coordinator.toString())
            h.coordinator.cancel()
            assertNull(result.await())
        }

    private fun ApprovalCoordinator.fill() {
        login = "outra"
        pin = "4321"
        reason = "outro motivo"
    }
}
