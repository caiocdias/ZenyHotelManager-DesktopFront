package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.core.realtime.ConnectionItem.Event
import com.zeny.desktop.core.realtime.ConnectionItem.Status
import com.zeny.desktop.core.session.AuthPlugin
import com.zeny.desktop.core.session.Authenticator
import com.zeny.desktop.core.session.TokenRefresher
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
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Servidor falso e coletor para os ataques à [StreamConnection]; um por cenário. */
internal class StreamAtaqueFixture(
    private val scope: TestScope,
    refresher: TokenRefresher = TokenRefresher { error("sem refresh") },
) {
    val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }
    val requests = CopyOnWriteArrayList<HttpRequestData>()
    val requestTimes = CopyOnWriteArrayList<Long>()
    val ended = CopyOnWriteArrayList<String?>()
    private val responses = ArrayDeque<suspend MockRequestHandleScope.() -> HttpResponseData>()
    private val auth =
        Authenticator(store, refresher, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))) {
            code,
            _,
            ->
            ended += code
        }

    fun respondWith(response: suspend MockRequestHandleScope.() -> HttpResponseData) {
        responses += response
    }

    fun stream(
        body: String,
        contentType: String? = EVENT_STREAM,
    ) = streamOf(ByteReadChannel(body), contentType)

    fun streamOf(
        channel: ByteReadChannel,
        contentType: String? = EVENT_STREAM,
    ) {
        val headers = if (contentType == null) headersOf() else headersOf(HttpHeaders.ContentType, contentType)
        responses += { respond(channel, HttpStatusCode.OK, headers) }
    }

    /** Um corpo que só termina quando o teste fecha o canal. */
    fun liveStream(): ByteChannel = ByteChannel(autoFlush = true).also { streamOf(it) }

    fun status(
        code: Int,
        body: String = "",
        contentType: String = "application/problem+json",
    ) {
        responses += { respond(body, HttpStatusCode.fromValue(code), headersOf(HttpHeaders.ContentType, contentType)) }
    }

    fun problem(
        code: Int,
        problemCode: String,
    ) = status(code, """{"code":"$problemCode","detail":"detalhe"}""")

    fun connection(
        topics: Set<String> = setOf("rooms"),
        startAfter: String? = null,
    ): StreamConnection {
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(scope.testScheduler)
                    addHandler { request ->
                        requests += request
                        requestTimes += scope.testScheduler.currentTime
                        val next = responses.removeFirstOrNull()
                        next?.invoke(this)
                            ?: respond(
                                ByteChannel(),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, EVENT_STREAM),
                            )
                    }
                },
            )
        val http = createCoreHttpClient(engine) { install(AuthPlugin) { authenticator = { auth } } }
        return StreamConnection(StreamApi(ogenBase(http, "http://servidor.teste")), topics, startAfter)
    }

    fun lastEventIds(): List<String?> = requests.map { it.headers["Last-Event-ID"] }

    fun bearers(): List<String?> = requests.map { it.headers[HttpHeaders.Authorization] }

    /** Coleta em segundo plano; o teste conduz o tempo virtual. */
    fun collectInBackground(
        connection: StreamConnection,
        onItem: suspend (ConnectionItem) -> Unit = {},
    ): Pair<MutableList<ConnectionItem>, Job> {
        val items = CopyOnWriteArrayList<ConnectionItem>()
        val job =
            scope.backgroundScope.launch {
                connection.run().collect {
                    onItem(it)
                    items += it
                }
            }
        return items to job
    }

    /** Escreve sem travar o teste: quem lê pode desistir do corpo no meio da escrita. */
    fun writeInBackground(write: suspend () -> Unit) {
        scope.backgroundScope.launch { runCatching { write() } }
    }

    companion object {
        const val EVENT_STREAM = "text/event-stream"
        val connecting = Status(RealtimeStatus.Connecting)
        val live = Status(RealtimeStatus.Live)

        fun reconnecting(
            attempt: Int,
            delayMillis: Long,
        ) = Status(RealtimeStatus.Reconnecting(attempt, delayMillis))

        fun ready(id: String) = "event: ready\nid: $id\ndata: {}\n\n"

        fun reset(id: String) = "event: reset\nid: $id\ndata: {}\n\n"

        fun message(
            id: String,
            topic: String = "rooms",
            data: String = """{"type":"rooms.room-status","id":"r-$id","version":3}""",
        ) = "event: $topic\nid: $id\ndata: $data\n\n"

        fun roomStatus(id: String) = RealtimeEvent.Message("rooms", "rooms.room-status", "r-$id", 3)

        fun waits(items: List<ConnectionItem>): List<RealtimeStatus.Reconnecting> =
            items.mapNotNull { (it as? Status)?.status as? RealtimeStatus.Reconnecting }
    }
}

/** Ataque à reconexão, à espera, ao *watchdog*, ao cancelamento, ao coletor e ao enquadramento no stream. */
class StreamConnectionAtaqueTest {
    private val mib = 1024 * 1024

    private val connecting = StreamAtaqueFixture.connecting
    private val live = StreamAtaqueFixture.live

    private fun reconnecting(
        attempt: Int,
        delayMillis: Long,
    ) = StreamAtaqueFixture.reconnecting(attempt, delayMillis)

    private fun ready(id: String) = StreamAtaqueFixture.ready(id)

    private fun reset(id: String) = StreamAtaqueFixture.reset(id)

    private fun message(
        id: String,
        data: String = """{"type":"rooms.room-status","id":"r-$id","version":3}""",
    ) = StreamAtaqueFixture.message(id, data = data)

    private fun roomStatus(id: String) = StreamAtaqueFixture.roomStatus(id)

    private suspend fun ByteChannel.send(text: String) = writeStringUtf8(text)

    // ---- posição (Last-Event-ID) ----

    @Test
    fun lastEventIdFollowsNewestIdAcrossSeveralDrops() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1") + message("e-2"))
            f.stream(ready("e-3"))
            f.status(503)
            f.stream(reset("e-7") + message("e-8"))
            f.stream(ready("e-9"))
            f.collectInBackground(f.connection())
            advanceTimeBy(10_000)
            assertEquals(listOf(null, "e-2", "e-3", "e-3", "e-8", "e-9"), f.lastEventIds())
            assertEquals(listOf(0L, 1_000L, 2_000L, 4_000L, 5_000L, 6_000L), f.requestTimes)
        }

    @Test
    fun readyWithoutIdKeepsCarriedPosition() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream("event: ready\ndata: {}\n\n")
            val (items, _) = f.collectInBackground(f.connection(startAfter = "e-0"))
            advanceTimeBy(1_500)
            assertEquals(Event(RealtimeEvent.Ready, "e-0"), items[2])
            assertEquals(listOf("e-0", "e-0"), f.lastEventIds())
        }

    @Test
    fun emptyStartAfterSendsNoLastEventId() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1"))
            f
                .connection(startAfter = "")
                .run()
                .take(3)
                .toList()
            assertEquals(listOf<String?>(null), f.lastEventIds())
        }

    @Test
    fun startAfterIsReusedWhileNoIdArrives() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream("")
            f.stream(": só comentário\n\n")
            f.collectInBackground(f.connection(startAfter = "e-0"))
            advanceTimeBy(3_500)
            assertEquals(listOf("e-0", "e-0", "e-0"), f.lastEventIds())
        }

    @Test
    fun emptyIdAfterMessageKeepsPositionInEventAndReconnection() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1") + "event: rooms\nid:\ndata: {\"type\":\"t\",\"id\":\"x\",\"version\":1}\n\n")
            val (items, _) = f.collectInBackground(f.connection())
            advanceTimeBy(1_500)
            assertEquals(Event(RealtimeEvent.Message("rooms", "t", "x", 1), "e-1"), items[3])
            assertEquals(listOf(null, "e-1"), f.lastEventIds())
        }

    @Test
    fun bodyEndingMidEventNeitherDeliversNorAdvances() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1") + "event: rooms\nid: e-2\ndata: {\"type\":\"t\",\"id\":\"x\",\"version\":1}\n")
            val (items, _) = f.collectInBackground(f.connection())
            advanceTimeBy(1_500)
            assertEquals(
                listOf(connecting, live, Event(RealtimeEvent.Ready, "e-1"), reconnecting(1, 1_000), connecting),
                items,
            )
            assertEquals(listOf(null, "e-1"), f.lastEventIds())
        }

    @Test
    fun bodyEndingMidLineNeitherDeliversNorAdvances() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1") + "event: rooms\nid: e-2\ndata: {\"type\":\"t\",\"id\":\"x\",\"ver")
            val (items, _) = f.collectInBackground(f.connection())
            advanceTimeBy(1_500)
            assertEquals(
                listOf(connecting, live, Event(RealtimeEvent.Ready, "e-1"), reconnecting(1, 1_000), connecting),
                items,
            )
            assertEquals(listOf(null, "e-1"), f.lastEventIds())
        }

    @Test
    fun partialEventIsNotCarriedIntoNextConnection() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1") + "event: rooms\nid: e-2\ndata: {\"type\":\"t\",\"id\":\"x\",\"version\":1}\n")
            f.stream("\n" + ready("e-5"))
            val items =
                f
                    .connection()
                    .run()
                    .take(7)
                    .toList()
            assertEquals(
                listOf(
                    connecting,
                    live,
                    Event(RealtimeEvent.Ready, "e-1"),
                    reconnecting(1, 1_000),
                    connecting,
                    live,
                    Event(RealtimeEvent.Ready, "e-5"),
                ),
                items,
            )
        }

    // ---- espera ----

    @Test
    fun backoffCapsAtThirtySeconds() =
        runTest {
            val f = StreamAtaqueFixture(this)
            repeat(7) { f.status(503) }
            f.stream(ready("e-1"))
            val (items, _) = f.collectInBackground(f.connection())
            advanceTimeBy(91_500)
            assertEquals(
                listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
                    .mapIndexed { i, d -> RealtimeStatus.Reconnecting(i + 1, d) },
                StreamAtaqueFixture.waits(items).take(7),
            )
            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 61_000L, 91_000L), f.requestTimes)
        }

    @Test
    fun unreadableMessageResetDoesNotZeroBackoffNorGoLive() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream("")
            f.stream("")
            f.stream(message("e-2", data = "lixo"))
            f.stream(ready("e-3"))
            val (items, _) = f.collectInBackground(f.connection())
            advanceTimeBy(7_500)
            assertEquals(
                listOf(
                    RealtimeStatus.Reconnecting(1, 1_000),
                    RealtimeStatus.Reconnecting(2, 2_000),
                    RealtimeStatus.Reconnecting(3, 4_000),
                ),
                StreamAtaqueFixture.waits(items).take(3),
            )
            val resetAt = items.indexOf(Event(RealtimeEvent.Reset, "e-2"))
            assertTrue(resetAt >= 0, "o dado ilegível vira Reset")
            assertTrue(items.indexOf(live) > resetAt, "o Reset de dado ilegível não põe a conexão em Live")
            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), f.requestTimes)
        }

    @Test
    fun resetEventZeroesBackoff() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream("")
            f.stream("")
            f.stream(reset("e-3"))
            f.stream("")
            val (items, _) = f.collectInBackground(f.connection())
            advanceTimeBy(6_500)
            assertEquals(
                listOf(
                    RealtimeStatus.Reconnecting(1, 1_000),
                    RealtimeStatus.Reconnecting(2, 2_000),
                    RealtimeStatus.Reconnecting(1, 1_000),
                    RealtimeStatus.Reconnecting(2, 2_000),
                ),
                StreamAtaqueFixture.waits(items),
            )
            assertEquals(listOf(0L, 1_000L, 3_000L, 4_000L, 6_000L), f.requestTimes)
        }

    // ---- watchdog ----

    @Test
    fun silenceRightAfterResponseToleratedUntil45Seconds() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.liveStream()
            val (items, _) = f.collectInBackground(f.connection())
            advanceTimeBy(44_000)
            assertEquals(listOf<ConnectionItem>(connecting), items)
            advanceTimeBy(1_500)
            assertEquals(reconnecting(1, 1_000), items.last())
            advanceTimeBy(1_000)
            assertEquals(listOf(0L, 46_000L), f.requestTimes)
        }

    @Test
    fun heartbeatsJustInsideWatchdogKeepConnectionAlive() =
        runTest {
            val f = StreamAtaqueFixture(this)
            val channel = f.liveStream()
            val (items, _) = f.collectInBackground(f.connection())
            runCurrent()
            channel.send(ready("e-1"))
            repeat(6) {
                advanceTimeBy(44_900)
                channel.send(":\n")
                runCurrent()
            }
            assertEquals(1, f.requests.size, "uma linha a cada 44,9 s mantém a conexão")
            assertEquals(Event(RealtimeEvent.Ready, "e-1"), items.last())
            advanceTimeBy(45_100)
            assertEquals(reconnecting(1, 1_000), items.last())
        }

    @Test
    fun reconnectedConnectionStartsFreshWatchdog() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream("")
            f.liveStream()
            val (items, _) = f.collectInBackground(f.connection())
            advanceTimeBy(1_000 + 44_500)
            assertEquals(2, f.requests.size)
            assertEquals(connecting, items.last())
            advanceTimeBy(1_000)
            assertEquals(reconnecting(2, 2_000), items.last())
        }

    @Test
    fun slowCollectorDoesNotTripWatchdog() =
        runTest {
            val f = StreamAtaqueFixture(this)
            val channel = f.liveStream()
            val (items, _) = f.collectInBackground(f.connection()) { delay(100_000) }
            f.writeInBackground {
                channel.send(ready("e-1"))
                repeat(20) {
                    delay(15_000)
                    channel.send(": heartbeat\n")
                }
                channel.send(message("e-2"))
                repeat(30) {
                    delay(15_000)
                    channel.send(": heartbeat\n")
                }
            }
            advanceTimeBy(600_000)
            assertEquals(1, f.requests.size)
            assertEquals(
                listOf(connecting, live, Event(RealtimeEvent.Ready, "e-1"), Event(roomStatus("e-2"), "e-2")),
                items,
            )
        }

    // ---- cancelamento ----

    @Test
    fun cancelBeforeResponseStopsWithoutRetry() =
        runTest {
            val f = StreamAtaqueFixture(this)
            var handlerCancelled = false
            f.respondWith {
                try {
                    awaitCancellation()
                } finally {
                    handlerCancelled = true
                }
            }
            val (items, job) = f.collectInBackground(f.connection())
            runCurrent()
            assertEquals(listOf<ConnectionItem>(connecting), items)
            job.cancel()
            advanceTimeBy(120_000)
            assertTrue(job.isCancelled)
            assertTrue(job.isCompleted)
            assertTrue(handlerCancelled, "a requisição pendente foi cancelada")
            assertEquals(1, f.requests.size)
            assertEquals(listOf<ConnectionItem>(connecting), items)
        }

    @Test
    fun cancelWhileReadingNeitherEmitsNorReconnects() =
        runTest {
            val f = StreamAtaqueFixture(this)
            val channel = f.liveStream()
            val (items, job) = f.collectInBackground(f.connection())
            runCurrent()
            channel.send(ready("e-1"))
            runCurrent()
            job.cancel()
            advanceTimeBy(120_000)
            assertTrue(job.isCompleted)
            assertTrue(channel.isClosedForWrite, "o corpo da resposta foi liberado")
            assertEquals(listOf(connecting, live, Event(RealtimeEvent.Ready, "e-1")), items)
            assertEquals(1, f.requests.size)
        }

    @Test
    fun cancelWhileReadingErrorBodyStopsWithoutRetry() =
        runTest {
            val f = StreamAtaqueFixture(this)
            val body = ByteChannel(autoFlush = true)
            f.respondWith {
                respond(
                    body,
                    HttpStatusCode.ServiceUnavailable,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            }
            val (items, job) = f.collectInBackground(f.connection())
            runCurrent()
            body.send("{\"code\":")
            runCurrent()
            job.cancel()
            advanceTimeBy(120_000)
            assertTrue(job.isCompleted)
            assertEquals(listOf<ConnectionItem>(connecting), items)
            assertEquals(1, f.requests.size)
        }

    @Test
    fun cancelDuringThirtySecondWaitStopsReconnecting() =
        runTest {
            val f = StreamAtaqueFixture(this)
            repeat(6) { f.status(503) }
            val (items, job) = f.collectInBackground(f.connection())
            advanceTimeBy(40_000)
            assertEquals(6, f.requests.size)
            assertEquals(reconnecting(6, 30_000), items.last())
            val before = items.toList()
            job.cancel()
            advanceTimeBy(120_000)
            assertTrue(job.isCompleted)
            assertEquals(6, f.requests.size)
            assertEquals(before, items)
        }

    @Test
    fun takeReleasesResponseAndStopsReconnecting() =
        runTest {
            val f = StreamAtaqueFixture(this)
            val channel = f.liveStream()
            f.writeInBackground { channel.send(ready("e-1")) }
            val items =
                f
                    .connection()
                    .run()
                    .take(3)
                    .toList()
            assertEquals(listOf(connecting, live, Event(RealtimeEvent.Ready, "e-1")), items)
            runCurrent()
            assertTrue(channel.isClosedForWrite, "o corpo da resposta foi liberado")
            advanceTimeBy(120_000)
            assertEquals(1, f.requests.size)
        }

    // ---- coletor ----

    @Test
    fun collectorExceptionPropagatesUnchangedAndReleasesResponse() =
        runTest {
            val f = StreamAtaqueFixture(this)
            val channel = f.liveStream()
            f.writeInBackground { channel.send(ready("e-1")) }
            val error =
                assertFailsWith<IllegalStateException> {
                    f.connection().run().collect { if (it is Event) error("coletor quebrou") }
                }
            assertEquals("coletor quebrou", error.message)
            runCurrent()
            assertTrue(channel.isClosedForWrite, "o corpo da resposta foi liberado")
            advanceTimeBy(120_000)
            assertEquals(1, f.requests.size)
        }

    @Test
    fun collectorIoExceptionIsNotTakenForNetworkDrop() =
        runTest {
            val f = StreamAtaqueFixture(this)
            val channel = f.liveStream()
            f.writeInBackground { channel.send(ready("e-1")) }
            val seen = CopyOnWriteArrayList<ConnectionItem>()
            val error =
                assertFailsWith<IOException> {
                    f.connection().run().collect {
                        seen += it
                        if (it is Event) throw IOException("disco cheio no coletor")
                    }
                }
            assertEquals("disco cheio no coletor", error.message)
            advanceTimeBy(120_000)
            assertEquals(1, f.requests.size)
            assertEquals(listOf(connecting, live, Event(RealtimeEvent.Ready, "e-1")), seen)
        }

    // ---- enquadramento ----

    @Test
    fun streamDeliveredOneByteAtATimeGivesSameItems() =
        runTest {
            val f = StreamAtaqueFixture(this)
            val channel = f.liveStream()
            val (items, _) = f.collectInBackground(f.connection())
            runCurrent()
            val text =
                "\uFEFFevent: ready\r\nid: e-1\r\ndata: {}\r\n\r\n" +
                    ": heartbeat\r" +
                    "event: rooms\rid: e-2\r" +
                    "data: {\"type\":\"rooms.room\",\"id\":\"quarto-çã-€-\uD83D\uDE00\",\"version\":2}\r\r" +
                    message("e-3")
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                channel.writeFully(byteArrayOf(byte))
                runCurrent()
            }
            assertEquals(
                listOf(
                    connecting,
                    live,
                    Event(RealtimeEvent.Ready, "e-1"),
                    Event(RealtimeEvent.Message("rooms", "rooms.room", "quarto-çã-€-\uD83D\uDE00", 2), "e-2"),
                    Event(roomStatus("e-3"), "e-3"),
                ),
                items,
            )
            assertEquals(1, f.requests.size)
        }

    @Test
    fun byteOrderMarkIsStrippedOnlyAtStartOfBody() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream("\uFEFF" + ready("e-1") + "\uFEFF" + ready("e-2") + message("e-3"))
            val items =
                f
                    .connection()
                    .run()
                    .take(4)
                    .toList()
            // O segundo "ready" começa por BOM: o campo vira "\uFEFFevent", desconhecido, e o evento é ignorado.
            assertEquals(
                listOf(connecting, live, Event(RealtimeEvent.Ready, "e-1"), Event(roomStatus("e-3"), "e-3")),
                items,
            )
        }

    @Test
    fun byteOrderMarkOnReconnectedBodyIsStripped() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1"))
            f.stream("\uFEFF" + reset("e-9"))
            val items =
                f
                    .connection()
                    .run()
                    .take(7)
                    .toList()
            assertEquals(listOf(live, Event(RealtimeEvent.Reset, "e-9")), items.drop(5))
        }

    @Test
    fun multiLineDataIsJoinedBeforeParsing() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(
                ready("e-1") + "event: rooms\nid: e-2\ndata: {\"type\":\"t\",\ndata: \"id\":\"x\",\"version\":5}\n\n",
            )
            val items =
                f
                    .connection()
                    .run()
                    .take(4)
                    .toList()
            assertEquals(Event(RealtimeEvent.Message("rooms", "t", "x", 5), "e-2"), items[3])
        }

    @Test
    fun extraJsonFieldsAreIgnoredAndLongVersionKept() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(
                ready("e-1") +
                    message("e-2", data = """{"type":"t","id":"x","version":9223372036854775807,"extra":{"a":[1]}}"""),
            )
            val items =
                f
                    .connection()
                    .run()
                    .take(4)
                    .toList()
            assertEquals(Event(RealtimeEvent.Message("rooms", "t", "x", Long.MAX_VALUE), "e-2"), items[3])
        }

    // ---- limites ----

    @Test
    fun commentLineOfExactlyOneMebibyteIsAccepted() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1") + ":" + "x".repeat(mib - 1) + "\n" + message("e-2"))
            val items =
                f
                    .connection()
                    .run()
                    .take(4)
                    .toList()
            assertEquals(Event(roomStatus("e-2"), "e-2"), items[3])
            assertEquals(1, f.requests.size)
        }

    /** Linhas do evento: "event: rooms" (12) + "id: e-2" (7) + "data: " (6) + [payloadChars]. */
    private fun eventWithPayload(payloadChars: Int) =
        "event: rooms\nid: e-2\ndata: " + "x".repeat(payloadChars) + "\n\n"

    @Test
    fun eventOfExactlyOneMebibyteIsDelivered() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1") + eventWithPayload(mib - 25))
            val items =
                f
                    .connection()
                    .run()
                    .take(4)
                    .toList()
            assertEquals(Event(RealtimeEvent.Reset, "e-2"), items[3])
            assertEquals(1, f.requests.size)
        }

    @Test
    fun eventOneByteOverLimitReconnectsWithoutAdvancing() =
        runTest {
            val f = StreamAtaqueFixture(this)
            f.stream(ready("e-1") + eventWithPayload(mib - 24) + message("e-3"))
            val (items, _) = f.collectInBackground(f.connection())
            advanceTimeBy(1_500)
            assertEquals(
                listOf(connecting, live, Event(RealtimeEvent.Ready, "e-1"), reconnecting(1, 1_000), connecting),
                items,
            )
            assertEquals(listOf(null, "e-1"), f.lastEventIds())
        }

    @Test
    fun dropMidBodyReconnectsFromLastPosition() =
        runTest {
            val f = StreamAtaqueFixture(this)
            val channel = f.liveStream()
            val (items, _) = f.collectInBackground(f.connection())
            runCurrent()
            channel.send(ready("e-1") + message("e-2"))
            runCurrent()
            channel.close(IOException("conexão caiu"))
            runCurrent()
            assertEquals(reconnecting(1, 1_000), items.last())
            advanceTimeBy(1_500)
            assertEquals(listOf(null, "e-2"), f.lastEventIds())
        }
}
