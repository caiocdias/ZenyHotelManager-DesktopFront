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

    /** Troca o par só se o atual ainda é `expected` (a mesma instância); `next == null` limpa. `true` quando trocou. */
    internal fun replaceIf(
        expected: Tokens,
        next: Tokens?,
    ): Boolean = tokens.compareAndSet(expected, next)

    /** Limpa só se o access atual ainda é o recusado; `true` quando limpou. */
    internal fun clearIfAccess(accessToken: String): Boolean {
        val current = tokens.get() ?: return false
        return current.accessToken == accessToken && tokens.compareAndSet(current, null)
    }
}
