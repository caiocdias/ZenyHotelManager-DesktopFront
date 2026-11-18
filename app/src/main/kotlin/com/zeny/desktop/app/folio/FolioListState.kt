package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.sdk.model.FolioResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Uma conta numa lista: número, dono (rótulo do tipo), saldo do servidor e se passou do limite; o [id] não aparece. */
data class FolioRow(
    val id: String,
    val number: String,
    val owner: String,
    val balance: String,
    val limitExceeded: Boolean,
)

/**
 * As contas abertas, uma página por vez pelo `CursorPager` sobre `openFolioPage`: [start] lê a primeira e [loadMore]
 * a seguinte, enquanto [hasMore]. Uma leitura por vez; [close] cancela a em voo e descarta a resposta tardia.
 */
class FolioListState(
    source: FolioSource,
    scope: CoroutineScope,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)
    private val pager = CursorPager(PAGE_SIZE) { cursor, limit -> source.openFolioPage(cursor, limit) }
    private var started = false
    private var closed = false

    var rows by mutableStateOf<List<FolioRow>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var hasMore by mutableStateOf(false)
        private set

    /** Lê a primeira página; da segunda vez, nada (a aba o chama a cada entrada). */
    fun start() {
        if (started) return
        started = true
        loadMore()
    }

    fun loadMore() {
        if (closed || loading) return
        loading = true
        error = null
        ownScope.launch {
            try {
                val page = pager.next()
                currentCoroutineContext().ensureActive()
                rows = rows + page.map(::folioRowOf)
                hasMore = pager.hasMore
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                error = apiErrorOf(e).userMessage
                hasMore = pager.hasMore
            } finally {
                loading = false
            }
        }
    }

    fun close() {
        closed = true
        jobs.cancel()
    }
}

/**
 * As contas de uma reserva, para o diálogo do botão "Conta": nenhuma dá [empty]; uma já fica [chosen]; mais de uma
 * oferece a escolha por número ([choose]). [close] cancela a leitura em voo e descarta a resposta tardia.
 */
class ReservationFoliosState(
    private val source: FolioSource,
    scope: CoroutineScope,
    private val reservationId: String,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)
    private var closed = false

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var folios by mutableStateOf<List<FolioRow>>(emptyList())
        private set

    /** "Esta reserva ainda não tem conta." depois de uma leitura sem nenhuma conta. */
    var empty by mutableStateOf<String?>(null)
        private set

    /** A conta escolhida (o id, que não aparece); nula enquanto há mais de uma e nenhuma foi escolhida. */
    var chosen by mutableStateOf<String?>(null)
        private set

    fun start() {
        if (closed || loading) return
        loading = true
        error = null
        ownScope.launch {
            try {
                val found = source.reservationFolios(reservationId).map(::folioRowOf)
                currentCoroutineContext().ensureActive()
                folios = found
                empty = if (found.isEmpty()) FolioText.NO_FOLIO else null
                chosen = found.singleOrNull()?.id
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                error = apiErrorOf(e).userMessage
            } finally {
                loading = false
            }
        }
    }

    /** Escolhe uma das contas listadas; outra coisa é ignorada. */
    fun choose(folioId: String) {
        if (folios.any { it.id == folioId }) chosen = folioId
    }

    fun close() {
        closed = true
        jobs.cancel()
    }
}

private const val PAGE_SIZE = 50

private fun folioRowOf(folio: FolioResponse) =
    FolioRow(
        id = folio.id.toString(),
        number = folio.number,
        owner = FolioText.owner(folio.owner.type),
        balance = FolioText.money(folio.balance),
        limitExceeded = folio.creditLimitExceeded,
    )

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
