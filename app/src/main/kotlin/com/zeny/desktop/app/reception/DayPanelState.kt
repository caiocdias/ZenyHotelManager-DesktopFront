package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.table.Column
import com.zeny.desktop.design.table.TableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val PAGE_LIMIT = 200
private const val ROOMS_WINDOW_MS = 2_000L
private const val ROOMS_TOPIC = "rooms"
private const val END_OF_DAY_TOPIC = "end-of-day"
private const val ROOMS_PERMISSION = "rooms.status.read"
private const val END_OF_DAY_PERMISSION = "endofday.runs.read"

private val tabNames =
    mapOf(DayList.ARRIVALS to "Chegadas", DayList.DEPARTURES to "Saídas", DayList.IN_HOUSE to "Hospedados")

/**
 * Estado do painel do dia: uma [TableState] por lista (chegadas, saídas, hospedados) na data operacional.
 *
 * - Nenhuma página sai antes da primeira data operacional: o `fetch` espera por ela, e cada listagem usa a data da
 *   carga que a criou. A primeira carga das abas é um `reload`; as seguintes, `refresh`, que não esvazia a tabela.
 * - Cada página resolve os titulares que faltam no cache (até 4 leituras em paralelo; `403` e
 *   `404` viram "—") e os tipos de UH, lidos uma vez por carga (`ApiError.Problem` vira "—" para todos).
 * - Atualizam o painel: [refresh], o ganho de foco da janela, o primeiro `ready` e todo `reset` do stream, a
 *   mensagem de `end-of-day` e, uma vez por rajada, as de `rooms` (2 s depois da primeira). No máximo uma
 *   atualização em voo e uma pendente. Cada tópico só é assinado com a permissão dele nas [permissions] recebidas
 *   da casca (ADR 0013); sem nenhuma, não há assinatura. A assinatura que termina, com erro ou sem, só deixa de
 *   atualizar o painel.
 *
 * Como o `RoomMapState`: as mutações correm no escopo da UI (uma thread só), por isso o estado dispensa trava fora
 * das leituras de apoio. Quem cria chama [close] quando o painel sai de cena.
 */
@Suppress("TooManyFunctions") // as ações da tela e um passo por regra do protocolo de carga e do stream
class DayPanelState(
    private val source: ReceptionSource,
    scope: CoroutineScope,
    /** As permissões efetivas da sessão, lidas pela casca: as ações da tela e o check-in e o walk-in as usam. */
    val permissions: Set<String>,
) {
    /** Filho do job da UI: o [close] cancela só o que é do painel. */
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val panelScope = CoroutineScope(scope.coroutineContext + jobs)

    /** O portão da primeira data operacional: `null` até a primeira carga lançar as abas. */
    private val loadDate = MutableStateFlow<BusinessDate?>(null)

    /**
     * A data operacional da carga vigente, atribuída antes de as abas atualizarem: a listagem nova a lê mesmo
     * que o `fetch` dela rode antes de a atualização terminar de lançar as abas.
     */
    private var currentDate: BusinessDate? = null

    /** Os titulares (só em memória, enquanto o painel vive) e os tipos de UH da carga vigente. */
    private val resolver = ReservationRowResolver(source::holder, source::roomTypes)

    val tabs: Map<DayList, TableState<ReservationRow>> =
        DayList.entries.associateWith { list -> TableState({ pagerOf(list) }, ReservationRow::id) }

    var selectedTab by mutableStateOf(DayList.ARRIVALS)
        private set
    var day by mutableStateOf<BusinessDay?>(null)
        private set

    /** Só da data operacional; o erro de uma lista fica na aba dela. */
    var error by mutableStateOf<ApiError?>(null)
        private set

    val columns: List<Column<ReservationRow>> = reservationColumns

    private var update: Job? = null
    private var updatePending = false
    private var tabsStarted = false
    private var readySeen = false
    private var roomsBurst: Job? = null
    private var focused = true
    private var closed = false

    init {
        panelScope.launch { listen() }
        refresh()
    }

    /** O nome da aba, com as linhas carregadas depois da primeira página e "+" enquanto há página por carregar. */
    fun tabLabel(list: DayList): String {
        val table = tabs.getValue(list)
        val name = tabNames.getValue(list)
        val count = table.rows.size
        return when {
            count == 0 && table.hasMore -> name
            table.hasMore -> "$name ($count+)"
            else -> "$name ($count)"
        }
    }

    fun select(list: DayList) {
        selectedTab = list
    }

    /** Relê a data operacional e atualiza as três abas; com uma atualização em voo, fica uma pendente. */
    fun refresh() {
        if (closed) return
        if (update?.isActive == true) {
            updatePending = true
            return
        }
        updatePending = false
        update =
            panelScope.launch {
                updateOnce()
                while (updatePending) {
                    updatePending = false
                    updateOnce()
                }
            }
    }

    /** Atualiza só quando a janela ganha o foco. */
    fun onWindowFocus(focused: Boolean) {
        val gained = focused && !this.focused
        this.focused = focused
        if (gained) refresh()
    }

    /** O painel saiu de cena: cancela a assinatura e as cargas, e não atualiza mais. */
    fun close() {
        closed = true
        jobs.cancel()
    }

    private suspend fun updateOnce() {
        val read =
            try {
                source.businessDay()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                error = e as? ApiError ?: ApiError.Network(e)
                return
            }
        error = null
        day = read
        resolver.forgetRoomTypes()
        val first = !tabsStarted
        tabsStarted = true
        currentDate = read.date
        coroutineScope {
            // Sem despacho: cada aba descarta o `fetch` que esperava a data antes de ela chegar, e ele não sai.
            tabs.values.forEach { table -> launch(start = CoroutineStart.UNDISPATCHED) { updateTable(table, first) } }
            loadDate.value = read.date
        }
    }

    private suspend fun updateTable(
        table: TableState<ReservationRow>,
        first: Boolean,
    ) {
        try {
            if (first) table.reload() else table.refresh()
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // A falha que não é da API (a tabela já guardou a atualização pendente) não derruba o painel.
            currentCoroutineContext().ensureActive()
        }
    }

    /** Uma listagem presa à data da carga em que fez a primeira página. */
    private fun pagerOf(list: DayList): CursorPager<ReservationRow> {
        var date: BusinessDate? = null
        return CursorPager(PAGE_LIMIT) { cursor, limit ->
            val pinned = date ?: (currentDate ?: loadDate.filterNotNull().first()).also { date = it }
            pageOf(list, pinned, cursor, limit)
        }
    }

    private suspend fun pageOf(
        list: DayList,
        date: BusinessDate,
        cursor: String?,
        limit: Int,
    ): Page<ReservationRow> {
        val page = source.dayPage(list, date, cursor, limit)
        return Page(resolver.rows(page.items), page.nextCursor)
    }

    private suspend fun listen() {
        val topics = topicsFor(permissions)
        if (topics.isEmpty()) return
        try {
            source.events(topics).collect(::onEvent)
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // O fim da assinatura só tira a atualização automática; o painel segue.
            currentCoroutineContext().ensureActive()
        }
    }

    private fun onEvent(event: RealtimeEvent) {
        when (event) {
            RealtimeEvent.Ready -> {
                if (!readySeen) {
                    readySeen = true
                    refresh()
                }
            }

            RealtimeEvent.Reset -> {
                refresh()
            }

            is RealtimeEvent.Message -> {
                when (event.topic) {
                    END_OF_DAY_TOPIC -> refresh()
                    ROOMS_TOPIC -> startRoomsWindow()
                    else -> Unit
                }
            }
        }
    }

    /** As mensagens de `rooms` que chegam até 2 s depois da primeira viram uma atualização só. */
    private fun startRoomsWindow() {
        if (roomsBurst?.isActive == true) return
        roomsBurst =
            panelScope.launch {
                delay(ROOMS_WINDOW_MS)
                refresh()
            }
    }
}

private fun topicsFor(permissions: Set<String>): Set<String> =
    buildSet {
        if (ROOMS_PERMISSION in permissions) add(ROOMS_TOPIC)
        if (END_OF_DAY_PERMISSION in permissions) add(END_OF_DAY_TOPIC)
    }
