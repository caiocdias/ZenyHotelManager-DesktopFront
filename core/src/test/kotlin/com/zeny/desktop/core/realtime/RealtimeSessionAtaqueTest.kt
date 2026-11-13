package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.FakeZenyServer
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.problem
import com.zeny.desktop.core.session.SessionState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val STREAM_PATH = "/api/v1/stream"
private const val LOGIN_PATH = "/api/v1/auth/login"
private const val REFRESH_PATH = "/api/v1/auth/refresh"
private const val PASSWORD_PATH = "/api/v1/auth/password"

/**
 * Ataque ao tempo real ligado ao `ZenyCore` real, contra o `FakeZenyServer`, no tempo virtual: troca de usuário
 * (com e sem logout), `Ended` vindo do próprio stream ou de outra chamada, refresh no meio do stream, troca de
 * senha, logins concorrentes e o `close()` em cada fase da conexão.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeSessionAtaqueTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    /** Uma montagem: o servidor falso, os corpos do stream e os ganchos do teste em volta de cada requisição. */
    private class Rig(
        val server: FakeZenyServer,
        val core: ZenyCore,
        val bodies: LinkedBlockingQueue<ByteChannel>,
        val opened: CopyOnWriteArrayList<ByteChannel>,
    ) {
        /** Roda antes de o servidor falso ver a requisição. */
        @Volatile var before: suspend (HttpRequestData) -> Unit = {}

        /** O próximo `/auth/refresh` é recusado com `401 REFRESH_TOKEN_REUSED`, sem chegar ao servidor. */
        @Volatile var rejectRefresh = false

        fun liveStream(): ByteChannel = ByteChannel(autoFlush = true).also { bodies += it }

        fun streamRequests() = server.requests.filter { it.url.encodedPath == STREAM_PATH }

        fun bearers() = streamRequests().map { server.authorizationOf(it) }

        fun lastEventIds() = streamRequests().map { it.headers["Last-Event-ID"] }
    }

    private fun TestScope.rig(): Rig {
        val server = FakeZenyServer()
        val bodies = LinkedBlockingQueue<ByteChannel>()
        val opened = CopyOnWriteArrayList<ByteChannel>()
        val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")
        server.routes = { request ->
            when (request.url.encodedPath) {
                STREAM_PATH -> {
                    val body = bodies.poll() ?: ByteChannel(autoFlush = true)
                    opened += body
                    respond(body, HttpStatusCode.OK, eventStream)
                }

                PASSWORD_PATH -> {
                    respond("", HttpStatusCode.NoContent)
                }

                else -> {
                    respond("", HttpStatusCode.NotFound)
                }
            }
        }
        val handler =
            server.engine.config.requestHandlers
                .single()
        lateinit var rig: Rig
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        rig.before(request)
                        if (request.url.encodedPath == REFRESH_PATH && rig.rejectRefresh) {
                            rig.rejectRefresh = false
                            server.requests += request
                            problem(HttpStatusCode.Unauthorized, "REFRESH_TOKEN_REUSED")
                        } else {
                            handler(this, request)
                        }
                    }
                },
            )
        val url = checkNotNull(ServerUrl.parse("http://servidor.teste"))
        val core = ZenyCore(url, MemorySettings(), engine, StandardTestDispatcher(testScheduler))
        rig = Rig(server, core, bodies, opened)
        return rig
    }

    private suspend fun ByteChannel.send(text: String) = writeStringUtf8(text)

    private fun ready(id: String) = "event: ready\nid: $id\ndata: {}\n\n"

    private fun message(id: String) =
        "event: rooms\nid: $id\ndata: {\"type\":\"room.changed\",\"id\":\"r-$id\",\"version\":3}\n\n"

    private fun changed(id: String) = RealtimeEvent.Message("rooms", "room.changed", "r-$id", 3)

    private class Subscriber {
        val events = CopyOnWriteArrayList<RealtimeEvent>()

        @Volatile var completed = false

        @Volatile var error: Throwable? = null

        lateinit var job: Job

        val completedNormally get() = completed && error == null
    }

    private fun TestScope.collectInBackground(
        flow: Flow<RealtimeEvent>,
        start: CoroutineStart = CoroutineStart.DEFAULT,
    ): Subscriber {
        val subscriber = Subscriber()
        subscriber.job = backgroundScope.launch(start = start) { subscriber.collect(flow) }
        return subscriber
    }

    private suspend fun Subscriber.collect(flow: Flow<RealtimeEvent>) {
        try {
            flow.collect { events += it }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            error = e
        }
        completed = true
    }

    /** Todos os valores que o `status` assumiu e o coletor chegou a ver. */
    private fun TestScope.statusHistory(core: ZenyCore): List<RealtimeStatus> {
        val seen = CopyOnWriteArrayList<RealtimeStatus>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { core.realtime.status.collect { seen += it } }
        return seen
    }

    @Test
    fun logoutThenLoginOfAnotherUserNeverCarriesThePositionOrTheOldBearer() =
        runTest {
            val rig = rig()
            val first = rig.liveStream()
            val second = rig.liveStream()
            rig.core.use { core ->
                core.session.login("maria", "segredo")
                val rooms = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                val both = collectInBackground(core.realtime.subscribe(setOf("rooms", "housekeeping")))
                runCurrent()
                first.send(ready("e-1") + message("e-2"))
                runCurrent()
                core.session.logout()
                runCurrent()
                assertTrue(rooms.completedNormally && both.completedNormally, "as duas assinaturas de maria terminam")
                core.session.login("joao", "segredo")
                val fresh = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                second.send(ready("e-50") + message("e-51"))
                runCurrent()
                // A conexão de joão cai: a reconexão leva a posição de joão, nunca a de maria.
                second.close()
                advanceTimeBy(1_001)
                runCurrent()
                assertEquals(listOf(RealtimeEvent.Ready, changed("e-51")), fresh.events)
                assertEquals(listOf(RealtimeEvent.Ready, changed("e-2")), rooms.events)
                assertEquals(
                    listOf(listOf("Bearer A1"), listOf("Bearer A2"), listOf("Bearer A2")),
                    rig.bearers(),
                )
                assertEquals(listOf(null, null, "e-51"), rig.lastEventIds())
                assertEquals("rooms", rig.streamRequests()[1].url.parameters["topics"], "a união de maria não vaza")
            }
        }

    @Test
    fun silentSwitchKeepsEachSessionsEventsWithItsOwnSubscribers() =
        runTest {
            val rig = rig()
            val first = rig.liveStream()
            val second = rig.liveStream()
            rig.core.use { core ->
                core.session.login("maria", "segredo")
                val old = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                first.send(ready("e-1") + message("e-2"))
                runCurrent()
                core.session.login("joao", "segredo")
                // A sessão velha ainda manda um evento; joão assina sem ceder a vez, antes de o core ver a troca.
                first.send(message("e-3"))
                val fresh = collectInBackground(core.realtime.subscribe(setOf("rooms")), CoroutineStart.UNDISPATCHED)
                runCurrent()
                second.send(ready("e-7") + message("e-8"))
                runCurrent()
                assertTrue(old.completedNormally)
                assertFalse(RealtimeEvent.Message("rooms", "room.changed", "r-e-8", 3) in old.events)
                assertEquals(listOf(RealtimeEvent.Ready, changed("e-8")), fresh.events)
                assertEquals(listOf<String?>(null, null), rig.lastEventIds())
                assertEquals(listOf(listOf("Bearer A1"), listOf("Bearer A2")), rig.bearers())
            }
        }

    @Test
    fun silentSwitchDuringReconnectWaitCancelsTheScheduledReconnect() =
        runTest {
            val rig = rig()
            val first = rig.liveStream()
            rig.core.use { core ->
                core.session.login("maria", "segredo")
                val old = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                first.send(ready("e-1") + message("e-2"))
                runCurrent()
                first.close()
                runCurrent()
                assertIs<RealtimeStatus.Reconnecting>(core.realtime.status.value)
                core.session.login("joao", "segredo")
                runCurrent()
                advanceTimeBy(120_000)
                runCurrent()
                assertTrue(old.completedNormally)
                assertEquals(RealtimeStatus.Idle, core.realtime.status.value)
                assertEquals(1, rig.streamRequests().size, "a reconexão agendada de maria saiu sob joão")
            }
        }

    /**
     * A reconexão da conexão de maria e o login de joão no mesmo instante, nas ordens que o despachante produz com
     * `k` cessões de vez a mais no login (de um lado e do outro da fronteira em que o login passa a perder). João
     * não assina nada: nenhuma requisição ao stream pode levar o `Bearer` de joão, e muito menos a posição de
     * maria com ele.
     */
    @Test
    fun reconnectOfTheOldSessionNeverCarriesTheNewBearerInTheExploredInterleavings() =
        runTest {
            val leaks = mutableListOf<String>()
            var cancelledReconnects = 0
            var reconnectsUnderMaria = 0
            for (loginFirst in listOf(true, false)) {
                for (k in 0..INTERLEAVINGS) {
                    val requests = switchUserDuringReconnect(loginFirst, k)
                    requests.forEachIndexed { index, (bearer, position) ->
                        if (bearer == listOf("Bearer A2")) {
                            leaks += "loginFirst=$loginFirst k=$k requisição $index: Bearer A2, Last-Event-ID $position"
                        }
                    }
                    if (requests.size == 1) cancelledReconnects++ else reconnectsUnderMaria++
                }
            }
            assertEquals(emptyList(), leaks, "a conexão da sessão anterior reabriu com o token da sessão nova")
            // A exploração cruza a fronteira: há ordens em que o login vence e ordens em que a reconexão vence.
            val crossed = cancelledReconnects > 0 && reconnectsUnderMaria > 0
            assertTrue(crossed, "canceladas $cancelledReconnects, sob maria $reconnectsUnderMaria")
        }

    /**
     * Maria assina e o stream cai; o login de joão termina no instante da reconexão, `k` cessões de vez depois de
     * começar ([loginFirst]: o login começa a esperar antes da queda). Devolve o `Authorization` e o
     * `Last-Event-ID` de cada requisição ao stream.
     */
    private suspend fun TestScope.switchUserDuringReconnect(
        loginFirst: Boolean,
        k: Int,
    ): List<Pair<List<String>?, String?>> {
        val rig = rig()
        val first = rig.liveStream()
        var slowLogin = false
        rig.before = { request ->
            if (request.url.encodedPath == LOGIN_PATH && slowLogin) {
                delay(1_000)
                repeat(k) { yield() }
            }
        }
        val core = rig.core
        core.session.login("maria", "segredo")
        val old = collectInBackground(core.realtime.subscribe(setOf("rooms")))
        runCurrent()
        first.send(ready("e-1") + message("e-2"))
        runCurrent()
        val startLogin = {
            slowLogin = true
            backgroundScope.launch { core.session.login("joao", "segredo") }
        }
        if (!loginFirst) {
            first.close()
            runCurrent()
        }
        val login = startLogin()
        runCurrent()
        if (loginFirst) first.close()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        val requests = rig.streamRequests().map { rig.server.authorizationOf(it) to it.headers["Last-Event-ID"] }
        old.job.cancel()
        login.cancel()
        core.close()
        runCurrent()
        return requests
    }

    /**
     * Dois logins em voo ao mesmo tempo (duplo envio do formulário): o de maria termina e a tela assina na hora;
     * o de joão termina logo depois, antes de o core ver maria. A assinatura aberta sob maria precisa terminar.
     */
    @Test
    fun concurrentLoginsNeverLeaveTheSkippedSessionsSubscriptionAlive() =
        runTest {
            val rig = rig()
            rig.core.use { core ->
                val maria = Subscriber()
                var mariaSession: String? = null
                maria.job =
                    backgroundScope.launch {
                        mariaSession = core.session.login("maria", "segredo").sessionId
                        maria.collect(core.realtime.subscribe(setOf("rooms")))
                    }
                var joaoSession: String? = null
                backgroundScope.launch { joaoSession = core.session.login("joao", "segredo").sessionId }
                runCurrent()
                advanceTimeBy(120_000)
                runCurrent()
                val current = (core.session.state.value as SessionState.Active).sessionId
                assertNotEquals(mariaSession, joaoSession)
                assertEquals(joaoSession, current, "pré-condição do cenário: o login de joão termina por último")
                assertTrue(maria.completedNormally, "a assinatura aberta sob maria ficou viva depois de joão entrar")
                assertEquals(
                    emptyList(),
                    rig.bearers().filter { it == listOf("Bearer A2") },
                    "a assinatura de maria recebe o stream com o token de joão",
                )
            }
        }

    @Test
    fun streamRejectedWithSessionRevokedOnReconnectEndsSessionAndStops() =
        runTest {
            val rig = rig()
            val first = rig.liveStream()
            rig.core.use { core ->
                core.session.login("maria", "segredo")
                val subscriber = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                first.send(ready("e-1"))
                runCurrent()
                // O servidor revoga a sessão e derruba o stream: a reconexão descobre o 401.
                rig.server.rejectAllWith = "SESSION_REVOKED"
                first.close()
                advanceTimeBy(1_001)
                runCurrent()
                assertIs<SessionState.Ended>(core.session.state.value)
                assertTrue(subscriber.completedNormally)
                assertEquals(RealtimeStatus.Idle, core.realtime.status.value)
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(2, rig.streamRequests().size, "o stream insistiu depois do 401")
                assertEquals(0, rig.server.count(REFRESH_PATH))
            }
        }

    @Test
    fun refreshByAnotherCallMidStreamThenServerClosesReconnectsWithTheNewTokenAndPosition() =
        runTest {
            val rig = rig()
            val first = rig.liveStream()
            val second = rig.liveStream()
            rig.core.use { core ->
                core.session.login("maria", "segredo")
                val subscriber = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                first.send(ready("e-1") + message("e-2"))
                runCurrent()
                // Outra tela renova o par enquanto o stream segue aberto com o token velho.
                rig.server.expireAccess()
                core.account.me()
                runCurrent()
                assertEquals(1, rig.server.count(REFRESH_PATH))
                assertFalse(first.isClosedForWrite, "o refresh não derruba o stream")
                // No vencimento o servidor fecha o stream: a reconexão já sai com o token novo.
                first.close()
                advanceTimeBy(1_001)
                runCurrent()
                second.send(ready("e-2") + message("e-3"))
                runCurrent()
                assertEquals(listOf(listOf("Bearer A1"), listOf("Bearer A2")), rig.bearers())
                assertEquals(listOf(null, "e-2"), rig.lastEventIds())
                assertEquals(1, rig.server.count(REFRESH_PATH), "nenhum refresh a mais")
                assertEquals(
                    listOf(RealtimeEvent.Ready, changed("e-2"), RealtimeEvent.Ready, changed("e-3")),
                    subscriber.events,
                )
                assertEquals(RealtimeStatus.Live, core.realtime.status.value)
            }
        }

    @Test
    fun refreshRejectedDuringStreamReconnectEndsSessionAndStream() =
        runTest {
            val rig = rig()
            val first = rig.liveStream()
            rig.core.use { core ->
                core.session.login("maria", "segredo")
                val subscriber = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                first.send(ready("e-1"))
                runCurrent()
                rig.server.expireAccess()
                rig.rejectRefresh = true
                first.close()
                advanceTimeBy(1_001)
                runCurrent()
                assertIs<SessionState.Ended>(core.session.state.value)
                assertTrue(subscriber.completedNormally)
                assertEquals(RealtimeStatus.Idle, core.realtime.status.value)
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(2, rig.streamRequests().size)
                assertEquals(1, rig.server.count(REFRESH_PATH))
            }
        }

    @Test
    fun endedByAnotherCallDuringReconnectWaitStopsThenLoginStreamsFromScratch() =
        runTest {
            val rig = rig()
            val first = rig.liveStream()
            val second = rig.liveStream()
            rig.core.use { core ->
                core.session.login("maria", "segredo")
                val before = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                first.send(ready("e-1") + message("e-2"))
                runCurrent()
                first.close()
                runCurrent()
                assertIs<RealtimeStatus.Reconnecting>(core.realtime.status.value)
                rig.server.rejectAllWith = "SESSION_REVOKED"
                runCatching { core.account.me() }
                runCurrent()
                assertIs<SessionState.Ended>(core.session.state.value)
                assertTrue(before.completedNormally)
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(1, rig.streamRequests().size, "a reconexão agendada saiu depois do Ended")
                rig.server.rejectAllWith = null
                core.session.login("maria", "segredo")
                val after = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                second.send(ready("e-9"))
                runCurrent()
                assertEquals(listOf<RealtimeEvent>(RealtimeEvent.Ready), after.events)
                assertEquals(listOf(listOf("Bearer A1"), listOf("Bearer A2")), rig.bearers())
                assertEquals(listOf<String?>(null, null), rig.lastEventIds())
            }
        }

    @Test
    fun passwordChangeKeepsTheStreamAndTheNextReconnectUsesTheNewToken() =
        runTest {
            val rig = rig()
            val first = rig.liveStream()
            rig.liveStream()
            rig.core.use { core ->
                core.session.login("maria", "segredo")
                val subscriber = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                first.send(ready("e-1") + message("e-2"))
                runCurrent()
                // O par gira e o estado vira outro `Active`, mas da mesma sessão.
                core.session.changePassword("segredo-antigo", "segredo-novo-123")
                runCurrent()
                assertFalse(subscriber.completed, "a troca de senha encerrou a assinatura")
                assertFalse(first.isClosedForWrite, "a troca de senha derrubou o stream")
                assertEquals(RealtimeStatus.Live, core.realtime.status.value)
                first.close()
                advanceTimeBy(1_001)
                runCurrent()
                assertEquals(listOf(listOf("Bearer A1"), listOf("Bearer A2")), rig.bearers())
                assertEquals(listOf(null, "e-2"), rig.lastEventIds())
            }
        }

    @Test
    fun closeDuringReconnectWaitCompletesFlowsAndSendsNothingMore() =
        runTest {
            val rig = rig()
            val first = rig.liveStream()
            val core = rig.core
            val history = statusHistory(core)
            core.session.login("maria", "segredo")
            val subscriber = collectInBackground(core.realtime.subscribe(setOf("rooms")))
            runCurrent()
            first.send(ready("e-1"))
            runCurrent()
            first.close()
            runCurrent()
            assertIs<RealtimeStatus.Reconnecting>(core.realtime.status.value)
            val seenBeforeClose = history.size
            core.close()
            runCurrent()
            assertTrue(subscriber.completedNormally)
            assertEquals(RealtimeStatus.Idle, core.realtime.status.value)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, rig.streamRequests().size)
            assertTrue(
                history.drop(seenBeforeClose).all { it == RealtimeStatus.Idle },
                "o close passou por outro status: ${history.drop(seenBeforeClose)}",
            )
        }

    @Test
    fun closeWhileWaitingForSessionCompletesTheWaitingFlow() =
        runTest {
            val rig = rig()
            val core = rig.core
            val waiting = collectInBackground(core.realtime.subscribe(setOf("rooms")))
            advanceTimeBy(5_000)
            runCurrent()
            assertFalse(waiting.completed)
            core.close()
            runCurrent()
            assertTrue(waiting.completedNormally)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(0, rig.streamRequests().size)
            assertEquals(RealtimeStatus.Idle, core.realtime.status.value)
        }

    @Test
    fun closeWhileTheStreamRequestIsInFlightCompletesFlowsAndSendsNothingMore() =
        runTest {
            val rig = rig()
            val core = rig.core
            val gate = CompletableDeferred<Unit>()
            core.session.login("maria", "segredo")
            rig.before = { request -> if (request.url.encodedPath == STREAM_PATH) gate.await() }
            val subscriber = collectInBackground(core.realtime.subscribe(setOf("rooms")))
            runCurrent()
            assertEquals(RealtimeStatus.Connecting, core.realtime.status.value)
            core.close()
            runCurrent()
            assertTrue(subscriber.completedNormally, "o Flow não completou com a requisição em voo")
            gate.complete(Unit)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(0, rig.streamRequests().size, "a requisição presa chegou ao servidor depois do close")
            assertEquals(RealtimeStatus.Idle, core.realtime.status.value)
        }

    @Test
    fun closeTwiceWithSeveralSubscribersCompletesAllAndStaysClosed() =
        runTest {
            val rig = rig()
            val first = rig.liveStream()
            val core = rig.core
            val history = statusHistory(core)
            core.session.login("maria", "segredo")
            val subscribers =
                listOf(setOf("rooms"), setOf("housekeeping"), setOf("rooms", "maintenance"))
                    .map { collectInBackground(core.realtime.subscribe(it)) }
            runCurrent()
            first.send(ready("e-1"))
            runCurrent()
            val seenBeforeClose = history.size
            core.close()
            core.close()
            runCurrent()
            assertTrue(subscribers.all { it.completedNormally })
            assertTrue(first.isClosedForWrite)
            core.close()
            val late = collectInBackground(core.realtime.subscribe(setOf("rooms")))
            advanceTimeBy(120_000)
            runCurrent()
            assertTrue(late.completedNormally)
            assertEquals(1, rig.streamRequests().size)
            assertTrue(
                history.drop(seenBeforeClose).none { it is RealtimeStatus.Failed || it is RealtimeStatus.Reconnecting },
                "o close passou por falha ou reconexão: ${history.drop(seenBeforeClose)}",
            )
        }

    private companion object {
        /** Cessões de vez exploradas; bem mais que os passos de uma requisição pelo pipeline do Ktor. */
        const val INTERLEAVINGS = 120
    }
}
