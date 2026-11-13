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
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A troca de conexão quando a anterior ainda não terminou, como num dispatcher com várias threads: a primeira
 * conexão segura o corpo sem poder ser cancelada até o teste soltar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeClientHandoverTest {
    private val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val bodies = CopyOnWriteArrayList<ByteChannel>()

    /** Quantos corpos de requisições anteriores ainda estavam abertos quando cada requisição chegou. */
    private val openAtRequest = CopyOnWriteArrayList<Int>()

    /** Enquanto incompleto, a primeira conexão segura o corpo dela, sem ceder ao cancelamento. */
    private val releaseFirst = CompletableDeferred<Unit>()

    private val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")

    private fun TestScope.client(): RealtimeClient {
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        openAtRequest += bodies.count { !it.isClosedForWrite }
                        requests += request
                        respond(ByteChannel(autoFlush = true).also { bodies += it }, HttpStatusCode.OK, eventStream)
                    }
                },
            )
        val first = AtomicBoolean(true)
        val holdFirst =
            createClientPlugin("HoldFirst") {
                on(Send) { request ->
                    val call = proceed(request)
                    if (first.getAndSet(false)) withContext(NonCancellable) { releaseFirst.await() }
                    call
                }
            }
        val refreshScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val auth = Authenticator(store, { error("sem refresh") }, refreshScope) { _, _ -> }
        val http =
            createCoreHttpClient(engine) {
                install(AuthPlugin) { authenticator = { auth } }
                install(holdFirst)
            }
        return RealtimeClient(StreamApi(ogenBase(http, "http://servidor.teste")), backgroundScope) { "s1" }
    }

    private fun TestScope.collectInBackground(flow: Flow<RealtimeEvent>): Job =
        backgroundScope.launch { runCatching { flow.collect {} } }

    @Test
    fun reopenAfterStopWaitsForPreviousConnection() =
        runTest {
            val client = client()
            val first = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            assertEquals(1, requests.size)
            // A última assinatura sai: a conexão é parada, mas ainda segura o corpo.
            first.cancel()
            runCurrent()
            collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            assertEquals(1, requests.size, "a nova conexão saiu antes de a anterior terminar")
            releaseFirst.complete(Unit)
            runCurrent()
            assertEquals(2, requests.size)
            assertEquals(listOf(0, 0), openAtRequest, "a conexão anterior ainda estava aberta")
        }

    @Test
    fun chainedReopeningsWaitForTheFirstConnection() =
        runTest {
            val client = client()
            collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            // Duas mudanças da união: a segunda conexão é trocada pela terceira enquanto espera a primeira.
            collectInBackground(client.subscribe(setOf("housekeeping")))
            runCurrent()
            collectInBackground(client.subscribe(setOf("maintenance")))
            runCurrent()
            assertEquals(1, requests.size, "uma conexão saiu antes de a primeira terminar")
            releaseFirst.complete(Unit)
            runCurrent()
            assertEquals(2, requests.size)
            assertEquals("housekeeping,maintenance,rooms", requests.last().url.parameters["topics"])
            assertEquals(listOf(0, 0), openAtRequest, "a conexão anterior ainda estava aberta")
        }

    @Test
    fun subscribeRejectsEmptyTopicOrTopicWithComma() =
        runTest {
            releaseFirst.complete(Unit)
            val client = client()
            for (topics in listOf(setOf(""), setOf("rooms", ""), setOf("rooms,housekeeping"), setOf("rooms", ","))) {
                assertFailsWith<IllegalArgumentException>("tópicos $topics") { client.subscribe(topics) }
            }
            runCurrent()
            assertEquals(0, requests.size)
            assertEquals(RealtimeStatus.Idle, client.status.value)
        }

    @Test
    fun sendToQueueClosedWithErrorIsEndedSubscription() =
        runTest {
            val queue = Channel<RealtimeEvent>(Channel.BUFFERED)
            queue.close(ApiError.Network(java.io.IOException("queda")))
            queue.sendUnlessClosed(RealtimeEvent.Ready)
            val plain = Channel<RealtimeEvent>(Channel.BUFFERED).apply { close() }
            plain.sendUnlessClosed(RealtimeEvent.Ready)
            val cancelled = Channel<RealtimeEvent>(Channel.BUFFERED).apply { cancel() }
            cancelled.sendUnlessClosed(RealtimeEvent.Ready)
            val open = Channel<RealtimeEvent>(Channel.BUFFERED)
            open.sendUnlessClosed(RealtimeEvent.Reset)
            assertEquals(RealtimeEvent.Reset, open.tryReceive().getOrNull())
        }

    @Test
    fun cancelledSenderStillPropagatesItsCancellation() =
        runTest {
            val full = Channel<RealtimeEvent>(Channel.RENDEZVOUS)
            var outcome: Throwable? = null
            val sender =
                launch {
                    try {
                        full.sendUnlessClosed(RealtimeEvent.Ready)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Throwable,
                    ) {
                        outcome = e
                        throw e
                    }
                }
            runCurrent()
            full.cancel()
            sender.cancel()
            runCurrent()
            assertIs<kotlinx.coroutines.CancellationException>(outcome)
            assertTrue(sender.isCancelled)
            assertFalse(sender.isActive)
        }
}
