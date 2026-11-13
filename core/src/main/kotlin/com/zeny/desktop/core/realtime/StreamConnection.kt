package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.ProblemMapper
import com.zeny.desktop.core.http.CoreJson
import com.zeny.desktop.sdk.client.StreamApi
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.atomic.AtomicBoolean

/** O que o stream diz ao cliente. O payload é mínimo: diante de [Message], o cliente aplica a mudança ou rebusca. */
sealed interface RealtimeEvent {
    /** A conexão está em dia: o cliente pode carregar o estado pela API. */
    data object Ready : RealtimeEvent

    /** A posição se perdeu (outro boot, fora do buffer) ou chegou mensagem ilegível: recarregue o estado. */
    data object Reset : RealtimeEvent

    data class Message(
        val topic: String,
        val type: String,
        val id: String,
        val version: Long,
    ) : RealtimeEvent
}

sealed interface RealtimeStatus {
    data object Idle : RealtimeStatus

    data object Connecting : RealtimeStatus

    data object Live : RealtimeStatus

    data class Reconnecting(
        val attempt: Int,
        val delayMillis: Long,
    ) : RealtimeStatus

    data class Failed(
        val error: ApiError,
    ) : RealtimeStatus
}

sealed interface ConnectionItem {
    /** [position] é o último `id` não vazio visto, para o `Last-Event-ID` de uma conexão futura. */
    data class Event(
        val event: RealtimeEvent,
        val position: String?,
    ) : ConnectionItem

    data class Status(
        val status: RealtimeStatus,
    ) : ConnectionItem
}

/**
 * Uma assinatura do stream: abre `GET /api/v1/stream` com os [topics] e reabre sozinha pela [ReconnectPolicy] a
 * partir do último `id` visto (de partida, [startAfter]). Termina com [RealtimeStatus.Failed] (falha definitiva),
 * sem item novo quando a sessão acabou ou o cliente HTTP fechou, ou ao ser cancelada. Nada do payload vai a log
 * ou mensagem de erro.
 */
internal class StreamConnection(
    private val stream: StreamApi,
    private val topics: Set<String>,
    private val startAfter: String?,
) {
    fun run(): Flow<ConnectionItem> =
        channelFlow {
            val state = RunState(startAfter?.takeIf(::isPosition))
            while (true) {
                if (clientClosed()) return@channelFlow
                send(ConnectionItem.Status(RealtimeStatus.Connecting))
                when (val outcome = connectOnce(this, state)) {
                    StreamOutcome.Retry -> {
                        val delayMillis = state.policy.nextDelayMillis()
                        state.attempt++
                        send(ConnectionItem.Status(RealtimeStatus.Reconnecting(state.attempt, delayMillis)))
                        delay(delayMillis)
                    }

                    StreamOutcome.Stop -> {
                        return@channelFlow
                    }

                    is StreamOutcome.Fail -> {
                        send(ConnectionItem.Status(RealtimeStatus.Failed(outcome.error)))
                        return@channelFlow
                    }
                }
            }
        }.buffer(Channel.RENDEZVOUS)

    /** Estado de uma execução de [run]: a posição e a espera sobrevivem às reconexões. */
    private class RunState(
        var position: String?,
    ) {
        val policy = ReconnectPolicy()
        var attempt = 0

        fun live() {
            policy.reset()
            attempt = 0
        }
    }

    /** O cliente HTTP fechado (`ZenyCore.close()`) encerra a assinatura sem erro novo. */
    private fun clientClosed(): Boolean = !stream.client.httpClient.isActive

    /**
     * Uma tentativa, com um guarda: se em [WATCHDOG_MILLIS] a resposta não se confirmou como stream (cabeçalhos
     * que não chegam, corpo de erro que não termina), a tentativa é interrompida e reconecta.
     */
    private suspend fun connectOnce(
        out: SendChannel<ConnectionItem>,
        state: RunState,
    ): StreamOutcome {
        val outcome =
            coroutineScope {
                val streaming = AtomicBoolean(false)
                val attempt = async { attemptOnce(out, state, streaming) }
                val guard =
                    launch {
                        delay(WATCHDOG_MILLIS)
                        if (!streaming.get()) attempt.cancel(CancellationException("stream sem resposta"))
                    }
                try {
                    attempt.await()
                } catch (e: CancellationException) {
                    // Quem coleta foi cancelado: propaga. Senão, foi o guarda.
                    ensureActive()
                    StreamFailures.classify(StreamInterruptedException("stream sem resposta", e))
                } finally {
                    guard.cancel()
                }
            }
        return if (clientClosed()) StreamOutcome.Stop else outcome
    }

    private suspend fun attemptOnce(
        out: SendChannel<ConnectionItem>,
        state: RunState,
        streaming: AtomicBoolean,
    ): StreamOutcome =
        try {
            stream
                .prepareOpenStream(topics.joinToString(","), state.position, EVENT_STREAM) {
                    timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                }.execute { response -> consume(out, response, state, streaming) }
        } catch (e: UnresolvedAddressException) {
            StreamFailures.classify(ApiError.Network(e))
        } catch (e: CancellationException) {
            // Só o cancelamento de quem coleta propaga; o corpo fechado pelo engine com ela é queda do stream.
            currentCoroutineContext().ensureActive()
            StreamFailures.classify(StreamInterruptedException("stream interrompido", e))
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            StreamFailures.classify(e)
        }

    private suspend fun consume(
        out: SendChannel<ConnectionItem>,
        response: HttpResponse,
        state: RunState,
        streaming: AtomicBoolean,
    ): StreamOutcome {
        val status = response.status.value
        if (status !in HTTP_SUCCESS) return StreamFailures.classify(status, errorOf(response))
        // Um `Content-Type` que nem se interpreta também é outro tipo.
        val contentType = runCatching { response.contentType() }.getOrNull()
        if (contentType?.match(EVENT_STREAM) != true) {
            return StreamOutcome.Fail(ApiError.IncompatibleResponse(IOException("Content-Type inesperado no stream")))
        }
        streaming.set(true)
        val reader = SseLineReader(response.bodyAsChannel())
        val parser = SseParser()
        var eventChars = 0
        while (true) {
            val line = reader.nextLineWithin(WATCHDOG_MILLIS)
            eventChars =
                when {
                    line.isEmpty() -> 0
                    line.startsWith(':') -> eventChars
                    else -> eventChars + line.length
                }
            if (eventChars > MAX_EVENT_CHARS) throw StreamInterruptedException("evento grande demais")
            parser.feed(line)?.let { deliver(out, it, state) }
        }
    }

    /** O problema do corpo de erro, lido só até [MAX_ERROR_BODY_BYTES]; corpo cortado vira erro inesperado. */
    private suspend fun errorOf(response: HttpResponse): ApiError {
        val text =
            try {
                response.bodyAsChannel().readUpTo(MAX_ERROR_BODY_BYTES)
            } catch (e: CancellationException) {
                throw e
            } catch (
                // Como no `ProblemMapper`: corpo ilegível (já lido pelo `AuthPlugin` no 401, rede) fica vazio.
                @Suppress("TooGenericExceptionCaught") ignored: Exception,
            ) {
                ""
            }
        return ProblemMapper.fromBody(response.status.value, text)
    }

    private suspend fun ByteReadChannel.readUpTo(limit: Int): String {
        val buffer = ByteArray(limit)
        var size = 0
        while (size < limit) {
            val read = readAvailable(buffer, size, limit - size)
            if (read < 0) break
            size += read
        }
        return String(buffer, 0, size, Charsets.UTF_8)
    }

    /**
     * Divergência conhecida da especificação SSE: um `id:` vazio (ou que não iria ao cabeçalho) não zera a posição,
     * que fica no último `id` válido. O servidor manda `id` em todo evento; um vazio seria defeito, e zerar a posição
     * por ele faria a próxima reconexão recomeçar do zero (`ready` e recarga completa) em vez de retomar do ponto.
     */
    private suspend fun deliver(
        out: SendChannel<ConnectionItem>,
        event: SseEvent,
        state: RunState,
    ) {
        event.id?.takeIf(::isPosition)?.let { state.position = it }
        val realtime =
            when (event.event) {
                READY -> RealtimeEvent.Ready
                RESET -> RealtimeEvent.Reset
                in topics -> messageOf(event.event, event.data) ?: RealtimeEvent.Reset
                else -> return
            }
        if (event.event == READY || event.event == RESET) {
            state.live()
            out.send(ConnectionItem.Status(RealtimeStatus.Live))
        }
        out.send(ConnectionItem.Event(realtime, state.position))
    }

    private fun messageOf(
        topic: String,
        data: String,
    ): RealtimeEvent.Message? {
        val obj = runCatching { CoreJson.parseToJsonElement(data) }.getOrNull() as? JsonObject ?: return null
        val type = obj.string("type")
        val id = obj.string("id")
        val version = (obj["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val complete = type != null && id != null && version != null
        return if (complete) RealtimeEvent.Message(topic, type, id, version) else null
    }

    /** Só ASCII visível vai ao `Last-Event-ID`: outro valor o cliente HTTP recusa no cabeçalho. */
    private fun isPosition(id: String): Boolean = id.isNotEmpty() && id.all { it in '!'..'~' }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull

    private companion object {
        val EVENT_STREAM = ContentType.parse("text/event-stream")
        val HTTP_SUCCESS = 200..299
        const val READY = "ready"
        const val RESET = "reset"

        /** Três *heartbeats* (15 s) sem nenhuma linha: a conexão caiu. */
        const val WATCHDOG_MILLIS = 45_000L
        const val MAX_EVENT_CHARS = 1024 * 1024
        const val MAX_ERROR_BODY_BYTES = 64 * 1024
    }
}

/** Fim do corpo, silêncio além do *watchdog* ou linha/evento grande demais: a conexão quebrou e se reabre. */
internal class StreamInterruptedException(
    reason: String,
    cause: Throwable? = null,
) : IOException(reason, cause)

/**
 * Corta o corpo em linhas no `\r\n`, no `\n` e no `\r` sozinho, tira o BOM do início e recusa linha maior que
 * [maxLineBytes]. Uma linha incompleta no fim do corpo é descartada, como na especificação.
 */
internal class SseLineReader(
    private val channel: ByteReadChannel,
    private val maxLineBytes: Int = MAX_LINE_BYTES,
) {
    private val chunk = ByteArray(CHUNK_BYTES)
    private var start = 0
    private var end = 0
    private var skipLineFeed = false
    private var firstLine = true
    private val line = ByteArrayOutputStream()

    /** A próxima linha, que precisa chegar em [timeoutMillis]; fim do corpo e silêncio viram [IOException]. */
    suspend fun nextLineWithin(timeoutMillis: Long): String {
        val next =
            try {
                withTimeout(timeoutMillis) { readLine() }
            } catch (e: TimeoutCancellationException) {
                // O *watchdog* não é cancelamento de quem coleta: vira queda, que reconecta.
                throw StreamInterruptedException("stream em silêncio", e)
            }
        return next ?: throw StreamInterruptedException("fim do stream")
    }

    /** A próxima linha sem o terminador, ou `null` no fim do corpo. */
    suspend fun readLine(): String? {
        while (true) {
            if (start == end && !fill()) return null
            if (skipLineFeed) {
                skipLineFeed = false
                if (chunk[start] == LF) start++
            }
            var stop = start
            while (stop < end && chunk[stop] != LF && chunk[stop] != CR) stop++
            append(stop)
            if (stop < end) {
                skipLineFeed = chunk[stop] == CR
                start = stop + 1
                return takeLine()
            }
            start = end
        }
    }

    private suspend fun fill(): Boolean {
        var read = 0
        while (read == 0) read = channel.readAvailable(chunk, 0, chunk.size)
        start = 0
        end = maxOf(read, 0)
        return read > 0
    }

    private fun append(stop: Int) {
        if (line.size() + (stop - start) > maxLineBytes) throw StreamInterruptedException("linha grande demais")
        line.write(chunk, start, stop - start)
    }

    private fun takeLine(): String {
        val text = line.toString(Charsets.UTF_8)
        line.reset()
        if (!firstLine) return text
        firstLine = false
        return text.removePrefix(BOM)
    }

    private companion object {
        const val LF = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
        const val BOM = "\uFEFF"
        const val CHUNK_BYTES = 8 * 1024
        const val MAX_LINE_BYTES = 1024 * 1024
    }
}
