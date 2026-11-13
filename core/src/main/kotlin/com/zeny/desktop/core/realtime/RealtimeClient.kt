package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.client.StreamApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * As assinaturas do stream de uma sessão, servidas por **uma** conexão com a união dos tópicos.
 *
 * Cada [subscribe] é um `Flow` frio: coletar entra na assinatura, cancelar sai. A conexão abre sob a sessão corrente
 * ([currentSessionId], lido ao abrir) e reabre com a posição guardada quando a união muda; sem sessão, a assinatura
 * espera uma. A entrega a cada assinante suspende em vez de descartar: o servidor derruba o cliente lento e a
 * reconexão repõe pelo `Last-Event-ID`. A conexão que para (`401` residual, cliente fechado) completa os `Flow`s
 * daquela sessão; a que falha os completa com o [ApiError]. Cancelar o [scope] encerra tudo.
 */
class RealtimeClient internal constructor(
    private val stream: StreamApi,
    private val scope: CoroutineScope,
    private val currentSessionId: () -> String?,
) {
    private val mutableStatus = MutableStateFlow<RealtimeStatus>(RealtimeStatus.Idle)
    val status: StateFlow<RealtimeStatus> = mutableStatus.asStateFlow()

    /** Guarda o estado abaixo; nunca fica preso durante uma suspensão. */
    private val lock = Any()
    private val subscribers = mutableListOf<Subscriber>()
    private var connection: Connection? = null
    private var position: Position? = null
    private var sessionWait: Job? = null
    private var closed = false

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    /** Os eventos dos [topics]: `Ready`/`Reset` da conexão e as `Message` desses tópicos. */
    fun subscribe(topics: Set<String>): Flow<RealtimeEvent> {
        require(topics.isNotEmpty()) { "assinatura sem tópicos" }
        val wanted = topics.toSet()
        return flow {
            val subscriber = Subscriber(wanted)
            register(subscriber)
            try {
                for (event in subscriber.events) emit(event)
            } finally {
                unregister(subscriber)
            }
        }
    }

    /**
     * Encerra as assinaturas e a conexão abertas sob [sessionId] (os `Flow`s completam normalmente) e descarta a
     * posição dela. Chamada atrasada, sem nada de [sessionId], não faz nada.
     */
    internal fun endSession(sessionId: String) {
        synchronized(lock) {
            val ended = subscribers.filter { it.sessionId == sessionId }
            val ownsPosition = position?.sessionId == sessionId
            val ownsConnection = connection?.sessionId == sessionId
            if (ended.isEmpty() && !ownsPosition && !ownsConnection) return
            if (ownsPosition) position = null
            complete(ended, failure = null)
            if (ownsConnection) stopConnection()
            reconcile()
        }
    }

    private fun register(subscriber: Subscriber) {
        synchronized(lock) {
            if (closed) {
                subscriber.events.close()
                return
            }
            subscriber.sessionId = currentSessionId()
            subscribers += subscriber
            reconcile()
            // A conexão já passou do `ready`: quem chega agora carrega o estado a partir daqui.
            val current = connection
            if (current != null && current.ready && current.sessionId == subscriber.sessionId) {
                subscriber.events.trySend(RealtimeEvent.Ready)
            }
        }
    }

    private fun unregister(subscriber: Subscriber) {
        synchronized(lock) {
            // Acorda o leitor que esperava este assinante.
            subscriber.events.cancel()
            if (subscribers.remove(subscriber)) reconcile()
        }
    }

    /** Leva a conexão ao que os assinantes da sessão corrente pedem. Chamada com o [lock]. */
    private fun reconcile() {
        val sessionId = currentSessionId()
        if (sessionId == null) {
            stopConnection()
            if (subscribers.any { it.sessionId == null }) awaitSession() else stopWaiting()
            return
        }
        subscribers.filter { it.sessionId == null }.forEach { it.sessionId = sessionId }
        val topics = subscribers.filter { it.sessionId == sessionId }.flatMapTo(sortedSetOf()) { it.topics }
        val current = connection
        when {
            topics.isEmpty() -> stopConnection()
            current == null || current.sessionId != sessionId || current.topics != topics -> open(sessionId, topics)
        }
    }

    /** A sessão corrente não avisa quando muda: sem sessão, consulta de tempos em tempos. Chamada com o [lock]. */
    private fun awaitSession() {
        if (sessionWait?.isActive == true) return
        sessionWait =
            scope.launch {
                while (currentSessionId() == null) delay(SESSION_POLL_MILLIS)
                synchronized(lock) {
                    sessionWait = null
                    reconcile()
                }
            }
    }

    private fun stopWaiting() {
        sessionWait?.cancel()
        sessionWait = null
    }

    /** Chamada com o [lock]. */
    private fun stopConnection() {
        val current = connection ?: return
        connection = null
        current.job.cancel()
        mutableStatus.value = RealtimeStatus.Idle
    }

    /** Reabre sob [sessionId]; a nova conexão só começa quando a anterior terminou. Chamada com o [lock]. */
    private fun open(
        sessionId: String,
        topics: Set<String>,
    ) {
        val previous = connection?.job
        previous?.cancel()
        if (position?.sessionId != sessionId) position = null
        val next = Connection(sessionId, topics)
        connection = next
        next.start(previous)
    }

    /** Chamada com o [lock]. */
    private fun complete(
        ended: List<Subscriber>,
        failure: ApiError?,
    ) {
        subscribers -= ended.toSet()
        ended.forEach { it.events.close(failure) }
    }

    /** O [scope] terminou (`ZenyCore.close()`): completa tudo e recusa assinaturas novas. */
    private fun close() {
        synchronized(lock) {
            closed = true
            stopWaiting()
            stopConnection()
            complete(subscribers.toList(), failure = null)
            mutableStatus.value = RealtimeStatus.Idle
        }
    }

    private class Subscriber(
        val topics: Set<String>,
    ) {
        /** Fila curta: cheia, a entrega suspende; nada se descarta. */
        val events = Channel<RealtimeEvent>(Channel.BUFFERED)

        /** A sessão sob a qual a assinatura entrou; `null` enquanto espera uma. */
        var sessionId: String? = null

        fun wants(event: RealtimeEvent): Boolean = event !is RealtimeEvent.Message || event.topic in topics

        suspend fun offer(event: RealtimeEvent) {
            try {
                events.send(event)
            } catch (ignored: ClosedSendChannelException) {
                // A assinatura já terminou.
            } catch (ignored: CancellationException) {
                // O assinante saiu e cancelou a fila; só o cancelamento do leitor propaga.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /** Uma conexão aberta sob [sessionId]; o que ela lê só vale enquanto for a [connection] corrente. */
    private inner class Connection(
        val sessionId: String,
        val topics: Set<String>,
    ) {
        lateinit var job: Job

        /** Já passou do `ready`/`reset` (guardado pelo [lock]). */
        var ready = false
        private var failure: ApiError? = null

        /** Chamada com o [lock]. */
        fun start(previous: Job?) {
            job =
                scope.launch {
                    previous?.join()
                    val startAfter = synchronized(lock) { position?.takeIf { it.sessionId == sessionId }?.value }
                    StreamConnection(stream, topics, startAfter).run().collect { item ->
                        when (item) {
                            is ConnectionItem.Status -> onStatus(item.status)
                            is ConnectionItem.Event -> deliver(item)
                        }
                    }
                    finish()
                }
        }

        private fun onStatus(status: RealtimeStatus) {
            synchronized(lock) {
                if (connection !== this) return
                if (status != RealtimeStatus.Live) ready = false
                if (status is RealtimeStatus.Failed) failure = status.error
                mutableStatus.value = status
            }
        }

        /** Entrega a todos os interessados e só então guarda a posição: uma entrega interrompida se repete. */
        private suspend fun deliver(item: ConnectionItem.Event) {
            val event = item.event
            val targets =
                synchronized(lock) {
                    if (connection !== this) return
                    if (event == RealtimeEvent.Ready || event == RealtimeEvent.Reset) ready = true
                    subscribers.filter { it.sessionId == sessionId && it.wants(event) }
                }
            targets.forEach { it.offer(event) }
            val newPosition = item.position ?: return
            synchronized(lock) {
                if (connection === this) position = Position(sessionId, newPosition)
            }
        }

        /** A conexão terminou sozinha: parou (`Stop`) ou falhou depois do último `Failed`. */
        private fun finish() {
            synchronized(lock) {
                if (connection !== this) return
                connection = null
                val error = failure
                if (error == null) mutableStatus.value = RealtimeStatus.Idle
                complete(subscribers.filter { it.sessionId == sessionId }, error)
                reconcile()
            }
        }
    }

    private class Position(
        val sessionId: String,
        val value: String,
    )

    private companion object {
        const val SESSION_POLL_MILLIS = 500L
    }
}
