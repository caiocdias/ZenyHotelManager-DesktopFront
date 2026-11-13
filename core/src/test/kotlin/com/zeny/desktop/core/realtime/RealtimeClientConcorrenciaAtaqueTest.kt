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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
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
import kotlin.test.assertTrue

/**
 * Servidor falso, sessão conduzida e assinantes para os ataques ao [RealtimeClient]; um por cenário.
 *
 * Cada requisição anota quantos corpos de requisições anteriores ainda estavam abertos quando ela chegou
 * ([openAtRequest]): duas conexões ao mesmo tempo aparecem ali como um número maior que zero.
 */
internal class RealtimeAtaqueFixture(
    private val test: TestScope,
) {
    val store = TokenStore().apply { set(Tokens("A1", "R1", "s1", "u1", false)) }

    @Volatile var session: String? = "s1"

    val requests = CopyOnWriteArrayList<HttpRequestData>()
    val openAtRequest = CopyOnWriteArrayList<Int>()
    private val bodies = CopyOnWriteArrayList<ByteChannel>()
    private val responses = ArrayDeque<suspend MockRequestHandleScope.() -> HttpResponseData>()

    /** Com ele, toda requisição é servida a partir do `Last-Event-ID` (e a fila de respostas fica de lado). */
    var replay: ReplayServer? = null

    private val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")

    fun client(scope: CoroutineScope = test.backgroundScope): RealtimeClient {
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(test.testScheduler)
                    addHandler { request ->
                        openAtRequest += bodies.count { !it.isClosedForWrite }
                        requests += request
                        val server = replay
                        val next = responses.removeFirstOrNull()
                        when {
                            server != null -> {
                                respond(server.open(request).also { bodies += it }, HttpStatusCode.OK, eventStream)
                            }

                            next != null -> {
                                next(this)
                            }

                            else -> {
                                respond(ByteChannel().also { bodies += it }, HttpStatusCode.OK, eventStream)
                            }
                        }
                    }
                },
            )
        val refreshScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val auth = Authenticator(store, { error("sem refresh") }, refreshScope) { _, _ -> }
        val http = createCoreHttpClient(engine) { install(AuthPlugin) { authenticator = { auth } } }
        return RealtimeClient(StreamApi(ogenBase(http, "http://servidor.teste")), scope) { session }
    }

    /** Um corpo que só termina quando o teste fecha o canal; o teste escreve nele os eventos. */
    fun liveStream(): ByteChannel =
        ByteChannel(autoFlush = true).also { channel ->
            responses += {
                bodies += channel
                respond(channel, HttpStatusCode.OK, eventStream)
            }
        }

    fun problem(
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

    fun login(sessionId: String) {
        store.set(Tokens("A-$sessionId", "R-$sessionId", sessionId, "u1", false))
        session = sessionId
    }

    fun lastEventIds(): List<String?> = requests.map { it.headers["Last-Event-ID"] }

    fun topicsOf(index: Int): String? = requests[index].url.parameters["topics"]

    /** Coleta em segundo plano; com [undispatched], a assinatura entra na hora, sem ceder a vez. */
    fun collect(
        flow: Flow<RealtimeEvent>,
        undispatched: Boolean = false,
        onEach: suspend (RealtimeEvent) -> Unit = {},
    ): Collected {
        val collected = Collected()
        val start = if (undispatched) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT
        collected.job =
            test.backgroundScope.launch(start = start) {
                try {
                    flow.collect {
                        onEach(it)
                        collected.events += it
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    collected.error = e
                }
                collected.completed = true
            }
        return collected
    }

    /** Escreve sem travar o teste: quem lê pode desistir do corpo no meio da escrita. */
    fun writeInBackground(write: suspend () -> Unit) {
        test.backgroundScope.launch { runCatching { write() } }
    }

    /** O que um assinante recebeu e como o `Flow` dele terminou. */
    class Collected {
        val events = CopyOnWriteArrayList<RealtimeEvent>()

        @Volatile var completed = false

        @Volatile var error: Throwable? = null

        lateinit var job: Job

        val completedNormally get() = completed && error == null

        /** O número `N` de cada `Message` recebida (id `r-e-N`), na ordem de chegada. */
        fun messageNumbers(): List<Int> = events.filterIsInstance<RealtimeEvent.Message>().map { it.id.number() }
    }

    companion object {
        fun ready(id: String) = "event: ready\nid: $id\ndata: {}\n\n"

        fun reset(id: String) = "event: reset\nid: $id\ndata: {}\n\n"

        fun message(
            id: String,
            topic: String = "rooms",
        ) = "event: $topic\nid: $id\ndata: {\"type\":\"$topic.changed\",\"id\":\"r-$id\",\"version\":3}\n\n"

        fun changed(
            id: String,
            topic: String = "rooms",
        ) = RealtimeEvent.Message(topic, "$topic.changed", "r-$id", 3)

        /** `e-7` → 7, `r-e-7` → 7, ausente → 0. */
        fun String?.number(): Int = this?.substringAfterLast('-')?.toInt() ?: 0

        /** Os jobs ainda vivos sob o [scope] dado ao cliente. */
        fun jobsUnder(scope: CoroutineScope): List<Job> =
            scope.coroutineContext.job.children
                .toList()
    }
}

/**
 * Um servidor que guarda o histórico e serve cada conexão a partir do `Last-Event-ID`: `ready` com a própria
 * posição, o que veio depois nos tópicos pedidos e, enquanto o corpo estiver aberto, o que for publicado.
 */
internal class ReplayServer(
    private val test: TestScope,
) {
    private val log = mutableListOf<Pair<String, Int>>()
    private val open = CopyOnWriteArrayList<Pair<Set<String>, Channel<String>>>()

    /** Os números publicados em cada tópico, em ordem. */
    fun published(topic: String): List<Int> =
        synchronized(this) {
            log.filter { it.first == topic }.map { it.second }
        }

    fun open(request: HttpRequestData): ByteChannel {
        val topics =
            request.url.parameters["topics"]
                .orEmpty()
                .split(",")
                .toSet()
        val after = request.headers["Last-Event-ID"]?.substringAfterLast('-')?.toInt() ?: 0
        val body = ByteChannel(autoFlush = true)
        val queue = Channel<String>(Channel.UNLIMITED)
        val entry = topics to queue
        synchronized(this) {
            queue.trySend(RealtimeAtaqueFixture.ready("e-$after"))
            log
                .filter { it.second > after && it.first in topics }
                .forEach { queue.trySend(RealtimeAtaqueFixture.message("e-${it.second}", it.first)) }
            open += entry
        }
        test.backgroundScope.launch {
            runCatching { for (text in queue) body.writeStringUtf8(text) }
            open -= entry
        }
        return body
    }

    fun publish(topic: String): Int =
        synchronized(this) {
            val number = log.size + 1
            log += topic to number
            val text = RealtimeAtaqueFixture.message("e-$number", topic)
            open.filter { topic in it.first }.forEach { it.second.trySend(text) }
            number
        }
}

/** Ataque ao multiplexador: assinantes que entram e saem depressa, lentos, rápidos, que lançam, e o `Ready`. */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeClientConcorrenciaAtaqueTest {
    private fun ready(id: String) = RealtimeAtaqueFixture.ready(id)

    private fun reset(id: String) = RealtimeAtaqueFixture.reset(id)

    private fun message(
        id: String,
        topic: String = "rooms",
    ) = RealtimeAtaqueFixture.message(id, topic)

    private fun changed(
        id: String,
        topic: String = "rooms",
    ) = RealtimeAtaqueFixture.changed(id, topic)

    private suspend fun ByteChannel.send(text: String) = writeStringUtf8(text)

    private fun positions(f: RealtimeAtaqueFixture): List<Int> =
        with(RealtimeAtaqueFixture) {
            f.lastEventIds().map { it.number() }
        }

    private fun assertNonDecreasing(
        values: List<Int>,
        what: String,
    ) {
        assertTrue(values.zipWithNext().all { (a, b) -> a <= b }, "$what regrediu: $values")
    }

    // ---- dois assinantes do mesmo tópico ----

    @Test
    fun twoSubscribersOfSameTopicEachGetEverythingOverOneConnection() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val channel = f.liveStream()
            val client = f.client()
            val first = f.collect(client.subscribe(setOf("rooms")))
            val second = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(ready("e-1") + message("e-2") + message("e-3"))
            runCurrent()
            val expected = listOf(RealtimeEvent.Ready, changed("e-2"), changed("e-3"))
            assertEquals(expected, first.events)
            assertEquals(expected, second.events)
            assertEquals(1, f.requests.size)
            assertEquals("rooms", f.topicsOf(0))
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    @Test
    fun sameFlowCollectedTwiceMakesTwoIndependentSubscriptions() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val channel = f.liveStream()
            val client = f.client()
            val flow = client.subscribe(setOf("rooms"))
            val first = f.collect(flow)
            val second = f.collect(flow)
            runCurrent()
            channel.send(ready("e-1") + message("e-2"))
            runCurrent()
            first.job.cancel()
            runCurrent()
            channel.send(message("e-3"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2")), first.events)
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2"), changed("e-3")), second.events)
            assertFalse(channel.isClosedForWrite, "ainda há uma coleta do mesmo Flow")
            second.job.cancel()
            runCurrent()
            assertTrue(channel.isClosedForWrite)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            assertEquals(1, f.requests.size)
        }

    @Test
    fun topicSetChangedAfterSubscribeHasNoEffect() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            f.liveStream()
            val client = f.client()
            val topics = mutableSetOf("rooms")
            val flow = client.subscribe(topics)
            topics += "housekeeping"
            f.collect(flow)
            runCurrent()
            assertEquals("rooms", f.topicsOf(0))
        }

    // ---- coletor que lança ----

    @Test
    fun throwingCollectorDoesNotTakeDownTheOthers() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val channel = f.liveStream()
            val client = f.client()
            val broken =
                f.collect(client.subscribe(setOf("rooms"))) {
                    check(it !is RealtimeEvent.Message) { "coletor quebrado" }
                }
            val healthy = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(ready("e-1") + message("e-2") + message("e-3"))
            runCurrent()
            channel.send(message("e-4"))
            runCurrent()
            assertIs<IllegalStateException>(broken.error)
            assertEquals(listOf<RealtimeEvent>(RealtimeEvent.Ready), broken.events)
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2"), changed("e-3"), changed("e-4")), healthy.events)
            assertFalse(healthy.completed)
            assertFalse(channel.isClosedForWrite)
            assertEquals(1, f.requests.size, "a união não mudou: a conexão segue")
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    @Test
    fun throwingCollectorOfOwnTopicReconnectsTheOthersWithoutLoss() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val server = ReplayServer(this).also { f.replay = it }
            val client = f.client()
            val broken =
                f.collect(client.subscribe(setOf("housekeeping"))) {
                    check(it !is RealtimeEvent.Message) { "coletor quebrado" }
                }
            val rooms = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            server.publish("rooms")
            server.publish("housekeeping")
            server.publish("rooms")
            runCurrent()
            server.publish("rooms")
            runCurrent()
            assertIs<IllegalStateException>(broken.error)
            assertEquals("housekeeping,rooms", f.topicsOf(0))
            assertEquals("rooms", f.topicsOf(f.requests.size - 1))
            assertEquals(server.published("rooms"), rooms.messageNumbers().distinct())
            assertNonDecreasing(rooms.messageNumbers(), "a ordem do assinante")
            assertNonDecreasing(positions(f), "o Last-Event-ID")
            assertTrue(f.openAtRequest.all { it == 0 }, "duas conexões abertas: ${f.openAtRequest}")
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    // ---- lento × rápido ----

    @Test
    fun slowAndFastSurviveUnionChurnWithoutLossOverlapOrRegression() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val server = ReplayServer(this).also { f.replay = it }
            val client = f.client()
            val slow = f.collect(client.subscribe(setOf("rooms"))) { delay(300) }
            val fast = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            var extra: RealtimeAtaqueFixture.Collected? = null
            // 150 eventos a cada 20 ms: o lento (300 ms cada) enche a fila e prende o leitor no meio da entrega.
            repeat(150) { i ->
                server.publish("rooms")
                if (i % 3 == 0) server.publish("housekeeping")
                when (i % 16) {
                    4 -> extra = f.collect(client.subscribe(setOf("housekeeping")))
                    11 -> extra?.job?.cancel()
                }
                advanceTimeBy(20)
            }
            extra?.job?.cancel()
            advanceTimeBy(60_000)
            assertTrue(f.requests.size >= 15, "a união mudou várias vezes: ${f.requests.size} conexões")
            assertTrue(f.openAtRequest.all { it == 0 }, "duas conexões abertas: ${f.openAtRequest}")
            assertNonDecreasing(positions(f), "o Last-Event-ID")
            val published = server.published("rooms")
            for ((name, subscriber) in listOf("lento" to slow, "rápido" to fast)) {
                assertEquals(RealtimeEvent.Ready, subscriber.events.first(), name)
                assertEquals(published, subscriber.messageNumbers().distinct(), "o $name perdeu evento")
                assertNonDecreasing(subscriber.messageNumbers(), "a ordem do $name")
                assertFalse(subscriber.completed, name)
            }
            assertEquals("rooms", f.topicsOf(f.requests.size - 1))
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    // ---- assinar e cancelar depressa ----

    @Test
    fun rapidUnionChangesWithoutYieldingNeverOverlapConnections() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val server = ReplayServer(this).also { f.replay = it }
            val client = f.client()
            val rooms = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            server.publish("rooms")
            server.publish("rooms")
            runCurrent()
            // Três mudanças da união seguidas, sem que nenhuma conexão tenha a vez de rodar.
            f.collect(client.subscribe(setOf("housekeeping")), undispatched = true)
            f.collect(client.subscribe(setOf("maintenance")), undispatched = true)
            f.collect(client.subscribe(setOf("billing")), undispatched = true)
            runCurrent()
            server.publish("rooms")
            runCurrent()
            assertTrue(f.openAtRequest.all { it == 0 }, "duas conexões abertas: ${f.openAtRequest}")
            assertEquals("billing,housekeeping,maintenance,rooms", f.topicsOf(f.requests.size - 1))
            assertEquals("e-2", f.lastEventIds().last())
            assertNonDecreasing(positions(f), "o Last-Event-ID")
            assertEquals(listOf(1, 2, 3), rooms.messageNumbers())
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    @Test
    fun subscribeAndCancelBeforeAnyDispatchLeavesConnectionOnFinalUnion() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val server = ReplayServer(this).also { f.replay = it }
            val client = f.client()
            val rooms = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            server.publish("rooms")
            runCurrent()
            repeat(5) {
                val passing = f.collect(client.subscribe(setOf("housekeeping")), undispatched = true)
                passing.job.cancel()
            }
            runCurrent()
            server.publish("rooms")
            runCurrent()
            assertTrue(f.openAtRequest.all { it == 0 }, "duas conexões abertas: ${f.openAtRequest}")
            assertEquals("rooms", f.topicsOf(f.requests.size - 1))
            assertNonDecreasing(positions(f), "o Last-Event-ID")
            assertEquals(listOf(1, 2), rooms.messageNumbers().distinct())
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    @Test
    fun lastCancelThenImmediateResubscribeNeverOverlapsConnections() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val old = f.liveStream()
            val current = f.liveStream()
            val client = f.client()
            val first = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            old.send(ready("e-1") + message("e-2"))
            runCurrent()
            first.job.cancel()
            val second = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            assertEquals(2, f.requests.size)
            assertEquals(listOf(0, 0), f.openAtRequest, "a conexão anterior ainda estava aberta")
            assertEquals(listOf(null, "e-2"), f.lastEventIds())
            current.send(ready("e-2") + message("e-3"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-3")), second.events)
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    @Test
    fun endSessionThenImmediateLoginNeverOverlapsConnections() =
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
            f.login("s2")
            val second = f.collect(client.subscribe(setOf("rooms")), undispatched = true)
            runCurrent()
            assertEquals(2, f.requests.size)
            assertEquals(listOf(0, 0), f.openAtRequest, "a conexão da sessão anterior ainda estava aberta")
            assertEquals(listOf<String?>(null, null), f.lastEventIds())
            assertEquals("Bearer A-s2", f.requests[1].headers[HttpHeaders.Authorization])
            current.send(ready("e-9"))
            runCurrent()
            assertTrue(first.completedNormally)
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2")), first.events)
            assertEquals(listOf<RealtimeEvent>(RealtimeEvent.Ready), second.events)
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    // ---- Ready sintético ----

    @Test
    fun syntheticReadyComesExactlyOnceAndBeforeAnyMessageUnderTraffic() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val channel = f.liveStream()
            val client = f.client()
            val first = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            f.writeInBackground {
                channel.send(ready("e-0"))
                for (i in 1..100) {
                    channel.send(message("e-$i"))
                    delay(10)
                }
            }
            val joiners = mutableListOf<RealtimeAtaqueFixture.Collected>()
            for (wait in listOf(105L, 228L, 1L, 173L)) {
                advanceTimeBy(wait)
                joiners += f.collect(client.subscribe(setOf("rooms")))
                joiners += f.collect(client.subscribe(setOf("rooms")), undispatched = true)
            }
            advanceTimeBy(2_000)
            assertEquals(1, f.requests.size)
            assertEquals((1..100).toList(), first.messageNumbers())
            for (joiner in joiners) {
                assertEquals(RealtimeEvent.Ready, joiner.events.first())
                assertEquals(1, joiner.events.count { it == RealtimeEvent.Ready }, "Ready repetido: ${joiner.events}")
                val numbers = joiner.messageNumbers()
                assertEquals((numbers.first()..100).toList(), numbers, "o assinante perdeu evento depois de entrar")
            }
        }

    @Test
    fun subscriberJoiningAfterResetGetsSyntheticReady() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val channel = f.liveStream()
            val client = f.client()
            val first = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(reset("e-1") + message("e-2"))
            runCurrent()
            val late = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(message("e-3"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Reset, changed("e-2"), changed("e-3")), first.events)
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-3")), late.events)
            assertEquals(1, f.requests.size)
        }

    @Test
    fun subscriberJoiningDuringReconnectGetsOnlyTheRealReady() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val old = f.liveStream()
            val current = f.liveStream()
            val client = f.client()
            val first = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            old.send(ready("e-1"))
            runCurrent()
            old.close()
            runCurrent()
            assertIs<RealtimeStatus.Reconnecting>(client.status.value)
            val late = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            assertEquals(emptyList(), late.events, "a conexão não está em dia: nada de Ready sintético")
            advanceTimeBy(1_001)
            assertEquals(2, f.requests.size)
            assertEquals("e-1", f.lastEventIds()[1])
            current.send(ready("e-2") + message("e-3"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-3")), late.events)
            assertEquals(listOf(RealtimeEvent.Ready, RealtimeEvent.Ready, changed("e-3")), first.events)
            assertEquals(RealtimeStatus.Live, client.status.value)
        }

    @Test
    fun subscriberWithNewTopicGetsOnlyTheRealReadyOfTheReopenedConnection() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val old = f.liveStream()
            val current = f.liveStream()
            val client = f.client()
            f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            old.send(ready("e-1"))
            runCurrent()
            val both = f.collect(client.subscribe(setOf("rooms", "housekeeping")), undispatched = true)
            runCurrent()
            assertEquals(emptyList(), both.events, "a união mudou: o Ready vem da conexão nova")
            current.send(ready("e-1") + message("e-2", topic = "housekeeping"))
            runCurrent()
            assertEquals(listOf(RealtimeEvent.Ready, changed("e-2", topic = "housekeeping")), both.events)
        }

    // ---- conjunto vazio ----

    @Test
    fun subscribeWithEmptySetFailsRightAwayAndOpensNothing() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            val channel = f.liveStream()
            val client = f.client()
            kotlin.test.assertFailsWith<IllegalArgumentException> { client.subscribe(emptySet()) }
            advanceTimeBy(10_000)
            assertEquals(0, f.requests.size)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            val subscriber = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            channel.send(ready("e-1"))
            runCurrent()
            assertEquals(listOf<RealtimeEvent>(RealtimeEvent.Ready), subscriber.events)
        }

    // ---- nenhum job vivo ----

    @Test
    fun noJobLeftAfterChurnSessionWaitAndLastCancel() =
        runTest {
            val f = RealtimeAtaqueFixture(this)
            ReplayServer(this).also { f.replay = it }
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val client = f.client(scope)
            val subscribers = mutableListOf<RealtimeAtaqueFixture.Collected>()
            subscribers += f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            subscribers += f.collect(client.subscribe(setOf("housekeeping")))
            runCurrent()
            subscribers.removeAt(0).job.cancel()
            runCurrent()
            f.session = null
            subscribers += f.collect(client.subscribe(setOf("rooms")))
            advanceTimeBy(2_000)
            assertEquals(RealtimeStatus.Idle, client.status.value)
            subscribers.forEach { it.job.cancel() }
            advanceTimeBy(1_000)
            assertEquals(emptyList(), RealtimeAtaqueFixture.jobsUnder(scope))
            assertEquals(RealtimeStatus.Idle, client.status.value)
            assertTrue(f.openAtRequest.all { it == 0 }, "duas conexões abertas: ${f.openAtRequest}")
            // Nada morreu: o cliente ainda serve.
            f.session = "s1"
            val again = f.collect(client.subscribe(setOf("rooms")))
            runCurrent()
            assertEquals(RealtimeEvent.Ready, again.events.first())
            assertEquals(RealtimeStatus.Live, client.status.value)
        }
}
