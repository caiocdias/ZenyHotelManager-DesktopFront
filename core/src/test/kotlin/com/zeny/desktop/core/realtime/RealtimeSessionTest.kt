package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.FakeZenyServer
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.session.SessionState
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val STREAM = "/api/v1/stream"

/** O tempo real do `ZenyCore` montado, contra o `FakeZenyServer`, no tempo virtual: sessão, refresh e `close()`. */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeSessionTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "dispositivo-teste"
    }

    /** Os corpos das próximas conexões ao stream, na ordem; sem corpo na fila, um que nunca escreve. */
    private val bodies = LinkedBlockingQueue<ByteChannel>()
    private val opened = CopyOnWriteArrayList<ByteChannel>()

    private fun TestScope.core(server: FakeZenyServer): ZenyCore {
        val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")
        server.routes = { request ->
            if (request.url.encodedPath == STREAM) {
                val body = bodies.poll() ?: ByteChannel(autoFlush = true)
                opened += body
                respond(body, HttpStatusCode.OK, eventStream)
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        val url = checkNotNull(ServerUrl.parse("http://servidor.teste"))
        return ZenyCore(url, MemorySettings(), server.engine, StandardTestDispatcher(testScheduler))
    }

    private fun TestScope.server() = FakeZenyServer(dispatcher = StandardTestDispatcher(testScheduler))

    private fun liveStream(): ByteChannel = ByteChannel(autoFlush = true).also { bodies += it }

    private suspend fun ByteChannel.send(text: String) = writeStringUtf8(text)

    private fun ready(id: String) = "event: ready\nid: $id\ndata: {}\n\n"

    private fun message(id: String) =
        "event: rooms\nid: $id\ndata: {\"type\":\"room.changed\",\"id\":\"r-$id\",\"version\":3}\n\n"

    private fun changed(id: String) = RealtimeEvent.Message("rooms", "room.changed", "r-$id", 3)

    private fun FakeZenyServer.streamRequests() = requests.filter { it.url.encodedPath == STREAM }

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
        subscriber.job =
            backgroundScope.launch(start = start) {
                try {
                    flow.collect { subscriber.events += it }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    subscriber.error = e
                }
                subscriber.completed = true
            }
        return subscriber
    }

    @Test
    fun realtimeUsesSessionBearer() =
        runTest {
            val server = server()
            val channel = liveStream()
            core(server).use { core ->
                core.session.login("maria", "segredo")
                val subscriber = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                channel.send(ready("e-1") + message("e-2"))
                runCurrent()
                assertEquals(listOf(RealtimeEvent.Ready, changed("e-2")), subscriber.events)
                assertEquals(RealtimeStatus.Live, core.realtime.status.value)
                val request = server.streamRequests().single()
                assertEquals(listOf("Bearer A1"), server.authorizationOf(request))
                assertEquals("rooms", request.url.parameters["topics"])
                assertFalse("A1" in request.url.toString(), "o token não vai na URL")
            }
        }

    @Test
    fun expiredTokenRefreshesAndReconnects() =
        runTest {
            val server = server()
            val first = liveStream()
            val second = liveStream()
            core(server).use { core ->
                core.session.login("maria", "segredo")
                val subscriber = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                first.send(ready("e-1") + message("e-2"))
                runCurrent()
                // O access expira e o stream fecha: a reconexão recebe 401 UNAUTHENTICATED e renova o par.
                server.expireAccess()
                first.close()
                advanceTimeBy(1_001)
                runCurrent()
                assertEquals(1, server.count("/api/v1/auth/refresh"))
                assertEquals(
                    listOf(listOf("Bearer A1"), listOf("Bearer A1"), listOf("Bearer A2")),
                    server.authorizations(STREAM),
                )
                assertEquals(listOf(null, "e-2", "e-2"), server.streamRequests().map { it.headers["Last-Event-ID"] })
                second.send(ready("e-2") + message("e-3"))
                runCurrent()
                assertEquals(
                    listOf(RealtimeEvent.Ready, changed("e-2"), RealtimeEvent.Ready, changed("e-3")),
                    subscriber.events,
                )
                assertFalse(subscriber.completed)
                assertIs<SessionState.Active>(core.session.state.value)
                assertEquals(RealtimeStatus.Live, core.realtime.status.value)
            }
        }

    @Test
    fun logoutEndsStream() =
        runTest {
            val server = server()
            val channel = liveStream()
            core(server).use { core ->
                core.session.login("maria", "segredo")
                val subscriber = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                channel.send(ready("e-1"))
                runCurrent()
                core.session.logout()
                runCurrent()
                assertTrue(subscriber.completedNormally)
                assertTrue(channel.isClosedForWrite, "o corpo da resposta foi liberado")
                assertEquals(RealtimeStatus.Idle, core.realtime.status.value)
                advanceTimeBy(120_000)
                assertEquals(1, server.streamRequests().size)
            }
        }

    @Test
    fun sessionEndedEndsStream() =
        runTest {
            val server = server()
            val channel = liveStream()
            core(server).use { core ->
                core.session.login("maria", "segredo")
                val subscriber = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                channel.send(ready("e-1"))
                runCurrent()
                // Outra chamada descobre que a sessão foi revogada; o stream aberto não sabe disso.
                server.rejectAllWith = "SESSION_REVOKED"
                assertFailsWith<ApiError.SessionEnded> { core.account.me() }
                runCurrent()
                assertIs<SessionState.Ended>(core.session.state.value)
                assertTrue(subscriber.completedNormally)
                assertTrue(channel.isClosedForWrite, "o corpo da resposta foi liberado")
                assertEquals(RealtimeStatus.Idle, core.realtime.status.value)
                advanceTimeBy(120_000)
                assertEquals(1, server.streamRequests().size)
            }
        }

    @Test
    fun loginAfterLogoutStreamsAgain() =
        runTest {
            val server = server()
            val first = liveStream()
            val second = liveStream()
            core(server).use { core ->
                core.session.login("maria", "segredo")
                val before = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                first.send(ready("e-1") + message("e-2"))
                runCurrent()
                core.session.logout()
                runCurrent()
                assertTrue(before.completedNormally)
                core.session.login("maria", "segredo")
                val after = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                second.send(ready("e-9") + message("e-10"))
                runCurrent()
                assertEquals(listOf(RealtimeEvent.Ready, changed("e-10")), after.events)
                assertEquals(listOf(listOf("Bearer A1"), listOf("Bearer A2")), server.authorizations(STREAM))
                assertEquals(listOf<String?>(null, null), server.streamRequests().map { it.headers["Last-Event-ID"] })
                assertEquals(RealtimeStatus.Live, core.realtime.status.value)
            }
        }

    @Test
    fun subscribeRightAfterLoginSurvives() =
        runTest {
            val server = server()
            val first = liveStream()
            val second = liveStream()
            core(server).use { core ->
                core.session.login("maria", "segredo")
                val old = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                first.send(ready("e-1"))
                runCurrent()
                // Outro usuário entra por cima e assina na hora, antes de o core ver a troca de sessão.
                core.session.login("joao", "segredo")
                val fresh = collectInBackground(core.realtime.subscribe(setOf("rooms")), CoroutineStart.UNDISPATCHED)
                runCurrent()
                assertTrue(old.completedNormally)
                assertTrue(first.isClosedForWrite, "o corpo da sessão anterior foi liberado")
                second.send(ready("e-5") + message("e-6"))
                runCurrent()
                assertFalse(fresh.completed)
                assertFalse(second.isClosedForWrite)
                assertEquals(listOf(RealtimeEvent.Ready, changed("e-6")), fresh.events)
                assertEquals(listOf(listOf("Bearer A1"), listOf("Bearer A2")), server.authorizations(STREAM))
                assertEquals(RealtimeStatus.Live, core.realtime.status.value)
            }
        }

    @Test
    fun switchingUserDropsPosition() =
        runTest {
            val server = server()
            val first = liveStream()
            liveStream()
            core(server).use { core ->
                core.session.login("maria", "segredo")
                val old = collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                first.send(ready("e-1") + message("e-2"))
                runCurrent()
                core.session.login("joao", "segredo")
                runCurrent()
                assertTrue(old.completedNormally)
                collectInBackground(core.realtime.subscribe(setOf("rooms")))
                runCurrent()
                assertEquals(listOf<String?>(null, null), server.streamRequests().map { it.headers["Last-Event-ID"] })
                assertEquals(listOf("Bearer A2"), server.authorizationOf(server.streamRequests().last()))
            }
        }

    @Test
    fun closeStopsStream() =
        runTest {
            val server = server()
            val channel = liveStream()
            val core = core(server)
            core.session.login("maria", "segredo")
            val subscriber = collectInBackground(core.realtime.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(ready("e-1"))
            runCurrent()
            core.close()
            runCurrent()
            assertTrue(subscriber.completedNormally)
            assertTrue(channel.isClosedForWrite, "o corpo da resposta foi liberado")
            assertEquals(RealtimeStatus.Idle, core.realtime.status.value)
            val late = collectInBackground(core.realtime.subscribe(setOf("rooms")))
            advanceTimeBy(120_000)
            assertTrue(late.completedNormally)
            assertEquals(1, server.streamRequests().size)
        }
}
