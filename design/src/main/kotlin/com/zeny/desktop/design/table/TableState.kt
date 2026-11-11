package com.zeny.desktop.design.table

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Uma coluna da tabela; sem `comparator`, a coluna não ordena. */
data class Column<T>(
    val id: String,
    val header: String,
    val weight: Float = 1f,
    val comparator: Comparator<T>? = null,
    val cell: (T) -> String,
)

/**
 * Linhas de uma listagem por cursor, carregadas uma página por vez.
 *
 * - Uma carga por vez: `loadMore` com carga em andamento retorna sem pedir outra página.
 * - `reload` descarta a carga em andamento (a página dela nunca entra), troca o pager pela `pagerFactory`
 *   e carrega a primeira página. Só o `fetch` é cancelado, nunca a corrotina de quem chamou `loadMore`.
 * - Linha cuja `key` já foi carregada é ignorada; página sem nenhuma linha nova encerra a listagem.
 * - `ApiError` do `fetch` fica em `error` e as linhas ficam; tentar de novo pede o mesmo cursor.
 * - `sort` e `filter` valem sobre as linhas já carregadas e nunca pedem página.
 */
class TableState<T>(
    private val pagerFactory: () -> CursorPager<T>,
    private val key: (T) -> Any,
) {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val keys = HashSet<Any>()
    private var pager = pagerFactory()

    @Volatile
    private var inFlight: Job? = null

    var rows: List<T> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var error: ApiError? by mutableStateOf(null)
        private set
    var hasMore: Boolean by mutableStateOf(true)
        private set

    /** Coluna e se é ascendente; `null` mantém a ordem do servidor. */
    var sort: Pair<String, Boolean>? by mutableStateOf(null)

    /** Texto procurado, sem diferenciar maiúsculas, nas células de qualquer coluna. */
    var filter: String by mutableStateOf("")

    fun visibleRows(columns: List<Column<T>>): List<T> {
        val needle = filter.trim()
        val filtered =
            if (needle.isEmpty()) {
                rows
            } else {
                rows.filter { row -> columns.any { it.cell(row).contains(needle, ignoreCase = true) } }
            }
        val comparator = sortComparator(columns) ?: return filtered
        return filtered.sortedWith(comparator)
    }

    /** O `comparator` da coluna de `sort` no sentido pedido; `null` se não há ordenação ou a coluna não ordena. */
    private fun sortComparator(columns: List<Column<T>>): Comparator<T>? {
        val (columnId, ascending) = sort ?: return null
        val comparator = columns.firstOrNull { it.id == columnId }?.comparator
        return if (ascending) comparator else comparator?.reversed()
    }

    suspend fun loadMore() {
        if (!mutex.tryLock()) return
        try {
            if (hasMore) load(generation.get())
        } finally {
            mutex.unlock()
        }
    }

    suspend fun reload() {
        val gen = generation.incrementAndGet()
        inFlight?.cancel()
        mutex.withLock {
            if (gen != generation.get()) return
            pager = pagerFactory()
            keys.clear()
            rows = emptyList()
            error = null
            hasMore = true
            load(gen)
        }
    }

    /** Chamada com o `mutex` travado. */
    private suspend fun load(gen: Long) {
        loading = true
        error = null
        try {
            val items =
                try {
                    fetchPage()
                } catch (e: CancellationException) {
                    // Cancelada pelo `reload`: quem chamou segue ativo e a carga termina sem efeito.
                    currentCoroutineContext().ensureActive()
                    if (gen == generation.get()) throw e
                    return
                }
            if (gen != generation.get()) return
            append(items)
        } catch (e: ApiError) {
            if (gen == generation.get()) error = e
        } finally {
            if (gen == generation.get()) loading = false
        }
    }

    private suspend fun fetchPage(): List<T> =
        coroutineScope {
            val fetch = async { pager.next() }
            inFlight = fetch
            try {
                fetch.await()
            } finally {
                inFlight = null
            }
        }

    private fun append(items: List<T>) {
        val fresh = items.filter { keys.add(key(it)) }
        if (fresh.isNotEmpty()) rows = rows + fresh
        hasMore = pager.hasMore && fresh.isNotEmpty()
    }
}
