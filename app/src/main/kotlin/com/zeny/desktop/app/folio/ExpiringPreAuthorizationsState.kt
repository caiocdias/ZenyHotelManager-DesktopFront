package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.ZoneId

private const val PAGE_SIZE = 50
private const val DAYS_AHEAD = 7L
private const val NEEDS_DATE = "Informe até quando."
private const val INVALID_DATE = "Data inválida."

/**
 * Uma caução a vencer na lista, na ordem do servidor: a validade no fuso do hotel, a situação (o desconhecido aparece
 * como "Outro (VALOR)"), o valor e o capturável do servidor (fora do formato, "—"). O [folioId] serve só ao "Abrir
 * conta" e nunca aparece. A linha não tem bandeira, últimos dígitos, autorização nem NSU.
 */
data class ExpiringRow(
    val folioId: String,
    val expires: String,
    val status: String,
    val amount: String,
    val capturable: String,
) {
    override fun toString(): String =
        "ExpiringRow(expires=$expires, status=$status, amount=$amount, capturable=$capturable)"
}

/**
 * A aba "Cauções a vencer": as cauções não encerradas cuja validade cai até o dia [untilText], uma página por vez pelo
 * `CursorPager`. [enter] (a cada entrada na aba) lê a data operacional (o fuso da validade) e relê a primeira página:
 * com o "Até" em branco, ele nasce com a data operacional mais 7 dias; se a leitura da data falhar, fica em branco com
 * [untilError] e nada é lido até a operadora digitar a data. [search] relê da primeira página com o "Até" digitado
 * (data inválida não lê). Uma leitura por vez: [search] e [enter] descartam a em voo; [close] cancela a em voo e
 * descarta a resposta tardia.
 */
class ExpiringPreAuthorizationsState(
    private val source: ExpiringPreAuthorizationSource,
    private val payments: PaymentSource,
    scope: CoroutineScope,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)
    private var pager: CursorPager<CardPreAuthorizationResponse>? = null
    private var zone: ZoneId? = null
    private var closed = false
    private var generation = 0
    private var loadJob: Job? = null
    private var enterJob: Job? = null

    /** Uma nova leitura pediu a troca das linhas: só uma página que chega a desliga (a falha a mantém para o retry). */
    private var replacePending = false

    /** O "Até" como a operadora o vê (`dd/MM/aaaa`). */
    var untilText by mutableStateOf("")

    var untilError by mutableStateOf<String?>(null)
        private set

    var rows by mutableStateOf<List<ExpiringRow>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var hasMore by mutableStateOf(false)
        private set

    /** Entra na aba: lê a data operacional e relê a primeira página com o "Até" vigente (ou a data mais 7 dias). */
    fun enter() {
        if (closed) return
        enterJob?.cancel()
        enterJob =
            ownScope.launch {
                val day = readDay()
                currentCoroutineContext().ensureActive()
                day?.zone?.let { zone = it }
                if (untilText.isBlank()) {
                    if (day == null) {
                        untilError = NEEDS_DATE
                        return@launch
                    }
                    untilText = DateInput.format(BusinessDate(day.date.value.plusDays(DAYS_AHEAD)))
                }
                parseUntil()?.let(::startReading)
            }
    }

    /** Relê da primeira página com o "Até" digitado; data em branco ou inválida mostra o texto e não lê. */
    fun search() {
        if (closed) return
        enterJob?.cancel()
        parseUntil()?.let(::startReading)
    }

    /** A página seguinte; depois de uma leitura que falhou, a mesma página de novo. */
    fun loadMore() {
        if (closed || loading) return
        load()
    }

    fun close() {
        closed = true
        jobs.cancel()
    }

    override fun toString(): String = "ExpiringPreAuthorizationsState(rows=${rows.size})"

    private suspend fun readDay() =
        try {
            payments.businessDay()
        } catch (
            // Sem a data operacional, o "Até" espera a operadora; o fuso já lido fica.
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            null
        }

    private fun parseUntil(): BusinessDate? =
        when (val parsed = DateInput.parse(untilText)) {
            InputResult.Empty -> {
                untilError = NEEDS_DATE
                null
            }

            is InputResult.Invalid -> {
                untilError = INVALID_DATE
                null
            }

            is InputResult.Valid -> {
                untilError = null
                parsed.value
            }
        }

    private fun startReading(to: BusinessDate) {
        loadJob?.cancel()
        pager = CursorPager(PAGE_SIZE) { cursor, limit -> source.page(to, cursor, limit) }
        replacePending = true
        load()
    }

    private fun load() {
        val current = pager ?: return
        val mine = ++generation
        val hotelZone = zone
        loading = true
        error = null
        loadJob =
            ownScope.launch {
                try {
                    val page = current.next().map { rowOf(it, hotelZone) }
                    currentCoroutineContext().ensureActive()
                    if (mine != generation) return@launch
                    rows = if (replacePending) page else rows + page
                    replacePending = false
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
}

private fun rowOf(
    response: CardPreAuthorizationResponse,
    zone: ZoneId?,
) = ExpiringRow(
    folioId = response.folioId.toString(),
    expires = FolioText.expiresAt(response.expiresAt, zone),
    status = FolioText.preAuthorizationStatus(response.status),
    amount = FolioText.money(response.amount),
    capturable = FolioText.money(response.capturableAmount),
)

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
