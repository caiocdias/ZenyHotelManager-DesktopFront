package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.RealtimeAtaqueFixture.Companion.jobsUnder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Ataque às sessões do multiplexador: `endSession`, troca de sessão, `Failed`, `Stop` e fechamento do escopo. */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeClientSessaoAtaqueTest {
    private fun ready(id: String) = RealtimeAtaqueFixture.ready(id)

    private fun message(
        id: String,
        topic: String = "rooms",
    ) = RealtimeAtaqueFixture.message(id, topic)

    private fun changed(
        id: String,
        topic: String = "rooms",
    ) = RealtimeAtaqueFixture.changed(id, topic)

    private suspend fun ByteChannel.send(text: String) = writeStringUtf8(text)

    // ---- endSession ----

    @Test
    fun endSessionRepeatedIsHarmlessAndClientStaysUsable() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val old = f.liveStream()
            val current = f.liveStream()
            val client = f.client()
            val first = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            old.send(ready("e-1") + message("e-2"))
            runCurrent()
            client.endSession("s1")
            client.endSession("s1")
            runCurrent()
            client.endSession("s1")
            runCurrent()
            assertTrue(first.completedNormally)
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2")), first.events)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            assertEquals(1, f.requests.size)
            val second = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            current.send(ready("e-5"))
            runCurrent()
            assertEquals(listOf<RealtimeEvent>(RealtimeEvent.Ready), second.events)
            assertEquals(listOf<String?>(null, null), f.lastEventIds())
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    @Test
    fun endSessionOfUnknownSessionChangesNothing() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val old = f.liveStream()
            f.liveStream()
            val client = f.client()
            val rooms = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            old.send(ready("e-1") + message("e-2"))
            runCurrent()
            client.endSession("s9")
            runCurrent()
            old.send(message("e-3"))
            runCurrent()
            assertFalse(rooms.completed)
            assertFalse(old.isClosedForWrite)
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2"), changed("e-3")), rooms.events)
            assertEquals(RealtimeStatus.Live, client.status.value)
            // A posição da sessão corrente ficou: a próxima conexão retoma dela.
            f.collect(client.subscribe(setOf("housekeeping")))
            runCurrent()
            assertEquals(listOf(null, "e-3"), f.lastEventIds())
        }

    @Test
    fun lateEndOfUnknownSessionAfterSilentSwitchLeavesOpenConnectionAlone() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val channel = f.liveStream()
            val client = f.client()
            val rooms = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(ready("e-1") + message("e-2"))
            runCurrent()
            // A sessão corrente já é outra, mas ninguém assinou sob ela; chega o fim atrasado de uma terceira.
            f.login("s2")
            client.endSession("s0")
            runCurrent()
            assertFalse(channel.isClosedForWrite, "a conexão da s1 não é da s0: o endSession(s0) não a fecha")
            assertEquals(RealtimeStatus.Live, client.status.value)
            assertFalse(rooms.completed)
            channel.send(message("e-3"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2"), changed("e-3")), rooms.events)
        }

    @Test
    fun endSessionDuringReconnectWaitStopsRetrying() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val channel = f.liveStream()
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val client = f.client(scope)
            val first = f.collect(client.subscribe(setOf("rooms")))
            val second = f.collect(client.subscribe(setOf("rooms", "housekeeping")))
            runCurrent()
            channel.send(ready("e-1"))
            runCurrent()
            channel.close()
            runCurrent()
            assertEquals(RealtimeStatus.Reconnecting(1, 1_000), client.status.value)
            client.endSession("s1")
            runCurrent()
            assertTrue(first.completedNormally)
            assertTrue(second.completedNormally)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            advanceTimeBy(120_000)
            assertEquals(1, f.requests.size)
            assertEquals(emptyList(), jobsUnder(scope))
        }

    @Test
    fun endSessionDuringDeliveryToSlowCollectorCompletesWithContiguousPrefix() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val channel = f.liveStream()
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val client = f.client(scope)
            val slow = f.collect(client.subscribe(setOf("rooms"))) { delay(1_000) }
            val fast = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            f.writeInBackground {
                channel.send(ready("e-0"))
                for (i in 1..200) channel.send(message("e-$i"))
            }
            advanceTimeBy(5_000)
            // O leitor está preso entregando ao lento.
            client.endSession("s1")
            advanceTimeBy(300_000)
            for ((name, subscriber) in listOf("lento" to slow, "rápido" to fast)) {
                assertTrue(subscriber.completedNormally, name)
                assertEquals(RealtimeEvent.Ready, subscriber.events.first(), name)
                val numbers = subscriber.messageNumbers()
                assertEquals((1..numbers.size).toList(), numbers, "o $name recebeu com buraco")
                assertTrue(numbers.size < 200, "o $name não podia receber tudo: ${numbers.size}")
            }
            assertTrue(channel.isClosedForWrite)
            assertEquals(1, f.requests.size)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            assertEquals(emptyList(), jobsUnder(scope))
        }

    @Test
    fun endSessionWhileWaitingForSessionKeepsTheWaitingSubscriber() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            f.session = null
            val channel = f.liveStream()
            val client = f.client()
            val waiting = f.collect(client.subscribe(setOf("rooms")))
            advanceTimeBy(2_000)
            client.endSession("s1")
            advanceTimeBy(2_000)
            assertFalse(waiting.completed, "a assinatura ainda não pertence a sessão nenhuma")
            assertEquals(0, f.requests.size)
            f.login("s2")
            advanceTimeBy(1_000)
            assertEquals(1, f.requests.size)
            assertEquals("Bearer A-s2", f.requests.single().headers[HttpHeaders.Authorization])
            channel.send(ready("e-1"))
            runCurrent()
            assertEquals(listOf<RealtimeEvent>(RealtimeEvent.Ready), waiting.events)
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    // ---- troca de sessão ----

    @Test
    fun switchToAnotherSessionWithoutEndSessionIsolatesTheOldSubscriber() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val old = f.liveStream()
            val current = f.liveStream()
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val client = f.client(scope)
            val before = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            old.send(ready("e-1") + message("e-2"))
            runCurrent()
            f.login("s2")
            val after = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            assertEquals(2, f.requests.size)
            assertEquals(listOf(0, 0), f.openAtRequest, "a conexão da s1 ainda estava aberta")
            assertEquals(listOf<String?>(null, null), f.lastEventIds(), "a posição da s1 não vai para a s2")
            assertEquals("Bearer A-s2", f.requests[1].headers[HttpHeaders.Authorization])
            current.send(ready("e-5") + message("e-6"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2")), before.events, "a s2 não fala com a s1")
            assertFalse(before.completed)
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-6")), after.events)
            assertEquals(RealtimeStatus.Live, client.status.value)
            after.job.cancel()
            runCurrent()
            assertEquals(RealtimeStatus.Idle, client.status.value)
            assertTrue(current.isClosedForWrite)
            client.endSession("s1")
            advanceTimeBy(1_000)
            assertTrue(before.completedNormally)
            assertEquals(2, f.requests.size)
            assertEquals(emptyList(), jobsUnder(scope))
        }

    @Test
    fun sessionGoesNullMidwayAndNextSessionStartsClean() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val old = f.liveStream()
            val current = f.liveStream()
            val client = f.client()
            val before = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            old.send(ready("e-1") + message("e-2"))
            runCurrent()
            f.session = null
            val waiting = f.collect(client.subscribe(setOf("rooms")))
            advanceTimeBy(10_000)
            assertTrue(old.isClosedForWrite, "sem sessão, nenhuma conexão fica aberta")
            assertEquals(RealtimeStatus.Idle, client.status.value)
            assertEquals(1, f.requests.size)
            assertFalse(waiting.completed)
            f.login("s2")
            advanceTimeBy(1_000)
            assertEquals(2, f.requests.size)
            assertEquals(listOf<String?>(null, null), f.lastEventIds())
            current.send(ready("e-7") + message("e-8"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-8")), waiting.events)
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2")), before.events)
            client.endSession("s1")
            runCurrent()
            assertTrue(before.completedNormally)
            assertFalse(waiting.completed)
            assertFalse(current.isClosedForWrite)
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    // ---- Failed e Stop depois de Live ----

    @Test
    fun forbiddenAfterLiveFailsBothSubscribersAndStopsRetrying() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val old = f.liveStream()
            f.problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
            val again = f.liveStream()
            val client = f.client()
            val first = f.collect(client.subscribe(setOf("rooms")))
            val second = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            old.send(ready("e-1") + message("e-2"))
            runCurrent()
            old.close()
            advanceTimeBy(1_001)
            for (subscriber in listOf(first, second)) {
                assertTrue(subscriber.completed)
                assertEquals(403, assertIs<ApiError.Problem>(subscriber.error).status)
                assertEquals(listOf(RealtimeEvent.Ready, changed("e-2")), subscriber.events)
            }
            val failed = assertIs<RealtimeStatus.Failed>(client.status.value)
            assertEquals("PERMISSION_DENIED", assertIs<ApiError.Problem>(failed.error).code)
            advanceTimeBy(120_000)
            assertEquals(2, f.requests.size)
            assertIs<RealtimeStatus.Failed>(client.status.value)
            // O cliente segue apto: uma assinatura nova tenta de novo.
            val third = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            again.send(ready("e-3"))
            runCurrent()
            assertEquals(3, f.requests.size)
            assertEquals(listOf<RealtimeEvent>(RealtimeEvent.Ready), third.events)
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    @Test
    fun residualUnauthorizedAfterLiveCompletesBothAndClientServesNextSession() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val old = f.liveStream()
            f.problem(HttpStatusCode.Unauthorized, "SESSION_REVOKED")
            val next = f.liveStream()
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val client = f.client(scope)
            val first = f.collect(client.subscribe(setOf("rooms")))
            val second = f.collect(client.subscribe(setOf("rooms", "housekeeping")))
            runCurrent()
            old.send(ready("e-1") + message("e-2"))
            runCurrent()
            old.close()
            advanceTimeBy(1_001)
            assertTrue(first.completedNormally)
            assertTrue(second.completedNormally)
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2")), first.events)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            advanceTimeBy(120_000)
            assertEquals(2, f.requests.size)
            assertEquals(emptyList(), jobsUnder(scope))
            f.login("s2")
            val third = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            next.send(ready("e-1"))
            runCurrent()
            assertEquals(3, f.requests.size)
            assertEquals(null, f.lastEventIds()[2])
            assertEquals("Bearer A-s2", f.requests[2].headers[HttpHeaders.Authorization])
            assertEquals(listOf<RealtimeEvent>(RealtimeEvent.Ready), third.events)
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    // ---- fechamento do escopo ----

    @Test
    fun closingScopeWithSeveralSubscribersCompletesAllAndLeavesNoJob() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val channel = f.liveStream()
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val client = f.client(scope)
            val subscribers =
                listOf(setOf("rooms"), setOf("housekeeping"), setOf("rooms", "housekeeping"))
                    .map { f.collect(client.subscribe(it)) }
            runCurrent()
            channel.send(ready("e-1") + message("e-2"))
            runCurrent()
            scope.cancel()
            runCurrent()
            subscribers.forEach { assertTrue(it.completedNormally) }
            assertTrue(channel.isClosedForWrite)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            assertEquals(emptyList(), jobsUnder(scope))
            advanceTimeBy(120_000)
            assertEquals(1, f.requests.size)
        }

    @Test
    fun closingScopeWhileWaitingForSessionCompletesAndNeverConnects() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            f.session = null
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val client = f.client(scope)
            val waiting = f.collect(client.subscribe(setOf("rooms")))
            advanceTimeBy(2_000)
            scope.cancel()
            runCurrent()
            assertTrue(waiting.completedNormally)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            assertEquals(emptyList(), jobsUnder(scope))
            f.session = "s1"
            val late = f.collect(client.subscribe(setOf("rooms")))
            advanceTimeBy(10_000)
            assertTrue(late.completedNormally)
            assertEquals(0, f.requests.size)
        }

    @Test
    fun closingScopeDuringReconnectCompletesAndStopsRetrying() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val channel = f.liveStream()
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val client = f.client(scope)
            val first = f.collect(client.subscribe(setOf("rooms")))
            val second = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(ready("e-1"))
            runCurrent()
            channel.close()
            runCurrent()
            assertIs<RealtimeStatus.Reconnecting>(client.status.value)
            scope.cancel()
            runCurrent()
            assertTrue(first.completedNormally)
            assertTrue(second.completedNormally)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            advanceTimeBy(120_000)
            assertEquals(1, f.requests.size)
            assertEquals(emptyList(), jobsUnder(scope))
        }
}
