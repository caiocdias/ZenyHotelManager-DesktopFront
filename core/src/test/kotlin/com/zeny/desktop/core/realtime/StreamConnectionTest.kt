package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.core.realtime.ConnectionItem.Event
import com.zeny.desktop.core.realtime.ConnectionItem.Status
import com.zeny.desktop.core.session.AuthPlugin
import com.zeny.desktop.core.session.Authenticator
import com.zeny.desktop.core.session.TokenStore
import com.zeny.desktop.core.session.Tokens
import com.zeny.desktop.sdk.client.StreamApi
import io.ktor.client.HttpClient
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
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamConnectionTest {
    private val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val requestTimes = CopyOnWriteArrayList<Long>()
    private val responses = ArrayDeque<suspend MockRequestHandleScope.() -> HttpResponseData>()
    private val ended = CopyOnWriteArrayList<String?>()

    private val ready = "event: ready\nid: e-1\ndata: {}\n\n"

    private fun message(
        id: String,
        topic: String = "rooms",
        data: String = """{"type":"rooms.room-status","id":"r-$id","version":3}""",
    ) = "event: $topic\nid: $id\ndata: $data\n\n"

    private fun stream(body: String) = streamOf(ByteReadChannel(body))

    private fun streamOf(channel: ByteReadChannel) {
        responses += { respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) }
    }

    /** Um stream que só termina quando o teste fecha o canal; o teste escreve nele as linhas. */
    private fun liveStream(): ByteChannel = ByteChannel(autoFlush = true).also(::streamOf)

    private fun status(
        code: HttpStatusCode,
        body: String = "",
        contentType: String = "application/problem+json",
    ) {
        responses += { respond(body, code, headersOf(HttpHeaders.ContentType, contentType)) }
    }

    private fun problem(
        code: HttpStatusCode,
        problemCode: String,
    ) = status(code, """{"code":"$problemCode","detail":"detalhe"}""")

    private fun TestScope.connection(
        topics: Set<String> = setOf("rooms"),
        startAfter: String? = null,
    ): StreamConnection {
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        requests += request
                        requestTimes += testScheduler.currentTime
                        val next = responses.removeFirstOrNull()
                        next?.invoke(this) ?: respond(ByteChannel(), HttpStatusCode.OK, eventStream)
                    }
                },
            )
        val refreshScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val auth = Authenticator(store, { error("sem refresh") }, refreshScope) { code, _ -> ended += code }
        val client = createCoreHttpClient(engine) { install(AuthPlugin) { authenticator = { auth } } }
        http = client
        httpEngine = engine
        return StreamConnection(StreamApi(ogenBase(client, "http://servidor.teste")), topics, startAfter)
    }

    /** O cliente e o engine da última [connection], para fechá-los como o `ZenyCore.close()`. */
    private lateinit var http: HttpClient
    private lateinit var httpEngine: MockEngine

    /** Uma resposta com o [code] cujo corpo o teste escreve e que só termina quando o teste fecha o canal. */
    private fun liveError(code: HttpStatusCode): ByteChannel =
        ByteChannel(autoFlush = true).also { channel ->
            responses += { respond(channel, code, headersOf(HttpHeaders.ContentType, "application/problem+json")) }
        }

    private val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")

    private suspend fun ByteChannel.send(text: String) = writeStringUtf8(text)

    /** Coleta em segundo plano; o teste conduz o tempo virtual. */
    private fun TestScope.collectInBackground(connection: StreamConnection): Pair<MutableList<ConnectionItem>, Job> {
        val items = CopyOnWriteArrayList<ConnectionItem>()
        val job = backgroundScope.launch { connection.run().collect { items += it } }
        return items to job
    }

    /** Escreve sem travar o teste: quem lê pode desistir do corpo no meio da escrita. */
    private fun TestScope.writeInBackground(write: suspend () -> Unit) {
        backgroundScope.launch { runCatching { write() } }
    }

    private val connecting = Status(RealtimeStatus.Connecting)
    private val live = Status(RealtimeStatus.Live)

    private fun reconnecting(
        attempt: Int,
        delayMillis: Long,
    ) = Status(RealtimeStatus.Reconnecting(attempt, delayMillis))

    @Test
    fun sendsBearerTopicsAndAccept() =
        runTest {
            stream(ready)
            connection(topics = linkedSetOf("rooms", "notifications")).run().take(3).toList()
            val request = requests.single()
            assertEquals("/api/v1/stream", request.url.encodedPath)
            assertEquals("rooms,notifications", request.url.parameters["topics"])
            assertEquals(listOf("Bearer A1"), request.headers.getAll(HttpHeaders.Authorization))
            assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
            assertNull(request.headers["Last-Event-ID"])
        }

    @Test
    fun emitsReadyThenMessages() =
        runTest {
            stream(ready + message("e-2") + message("e-3", data = """{"type":"rooms.room","id":"x","version":7}"""))
            val items = connection().run().take(5).toList()
            assertEquals(
                listOf(
                    connecting,
                    live,
                    Event(RealtimeEvent.Ready, "e-1"),
                    Event(RealtimeEvent.Message("rooms", "rooms.room-status", "r-e-2", 3), "e-2"),
                    Event(RealtimeEvent.Message("rooms", "rooms.room", "x", 7), "e-3"),
                ),
                items,
            )
        }

    @Test
    fun startsAfterGivenPosition() =
        runTest {
            stream(ready)
            connection(startAfter = "e-0").run().take(3).toList()
            assertEquals("e-0", requests.single().headers["Last-Event-ID"])
        }

    @Test
    fun reconnectsWithLastEventId() =
        runTest {
            // O `id:` vazio do terceiro evento não apaga a posição.
            stream(ready + message("e-2") + "event: rooms\nid:\ndata: {}\n\n")
            stream(ready)
            val items = connection().run().take(9).toList()
            assertEquals(reconnecting(1, 1_000), items[5])
            assertEquals(listOf(null, "e-2"), requests.map { it.headers["Last-Event-ID"] })
        }

    @Test
    fun positionComesFromReadyAndReset() =
        runTest {
            stream("event: ready\nid: e-5\ndata: {}\n\n")
            stream("event: reset\nid: e-9\ndata: {}\n\n")
            stream(ready)
            connection(startAfter = "e-0").run().take(10).toList()
            assertEquals(listOf("e-0", "e-5", "e-9"), requests.map { it.headers["Last-Event-ID"] })
        }

    @Test
    fun resetOnlyAfterRestartIsEnough() =
        runTest {
            stream(ready)
            stream("event: reset\nid: e-9\ndata: {}\n\n")
            val items = connection().run().take(7).toList()
            assertEquals(
                listOf(
                    connecting,
                    live,
                    Event(RealtimeEvent.Ready, "e-1"),
                    reconnecting(1, 1_000),
                    connecting,
                    live,
                    Event(RealtimeEvent.Reset, "e-9"),
                ),
                items,
            )
        }

    @Test
    fun backoffFollowsPolicyAndResetsOnReady() =
        runTest {
            repeat(3) { stream("") }
            stream(ready)
            stream("")
            val items = connection().run().take(12).toList()
            val waits = items.mapNotNull { (it as? Status)?.status as? RealtimeStatus.Reconnecting }
            assertEquals(
                listOf(
                    RealtimeStatus.Reconnecting(1, 1_000),
                    RealtimeStatus.Reconnecting(2, 2_000),
                    RealtimeStatus.Reconnecting(3, 4_000),
                    RealtimeStatus.Reconnecting(1, 1_000),
                    RealtimeStatus.Reconnecting(2, 2_000),
                ),
                waits,
            )
            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 8_000L), requestTimes)
        }

    @Test
    fun silenceOver45sReconnects() =
        runTest {
            val first = liveStream()
            stream(ready)
            val (items, _) = collectInBackground(connection())
            runCurrent()
            first.send(ready)
            advanceTimeBy(30_000)
            first.send(": heartbeat\n\n")
            runCurrent()
            advanceTimeBy(44_999)
            assertEquals(1, requests.size, "uma linha a cada 45 s mantém a conexão")
            advanceTimeBy(2)
            assertEquals(reconnecting(1, 1_000), items.last())
            advanceTimeBy(1_000)
            assertEquals(2, requests.size)
            assertEquals(30_000L + 45_000L + 1_000L, requestTimes[1])
        }

    @Test
    fun longLivedStreamIsNotCutByRequestTimeout() =
        runTest {
            val channel = liveStream()
            val (items, _) = collectInBackground(connection())
            runCurrent()
            channel.send(ready)
            repeat(10) {
                advanceTimeBy(15_000)
                channel.send(": heartbeat\n\n")
                runCurrent()
            }
            assertEquals(1, requests.size)
            assertEquals(listOf(connecting, live, Event(RealtimeEvent.Ready, "e-1")), items)
        }

    @Test
    fun badGatewayRetriesWithBackoff() =
        runTest {
            status(HttpStatusCode.BadGateway, "<html>proxy</html>", "text/html")
            status(HttpStatusCode.BadGateway, "<html>proxy</html>", "text/html")
            stream(ready)
            val items = connection().run().take(7).toList()
            assertEquals(
                listOf(
                    connecting,
                    reconnecting(1, 1_000),
                    connecting,
                    reconnecting(2, 2_000),
                    connecting,
                    live,
                    Event(RealtimeEvent.Ready, "e-1"),
                ),
                items,
            )
            assertEquals(listOf(0L, 1_000L, 3_000L), requestTimes)
        }

    @Test
    fun serviceUnavailableRetries() =
        runTest {
            problem(HttpStatusCode.ServiceUnavailable, "UNAVAILABLE")
            status(HttpStatusCode.TooManyRequests)
            status(HttpStatusCode.RequestTimeout)
            stream(ready)
            val items = connection().run().take(9).toList()
            assertEquals(Event(RealtimeEvent.Ready, "e-1"), items.last())
            assertEquals(4, requests.size)
        }

    @Test
    fun unauthorizedStops() =
        runTest {
            problem(HttpStatusCode.Unauthorized, "SESSION_REVOKED")
            val items = connection().run().toList()
            assertEquals(listOf(connecting), items)
            assertEquals(1, requests.size)
            assertEquals(listOf<String?>("SESSION_REVOKED"), ended)
        }

    @Test
    fun forbiddenFailsWithoutRetry() =
        runTest {
            problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
            val items = connection().run().toList()
            assertEquals(2, items.size)
            val failed = assertIs<RealtimeStatus.Failed>(assertIs<Status>(items.last()).status)
            val error = assertIs<ApiError.Problem>(failed.error)
            assertEquals(403, error.status)
            assertEquals("PERMISSION_DENIED", error.code)
            assertEquals(1, requests.size)
        }

    @Test
    fun unknownTopicFailsWithoutRetry() =
        runTest {
            problem(HttpStatusCode.BadRequest, "UNKNOWN_STREAM_TOPIC")
            val items = connection(topics = setOf("nope")).run().toList()
            val failed = assertIs<RealtimeStatus.Failed>(assertIs<Status>(items.last()).status)
            assertEquals("UNKNOWN_STREAM_TOPIC", assertIs<ApiError.Problem>(failed.error).code)
            assertEquals(1, requests.size)
        }

    @Test
    fun redirectFailsWithoutRetry() =
        runTest {
            responses += { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://outro.teste/")) }
            val items = connection().run().toList()
            assertIs<RealtimeStatus.Failed>(assertIs<Status>(items.last()).status)
            assertEquals(1, requests.size)
        }

    @Test
    fun nonEventStreamFails() =
        runTest {
            status(HttpStatusCode.OK, """{"token":"A1"}""", "application/json")
            val items = connection().run().toList()
            val failed = assertIs<RealtimeStatus.Failed>(assertIs<Status>(items.last()).status)
            assertIs<ApiError.IncompatibleResponse>(failed.error)
            assertEquals(1, requests.size)
        }

    @Test
    fun unknownEventNameIgnored() =
        runTest {
            stream(ready + message("e-2", topic = "other") + "data: sem nome\n\n" + message("e-3"))
            val items = connection().run().take(4).toList()
            assertEquals(Event(RealtimeEvent.Message("rooms", "rooms.room-status", "r-e-3", 3), "e-3"), items[3])
        }

    @Test
    fun garbageDataBecomesReset() =
        runTest {
            stream(
                ready +
                    message("e-2", data = "isto não é json") +
                    message("e-3", data = """{"type":"t","id":"x"}""") +
                    message("e-4", data = """{"type":"t","id":"x","version":"3"}""") +
                    message("e-5", data = "[1]"),
            )
            val items = connection().run().take(7).toList()
            assertEquals(
                listOf("e-2", "e-3", "e-4", "e-5").map { Event(RealtimeEvent.Reset, it) },
                items.drop(3),
            )
        }

    @Test
    fun noTokenInUrl() =
        runTest {
            problem(HttpStatusCode.Forbidden, "PERMISSION_DENIED")
            val items = connection().run().toList()
            val request = requests.single()
            assertFalse("A1" in request.url.toString())
            val failed = assertIs<RealtimeStatus.Failed>(assertIs<Status>(items.last()).status)
            assertFalse("A1" in failed.error.stackTraceToString())
        }

    @Test
    fun cancelClosesResponse() =
        runTest {
            val channel = liveStream()
            val (items, job) = collectInBackground(connection())
            runCurrent()
            channel.send(ready)
            runCurrent()
            assertEquals(Event(RealtimeEvent.Ready, "e-1"), items.last())
            job.cancel()
            advanceUntilIdle()
            assertTrue(job.isCancelled)
            assertTrue(channel.isClosedForWrite, "o corpo da resposta foi liberado")
            assertEquals(1, requests.size)
        }

    @Test
    fun bodyCancelledByEngineWhileCollectingReconnects() =
        runTest {
            // O engine fecha o corpo com uma CancellationException; quem coleta continua ativo.
            val channel = liveStream()
            stream(ready)
            val (items, job) = collectInBackground(connection())
            runCurrent()
            channel.send(ready)
            runCurrent()
            channel.cancel(CancellationException("corpo fechado pelo engine"))
            runCurrent()
            assertTrue(job.isActive)
            assertEquals(reconnecting(1, 1_000), items.last())
            advanceTimeBy(1_001)
            assertEquals(2, requests.size)
        }

    @Test
    fun noResponseWithin45sReconnects() =
        runTest {
            responses += { awaitCancellation() }
            stream(ready)
            val (items, job) = collectInBackground(connection())
            advanceTimeBy(44_999)
            assertEquals(listOf(connecting), items.toList())
            advanceTimeBy(2)
            assertTrue(job.isActive)
            assertEquals(reconnecting(1, 1_000), items.last())
            advanceTimeBy(1_000)
            assertEquals(listOf(0L, 46_000L), requestTimes.take(2))
        }

    @Test
    fun errorBodyThatNeverEndsReconnects() =
        runTest {
            val body = liveError(HttpStatusCode.Forbidden)
            stream(ready)
            val (items, _) = collectInBackground(connection())
            runCurrent()
            body.send("""{"code":""")
            advanceTimeBy(44_999)
            assertEquals(listOf(connecting), items.toList())
            advanceTimeBy(2)
            assertEquals(reconnecting(1, 1_000), items.last())
        }

    @Test
    fun errorBodyIsReadOnlyUpTo64KiB() =
        runTest {
            // Um corpo de erro grande que não termina: a leitura para no limite, sem esperar o fim.
            val body = liveError(HttpStatusCode.Forbidden)
            val (items, job) = collectInBackground(connection())
            runCurrent()
            writeInBackground { body.writeFully(ByteArray(70 * 1024) { ' '.code.toByte() }) }
            runCurrent()
            val failed = assertIs<RealtimeStatus.Failed>(assertIs<Status>(items.last()).status)
            assertEquals(403, assertIs<ApiError.Unexpected>(failed.error).status)
            assertTrue(job.isCompleted)
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun clientClosedMidStreamTerminates() =
        runTest {
            val channel = liveStream()
            val (items, job) = collectInBackground(connection())
            runCurrent()
            channel.send(ready)
            runCurrent()
            assertEquals(Event(RealtimeEvent.Ready, "e-1"), items.last())
            http.close()
            httpEngine.close()
            advanceTimeBy(120_000)
            assertTrue(job.isCompleted, "o Flow terminou")
            assertEquals(1, requests.size)
            assertFalse(items.any { (it as? Status)?.status is RealtimeStatus.Failed })
        }

    @Test
    fun clientClosedDuringBackoffTerminates() =
        runTest {
            stream("")
            val (_, job) = collectInBackground(connection())
            runCurrent()
            http.close()
            httpEngine.close()
            advanceTimeBy(120_000)
            assertTrue(job.isCompleted, "o Flow terminou")
            assertEquals(1, requests.size)
        }

    @Test
    fun onlyVisibleAsciiIdBecomesPosition() =
        runTest {
            stream(ready + message("e-2") + message("e-\u00e9") + message("e 3") + message("e-\u00014"))
            stream(ready)
            val items = connection().run().take(10).toList()
            assertEquals(
                listOf("e-2", "e-2", "e-2", "e-2"),
                items.drop(3).take(4).map { assertIs<Event>(it).position },
            )
            assertEquals(listOf(null, "e-2"), requests.map { it.headers["Last-Event-ID"] })
        }

    @Test
    fun cancelDuringBackoffStopsReconnecting() =
        runTest {
            stream("")
            val (items, job) = collectInBackground(connection())
            runCurrent()
            assertEquals(reconnecting(1, 1_000), items.last())
            job.cancel()
            advanceTimeBy(60_000)
            assertEquals(1, requests.size)
        }

    @Test
    fun byteOrderMarkAndLineEndingsAreHandled() =
        runTest {
            val crlf = "\uFEFFevent: ready\r\nid: e-1\r\ndata: {}\r\n\r\n"
            val cr = "event: rooms\rid: e-2\rdata: {\"type\":\"t\",\"id\":\"x\",\"version\":1}\r\r"
            stream(crlf + cr)
            val items = connection().run().take(4).toList()
            assertEquals(
                listOf(
                    connecting,
                    live,
                    Event(RealtimeEvent.Ready, "e-1"),
                    Event(RealtimeEvent.Message("rooms", "t", "x", 1), "e-2"),
                ),
                items,
            )
        }

    @Test
    fun crlfSplitAcrossChunksIsOneLineBreak() =
        runTest {
            val channel = liveStream()
            val (items, _) = collectInBackground(connection())
            runCurrent()
            channel.send("event: ready\r")
            runCurrent()
            channel.send("\nid: e-1\r")
            runCurrent()
            channel.send("\ndata: {}\r")
            runCurrent()
            channel.send("\n\r")
            runCurrent()
            channel.send("\n")
            runCurrent()
            assertEquals(listOf(connecting, live, Event(RealtimeEvent.Ready, "e-1")), items)
        }

    @Test
    fun oversizedLineReconnects() =
        runTest {
            val channel = liveStream()
            stream(ready)
            val (items, _) = collectInBackground(connection())
            runCurrent()
            channel.send(ready)
            writeInBackground { channel.writeFully(ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() }) }
            runCurrent()
            assertEquals(reconnecting(1, 1_000), items.last())
        }

    @Test
    fun oversizedEventReconnects() =
        runTest {
            val channel = liveStream()
            stream(ready)
            val (items, _) = collectInBackground(connection())
            runCurrent()
            channel.send(ready + "event: rooms\n")
            val chunk = "data: " + "x".repeat(64 * 1024) + "\n"
            writeInBackground { repeat(17) { channel.send(chunk) } }
            runCurrent()
            assertEquals(reconnecting(1, 1_000), items.last())
        }
}
