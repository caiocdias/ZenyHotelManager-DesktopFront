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
    private var inFlightOrigin: Tokens? = null

    /** O access atual, sem refresh. */
    fun accessToken(): String? = store.current()?.accessToken

    /** O par atual, que o `AuthPlugin` guarda para saber de que sessão é a requisição. */
    internal fun currentTokens(): Tokens? = store.current()

    /**
     * Reação ao `401 UNAUTHENTICATED` dado ao par `rejected` (o que a requisição usou):
     * - o store guarda o mesmo par: entra no voo único;
     * - guarda outro par da mesma sessão (já girado): devolve o atual, sem refresh;
     * - está vazio ou é de outra sessão (logout, novo login): `null`, sem refresh e sem encerrar nada.
     */
    suspend fun refreshAfterRejection(rejected: Tokens): Tokens? =
        flight(isRejected = { it === rejected }, sameSession = { it.sessionId == rejected.sessionId }).await()

    /**
     * Forma do brief, só pelo access token: não distingue sessões (um token de outra sessão conta como antigo
     * da atual). O `AuthPlugin` usa a forma com `Tokens`.
     */
    suspend fun refreshAfterRejection(rejectedAccessToken: String): String? =
        flight(isRejected = { it.accessToken == rejectedAccessToken }, sameSession = { true }).await()?.accessToken

    /** Espera o refresh em voo, se houver, e faz um novo pelo voo único, com o refresh token já girado. */
    suspend fun forceRefresh(): Tokens? {
        mutex.withLock { inFlight }?.join()
        return flight(isRejected = { true }, sameSession = { true }).await()
    }

    /**
     * Encerra a sessão pelo `401` dado ao par `rejected`, se o store ainda guarda um par da mesma sessão
     * (avisa uma vez só). Store vazio ou de outra sessão: nada a encerrar.
     */
    internal fun endSession(
        rejected: Tokens,
        code: String?,
    ) {
        if (store.clearIfSession(rejected.sessionId)) onSessionEnded(code)
    }

    /**
     * Sob o `Mutex`, decide entre o voo em curso, o par atual, `null` ou um voo novo a partir do par atual.
     * O voo é olhado antes do store: o voo grava o par novo antes de completar, então voo inativo garante
     * store já atualizado (na ordem inversa, um par lido antes da gravação abriria um segundo refresh).
     */
    private suspend fun flight(
        isRejected: (Tokens) -> Boolean,
        sameSession: (Tokens) -> Boolean,
    ): Deferred<Tokens?> =
        mutex.withLock {
            val active = inFlight?.takeIf { it.isActive }
            val current = store.current()
            if (current == null || !sameSession(current)) return@withLock completed(null)
            if (active != null && inFlightOrigin === current) return@withLock active
            if (!isRejected(current)) return@withLock completed(current)
            scope.async { refresh(current) }.also {
                inFlight = it
                inFlightOrigin = current
            }
        }

    /**
     * Grava o par novo só se o `TokenStore` ainda guarda o par usado no refresh: se a sessão foi limpa ou
     * trocada no meio (logout, novo login), o resultado é descartado e quem espera recebe `null`.
     */
    private suspend fun refresh(current: Tokens): Tokens? =
        try {
            val next = refresher.refresh(current)
            next.takeIf { store.replaceIf(current, it) }
        } catch (e: ApiError.Network) {
            throw e
        } catch (e: ApiError) {
            if (store.replaceIf(current, null)) onSessionEnded(codeOf(e))
            null
        }

    /** `CompletableDeferred(null)` cairia no construtor com `parent: Job?` e nunca completaria. */
    private fun completed(tokens: Tokens?): Deferred<Tokens?> =
        CompletableDeferred<Tokens?>().apply { complete(tokens) }

    private fun codeOf(error: ApiError): String? =
        when (error) {
            is ApiError.Problem -> error.code
            is ApiError.SessionEnded -> error.code
            else -> null
        }
}
