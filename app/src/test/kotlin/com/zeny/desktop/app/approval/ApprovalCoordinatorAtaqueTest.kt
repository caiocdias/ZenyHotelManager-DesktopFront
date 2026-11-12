package com.zeny.desktop.app.approval

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.http.ApiResponse
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
class ApprovalCoordinatorAtaqueTest {
    private fun required() =
        ApiError.ApprovalRequired("iam.user.deactivate", "iam.users.manage", "iam.user:0199", null, null)

    private class Act(
        val required: ApiError.ApprovalRequired,
        private val onRetry: (String) -> ApiResponse<String> = { ApiResponse("ok", null, 200) },
    ) : ApprovableAction<String> {
        val tokens = mutableListOf<String?>()

        override suspend fun run(approvalToken: String?): ApiResponse<String> {
            tokens += approvalToken
            if (approvalToken == null) throw required
            return onRetry(approvalToken)
        }
    }

    private class H(
        scope: CoroutineScope,
        val grantResult: suspend (ApprovalInput) -> String,
    ) {
        val ended = MutableSharedFlow<Unit>()
        val grants = mutableListOf<ApprovalInput>()
        val c =
            ApprovalCoordinator(
                grant = { _, input ->
                    grants += input
                    grantResult(input)
                },
                sessionEnded = ended,
                scope = scope,
            )

        fun fill(
            login: String = "joana",
            pin: String = "1234",
            reason: String = "motivo",
        ) {
            c.login = login
            c.pin = pin
            c.reason = reason
        }

        fun assertCleared() {
            assertNull(c.pending)
            assertNull(c.dialog)
            assertEquals("", c.login)
            assertEquals("", c.pin)
            assertEquals("", c.reason)
        }
    }

    private fun TestScope.h(g: suspend (ApprovalInput) -> String = { "t1" }) = H(backgroundScope, g)

    @Test
    fun rejectionClearsPinAndKeepsMessageFreeOfPinAndRawJson() =
        runTest {
            val bad = ApiError.Problem(403, "INVALID_APPROVER_CREDENTIALS", "Credenciais inválidas", emptyList())
            val h = h { throw bad }
            val action = Act(required())
            val result = async { h.c.run(action) }
            runCurrent()
            h.fill(pin = "987654")
            h.c.confirm()
            runCurrent()

            val d = assertNotNull(h.c.dialog)
            assertEquals("", d.pin)
            assertEquals("", h.c.pin)
            val msg = assertNotNull(d.message)
            assertFalse(msg.contains("987654"), msg)
            assertFalse(msg.contains("{") || msg.contains("INVALID_APPROVER_CREDENTIALS"), msg)
            assertFalse(d.toString().contains("987654"))
            assertEquals("joana", d.login)
            h.c.cancel()
            assertNull(result.await())
            h.assertCleared()
        }

    @Test
    fun cancelAfterRejectionClearsEverythingWithoutRetry() =
        runTest {
            val h = h { throw ApiError.Network(java.io.IOException("x")) }
            val action = Act(required())
            val result = async { h.c.run(action) }
            runCurrent()
            h.fill()
            h.c.confirm()
            runCurrent()
            h.c.cancel()

            assertNull(result.await())
            assertEquals(listOf<String?>(null), action.tokens)
            h.assertCleared()
        }

    @Test
    fun refillingAfterRejectionGrantsAgainAndRetriesExactlyOnce() =
        runTest {
            var n = 0
            val h = h { if (++n == 1) throw ApiError.Network(java.io.IOException("x")) else "tok-2" }
            val action = Act(required())
            val result = async { h.c.run(action) }
            runCurrent()
            h.fill()
            h.c.confirm()
            runCurrent()
            h.c.confirm() // PIN limpo: nada acontece
            runCurrent()
            assertEquals(1, h.grants.size)
            h.c.pin = "5678"
            h.c.confirm()

            assertEquals("ok", result.await()?.value)
            assertEquals(2, h.grants.size)
            assertEquals("5678", h.grants[1].approverPin)
            assertEquals(listOf(null, "tok-2"), action.tokens)
            h.assertCleared()
        }

    @Test
    fun sessionEndedOnTheRetryPropagatesAndLeavesNothing() =
        runTest {
            val end = ApiError.SessionEnded("SESSION_REVOKED")
            val h = h()
            val action = Act(required()) { throw end }
            val result = async { runCatching { h.c.run(action) } }
            runCurrent()
            h.fill()
            h.c.confirm()

            assertSame(end, result.await().exceptionOrNull())
            assertEquals(listOf(null, "t1"), action.tokens)
            h.assertCleared()
        }

    @Test
    fun grantRuntimeFailurePropagatesClosesAndClearsPin() =
        runTest {
            val boom = IllegalArgumentException("falha")
            val h = h { throw boom }
            val action = Act(required())
            val result = async { runCatching { h.c.run(action) } }
            runCurrent()
            h.fill(pin = "987654")
            h.c.confirm()

            val thrown = result.await().exceptionOrNull()
            assertTrue(thrown is IllegalArgumentException && thrown.message == "falha", "$thrown")
            assertEquals(listOf<String?>(null), action.tokens)
            h.assertCleared()
        }

    @Test
    fun sessionEndWithRejectionMessageClearsMessageAndLogin() =
        runTest {
            val h = h { throw ApiError.Network(java.io.IOException("x")) }
            val result = async { h.c.run(Act(required())) }
            runCurrent()
            h.fill()
            h.c.confirm()
            runCurrent()
            assertNotNull(h.c.dialog?.message)

            h.ended.emit(Unit)
            runCurrent()

            assertNull(result.await())
            h.assertCleared()
        }

    @Test
    fun sessionEndWithoutPendingIsHarmlessAndLaterRunStillWorks() =
        runTest {
            val h = h()
            h.ended.emit(Unit)
            runCurrent()
            val action = Act(required())
            val result = async { h.c.run(action) }
            runCurrent()
            assertNotNull(h.c.dialog)
            h.fill()
            h.c.confirm()
            assertEquals("ok", result.await()?.value)
        }

    @Test
    fun repeatedConfirmBurstGrantsOnce() =
        runTest {
            val h = h()
            val action = Act(required())
            val result = async { h.c.run(action) }
            runCurrent()
            h.fill()
            repeat(6) { h.c.confirm() }
            assertEquals("ok", result.await()?.value)
            assertEquals(1, h.grants.size)
            assertEquals(listOf(null, "t1"), action.tokens)
        }

    @Test
    fun confirmAndCancelAfterTheRunEndedDoNothing() =
        runTest {
            val h = h()
            val result = async { h.c.run(Act(required())) }
            runCurrent()
            h.fill()
            h.c.confirm()
            result.await()
            h.fill()
            h.c.confirm()
            h.c.cancel()
            runCurrent()
            assertEquals(1, h.grants.size)
        }

    @Test
    fun grantReceivesTrimmedLoginAndReasonButPinUntouched() =
        runTest {
            val h = h()
            val result = async { h.c.run(Act(required())) }
            runCurrent()
            h.fill(login = "  joana  ", pin = "1234", reason = "  porque sim \n")
            h.c.confirm()
            result.await()
            assertEquals(ApprovalInput("joana", "1234", "porque sim"), h.grants.single())
        }

    @Test
    fun confirmRejectsUnicodeDigitsSignsAndSpacesInThePin() =
        runTest {
            val h = h()
            val result = async { h.c.run(Act(required())) }
            runCurrent()
            listOf("٣٣٣٣", "１２３４", " 1234", "1234 ", "1234\n", "+1234", "-1234", "12.34", "123", "123456789")
                .forEach {
                    h.fill(pin = it)
                    h.c.confirm()
                    runCurrent()
                }
            assertTrue(h.grants.isEmpty(), "grants: ${h.grants}")
            h.fill(reason = " \n\t ")
            h.c.confirm()
            h.fill(reason = "x".repeat(501))
            h.c.confirm()
            h.fill(login = "   ")
            h.c.confirm()
            runCurrent()
            assertTrue(h.grants.isEmpty(), "grants: ${h.grants}")
            h.c.cancel()
            assertNull(result.await())
        }

    @Test
    fun confirmAcceptsPinBoundsAndReasonBounds() =
        runTest {
            listOf("1234", "12345678").forEach { pin ->
                val h = h()
                val result = async { h.c.run(Act(required())) }
                runCurrent()
                h.fill(pin = pin, reason = "x".repeat(500) + "   ")
                h.c.confirm()
                assertEquals("ok", result.await()?.value, pin)
                assertEquals(
                    500,
                    h.grants
                        .single()
                        .reason.length,
                )
            }
        }

    @Test
    fun closeWithPendingReturnsNullAndClears() =
        runTest {
            val h = h()
            val action = Act(required())
            val result = async { h.c.run(action) }
            runCurrent()
            h.fill()
            h.c.close()
            assertNull(result.await())
            assertEquals(listOf<String?>(null), action.tokens)
            h.assertCleared()
        }

    @Test
    fun secondRunErrorDoesNotLeakPinAndDoesNotDisturbThePending() =
        runTest {
            val h = h()
            val result = async { h.c.run(Act(required())) }
            runCurrent()
            h.fill(pin = "987654")
            val e = assertFailsWith<IllegalStateException> { h.c.run(Act(required())) }
            assertFalse(e.message.orEmpty().contains("987654"))
            assertNotNull(h.c.dialog)
            assertEquals("987654", h.c.pin)
            h.c.cancel()
            assertNull(result.await())
        }
}
