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
 * a seguinte, enquanto [hasMore]. [refresh] recomeça da primeira página (depois de uma escrita na conta aberta, para o
 * saldo, o "Limite excedido" e as encerradas virem do servidor). Uma leitura por vez: a de [refresh] descarta a que
 * estava em voo; [close] cancela a em voo e descarta a resposta tardia.
 */
class FolioListState(
    private val source: FolioSource,
    scope: CoroutineScope,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)
    private var pager = newPager()
    private var started = false
    private var closed = false
    private var generation = 0
    private var loadJob: Job? = null

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
        load(reset = false)
    }

    /** Relê a primeira página com um pager novo; as linhas são trocadas quando ela chega. */
    fun refresh() {
        if (closed) return
        started = true
        loadJob?.cancel()
        pager = newPager()
        load(reset = true)
    }

    private fun load(reset: Boolean) {
        val mine = ++generation
        val current = pager
        loading = true
        error = null
        loadJob =
            ownScope.launch {
                try {
                    val page = current.next().map(::folioRowOf)
                    currentCoroutineContext().ensureActive()
                    if (mine != generation) return@launch
                    rows = if (reset) page else rows + page
                    hasMore = current.hasMore
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    if (mine == generation) {
                        error = apiErrorOf(e).userMessage
                        hasMore = current.hasMore
                    }
                } finally {
                    if (mine == generation) loading = false
                }
            }
    }

    private fun newPager() = CursorPager(PAGE_SIZE) { cursor, limit -> source.openFolioPage(cursor, limit) }

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

/**
 * A aba "Contas" de uma sessão: a lista das contas abertas e a conta escolhida nela. Quem a cria (com a chave da sessão
 * e do conjunto da casca) a fecha ao sair da área; escolher outra conta fecha a anterior. Uma escrita na conta aberta
 * (ou a conta que o servidor disse encerrada) relê a lista.
 */
class FolioAccounts(
    private val wiring: FolioWiring,
    private val scope: CoroutineScope,
) {
    val list = FolioListState(wiring.source, scope)

    var open by mutableStateOf<FolioState?>(null)
        private set

    fun select(folioId: String) {
        if (open?.folioId == folioId) return
        open?.close()
        open = FolioState(wiring, scope, folioId, onWritten = list::refresh).also { it.start() }
    }

    fun close() {
        list.close()
        open?.close()
        open = null
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
