package com.zeny.desktop.core.session

import java.util.concurrent.atomic.AtomicReference

/** Par de tokens da sessão; só em memória, e o `toString()` não mostra nenhum dos dois tokens. */
class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val sessionId: String,
    val userId: String,
    val passwordChangeRequired: Boolean,
) {
    override fun toString(): String = "Tokens(sessionId=$sessionId, ***)"
}

/** Guarda os tokens da sessão só em memória, seguro entre threads. */
class TokenStore {
    private val tokens = AtomicReference<Tokens?>(null)

    fun current(): Tokens? = tokens.get()

    fun set(tokens: Tokens) {
        this.tokens.set(tokens)
    }

    fun clear() {
        tokens.set(null)
    }

    /** Limpa só se o access atual ainda é o recusado; `true` quando limpou. */
    internal fun clearIfAccess(accessToken: String): Boolean {
        val current = tokens.get() ?: return false
        return current.accessToken == accessToken && tokens.compareAndSet(current, null)
    }
}
