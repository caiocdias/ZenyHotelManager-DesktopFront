package com.zeny.desktop.core.session

import com.zeny.desktop.core.error.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Troca o par atual por um novo; lança `ApiError` (por exemplo `Problem` `401 REFRESH_TOKEN_REUSED`, ou `Network`). */
fun interface TokenRefresher {
    suspend fun refresh(current: Tokens): Tokens
}

/**
 * Dono do refresh: todo refresh do `core` passa pelo voo único daqui. O refresh roda no `scope` do `core`
 * (com `SupervisorJob`), então quem espera e é cancelado só deixa de esperar; o par novo é gravado mesmo assim.
 * `ApiError.Network` chega a todos que esperam e mantém a sessão; qualquer outro `ApiError` a encerra.
 */
class Authenticator(
    private val store: TokenStore,
    private val refresher: TokenRefresher,
    private val scope: CoroutineScope,
    private val onSessionEnded: (code: String?) -> Unit,
) {
    private val mutex = Mutex()
    private var inFlight: Deferred<Tokens?>? = null

    /** O access atual, sem refresh. */
    fun accessToken(): String? = store.current()?.accessToken

    /** Se o token atual já é outro, devolve o atual sem refresh; senão entra no voo único. `null`: sem sessão. */
    suspend fun refreshAfterRejection(rejectedAccessToken: String): String? =
        flight(rejectedAccessToken).await()?.accessToken

    /** Espera o refresh em voo, se houver, e faz um novo pelo voo único, com o refresh token já girado. */
    suspend fun forceRefresh(): Tokens? {
        mutex.withLock { inFlight }?.join()
        return flight(rejectedAccessToken = null).await()
    }

    /** Encerra a sessão pelo `401` dado ao `rejectedAccessToken`, se ele ainda é o atual (avisa uma vez só). */
    internal fun endSession(
        rejectedAccessToken: String,
        code: String?,
    ) {
        if (store.clearIfAccess(rejectedAccessToken)) onSessionEnded(code)
    }

    private suspend fun flight(rejectedAccessToken: String?): Deferred<Tokens?> =
        mutex.withLock {
            inFlight?.takeIf { it.isActive }?.let { return@withLock it }
            val current = store.current()
            if (current == null || (rejectedAccessToken != null && current.accessToken != rejectedAccessToken)) {
                return@withLock CompletableDeferred(current)
            }
            scope.async { refresh(current) }.also { inFlight = it }
        }

    private suspend fun refresh(current: Tokens): Tokens? =
        try {
            refresher.refresh(current).also(store::set)
        } catch (e: ApiError.Network) {
            throw e
        } catch (e: ApiError) {
            store.clear()
            onSessionEnded(codeOf(e))
            null
        }

    private fun codeOf(error: ApiError): String? =
        when (error) {
            is ApiError.Problem -> error.code
            is ApiError.SessionEnded -> error.code
            else -> null
        }
}
