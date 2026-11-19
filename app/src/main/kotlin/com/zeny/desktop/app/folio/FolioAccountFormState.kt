package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.CompanyChoice
import com.zeny.desktop.core.facade.VersionedFolio
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.FolioResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50
private const val MIN_NAME = 2
private const val MAX_NAME = 200
private const val FOLIO_CLOSED = "FOLIO_CLOSED"
private const val HTTP_CONFLICT = 409

/**
 * Os textos de uma releitura pedida por um envio: [done] com a conta relida e aberta, [closed] com ela relida e
 * encerrada (salvar fica desligado) e [failed] antes do texto da falha, quando a releitura falha.
 */
private class RereadTexts(
    val done: String,
    val closed: String,
    val failed: String,
)

/**
 * As empresas ativas para escolher o pagador, uma página de 50 por vez pelo `CursorPager`, na ordem do servidor. [search]
 * relê da primeira página com o [query] (cortado em 200 caracteres; com menos de 2, sem filtro, como o contrato exige)
 * e descarta a leitura em voo; [loadMore] lê a seguinte (ou a mesma de novo, depois de uma falha). A resposta que
 * chega depois de outra leitura, ou depois de o formulário fechar, é descartada.
 */
class CompanyList internal constructor(
    private val source: FolioAccountSource,
    private val scope: CoroutineScope,
) {
    private var pager: CursorPager<CompanyChoice>? = null
    private var generation = 0
    private var loadJob: Job? = null

    /** Uma nova busca pediu a troca das linhas: só uma página que chega a desliga. */
    private var replacePending = false

    var query by mutableStateOf("")

    var rows by mutableStateOf<List<CompanyChoice>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var hasMore by mutableStateOf(false)
        private set

    /** Uma leitura terminou com êxito desde a última busca: só então "nenhuma empresa" é verdade. */
    var loaded by mutableStateOf(false)
        private set

    fun search() {
        val name = query.trim().take(MAX_NAME).takeIf { it.length >= MIN_NAME }
        loadJob?.cancel()
        pager = CursorPager(PAGE_SIZE) { cursor, limit -> source.activeCompanyPage(name, cursor, limit) }
        replacePending = true
        loaded = false
        load()
    }

    fun loadMore() {
        if (loading) return
        load()
    }

    private fun load() {
        val current = pager ?: return
        val mine = ++generation
        loading = true
        error = null
        loadJob =
            scope.launch {
                try {
                    val page = current.next()
                    currentCoroutineContext().ensureActive()
                    if (mine != generation) return@launch
                    rows = if (replacePending) page else rows + page
                    replacePending = false
                    loaded = true
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

    override fun toString(): String = "CompanyList(rows=${rows.size}, loading=$loading)"
}

/**
 * O diálogo "Pagador e limite" da conta [folioId]. [start] lê a conta com o `ETag` (a versão que vai no `If-Match` é a
 * dessa leitura, nunca a da tela) e o nome do pagador vigente por id; se essa leitura falha, ou sem [canChoosePayer]
 * (`guests.companies.read`), o pagador aparece como "Empresa pagadora definida", sem bloquear o formulário. Sem
 * [canChoosePayer], o pagador fica como está ([choose] é no-op, [companies] é nulo e [payerHint] diz por quê).
 *
 * O `PUT` substitui os dois campos: [save] sempre envia o pagador escolhido (ou o vigente) e o limite digitado; vazio é
 * "Sem limite", negativo é recusado sem ir à rede. Com [busy], as entradas e o [save] são no-op: um envio só. O sucesso
 * chama [onSaved] e trava o formulário. O `412` relê a conta (o formulário fica com o pagador, o limite e o `ETag`
 * relidos, sem salvar) e chama [onReread]; o resultado desconhecido (sem `Idempotency-Key`) faz o mesmo e diz para
 * conferir. `FOLIO_CLOSED` mostra o texto do catálogo, trava o salvamento e chama [onReread]; as outras recusas só
 * mostram o texto do catálogo. Depois de [close], nenhuma resposta muda o estado. O `toString()` não mostra os valores.
 */
@Suppress("LongParameterList") // a porta, o escopo, a conta, a permissão e os dois avisos para o painel
class FolioAccountFormState(
    private val source: FolioAccountSource,
    scope: CoroutineScope,
    private val folioId: String,
    private val canChoosePayer: Boolean,
    private val onSaved: () -> Unit,
    private val onReread: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    /** As empresas ativas para escolher; nulo sem [canChoosePayer]. */
    val companies: CompanyList? = if (canChoosePayer) CompanyList(source, ownScope) else null

    /** Por que o pagador não pode ser trocado; nulo com [canChoosePayer]. */
    val payerHint: String? = if (canChoosePayer) null else FolioText.PAYER_CHOICE_FORBIDDEN

    /** O pagador que vai no `PUT`; nulo = "Sem pagador". */
    var payer by mutableStateOf<CompanyChoice?>(null)
        private set

    /** O nome do pagador vigente lido; nulo sem pagador. */
    var payerName by mutableStateOf<String?>(null)
        private set

    /** O pagador vigente foi lido: antes disso, ou com a última leitura falhando, a tela não afirma qual é. */
    var payerKnown by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)
        private set

    var saved by mutableStateOf(false)
        private set

    private var limitValue by mutableStateOf("")
    private var etag by mutableStateOf<String?>(null)
    private var folioClosed by mutableStateOf(false)
    private var closed by mutableStateOf(false)
    private var generation = 0

    private val locked: Boolean get() = closed || busy || loading || saved

    /** O limite digitado (`MoneyField`, `1.234,56`); vazio é "Sem limite". */
    var limitText: String
        get() = limitValue
        set(value) {
            if (!locked) limitValue = value
        }

    val canSave: Boolean get() = !locked && etag != null && !folioClosed

    /** Escolhe o pagador ([company] nulo = "Sem pagador"); sem [canChoosePayer] ou travado, nada. */
    fun choose(company: CompanyChoice?) {
        if (canChoosePayer && !locked) payer = company
    }

    fun start() {
        if (closed) return
        val mine = ++generation
        loading = true
        ownScope.launch {
            try {
                reread(mine, null)
            } finally {
                if (mine == generation) loading = false
            }
        }
        companies?.search()
    }

    fun save() {
        if (!canSave) return
        val limit = typedLimit(limitValue)
        message = limit.error
        if (limit.error == null) send(checkNotNull(etag), payer?.id, limit.value)
    }

    fun close() {
        closed = true
        busy = false
        jobs.cancel()
    }

    private fun send(
        version: String,
        payerId: String?,
        limit: Money?,
    ) {
        busy = true
        message = null
        ownScope.launch {
            try {
                val outcome = attempt { source.updateFolio(folioId, version, payerId, limit) }
                if (closed) return@launch
                outcome.fold({
                    saved = true
                    onSaved()
                }, { fail(apiErrorOf(it)) })
            } finally {
                busy = false
            }
        }
    }

    private suspend fun fail(failure: ApiError) {
        when {
            failure is ApiError.PreconditionFailed -> {
                val stale = failure.userMessage
                rereadAll(RereadTexts("$stale ${FolioText.ACCOUNT_REREAD}", FOLIO_CLOSED_TEXT, stale))
            }

            classifyWriteFailure(failure) != WriteFailure.DEFINITIVE -> {
                val start = FolioText.ACCOUNT_UNCONFIRMED_START
                rereadAll(RereadTexts(FolioText.ACCOUNT_UNCONFIRMED, "$start $FOLIO_CLOSED_TEXT", start))
            }

            (failure as? ApiError.Problem)?.code == FOLIO_CLOSED -> {
                message = failure.userMessage
                folioClosed = true
                onReread()
            }

            else -> {
                message = failure.userMessage
            }
        }
    }

    /** O painel relê a conta, e o formulário também: fica com os valores e o `ETag` relidos e com os [texts]. */
    private suspend fun rereadAll(texts: RereadTexts) {
        onReread()
        reread(++generation, texts)
    }

    /**
     * Lê a conta e a aplica; a falha apaga o `ETag` (o salvamento fica bloqueado) e mostra o texto dela, depois do
     * aviso que pediu a releitura.
     */
    private suspend fun reread(
        mine: Int,
        texts: RereadTexts?,
    ) {
        val read = attempt { source.versionedFolio(folioId) }
        if (closed || mine != generation) return
        read.fold({ apply(it, mine, texts) }, {
            etag = null
            payerKnown = false
            message = listOfNotNull(texts?.failed, apiErrorOf(it).userMessage).joinToString(" ")
        })
    }

    private suspend fun apply(
        version: VersionedFolio,
        mine: Int,
        texts: RereadTexts?,
    ) {
        val folio = version.folio
        val payerId = (folio.payerCompanyId as? Option.Some)?.value?.toString()
        val name = payerId?.let { payerNameOf(it) }
        if (closed || mine != generation) return
        payer = if (payerId != null && name != null) CompanyChoice(payerId, name) else null
        payerName = name
        payerKnown = true
        limitValue = limitTextOf(folio.creditLimit)
        etag = version.etag
        folioClosed = folio.status == FolioResponse.Status.CLOSED
        message = if (folioClosed) texts?.closed else texts?.done
    }

    /** O nome do pagador por id; sem a leitura das empresas, ou com ela falhando, "Empresa pagadora definida". */
    private suspend fun payerNameOf(id: String): String =
        if (canChoosePayer) {
            attempt { source.company(id).name }.getOrDefault(FolioText.PAYER_SET)
        } else {
            FolioText.PAYER_SET
        }

    override fun toString(): String = "FolioAccountFormState(busy=$busy, saved=$saved)"
}

/** O texto do catálogo para a conta encerrada. */
private val FOLIO_CLOSED_TEXT: String = ApiError.Problem(HTTP_CONFLICT, FOLIO_CLOSED, null, emptyList()).userMessage

/** O limite digitado: [value] nulo é "Sem limite"; com [error], nada vai à rede. */
private class TypedLimit(
    val value: Money?,
    val error: String?,
)

private fun typedLimit(text: String): TypedLimit =
    when (val parsed = MoneyInput.parse(text)) {
        InputResult.Empty -> {
            TypedLimit(null, null)
        }

        is InputResult.Invalid -> {
            TypedLimit(null, parsed.message)
        }

        is InputResult.Valid -> {
            if (parsed.value.amount.signum() < 0) {
                TypedLimit(null, FolioText.LIMIT_NEGATIVE)
            } else {
                TypedLimit(parsed.value, null)
            }
        }
    }

/** O limite do servidor no formato do `MoneyField`; fora do formato, o texto como veio (salvar o recusa). */
private fun limitTextOf(creditLimit: Option<String?>): String {
    val raw = (creditLimit as? Option.Some)?.value ?: return ""
    return moneyOrNull(raw)?.let(MoneyInput::format) ?: raw
}

private suspend fun <T> attempt(call: suspend () -> T): Result<T> =
    try {
        Result.success(call())
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
