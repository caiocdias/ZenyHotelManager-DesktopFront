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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Refresher que, nas primeiras chamadas, espera um portão e lança o erro dado; depois delega à rotação.
 * Serve para segurar uma falha enquanto vários chamadores esperam o mesmo voo.
 */
private class FailingThenRotating(
    private val failure: () -> Throwable,
    private val failingCalls: Int = 1,
) : TokenRefresher {
    val rotating = RotatingRefresher()
    val gate = CompletableDeferred<Unit>()
    val calls = AtomicInteger()
    val entered = CompletableDeferred<Unit>()

    override suspend fun refresh(current: Tokens): Tokens {
        if (calls.incrementAndGet() <= failingCalls) {
            entered.complete(Unit)
            gate.await()
            throw failure()
        }
        return rotating.refresh(current)
    }
}

// O refresh roda em threads reais (Dispatchers.Default), como no escopo do core.
@Suppress("InjectDispatcher")
class AuthenticatorAttackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }
    private val ended = CopyOnWriteArrayList<String?>()

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun authWith(refresher: TokenRefresher) = Authenticator(store, refresher, scope) { ended += it }

    @Test
    fun twentyFiveConcurrentRejectionsShareOneRefreshAcrossManyRounds() =
        runTest(timeout = 5.seconds) {
            repeat(40) { round ->
                val roundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val roundStore = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }
                val refresher = RotatingRefresher()
                val roundEnded = CopyOnWriteArrayList<String?>()
                val auth = Authenticator(roundStore, refresher, roundScope) { roundEnded += it }
                val go = CompletableDeferred<Unit>()
                val callers =
                    List(25) {
                        async(Dispatchers.Default) {
                            go.await()
                            auth.refreshAfterRejection("A1")
                        }
                    }

                go.complete(Unit)

                assertEquals(List(25) { "A2" }, callers.awaitAll(), "rodada $round")
                assertEquals(listOf("R1"), refresher.received.toList(), "rodada $round")
                assertTrue(roundEnded.isEmpty(), "rodada $round")
                roundScope.cancel()
            }
        }

    @Test
    fun rotatedRefreshTokenIsNeverResentAcrossConsecutiveRejectionWaves() =
        runTest(timeout = 5.seconds) {
            val refresher = RotatingRefresher()
            val auth = authWith(refresher)

            for (wave in 1..30) {
                val rejected = store.current()!!.accessToken
                val previous = "A${wave - 1}"
                val callers =
                    List(10) { i ->
                        // Metade rejeita o token atual; a outra metade chega atrasada com o token anterior.
                        val token = if (i % 2 == 0 || wave == 1) rejected else previous
                        async(Dispatchers.Default) { auth.refreshAfterRejection(token) }
                    }
                val results = callers.awaitAll()
                assertTrue(results.all { it == "A${wave + 1}" || it == rejected }, "onda $wave: $results")
                assertEquals("A${wave + 1}", store.current()?.accessToken, "onda $wave")
            }

            assertEquals((1..30).map { "R$it" }, refresher.received.toList())
            assertTrue(ended.isEmpty(), "nenhum REFRESH_TOKEN_REUSED: $ended")
        }

    @Test
    fun cancellingEveryWaiterLetsTheRefreshFinishAndStoreTheNewPair() =
        runTest(timeout = 5.seconds) {
            val refresher = RotatingRefresher()
            val gate = CompletableDeferred<Unit>()
            refresher.gates += gate
            val auth = authWith(refresher)
            val waiters = List(6) { async(start = CoroutineStart.UNDISPATCHED) { auth.refreshAfterRejection("A1") } }
            refresher.entered.receive()

            waiters.forEach { it.cancelAndJoin() }
            gate.complete(Unit)

            assertTrue(waiters.all { it.isCancelled })
            assertEquals("A2", auth.refreshAfterRejection("A1"))
            assertEquals(listOf("R1"), refresher.received.toList())
            assertEquals("A2", store.current()?.accessToken)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun cancellingSomeWaitersStillDeliversTheNewTokenToTheOthers() =
        runTest(timeout = 5.seconds) {
            val refresher = RotatingRefresher()
            val gate = CompletableDeferred<Unit>()
            refresher.gates += gate
            val auth = authWith(refresher)
            val waiters = List(8) { async(start = CoroutineStart.UNDISPATCHED) { auth.refreshAfterRejection("A1") } }
            refresher.entered.receive()

            waiters.filterIndexed { i, _ -> i % 2 == 0 }.forEach { it.cancelAndJoin() }
            gate.complete(Unit)

            val survivors = waiters.filterIndexed { i, _ -> i % 2 == 1 }
            assertEquals(List(4) { "A2" }, survivors.awaitAll())
            assertEquals(1, refresher.calls)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun networkFailureReachesEveryWaiterKeepsSessionAndNextRefreshUsesSameRefreshToken() =
        runTest(timeout = 5.seconds) {
            val refresher = FailingThenRotating({ ApiError.Network(IOException("sem rede")) })
            val auth = authWith(refresher)
            val waiters =
                List(20) {
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { auth.refreshAfterRejection("A1") }
                    }
                }
            refresher.entered.await()

            refresher.gate.complete(Unit)

            val results = waiters.awaitAll()
            assertTrue(results.all { it.exceptionOrNull() is ApiError.Network }, "todos recebem Network: $results")
            assertEquals(1, refresher.calls.get())
            assertEquals("A1", store.current()?.accessToken)
            assertTrue(ended.isEmpty())
            assertTrue(scope.isActive, "o escopo do core sobrevive à falha do voo")

            assertEquals("A2", auth.refreshAfterRejection("A1"))
            assertEquals(listOf("R1"), refresher.rotating.received.toList())
            assertTrue(ended.isEmpty())
        }

    @Test
    fun unexpectedRefresherFailureDoesNotPoisonTheSingleFlightNorKillTheScope() =
        runTest(timeout = 5.seconds) {
            val refresher = FailingThenRotating({ IllegalStateException("falha inesperada") })
            val auth = authWith(refresher)
            val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { auth.refreshAfterRejection("A1") } }
            refresher.entered.await()

            refresher.gate.complete(Unit)

            assertTrue(first.await().isFailure)
            assertTrue(scope.isActive, "o escopo do core sobrevive à falha do voo")
            assertEquals("A2", auth.refreshAfterRejection("A1"))
            assertEquals("A2", store.current()?.accessToken)
        }

    @Test
    fun rejectedRefreshWithTwentyWaitersEndsSessionExactlyOnce() =
        runTest(timeout = 5.seconds) {
            val refresher =
                FailingThenRotating({ ApiError.Problem(401, "REFRESH_TOKEN_REUSED", null, emptyList()) })
            val auth = authWith(refresher)
            val waiters = List(20) { async(start = CoroutineStart.UNDISPATCHED) { auth.refreshAfterRejection("A1") } }
            refresher.entered.await()

            refresher.gate.complete(Unit)

            assertEquals(List(20) { null }, waiters.awaitAll())
            assertEquals(1, refresher.calls.get())
            assertNull(store.current())
            assertEquals(listOf<String?>("REFRESH_TOKEN_REUSED"), ended.toList())
            assertNull(auth.refreshAfterRejection("A1"))
            assertNull(auth.forceRefresh())
            assertEquals(1, refresher.calls.get(), "sem sessão não há novo refresh")
            assertEquals(1, ended.size)
        }

    @Test
    fun nonNetworkApiErrorFromRefresherEndsSessionWithItsCode() =
        runTest(timeout = 5.seconds) {
            val auth = authWith { throw ApiError.SessionEnded("SESSION_REVOKED") }

            assertNull(auth.refreshAfterRejection("A1"))

            assertNull(store.current())
            assertEquals(listOf<String?>("SESSION_REVOKED"), ended.toList())
        }

    @Test
    fun forceRefreshAfterInFlightNetworkFailureRunsANewRefresh() =
        runTest(timeout = 5.seconds) {
            val refresher = FailingThenRotating({ ApiError.Network(IOException("sem rede")) })
            val auth = authWith(refresher)
            val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { auth.refreshAfterRejection("A1") } }
            refresher.entered.await()
            val forced = async(start = CoroutineStart.UNDISPATCHED) { auth.forceRefresh() }

            refresher.gate.complete(Unit)

            assertTrue(first.await().exceptionOrNull() is ApiError.Network)
            assertEquals("A2", forced.await()?.accessToken)
            assertEquals(2, refresher.calls.get())
            assertTrue(ended.isEmpty())
        }

    @Test
    fun forceRefreshAfterInFlightRejectionReturnsNullWithoutAnotherCall() =
        runTest(timeout = 5.seconds) {
            val refresher =
                FailingThenRotating({ ApiError.Problem(401, "REFRESH_TOKEN_REUSED", null, emptyList()) })
            val auth = authWith(refresher)
            val first = async(start = CoroutineStart.UNDISPATCHED) { auth.refreshAfterRejection("A1") }
            refresher.entered.await()
            val forced = async(start = CoroutineStart.UNDISPATCHED) { auth.forceRefresh() }

            refresher.gate.complete(Unit)

            assertNull(first.await())
            assertNull(forced.await())
            assertEquals(1, refresher.calls.get())
            assertEquals(listOf<String?>("REFRESH_TOKEN_REUSED"), ended.toList())
        }

    @Test
    fun forceRefreshWithoutSessionReturnsNullAndNeverCallsTheRefresher() =
        runTest(timeout = 5.seconds) {
            val refresher = RotatingRefresher()
            val auth = authWith(refresher)
            store.clear()

            assertNull(auth.forceRefresh())
            assertNull(auth.refreshAfterRejection("A1"))
            assertNull(auth.accessToken())
            assertEquals(0, refresher.calls)
            assertTrue(ended.isEmpty())
        }

    @Test
    fun forceRefreshWithoutInFlightRefreshRotatesOnceAndKeepsPasswordFlag() =
        runTest(timeout = 5.seconds) {
            val refresher = RotatingRefresher()
            val auth = authWith(refresher)

            val tokens = assertNotNull(auth.forceRefresh())

            assertEquals("A2", tokens.accessToken)
            assertEquals("A2", auth.accessToken())
            assertEquals(listOf("R1"), refresher.received.toList())
        }

    @Test
    fun concurrentForceRefreshesAndRejectionsNeverReuseARefreshToken() =
        runTest(timeout = 5.seconds) {
            repeat(20) { round ->
                val roundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val roundStore = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }
                val refresher = RotatingRefresher()
                val roundEnded = CopyOnWriteArrayList<String?>()
                val auth = Authenticator(roundStore, refresher, roundScope) { roundEnded += it }
                val go = CompletableDeferred<Unit>()
                val callers =
                    List(24) { i ->
                        async(Dispatchers.Default) {
                            go.await()
                            if (i % 4 == 0) {
                                assertNotNull(auth.forceRefresh(), "rodada $round").accessToken
                            } else {
                                auth.refreshAfterRejection(roundStore.current()!!.accessToken)
                            }
                        }
                    }

                go.complete(Unit)
                callers.awaitAll()

                val received = refresher.received.toList()
                assertTrue(roundEnded.isEmpty(), "rodada $round: REFRESH_TOKEN_REUSED com $received")
                assertEquals((1..received.size).map { "R$it" }, received, "rodada $round")
                assertEquals("A${received.size + 1}", roundStore.current()?.accessToken, "rodada $round")
                roundScope.cancel()
            }
        }

    @Test
    fun forceRefreshAlwaysRotatesAfterTheRefreshThatWasInFlightWhenItWasCalled() =
        runTest(timeout = 5.seconds) {
            val refresher = RotatingRefresher()
            val gate = CompletableDeferred<Unit>()
            refresher.gates += gate
            val auth = authWith(refresher)
            val first = async(start = CoroutineStart.UNDISPATCHED) { auth.refreshAfterRejection("A1") }
            refresher.entered.receive()
            val forced = List(3) { async(start = CoroutineStart.UNDISPATCHED) { auth.forceRefresh() } }
            val lateRejection = async(start = CoroutineStart.UNDISPATCHED) { auth.refreshAfterRejection("A1") }

            gate.complete(Unit)

            assertEquals("A2", first.await())
            assertEquals("A2", lateRejection.await())
            val results = forced.awaitAll().map { it?.accessToken }
            // Nenhum forceRefresh devolve o par do voo que já estava no ar (A2): cada um vem de um refresh posterior.
            assertTrue(results.none { it == null || it == "A2" || it == "A1" }, "par antigo devolvido: $results")
            val received = refresher.received.toList()
            assertEquals((1..received.size).map { "R$it" }, received)
            assertTrue(received.size in 2..4, "refreshes: $received")
            assertTrue(ended.isEmpty())
        }

    @Test
    fun rejectionOfAnOlderTokenThanTheCurrentNeverRefreshes() =
        runTest(timeout = 5.seconds) {
            val refresher = RotatingRefresher()
            val auth = authWith(refresher)
            store.set(Tokens("A5", "R1", "s1", "u1", false))

            assertEquals("A5", auth.refreshAfterRejection("A4"))
            assertEquals("A5", auth.refreshAfterRejection(""))
            assertEquals(0, refresher.calls)
        }

    @Test
    fun noTokenAppearsInToStringOfTokensStoreOrAuthenticator() {
        val secrets = listOf("access-secreto-xyz", "refresh-secreto-xyz")
        val tokens = Tokens(secrets[0], secrets[1], "s1", "u1", true)
        store.set(tokens)
        val auth = authWith(RotatingRefresher())

        for (text in listOf(
            tokens.toString(),
            "$tokens",
            store.toString(),
            auth.toString(),
            listOf(tokens).toString(),
        )) {
            for (secret in secrets) assertTrue(secret !in text, "token em '$text'")
        }
    }
}
