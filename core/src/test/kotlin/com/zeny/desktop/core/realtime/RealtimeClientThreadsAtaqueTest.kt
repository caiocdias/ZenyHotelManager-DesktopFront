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
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real (não virtual): aqui as threads são de verdade; o limite só corta um teste travado. */
private const val REAL_TIMEOUT_MILLIS = 10_000L
private const val THREADS = 8
private const val ROUNDS = 25
private const val SENDS = 2_000

/**
 * O [RealtimeClient] e o `sendUnlessClosed` sob um dispatcher com várias threads de verdade. Os testes não
 * dependem de uma ordem: afirmam o que vale em qualquer intercalação (nada sobra preso, ninguém lança, uma
 * conexão por vez), então não ficam vermelhos por azar quando a implementação está certa. O contrário não vale:
 * enquanto a conexão trocada antes de começar a rodar não esperar a anterior (o defeito que o
 * `RealtimeClientHandoverAtaqueTest` mostra de forma determinística), o teste encadeado fica vermelho só em
 * parte das execuções.
 */
class RealtimeClientThreadsAtaqueTest {
    private val pool: ExecutorCoroutineDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    @AfterTest
    fun tearDown() {
        pool.close()
    }

    /**
     * Um cliente sobre o [pool]; a primeira conexão fica presa no `Send`, sem ceder ao cancelamento, até
     * [releaseFirst].
     *
     * Com [announceReady], cada corpo já nasce com um `ready`, e só conta como aberto o corpo que o cliente leu e
     * ainda não soltou: o `MockEngine` não fecha o corpo de uma requisição cancelada antes de a resposta ser lida
     * (um órfão, sem conexão viva atrás), e esse não é sobreposição.
     */
    private class Rig(
        dispatcher: CoroutineDispatcher,
        val scope: CoroutineScope,
        announceReady: Boolean = false,
    ) {
        val requests = CopyOnWriteArrayList<HttpRequestData>()
        val openAtRequest = CopyOnWriteArrayList<Int>()

        /** Se a primeira conexão já tinha saído do `Send` segurado quando cada requisição chegou. */
        val firstDoneAtRequest = CopyOnWriteArrayList<Boolean>()

        @Volatile private var firstDone = false
        val arrivals = Channel<HttpRequestData>(Channel.UNLIMITED)
        val releaseFirst = CompletableDeferred<Unit>()
        private val bodies = CopyOnWriteArrayList<ByteChannel>()
        private val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }

        val client: RealtimeClient

        init {
            val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")
            val engine =
                MockEngine(
                    MockEngineConfig().apply {
                        this.dispatcher = dispatcher
                        addHandler { request ->
                            openAtRequest +=
                                bodies.count { !it.isClosedForWrite && (!announceReady || it.availableForRead == 0) }
                            firstDoneAtRequest += firstDone
                            requests += request
                            arrivals.trySend(request)
                            val body = ByteChannel(autoFlush = true).also { bodies += it }
                            if (announceReady) body.writeStringUtf8("event: ready\ndata: {}\n\n")
                            respond(body, HttpStatusCode.OK, eventStream)
                        }
                    },
                )
            val first = AtomicBoolean(true)
            val holdFirst =
                createClientPlugin("HoldFirstThreads") {
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
            val auth = Authenticator(store, { error("sem refresh") }, scope) { _, _ -> }
            val http =
                createCoreHttpClient(engine) {
                    install(AuthPlugin) { authenticator = { auth } }
                    install(holdFirst)
                }
            client = RealtimeClient(StreamApi(ogenBase(http, "http://servidor.teste")), scope) { "s1" }
        }
    }

    /** Assina de [THREADS] threads ao mesmo tempo; cada assinatura já entrou quando a thread termina. */
    private fun subscribeFromThreads(
        rig: Rig,
        topics: (Int) -> String,
    ): List<Job> {
        val jobs = CopyOnWriteArrayList<Job>()
        val barrier = CyclicBarrier(THREADS)
        val threads =
            (1..THREADS).map { i ->
                thread {
                    barrier.await(REAL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    jobs +=
                        rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            rig.client.subscribe(setOf(topics(i))).collect {}
                        }
                }
            }
        threads.forEach { it.join(REAL_TIMEOUT_MILLIS) }
        assertTrue(threads.none { it.isAlive }, "uma thread de assinatura travou")
        assertEquals(THREADS, jobs.size)
        return jobs
    }

    @Test
    fun chainedReopeningsFromManyThreadsWaitForTheHeldConnectionAndOpenOnlyTheFinalUnion() =
        runBlocking {
            repeat(ROUNDS) { round ->
                val scope = CoroutineScope(SupervisorJob() + pool)
                try {
                    val rig = Rig(pool, scope)
                    scope.launch { rig.client.subscribe(setOf("rooms")).collect {} }
                    withTimeout(REAL_TIMEOUT_MILLIS) { rig.arrivals.receive() }
                    // Oito mudanças da união, de oito threads, com a primeira conexão ainda segurando o corpo.
                    subscribeFromThreads(rig) { "topico$it" }
                    rig.releaseFirst.complete(Unit)
                    val second = withTimeout(REAL_TIMEOUT_MILLIS) { rig.arrivals.receive() }
                    val union = (listOf("rooms") + (1..THREADS).map { "topico$it" }).sorted().joinToString(",")
                    val topics = second.url.parameters["topics"]
                    assertEquals(union, topics, "rodada $round: a segunda conexão não é a final")
                    // O corpo segurado não serve de medida aqui: o `MockEngine` não o fecha quando a chamada
                    // cancelada volta do `Send` (com um engine de verdade, ele cai com o job da chamada).
                    assertEquals(
                        listOf(false, true),
                        rig.firstDoneAtRequest.take(2),
                        "rodada $round: a segunda conexão saiu com a primeira ainda viva",
                    )
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test
    fun everyoneLeavingFromManyThreadsStopsTheConnectionAndTheNextOneNeverOverlaps() =
        runBlocking {
            repeat(ROUNDS) { round ->
                val scope = CoroutineScope(SupervisorJob() + pool)
                try {
                    val rig = Rig(pool, scope, announceReady = true)
                    rig.releaseFirst.complete(Unit)
                    val jobs = subscribeFromThreads(rig) { "topico${it % 3}" }
                    withTimeout(REAL_TIMEOUT_MILLIS) { rig.client.status.first { it == RealtimeStatus.Live } }
                    // Todos saem ao mesmo tempo, de threads diferentes.
                    val barrier = CyclicBarrier(jobs.size)
                    val leaving =
                        jobs.map { job ->
                            thread {
                                barrier.await()
                                job.cancel()
                            }
                        }
                    leaving.forEach { it.join(REAL_TIMEOUT_MILLIS) }
                    withTimeout(REAL_TIMEOUT_MILLIS) { jobs.forEach { it.join() } }
                    withTimeout(REAL_TIMEOUT_MILLIS) { rig.client.status.first { it == RealtimeStatus.Idle } }
                    // Volta na hora: a conexão nova só sai quando a anterior soltou o corpo.
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        rig.client.subscribe(setOf("rooms")).collect {}
                    }
                    var last = withTimeout(REAL_TIMEOUT_MILLIS) { rig.arrivals.receive() }
                    while (last.url.parameters["topics"] != "rooms") {
                        last = withTimeout(REAL_TIMEOUT_MILLIS) { rig.arrivals.receive() }
                    }
                    assertTrue(rig.openAtRequest.all { it == 0 }, "rodada $round: ${rig.openAtRequest}")
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test
    fun sendUnlessClosedNeverThrowsWhenTheQueueEndsConcurrentlyInAnyWay() =
        runBlocking {
            val failures = CopyOnWriteArrayList<Throwable>()
            withContext(pool) {
                repeat(SENDS) { i ->
                    val queue = Channel<RealtimeEvent>(if (i % 2 == 0) Channel.RENDEZVOUS else 1)
                    val sender =
                        async {
                            try {
                                repeat(10) { queue.sendUnlessClosed(RealtimeEvent.Ready) }
                            } catch (
                                @Suppress("TooGenericExceptionCaught") e: Throwable,
                            ) {
                                failures += e
                            }
                        }
                    // Como o Flow do assinante: lê até o fim (fechada, a fila ainda entrega o que já estava a caminho).
                    val receiver = launch { runCatching { queue.consumeEach { } } }
                    launch {
                        when (i % 3) {
                            0 -> queue.close()
                            1 -> queue.close(ApiError.Network(IOException("queda")))
                            else -> queue.cancel()
                        }
                    }
                    withTimeout(REAL_TIMEOUT_MILLIS) { sender.await() }
                    receiver.cancel()
                }
            }
            assertEquals(emptyList(), failures.map { it::class.simpleName }, "a entrega lançou com a fila terminando")
        }

    @Test
    fun cancelledSenderRacingTheQueueEndNeverFailsWithAnythingButCancellation() =
        runBlocking {
            val wrong = CopyOnWriteArrayList<String>()
            withContext(pool) {
                repeat(SENDS) { i ->
                    val queue = Channel<RealtimeEvent>(Channel.RENDEZVOUS)
                    val suspended = CompletableDeferred<Unit>()
                    val sender =
                        launch {
                            suspended.complete(Unit)
                            try {
                                queue.sendUnlessClosed(RealtimeEvent.Ready)
                                queue.sendUnlessClosed(RealtimeEvent.Reset)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (
                                @Suppress("TooGenericExceptionCaught") e: Throwable,
                            ) {
                                wrong += "rodada $i: ${e::class.simpleName}"
                            }
                        }
                    suspended.await()
                    val barrier = CyclicBarrier(2)
                    val ender =
                        thread {
                            barrier.await()
                            if (i % 2 == 0) queue.close(ApiError.Network(IOException("queda"))) else queue.cancel()
                        }
                    val canceller =
                        thread {
                            barrier.await()
                            sender.cancel()
                        }
                    ender.join(REAL_TIMEOUT_MILLIS)
                    canceller.join(REAL_TIMEOUT_MILLIS)
                    withTimeout(REAL_TIMEOUT_MILLIS) { sender.join() }
                }
            }
            assertEquals(emptyList(), wrong)
        }
}
