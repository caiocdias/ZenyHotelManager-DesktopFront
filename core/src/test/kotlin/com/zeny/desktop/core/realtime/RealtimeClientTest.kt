package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.core.session.AuthPlugin
import com.zeny.desktop.core.session.Authenticator
import com.zeny.desktop.core.session.TokenStore
import com.zeny.desktop.core.session.Tokens
import com.zeny.desktop.sdk.client.StreamApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeClientTest {
    private val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val responses = ArrayDeque<suspend MockRequestHandleScope.() -> HttpResponseData>()

    /** A sessão corrente, conduzida pelo teste. */
    @Volatile private var session: String? = "s1"

    private val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")

    private fun TestScope.client(scope: CoroutineScope = backgroundScope): RealtimeClient {
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        requests += request
                        val next = responses.removeFirstOrNull()
                        next?.invoke(this) ?: respond(ByteChannel(), HttpStatusCode.OK, eventStream)
                    }
                },
            )
        val refreshScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val auth = Authenticator(store, { error("sem refresh") }, refreshScope) { _, _ -> }
        val http = createCoreHttpClient(engine) { install(AuthPlugin) { authenticator = { auth } } }
        return RealtimeClient(StreamApi(ogenBase(http, "http://servidor.teste")), scope) { session }
    }

    /** Um stream que só termina quando o teste fecha o canal; o teste escreve nele os eventos. */
    private fun liveStream(): ByteChannel =
        ByteChannel(autoFlush = true).also { channel ->
            responses += { respond(channel, HttpStatusCode.OK, eventStream) }
        }

    private fun problem(
        code: HttpStatusCode,
        problemCode: String,
    ) {
        responses += {
            respond(
                """{"code":"$problemCode","detail":"detalhe"}""",
                code,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
    }

    private suspend fun ByteChannel.send(text: String) = writeStringUtf8(text)

    private fun ready(id: String) = "event: ready\nid: $id\ndata: {}\n\n"

    private fun message(
        id: String,
        topic: String = "rooms",
    ) = "event: $topic\nid: $id\ndata: {\"type\":\"$topic.changed\",\"id\":\"r-$id\",\"version\":3}\n\n"

    private fun changed(
        id: String,
        topic: String = "rooms",
    ) = RealtimeEvent.Message(topic, "$topic.changed", "r-$id", 3)

    private fun lastEventIds(): List<String?> = requests.map { it.headers["Last-Event-ID"] }

    /** O que um assinante recebeu e como o `Flow` dele terminou. */
    private class Subscriber {
        val events = CopyOnWriteArrayList<RealtimeEvent>()

        @Volatile var completed = false

        @Volatile var error: Throwable? = null

        lateinit var job: Job

        val completedNormally get() = completed && error == null
    }

    /** Coleta em segundo plano; o teste conduz o tempo virtual. */
    private fun TestScope.collectInBackground(
        flow: Flow<RealtimeEvent>,
        onEach: suspend (RealtimeEvent) -> Unit = {},
    ): Subscriber {
        val subscriber = Subscriber()
        subscriber.job =
            backgroundScope.launch {
                try {
                    flow.collect {
                        onEach(it)
                        subscriber.events += it
                    }
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

    /** Escreve sem travar o teste: quem lê pode desistir do corpo no meio da escrita. */
    private fun TestScope.writeInBackground(write: suspend () -> Unit) {
        backgroundScope.launch { runCatching { write() } }
    }

    @Test
    fun secondSubscriberDoesNotStarveFirst() =
        runTest {
            val channel = liveStream()
            val client = client()
            val first = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(ready("e-1") + message("e-2"))
            runCurrent()
            val second = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(message("e-3"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2"), changed("e-3")), first.events)
            // A conexão já estava em dia: o segundo recebe o `Ready` e não reabre a conexão.
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-3")), second.events)
            assertEquals(1, requests.size)
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    @Test
    fun unionChangeReconnectsWithPosition() =
        runTest {
            val old = liveStream()
            val current = liveStream()
            val client = client()
            val rooms = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            old.send(ready("e-1") + message("e-2"))
            runCurrent()
            val housekeeping = collectInBackground(client.subscribe(setOf("housekeeping")))
            runCurrent()
            assertEquals(2, requests.size)
            assertEquals("housekeeping,rooms", requests[1].url.parameters["topics"])
            assertEquals(listOf(null, "e-2"), lastEventIds())
            assertTrue(old.isClosedForWrite, "a conexão anterior foi liberada")
            current.send(ready("e-3") + message("e-4", topic = "housekeeping") + message("e-5"))
            runCurrent()
            assertEquals(
                listOf(RealtimeEvent.Ready, changed("e-2"), RealtimeEvent.Ready, changed("e-5")),
                rooms.events,
            )
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-4", topic = "housekeeping")), housekeeping.events)
        }

    @Test
    fun cancelLastCollectorClosesConnection() =
        runTest {
            val channel = liveStream()
            val client = client()
            val first = collectInBackground(client.subscribe(setOf("rooms")))
            val second = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(ready("e-1"))
            runCurrent()
            assertEquals(RealtimeStatus.Live, client.status.value)
            first.job.cancel()
            runCurrent()
            assertFalse(channel.isClosedForWrite, "ainda há um assinante")
            second.job.cancel()
            runCurrent()
            assertEquals(RealtimeStatus.Idle, client.status.value)
            assertTrue(channel.isClosedForWrite, "o corpo da resposta foi liberado")
            advanceTimeBy(120_000)
            assertEquals(1, requests.size)
        }

    @Test
    fun slowCollectorNeverDropsEvents() =
        runTest {
            val channel = liveStream()
            val client = client()
            val slow = collectInBackground(client.subscribe(setOf("rooms"))) { delay(1_000) }
            val fast = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            writeInBackground {
                channel.send(ready("e-0"))
                for (i in 1..200) channel.send(message("e-$i"))
            }
            advanceTimeBy(100_000)
            // O leitor espera o lento sem descartar nem cair pelo *watchdog*.
            assertEquals(1, requests.size)
            assertTrue(slow.events.size in 90..110, "o lento recebe no ritmo dele: ${slow.events.size}")
            advanceTimeBy(110_000)
            val expected = listOf(RealtimeEvent.Ready) + (1..200).map { changed("e-$it") }
            assertEquals(expected, slow.events)
            assertEquals(expected, fast.events)
        }

    @Test
    fun failedCompletesFlowsWithError() =
        runTest {
            problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
            val client = client()
            val first = collectInBackground(client.subscribe(setOf("rooms")))
            val second = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            for (subscriber in listOf(first, second)) {
                assertTrue(subscriber.completed)
                assertEquals(403, assertIs<ApiError.Problem>(subscriber.error).status)
            }
            val failed = assertIs<RealtimeStatus.Failed>(client.status.value)
            assertEquals("PERMISSION_DENIED", assertIs<ApiError.Problem>(failed.error).code)
            advanceTimeBy(120_000)
            assertEquals(1, requests.size)
        }

    @Test
    fun endSessionCompletesItsFlowsAndClientStaysUsable() =
        runTest {
            val old = liveStream()
            val current = liveStream()
            val client = client()
            val first = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            old.send(ready("e-1") + message("e-2"))
            runCurrent()
            client.endSession("s1")
            runCurrent()
            assertTrue(first.completedNormally)
            assertTrue(old.isClosedForWrite, "o corpo da resposta foi liberado")
            assertEquals(RealtimeStatus.Idle, client.status.value)
            // Novo login com a mesma sessão de antes: a posição foi descartada com ela.
            val second = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            current.send(ready("e-9"))
            runCurrent()
            assertEquals(listOf<RealtimeEvent>(RealtimeEvent.Ready), second.events)
            assertEquals(listOf(null, null), lastEventIds())
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    @Test
    fun subscribeRightAfterLoginSurvives() =
        runTest {
            session = null
            val channel = liveStream()
            val client = client()
            session = "s2"
            val subscriber = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            // O fim da sessão anterior chega depois do login.
            client.endSession("s1")
            runCurrent()
            channel.send(ready("e-1") + message("e-2"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2")), subscriber.events)
            assertFalse(subscriber.completed)
            assertFalse(channel.isClosedForWrite)
            assertEquals(1, requests.size)
        }

    @Test
    fun lateEndOfOldSessionDoesNotKillNewSubscription() =
        runTest {
            val old = liveStream()
            val current = liveStream()
            val client = client()
            val oldSubscriber = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            old.send(ready("e-1") + message("e-2"))
            runCurrent()
            session = "s2"
            val newSubscriber = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            assertEquals(2, requests.size, "a nova sessão abre a própria conexão")
            client.endSession("s1")
            runCurrent()
            assertTrue(oldSubscriber.completedNormally)
            assertFalse(newSubscriber.completed)
            assertFalse(current.isClosedForWrite)
            current.send(ready("e-7") + message("e-8"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-8")), newSubscriber.events)
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2")), oldSubscriber.events)
            assertEquals(RealtimeStatus.Live, client.status.value)
            assertEquals(2, requests.size)
        }

    @Test
    fun newSessionStartsWithoutLastEventId() =
        runTest {
            val first = liveStream()
            liveStream()
            liveStream()
            val client = client()
            val subscriber = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            first.send(ready("e-1") + message("e-2"))
            runCurrent()
            subscriber.job.cancel()
            runCurrent()
            assertEquals(RealtimeStatus.Idle, client.status.value)
            // Mesma sessão: a posição sobrevive à conexão fechada.
            val again = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            again.job.cancel()
            runCurrent()
            session = "s2"
            collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            assertEquals(listOf(null, "e-2", null), lastEventIds())
        }

    @Test
    fun waitsForSessionBeforeConnecting() =
        runTest {
            session = null
            val channel = liveStream()
            val client = client()
            val subscriber = collectInBackground(client.subscribe(setOf("rooms")))
            advanceTimeBy(60_000)
            assertEquals(0, requests.size)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            assertFalse(subscriber.completed)
            session = "s1"
            advanceTimeBy(1_000)
            assertEquals(1, requests.size)
            assertEquals("Bearer A1", requests.single().headers[HttpHeaders.Authorization])
            channel.send(ready("e-1"))
            runCurrent()
            assertEquals(listOf<RealtimeEvent>(RealtimeEvent.Ready), subscriber.events)
        }

    @Test
    fun connectionStopCompletesSubscribers() =
        runTest {
            problem(HttpStatusCode.Unauthorized, "SESSION_REVOKED")
            val client = client()
            val first = collectInBackground(client.subscribe(setOf("rooms")))
            val second = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            assertTrue(first.completedNormally)
            assertTrue(second.completedNormally)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            advanceTimeBy(120_000)
            assertEquals(1, requests.size)
        }

    @Test
    fun closingScopeCompletesSubscribers() =
        runTest {
            val channel = liveStream()
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val client = client(scope)
            val subscriber = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(ready("e-1"))
            runCurrent()
            scope.cancel()
            runCurrent()
            assertTrue(subscriber.completedNormally)
            assertTrue(channel.isClosedForWrite, "o corpo da resposta foi liberado")
            assertEquals(RealtimeStatus.Idle, client.status.value)
            val late = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            assertTrue(late.completedNormally)
            assertEquals(1, requests.size)
        }

    @Test
    fun subscriberGetsOnlyItsTopics() =
        runTest {
            val channel = liveStream()
            val client = client()
            val rooms = collectInBackground(client.subscribe(setOf("rooms")))
            val all = collectInBackground(client.subscribe(setOf("rooms", "housekeeping")))
            runCurrent()
            assertEquals("housekeeping,rooms", requests.single().url.parameters["topics"])
            channel.send(ready("e-1") + message("e-2", topic = "housekeeping") + message("e-3"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-3")), rooms.events)
            assertEquals(
                listOf(RealtimeEvent.Ready, changed("e-2", topic = "housekeeping"), changed("e-3")),
                all.events,
            )
            assertNull(rooms.error)
        }
}
