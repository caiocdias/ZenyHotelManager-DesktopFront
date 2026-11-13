package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.error.ApiError
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** O que fazer depois que uma conexão do stream termina. */
sealed interface StreamOutcome {
    /** Reconectar, esperando o próximo atraso da [ReconnectPolicy]. */
    data object Retry : StreamOutcome

    /** Falha definitiva: não repetir; o erro vai ao usuário. */
    data class Fail(
        val error: ApiError,
    ) : StreamOutcome

    /** Encerrar sem erro novo (sessão acabou). */
    data object Stop : StreamOutcome
}

/** Classifica falhas do stream em repetir, falhar ou parar. */
object StreamFailures {
    private const val UNAUTHORIZED = 401
    private const val SERVER_ERROR_FROM = 500
    private const val REQUEST_TIMEOUT = 408
    private const val TOO_MANY_REQUESTS = 429
    private val retryableStatuses = setOf(REQUEST_TIMEOUT, TOO_MANY_REQUESTS)

    fun classify(
        status: Int,
        error: ApiError?,
    ): StreamOutcome =
        when {
            error is ApiError.SessionEnded || status == UNAUTHORIZED -> StreamOutcome.Stop
            status >= SERVER_ERROR_FROM || status in retryableStatuses -> StreamOutcome.Retry
            else -> StreamOutcome.Fail(error ?: ApiError.Problem(status, null, null, emptyList()))
        }

    /**
     * Fim do corpo e *watchdog* chegam aqui como [IOException]; [ApiError.Network] (queda de rede já
     * convertida pelo executor) também repete. A [CancellationException] e os [Error] fatais propagam.
     */
    fun classify(cause: Throwable): StreamOutcome =
        when (cause) {
            is CancellationException -> throw cause
            is ApiError.SessionEnded -> StreamOutcome.Stop
            is ApiError.Network -> StreamOutcome.Retry
            is ApiError -> StreamOutcome.Fail(cause)
            is IOException -> StreamOutcome.Retry
            !is Exception -> throw cause
            else -> StreamOutcome.Fail(ApiError.Network(cause))
        }
}

/**
 * Espera crescente entre reconexões: 1 s, 2 s, 4 s, 8 s, 16 s e teto de 30 s.
 * Não é segura para uso concorrente: o uso previsto é uma corrotina por conexão.
 */
class ReconnectPolicy {
    private var attempt = 0

    fun nextDelayMillis(): Long {
        val delay = minOf(BASE_DELAY_MILLIS shl attempt, MAX_DELAY_MILLIS)
        if (attempt < STEPS_TO_CAP) attempt++
        return delay
    }

    fun reset() {
        attempt = 0
    }

    private companion object {
        const val BASE_DELAY_MILLIS = 1_000L
        const val MAX_DELAY_MILLIS = 30_000L
        const val STEPS_TO_CAP = 5
    }
}
