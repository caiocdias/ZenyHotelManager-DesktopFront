package com.zeny.desktop.core.session

import com.zeny.desktop.core.error.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Refresher falso que modela a rotação do servidor: o refresh token vale uma vez e gira ao chegar;
 * repetido, responde `REFRESH_TOKEN_REUSED`. A resposta pode ficar presa num portão do teste.
 */
class RotatingRefresher(
    initialRefreshToken: String = "R1",
) : TokenRefresher {
    private val lock = Any()
    private var validRefreshToken = initialRefreshToken
    private var generation = 1
    val received = CopyOnWriteArrayList<String>()
    val gates = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
    val failures = ConcurrentLinkedQueue<ApiError>()
    val entered = Channel<Unit>(Channel.UNLIMITED)

    val calls: Int get() = received.size

    override suspend fun refresh(current: Tokens): Tokens {
        received += current.refreshToken
        entered.trySend(Unit)
        failures.poll()?.let { throw it }
        val next =
            synchronized(lock) {
                if (current.refreshToken != validRefreshToken) {
                    throw ApiError.Problem(401, "REFRESH_TOKEN_REUSED", null, emptyList())
                }
                generation++
                validRefreshToken = "R$generation"
                Tokens("A$generation", "R$generation", current.sessionId, current.userId, false)
            }
        gates.poll()?.await()
        return next
    }
}

// O refresh roda em threads reais (Dispatchers.Default), como no escopo do core.
@Suppress("InjectDispatcher")
class AuthenticatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", true)) }
    private val refresher = RotatingRefresher()
    private val ended = CopyOnWriteArrayList<String?>()
    private val auth = Authenticator(store, refresher, scope) { ended += it }

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun concurrentRejectionsRefreshOnce() =
        runTest(timeout = 5.seconds) {
            val go = CompletableDeferred<Unit>()
            val callers =
                List(5) {
                    async(Dispatchers.Default) {
                        go.await()
                        auth.refreshAfterRejection("A1")
                    }
                }

            go.complete(Unit)

            assertEquals(List(5) { "A2" }, callers.awaitAll())
            assertEquals(1, refresher.calls)
            assertEquals("A2", store.current()?.accessToken)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun staleRejectionReusesNewToken() =
        runTest(timeout = 5.seconds) {
            assertEquals("A2", auth.refreshAfterRejection("A1"))

            assertEquals("A2", auth.refreshAfterRejection("A1"))
            assertEquals(1, refresher.calls)
        }

    @Test
    fun refreshNetworkFailureKeepsSession() =
        runTest(timeout = 5.seconds) {
            refresher.failures += ApiError.Network(IOException("sem rede"))

            assertFailsWith<ApiError.Network> { auth.refreshAfterRejection("A1") }

            assertEquals("A1", store.current()?.accessToken)
            assertTrue(ended.isEmpty())
            assertEquals("A2", auth.refreshAfterRejection("A1"))
            assertEquals(2, refresher.calls)
        }

    @Test
    fun refreshRejectedEndsSession() =
        runTest(timeout = 5.seconds) {
            store.set(Tokens("A0", "R0", "s1", "u1", false))
            val gate = CompletableDeferred<Unit>()
            val callers =
                List(3) {
                    async(Dispatchers.Default) {
                        gate.await()
                        auth.refreshAfterRejection("A0")
                    }
                }

            gate.complete(Unit)

            assertEquals(List(3) { null }, callers.awaitAll())
            assertNull(store.current())
            assertEquals(listOf<String?>("REFRESH_TOKEN_REUSED"), ended.toList())
        }

    @Test
    fun cancelledCallerDoesNotCancelRefresh() =
        runTest(timeout = 5.seconds) {
            val gate = CompletableDeferred<Unit>()
            refresher.gates += gate
            val first = launch(start = CoroutineStart.UNDISPATCHED) { auth.refreshAfterRejection("A1") }
            refresher.entered.receive()

            first.cancelAndJoin()
            gate.complete(Unit)

            assertEquals("A2", auth.refreshAfterRejection("A1"))
            assertEquals(listOf("R1"), refresher.received.toList())
            assertEquals("A2", store.current()?.accessToken)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun logoutDuringRefreshIsNotUndone() =
        runTest(timeout = 5.seconds) {
            val gate = CompletableDeferred<Unit>()
            refresher.gates += gate
            val caller = async(start = CoroutineStart.UNDISPATCHED) { auth.refreshAfterRejection("A1") }
            refresher.entered.receive()

            store.clear()
            gate.complete(Unit)

            assertNull(caller.await())
            assertNull(store.current())
            assertTrue(ended.isEmpty())
        }

    @Test
    fun newLoginDuringRefreshIsKept() =
        runTest(timeout = 5.seconds) {
            val gate = CompletableDeferred<Unit>()
            refresher.gates += gate
            val caller = async(start = CoroutineStart.UNDISPATCHED) { auth.refreshAfterRejection("A1") }
            refresher.entered.receive()

            store.set(Tokens("B1", "S1", "s2", "u2", false))
            gate.complete(Unit)

            assertNull(caller.await())
            assertEquals("B1", store.current()?.accessToken)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun rejectedRefreshAfterNewLoginKeepsNewSession() =
        runTest(timeout = 5.seconds) {
            val gate = CompletableDeferred<Unit>()
            val rejecting =
                Authenticator(
                    store,
                    {
                        gate.await()
                        throw ApiError.Problem(401, "REFRESH_TOKEN_REUSED", null, emptyList())
                    },
                    scope,
                ) { ended += it }
            val caller = async(start = CoroutineStart.UNDISPATCHED) { rejecting.refreshAfterRejection("A1") }

            store.set(Tokens("B1", "S1", "s2", "u2", false))
            gate.complete(Unit)

            assertNull(caller.await())
            assertEquals("B1", store.current()?.accessToken)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun forceRefreshRunsAfterInFlightRefresh() =
        runTest(timeout = 5.seconds) {
            val gate = CompletableDeferred<Unit>()
            refresher.gates += gate
            val first = async(start = CoroutineStart.UNDISPATCHED) { auth.refreshAfterRejection("A1") }
            refresher.entered.receive()
            val forced = async(start = CoroutineStart.UNDISPATCHED) { auth.forceRefresh() }

            gate.complete(Unit)

            assertEquals("A2", first.await())
            val tokens = forced.await()
            assertEquals("A3", tokens?.accessToken)
            assertFalse(tokens!!.passwordChangeRequired)
            assertEquals(listOf("R1", "R2"), refresher.received.toList())
            assertEquals("A3", store.current()?.accessToken)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun tokensToStringHidesSecrets() {
        val text = Tokens("access-secreto", "refresh-secreto", "s1", "u1", false).toString()

        assertFalse("access-secreto" in text)
        assertFalse("refresh-secreto" in text)
        assertTrue("s1" in text)
    }
}
