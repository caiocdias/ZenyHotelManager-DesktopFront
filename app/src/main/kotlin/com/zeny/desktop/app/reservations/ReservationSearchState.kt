package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.reception.COUNTRY_REQUIRED
import com.zeny.desktop.app.reception.COUNTRY_SHAPE
import com.zeny.desktop.app.reception.GUEST_DOCUMENT_TYPES
import com.zeny.desktop.app.reception.ReservationRow
import com.zeny.desktop.app.reception.ReservationRowResolver
import com.zeny.desktop.app.reception.reservationColumns
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DocumentQuery
import com.zeny.desktop.core.facade.RESERVATION_STATUSES
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.table.Column
import com.zeny.desktop.design.table.TableState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val DEFAULT_PAGE_SIZE = 50
private const val DEFAULT_PERIOD_DAYS = 30L
private const val MIN_NAME_LENGTH = 2
private const val MAX_NAME_LENGTH = 200
private const val MAX_CODE_LENGTH = 30
private const val MAX_EXTERNAL_ID_LENGTH = 60
private const val MAX_DOCUMENT_LENGTH = 40
private const val DEFAULT_DOCUMENT_TYPE = "CPF"

/**
 * Estado da área Reservas: os filtros digitados, a busca vigente e a [table] com as páginas dela.
 *
 * - Os filtros só valem ao buscar: [search] e [searchByDocument] validam no cliente (o motivo vai para [fieldError],
 *   sem ir à rede) e congelam a consulta, que o "Carregar mais" reenvia até a próxima busca.
 * - Nenhuma página sai sem consulta: antes de o [start] congelar a primeira, o `fetch` da tabela espera por ela.
 * - Cada página resolve os titulares que faltam no cache (`403` e `404` viram "—") e os tipos de UH, lidos uma vez
 *   por estado (`ApiError.Problem` vira "—" para todos).
 * - Dado pessoal: o número do documento sai do campo ao buscar e só vive na consulta vigente, que a busca seguinte e
 *   o [close] descartam; na tela, só [searchedDocument], mascarado. O `toString` não mostra nada.
 *
 * Como o `DayPanelState`: as mutações correm no escopo da UI (uma thread só); [close] cancela só o que é daqui.
 */
@Suppress("TooManyFunctions") // as duas buscas, as validações delas e um passo por regra da carga de página
class ReservationSearchState(
    private val source: ReservationSearchSource,
    scope: CoroutineScope,
    pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    /** A consulta congelada por uma busca; nunca vai a `toString` nem a log. */
    private sealed interface Query {
        class Filtered(
            val filter: ReservationFilter,
        ) : Query

        class ByDocument(
            val document: DocumentQuery,
            val statuses: Set<String>,
        ) : Query
    }

    /** Filho do job da UI: o [close] cancela só o que é da busca. */
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    /** A consulta vigente, lida a cada página: a busca seguinte a troca e o [close] a anula. */
    private var current: Query? = null

    /** O portão da primeira consulta: aberto pela primeira busca ou pelo [close]. */
    private val firstQuery = CompletableDeferred<Unit>()
    private var closed = false

    /** Os titulares e os tipos de UH, lidos uma vez enquanto o estado vive. */
    private val resolver = ReservationRowResolver(source::holder, source::roomTypes)

    val table = TableState({ CursorPager(pageSize, ::pageOf) }, ReservationRow::id)

    val columns: List<Column<ReservationRow>> = reservationColumns

    var number by mutableStateOf("")
    var name by mutableStateOf("")
    var fromText by mutableStateOf("")
    var toText by mutableStateOf("")

    /** As situações escolhidas, dentre [RESERVATION_STATUSES]; vazio busca todas. */
    var statuses by mutableStateOf<Set<String>>(emptySet())

    /** Um de `GUEST_DOCUMENT_TYPES`. */
    var documentType by mutableStateOf(DEFAULT_DOCUMENT_TYPE)
    var documentNumber by mutableStateOf("")
    var issuingCountry by mutableStateOf("")

    /** O motivo, em pt-BR, de a última busca não ter ido à rede. */
    var fieldError by mutableStateOf<String?>(null)
        private set

    /** Só da data operacional; o erro de uma página fica na [table]. */
    var error by mutableStateOf<ApiError?>(null)
        private set

    /** O rótulo mascarado do documento da busca vigente, ou `null` se ela não é por documento. */
    var searchedDocument by mutableStateOf<String?>(null)
        private set

    var day by mutableStateOf<BusinessDay?>(null)
        private set

    /**
     * Lê a data operacional e, se nenhuma busca foi enviada ainda, põe o período padrão (dela até 30 dias depois) e
     * busca. Também é o "Tentar de novo" de quando a data falhou.
     */
    fun start() {
        if (closed) return
        ownScope.launch {
            val read =
                try {
                    source.businessDay()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    error = e as? ApiError ?: ApiError.Network(e)
                    return@launch
                }
            error = null
            day = read
            // A busca que o usuário já enviou (com a data presa) vale: a carga inicial não a substitui.
            if (current != null) return@launch
            fromText = DateInput.format(read.date)
            toText = DateInput.format(BusinessDate(read.date.value.plusDays(DEFAULT_PERIOD_DAYS)))
            search()
        }
    }

    /** Busca pelos filtros digitados; inválidos, só o motivo em [fieldError]. */
    fun search() {
        if (closed) return
        val problem = filterProblem()
        fieldError = problem
        if (problem != null) return
        searchedDocument = null
        runQuery(Query.Filtered(submittedFilter()))
    }

    /** Busca pelo documento digitado, com as situações escolhidas; o número sai do campo antes da rede. */
    fun searchByDocument() {
        if (closed) return
        val number = documentNumber.trim()
        val type = documentType
        val country = issuingCountry.trim()
        val problem =
            when {
                number.isEmpty() -> ReservationSearchText.NEED_NUMBER
                number.length > MAX_DOCUMENT_LENGTH -> ReservationSearchText.DOCUMENT_TOO_LONG
                type !in GUEST_DOCUMENT_TYPES -> ReservationSearchText.BAD_TYPE
                country.isEmpty() && type in COUNTRY_REQUIRED -> ReservationSearchText.NEED_COUNTRY
                country.isNotEmpty() && !COUNTRY_SHAPE.matches(country) -> ReservationSearchText.BAD_COUNTRY
                else -> null
            }
        fieldError = problem
        if (problem != null) return
        val query = DocumentQuery(type, number, country.uppercase().takeIf { it.isNotEmpty() })
        documentNumber = ""
        searchedDocument = ReservationSearchText.maskedDocument(type, number)
        runQuery(Query.ByDocument(query, knownStatuses()))
    }

    /** A área saiu de cena: cancela as cargas, descarta a consulta e o documento, e não busca mais. */
    fun close() {
        closed = true
        jobs.cancel()
        current = null
        documentNumber = ""
        searchedDocument = null
        firstQuery.complete(Unit)
    }

    // Dado pessoal (nome e documento buscados): nunca em `toString`.
    override fun toString(): String = "ReservationSearchState(***)"

    private fun knownStatuses(): Set<String> = statuses.filterTo(LinkedHashSet()) { it in RESERVATION_STATUSES }

    /** O primeiro motivo de recusa dos filtros digitados, ou `null` se valem. */
    private fun filterProblem(): String? {
        val term = name.trim()
        val code = number.trim()
        val from = DateInput.parse(fromText)
        val to = DateInput.parse(toText)
        return when {
            term.isNotEmpty() && term.length < MIN_NAME_LENGTH -> ReservationSearchText.SHORT_NAME
            term.length > MAX_NAME_LENGTH -> ReservationSearchText.NAME_TOO_LONG
            from is InputResult.Invalid -> from.message
            to is InputResult.Invalid -> to.message
            isInverted(from, to) -> ReservationSearchText.PERIOD_INVERTED
            code.length > maxNumberLength(code) -> ReservationSearchText.NUMBER_TOO_LONG
            else -> null
        }
    }

    private fun isInverted(
        from: InputResult<BusinessDate>,
        to: InputResult<BusinessDate>,
    ) = from is InputResult.Valid && to is InputResult.Valid && to.value.value.isBefore(from.value.value)

    /** O filtro dos campos digitados, já validados por [filterProblem]. */
    private fun submittedFilter(): ReservationFilter =
        ReservationSearchText.numberFilter(number).copy(
            name = name.trim().takeIf { it.isNotEmpty() },
            from = (DateInput.parse(fromText) as? InputResult.Valid)?.value,
            to = (DateInput.parse(toText) as? InputResult.Valid)?.value,
            statuses = knownStatuses(),
        )

    private fun maxNumberLength(code: String): Int {
        val filter = ReservationSearchText.numberFilter(code)
        return if (filter.code != null || filter.bookingCode != null) MAX_CODE_LENGTH else MAX_EXTERNAL_ID_LENGTH
    }

    /**
     * Troca a consulta vigente e recarrega a tabela sem suspender entre os dois: o `reload` cancela o `fetch` que
     * esperava a primeira consulta antes de o portão abrir, e a primeira página não sai duas vezes.
     */
    private fun runQuery(query: Query) {
        current = query
        ownScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                table.reload()
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                // A falha que não é da API não derruba a área; a tabela fica como o `reload` a deixou.
                currentCoroutineContext().ensureActive()
            }
        }
        firstQuery.complete(Unit)
    }

    /**
     * Uma página da consulta vigente. Depois do [close], vazia e sem ir à rede; o resultado (ou o erro) que chega
     * depois dele é descartado.
     */
    private suspend fun pageOf(
        cursor: String?,
        limit: Int,
    ): Page<ReservationRow> {
        if (!closed && current == null) firstQuery.await()
        val query = current
        val page =
            try {
                query?.let { rowsOf(it, cursor, limit) }
            } catch (e: ApiError) {
                if (!closed) throw e
                null
            }
        return page?.takeUnless { closed } ?: Page(emptyList(), null)
    }

    private suspend fun rowsOf(
        query: Query,
        cursor: String?,
        limit: Int,
    ): Page<ReservationRow> {
        val page =
            when (query) {
                is Query.Filtered -> source.search(query.filter, cursor, limit)
                is Query.ByDocument -> source.searchByDocument(query.document, query.statuses, cursor, limit)
            }
        // Fechado durante a página: nem titulares nem tipos de UH saem à rede.
        if (closed) return Page(emptyList(), null)
        return Page(resolver.rows(page.items), page.nextCursor)
    }
}
