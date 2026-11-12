package com.zeny.desktop.core.http

private const val DEFAULT_PAGE_SIZE = 50

/** Uma página de uma listagem por cursor; `nextCursor` nulo ou em branco é a última. */
data class Page<I>(
    val items: List<I>,
    val nextCursor: String?,
)

/**
 * Percorre uma listagem por cursor, uma página por `next()`. Para quando o `nextCursor` é nulo, em branco ou
 * igual ao cursor enviado (servidor que não avança). Uma chamada de `next()` por vez.
 */
class CursorPager<I>(
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val fetch: suspend (cursor: String?, limit: Int) -> Page<I>,
) {
    private var cursor: String? = null

    var hasMore: Boolean = true
        private set

    /** A próxima página; vazia, sem chamar o `fetch`, quando não há mais. */
    suspend fun next(): List<I> {
        if (!hasMore) return emptyList()
        val page = fetch(cursor, pageSize)
        val next = page.nextCursor
        hasMore = !next.isNullOrBlank() && next != cursor
        cursor = next
        return page.items
    }
}
