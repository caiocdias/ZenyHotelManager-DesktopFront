package com.zeny.desktop.core.realtime

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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

/**
 * Ataque à passagem de conexão: duas trocas seguidas sem ceder a vez entre elas, como acontece quando duas
 * threads mudam a união quase juntas. A primeira conexão fica presa no `Send`, sem ceder ao cancelamento, até o
 * teste soltar; em [firstDoneAtRequest], cada requisição anota se ela já tinha saído de lá.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeClientHandoverAtaqueTest {
    private val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val firstDoneAtRequest = CopyOnWriteArrayList<Boolean>()
    private val releaseFirst = CompletableDeferred<Unit>()

    @Volatile private var firstDone = false

    private fun TestScope.client(): RealtimeClient {
        val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        firstDoneAtRequest += firstDone
                        requests += request
                        respond(ByteChannel(autoFlush = true), HttpStatusCode.OK, eventStream)
                    }
                },
            )
        val first = AtomicBoolean(true)
        val holdFirst =
            createClientPlugin("HoldFirstAtaque") {
                on(Send) { request ->
                    val holds = first.getAndSet(false)
                    try {
                        val call = proceed(request)
                        if (holds) withContext(NonCancellable) { releaseFirst.await() }
                        call
                    } finally {
                        if (holds) firstDone = true
                    }
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

    /** Assina sem ceder a vez: a assinatura entra (e a união muda) antes de qualquer outra corrotina rodar. */
    private fun TestScope.subscribeNow(flow: Flow<RealtimeEvent>): Job =
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { runCatching { flow.collect {} } }

    @Test
    fun twoUnionChangesInARowWaitForTheHeldConnection() =
        runTest {
            val client = client()
            subscribeNow(client.subscribe(setOf("rooms")))
            runCurrent()
            assertEquals(1, requests.size)
            // A segunda conexão é trocada pela terceira antes mesmo de começar a rodar.
            subscribeNow(client.subscribe(setOf("housekeeping")))
            subscribeNow(client.subscribe(setOf("maintenance")))
            runCurrent()
            assertEquals(1, requests.size, "uma conexão saiu com a primeira ainda presa")
            releaseFirst.complete(Unit)
            runCurrent()
            assertEquals(2, requests.size)
            assertEquals("housekeeping,maintenance,rooms", requests.last().url.parameters["topics"])
            assertEquals(listOf(false, true), firstDoneAtRequest)
        }

    @Test
    fun stopThenTwoReopeningsInARowWaitForTheHeldConnection() =
        runTest {
            val client = client()
            val only = subscribeNow(client.subscribe(setOf("rooms")))
            runCurrent()
            assertEquals(1, requests.size)
            // A última assinatura sai (a conexão para, mas segue presa) e duas voltam sem ceder a vez.
            only.cancel()
            runCurrent()
            assertEquals(RealtimeStatus.Idle, client.status.value)
            subscribeNow(client.subscribe(setOf("rooms")))
            subscribeNow(client.subscribe(setOf("housekeeping")))
            runCurrent()
            assertEquals(1, requests.size, "uma conexão saiu com a anterior ainda presa")
            releaseFirst.complete(Unit)
            runCurrent()
            assertEquals(2, requests.size)
            assertEquals("housekeeping,rooms", requests.last().url.parameters["topics"])
            assertEquals(listOf(false, true), firstDoneAtRequest)
        }

    @Test
    fun unionChangesWithAYieldBetweenThemStillWaitForTheHeldConnection() =
        runTest {
            // Controle: com a vez cedida entre as trocas (cada conexão nova começa a esperar), a corrente segura.
            val client = client()
            subscribeNow(client.subscribe(setOf("rooms")))
            runCurrent()
            subscribeNow(client.subscribe(setOf("housekeeping")))
            runCurrent()
            subscribeNow(client.subscribe(setOf("maintenance")))
            runCurrent()
            assertEquals(1, requests.size)
            releaseFirst.complete(Unit)
            runCurrent()
            assertEquals(listOf(false, true), firstDoneAtRequest)
        }
}
