package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.reception.HolderNames
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.design.table.Column
import com.zeny.desktop.design.table.TableState
import com.zeny.desktop.sdk.model.DepositShortfallResponse
import com.zeny.desktop.sdk.model.PendingGuaranteeResponse
import com.zeny.desktop.sdk.model.ReallocationRequiredResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val DEFAULT_PAGE_SIZE = 50
private const val GUARANTEE_WINDOW_DAYS = 730L
private const val UNKNOWN = "—"

/**
 * O que as pendências usam de um servidor: o fuso do hotel, as quatro listas e o titular de cada linha. A mesma forma
 * da `PendingFacade` do `core`; quem liga as duas é o `CoreConnection`.
 */
interface PendingSource {
    suspend fun businessDay(): BusinessDay

    suspend fun pendingGuarantees(
        to: Instant,
        cursor: String?,
        limit: Int,
    ): Page<PendingGuaranteeResponse>

    suspend fun depositShortfalls(): List<DepositShortfallResponse>

    suspend fun expectedNoShows(
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse>

    suspend fun reallocationRequired(
        cursor: String?,
        limit: Int,
    ): Page<ReallocationRequiredResponse>

    suspend fun holder(guestId: String): HolderSummary
}

/** As quatro listas de pendências, na ordem do seletor. */
enum class PendingList(
    val label: String,
) {
    GUARANTEES("Garantias pendentes"),
    DEPOSITS("Depósitos em falta"),
    NO_SHOWS("No-shows previstos"),
    REALLOCATION("Realocação necessária"),
}

/** Uma linha de pendência, toda em texto; o `toString` não mostra o titular. */
data class PendingRow(
    val reservationId: String,
    val code: String,
    val holder: String,
    val period: String,
    val detail: String,
    val due: String,
) {
    // O titular é dado pessoal: nunca em log nem em `toString`.
    override fun toString(): String =
        "PendingRow(reservationId=$reservationId, code=$code, period=$period, detail=$detail, due=$due)"
}

/**
 * Estado das pendências de reservas: uma [TableState] por [PendingList], cada uma paginada por cursor (a de depósitos
 * tem uma página só).
 *
 * - [start] carrega as quatro listas, juntas; a falha de uma fica na tabela dela e não toca as outras. [select] só
 *   troca a lista mostrada; [refresh] recarrega só a selecionada (o botão "Atualizar"); [refreshAll] recarrega as
 *   quatro (depois de uma ação: a mesma reserva pode estar em mais de uma lista). Cada recarga passa pela geração
 *   da [TableState] da lista: a resposta de uma carga anterior nunca entra na recarregada.
 * - Garantias: pede todas, das vencidas até 730 dias à frente (`to = now() + 730 dias`, calculado a cada primeira
 *   página): o padrão do servidor esconderia a pré-reserva recém-criada.
 * - Titulares: um cache só, lido por [HolderNames] (`403` e `404` viram "—"). Ele não aceita chamadas em paralelo,
 *   então as leituras das quatro listas passam por um `Mutex`. A lista de realocação não traz o titular: "—".
 * - O fuso do hotel é lido uma vez (a primeira página que precisa dele); sem fuso, os instantes ficam "—".
 *
 * Como o `ReservationSearchState`: as mutações correm no escopo da UI (uma thread só); [close] cancela só o que é
 * daqui, e o que chega depois dele é descartado.
 */
@Suppress("TooManyFunctions") // uma carga de página por lista, mais as passagens comuns
class PendingListsState(
    private val source: PendingSource,
    scope: CoroutineScope,
    private val now: () -> Instant,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    /** Filho do job da UI: o [close] cancela só as cargas daqui. */
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)
    private var closed = false

    private val holderNames = HolderNames(source::holder)
    private val lookups = Mutex()

    private var zone: ZoneId? = null
    private var zoneRead = false
    private val zoneLookup = Mutex()

    /** O fim da janela das garantias da carga vigente; as páginas seguintes a reusam. */
    private var guaranteeWindowEnd: Instant? = null

    private val tables = PendingList.entries.associateWith { newTable(it) }

    var selected: PendingList by mutableStateOf(PendingList.GUARANTEES)

    val columns: List<Column<PendingRow>> =
        listOf(
            Column("code", "Reserva") { it.code },
            Column("holder", "Titular") { it.holder },
            Column("period", "Período") { it.period },
            Column("detail", "Situação") { it.detail },
            Column("due", "Prazo") { it.due },
        )

    fun table(list: PendingList): TableState<PendingRow> = tables.getValue(list)

    /** A tabela da lista selecionada. */
    val table: TableState<PendingRow> get() = table(selected)

    /** Carrega as quatro listas, juntas. */
    fun start() {
        if (closed) return
        PendingList.entries.forEach { list -> launchLoad { table(list).reload() } }
    }

    fun select(list: PendingList) {
        selected = list
    }

    /** Recarrega só a lista selecionada; as linhas dela ficam até a primeira página nova chegar. */
    fun refresh() {
        if (closed) return
        val list = selected
        launchLoad { table(list).refresh() }
    }

    /** Recarrega as quatro listas; as linhas de cada uma ficam até a primeira página nova chegar. */
    fun refreshAll() {
        if (closed) return
        PendingList.entries.forEach { list -> launchLoad { table(list).refresh() } }
    }

    /** A área saiu de cena: cancela as cargas e não pede mais nada. */
    fun close() {
        closed = true
        jobs.cancel()
    }

    // Dado pessoal (titulares): nunca em `toString`.
    override fun toString(): String = "PendingListsState(***)"

    private fun launchLoad(load: suspend () -> Unit) {
        ownScope.launch {
            try {
                load()
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                // A falha que não é da API não derruba a área; a tabela fica como a carga a deixou.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private fun newTable(list: PendingList): TableState<PendingRow> =
        TableState(
            { CursorPager(pageSize) { cursor, limit -> pageOf(list, cursor, limit) } },
            PendingRow::reservationId,
        )

    /** Uma página da lista. Depois do [close], vazia e sem ir à rede; o resultado (ou o erro) tardio é descartado. */
    private suspend fun pageOf(
        list: PendingList,
        cursor: String?,
        limit: Int,
    ): Page<PendingRow> {
        if (closed) return Page(emptyList(), null)
        val page =
            try {
                rowsOf(list, cursor, limit)
            } catch (e: ApiError) {
                if (!closed) throw e
                null
            }
        return page?.takeUnless { closed } ?: Page(emptyList(), null)
    }

    private suspend fun rowsOf(
        list: PendingList,
        cursor: String?,
        limit: Int,
    ): Page<PendingRow> =
        when (list) {
            PendingList.GUARANTEES -> guarantees(cursor, limit)
            PendingList.DEPOSITS -> deposits()
            PendingList.NO_SHOWS -> noShows(cursor, limit)
            PendingList.REALLOCATION -> reallocations(cursor, limit)
        }

    private suspend fun guarantees(
        cursor: String?,
        limit: Int,
    ): Page<PendingRow> {
        val end = guaranteeWindowEnd.takeIf { cursor != null } ?: windowEnd().also { guaranteeWindowEnd = it }
        val page = source.pendingGuarantees(end, cursor, limit)
        val zone = hotelZone()
        return withHolders(page, { it.holderGuestId.toString() }) { item, holder ->
            val (detail, due) = PendingText.guarantee(item, zone)
            PendingRow(
                item.id.toString(),
                item.code,
                holder,
                ReceptionText.period(item.arrival, item.departure, dayUse = false),
                detail,
                due,
            )
        }
    }

    private suspend fun deposits(): Page<PendingRow> {
        val page = Page(source.depositShortfalls(), null)
        val zone = hotelZone()
        return withHolders(page, { it.reservation.holderGuestId.toString() }) { item, holder ->
            val reservation = item.reservation
            val (detail, due) = PendingText.deposit(item, zone)
            PendingRow(
                reservation.id.toString(),
                reservation.code,
                holder,
                ReceptionText.period(reservation.arrival, reservation.departure, reservation.dayUse),
                detail,
                due,
            )
        }
    }

    private suspend fun noShows(
        cursor: String?,
        limit: Int,
    ): Page<PendingRow> =
        withHolders(source.expectedNoShows(cursor, limit), { it.holderGuestId.toString() }) { item, holder ->
            val (detail, due) = PendingText.noShow(item)
            PendingRow(
                item.id.toString(),
                item.code,
                holder,
                ReceptionText.period(item.arrival, item.departure, item.dayUse),
                detail,
                due,
            )
        }

    private suspend fun reallocations(
        cursor: String?,
        limit: Int,
    ): Page<PendingRow> =
        withHolders(source.reallocationRequired(cursor, limit), { null }) { item, holder ->
            val (detail, due) = PendingText.reallocation(item)
            PendingRow(
                item.reservationId.toString(),
                item.reservationCode,
                holder,
                ReceptionText.period(item.arrival, item.departure, item.assignment.dayUse),
                detail,
                due,
            )
        }

    /** As linhas de uma página, com o titular de cada uma (`null` do [guestId]: sem titular, "—"). */
    private suspend fun <T> withHolders(
        page: Page<T>,
        guestId: (T) -> String?,
        row: (T, String) -> PendingRow,
    ): Page<PendingRow> {
        // Fechado durante a leitura da página: nem titulares nem fuso saem à rede.
        if (closed) return Page(emptyList(), null)
        val ids = page.items.mapNotNull(guestId)
        val names = if (ids.isEmpty()) emptyMap() else lookups.withLock { holderNames.resolve(ids) }
        return Page(page.items.map { row(it, guestId(it)?.let(names::getValue) ?: UNKNOWN) }, page.nextCursor)
    }

    private fun windowEnd(): Instant = now().plus(Duration.ofDays(GUARANTEE_WINDOW_DAYS))

    /** O fuso do hotel, lido uma vez; se a leitura falha, os instantes ficam "—" e a página seguinte tenta de novo. */
    private suspend fun hotelZone(): ZoneId? =
        zoneLookup.withLock {
            if (!zoneRead && !closed) {
                try {
                    zone = source.businessDay().zone
                    zoneRead = true
                } catch (_: ApiError) {
                    zone = null
                }
            }
            zone
        }
}
