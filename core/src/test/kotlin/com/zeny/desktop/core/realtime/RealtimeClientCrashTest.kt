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
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Um `Error` no meio da coleta não deixa a conexão corrente e morta: o cliente se recompõe e o `Error` segue. */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeClientCrashTest {
    private val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }
    private val responses = ArrayDeque<suspend MockRequestHandleScope.() -> HttpResponseData>()
    private var requests = 0
    private val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")

    /** O que escapou das conexões: o escopo do cliente as isola como o `SupervisorJob` do `ZenyCore`. */
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    private fun TestScope.client(): RealtimeClient {
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        requests++
                        val next = responses.removeFirstOrNull()
                        next?.invoke(this) ?: respond(ByteChannel(), HttpStatusCode.OK, eventStream)
                    }
                },
            )
        val refreshScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val auth = Authenticator(store, { error("sem refresh") }, refreshScope) { _, _ -> }
        val http = createCoreHttpClient(engine) { install(AuthPlugin) { authenticator = { auth } } }
        val scope =
            CoroutineScope(
                SupervisorJob() + StandardTestDispatcher(testScheduler) +
                    CoroutineExceptionHandler { _, e -> uncaught += e },
            )
        return RealtimeClient(StreamApi(ogenBase(http, "http://servidor.teste")), scope) { "s1" }
    }

    private class Subscriber {
        val events = CopyOnWriteArrayList<RealtimeEvent>()

        @Volatile var completed = false

        @Volatile var error: Throwable? = null
    }

    private fun TestScope.collectInBackground(flow: Flow<RealtimeEvent>): Subscriber {
        val subscriber = Subscriber()
        backgroundScope.launch {
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
    fun errorMidStreamDoesNotLeaveZombieConnection() =
        runTest {
            val channel = ByteChannel(autoFlush = true)
            responses += { respond(channel, HttpStatusCode.OK, eventStream) }
            // A reconexão estoura um `Error` dentro da coleta da conexão (o corpo não o deixaria passar como tal).
            responses += { throw AssertionError("falha interna no meio da coleta") }
            val client = client()
            val first = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.writeStringUtf8("event: ready\nid: e-1\ndata: {}\n\n")
            runCurrent()
            assertEquals(RealtimeStatus.Live, client.status.value)
            assertEquals(listOf<RealtimeEvent>(RealtimeEvent.Ready), first.events)

            channel.close()
            runCurrent()
            assertEquals(RealtimeStatus.Reconnecting(1, 1_000), client.status.value)
            advanceTimeBy(1_001)

            val thrown = uncaught.single()
            assertIs<AssertionError>(thrown, "o Error segue relançado")
            assertTrue(first.completed, "o assinante ficou pendurado na conexão morta")
            assertIs<ApiError.Network>(first.error)
            val failed = assertIs<RealtimeStatus.Failed>(client.status.value, "o status ficou parado")
            assertIs<ApiError.Network>(failed.error)

            // A conexão morta saiu de cena: quem assina de novo reabre.
            val second = collectInBackground(client.subscribe(setOf("rooms")))
            runCurrent()
            // A primeira conexão, a reconexão que estourou e a nova.
            assertEquals(3, requests, "a assinatura nova não reabriu a conexão")
            assertEquals(RealtimeStatus.Connecting, client.status.value)
            assertEquals(false, second.completed)
        }
}
