package com.zeny.desktop.design.table

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 *   Um `reload` cancelado antes de começar ainda reinicia a listagem, sem pedir página.
 * - Linha cuja `key` já foi carregada é ignorada; página sem nenhuma linha nova encerra a listagem.
 * - `ApiError` do `fetch` fica em `error` e as linhas ficam; tentar de novo pede o mesmo cursor.
 * - `sort` e `filter` valem sobre as linhas já carregadas e nunca pedem página.
 */
class TableState<T>(
    private val pagerFactory: () -> CursorPager<T>,
    private val key: (T) -> Any,
) {
    private val mutex = Mutex()

    /** Número do último `reload` pedido; sobe fora do `mutex`, no instante do pedido. */
    private val generation = AtomicLong()

    // Só mudam com o `mutex` travado. `pagerGeneration` é o `reload` que criou o `pager`: uma carga é atual
    // enquanto nenhum `reload` foi pedido depois dele.
    private var pager = pagerFactory()
    private var pagerGeneration = 0L
    private val keys = HashSet<Any>()

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
            // A geração vem do pager, não do contador: um reload pedido depois do `tryLock` já a torna velha.
            if (isCurrent(pagerGeneration) && hasMore) load(pagerGeneration)
        } finally {
            mutex.unlock()
        }
    }

    suspend fun reload() {
        // Antes do lock, nada de estado observável: só o pedido e o cancelamento do fetch em voo.
        val gen = generation.incrementAndGet()
        inFlight?.cancel()
        var started = false
        try {
            mutex.withLock {
                started = true
                if (!isCurrent(gen)) return
                restart(gen)
                load(gen)
            }
        } catch (e: CancellationException) {
            // Cancelado na fila do lock: a carga que este pedido descartou não volta, então a listagem
            // recomeça sem página, a menos que um reload mais novo já cuide dela (aí nem espera o lock).
            if (!started && isCurrent(gen)) {
                withContext(NonCancellable) { mutex.withLock { if (isCurrent(gen)) restartIdle(gen) } }
            }
            throw e
        }
    }

    private fun isCurrent(gen: Long) = gen == generation.get()

    private fun restart(gen: Long) {
        pager = pagerFactory()
        pagerGeneration = gen
        keys.clear()
        rows = emptyList()
        error = null
        hasMore = true
    }

    private fun restartIdle(gen: Long) {
        restart(gen)
        loading = false
    }

    /** Chamada com o `mutex` travado; `loading`, `error` e `rows` só mudam enquanto [gen] é atual. */
    private suspend fun load(gen: Long) {
        loading = true
        error = null
        try {
            val items = fetchPage(gen) ?: return
            if (isCurrent(gen)) append(items)
        } catch (e: ApiError) {
            if (isCurrent(gen)) error = e
        } finally {
            if (isCurrent(gen)) loading = false
        }
    }

    /** A próxima página, ou `null` quando um reload a descartou. */
    private suspend fun fetchPage(gen: Long): List<T>? =
        try {
            coroutineScope {
                val fetch = async(start = CoroutineStart.LAZY) { pager.next() }
                // Registra antes de conferir: ou o reload acha este fetch e o cancela, ou o pedido dele já
                // aparece aqui e o fetch nem começa.
                inFlight = fetch
                if (!isCurrent(gen)) fetch.cancel()
                try {
                    fetch.await()
                } finally {
                    inFlight = null
                }
            }
        } catch (e: CancellationException) {
            // Quem chamou foi cancelado, ou o próprio fetch lançou: propaga. Só o reload termina sem efeito.
            currentCoroutineContext().ensureActive()
            if (isCurrent(gen)) throw e
            null
        }

    private fun append(items: List<T>) {
        val fresh = items.filter { keys.add(key(it)) }
        if (fresh.isNotEmpty()) rows = rows + fresh
        hasMore = pager.hasMore && fresh.isNotEmpty()
    }
}
