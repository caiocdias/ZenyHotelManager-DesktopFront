package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.reception.RegistrationCardState
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.FolioStatementResponse
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.StatementDayResponse
import com.zeny.desktop.sdk.model.StatementLineResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** O cabeçalho da conta: número, dono (rótulo do tipo), situação, saldo e limite como o servidor os devolve. */
data class FolioView(
    val number: String,
    val owner: String,
    val status: String,
    val balance: String,
    val creditLimit: String,
    val limitExceeded: Boolean,
    val closed: Boolean,
)

/** Um lançamento na tabela; o [id] serve às ações e nunca aparece. A taxa de serviço vem logo abaixo do item dela. */
data class PostingRow(
    val id: String,
    val date: String,
    val description: String,
    val type: String,
    val quantity: String,
    val amount: String,
    val reversed: Boolean,
    val serviceCharge: Boolean,
)

/** Uma linha do extrato: a descrição que o servidor escreveu e, só no `INTERNAL`, o motivo quando vem. */
data class StatementLineView(
    val date: String,
    val description: String,
    val kind: String,
    val amount: String,
    val reason: String?,
)

data class StatementDayView(
    val date: String,
    val lines: List<StatementLineView>,
    val charges: String,
    val payments: String,
    val balanceAtEnd: String,
)

/** O extrato como o servidor o devolve: os dias, os totais e o saldo, só formatados. */
data class StatementView(
    val audience: String,
    val days: List<StatementDayView>,
    val charges: String,
    val payments: String,
    val balance: String,
)

/**
 * A conta [folioId] na tela: o cabeçalho, os lançamentos (a taxa de serviço logo abaixo do item dela) e o extrato da
 * [audience] escolhida (padrão `INTERNAL`, só para a tela; o PDF tem os dois botões dele). O front não calcula nada:
 * saldo, limite, totais e saldo do dia são os do servidor. O [pdf] aberto é deste estado e fecha com ele. As leituras
 * correm no escopo da UI; [close] cancela o que é daqui, e a resposta que chega depois dele é descartada.
 */
class FolioState(
    private val wiring: FolioWiring,
    scope: CoroutineScope,
    val folioId: String,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)
    private var closed = false
    private var generation = 0
    private var statementGeneration = 0

    var loading by mutableStateOf(false)
        private set

    /** O texto de pessoa da falha da leitura da conta (com "Tentar de novo", [reload]). */
    var error by mutableStateOf<String?>(null)
        private set

    var view by mutableStateOf<FolioView?>(null)
        private set

    var postings by mutableStateOf<List<PostingRow>>(emptyList())
        private set

    var statement by mutableStateOf<StatementView?>(null)
        private set

    var statementLoading by mutableStateOf(false)
        private set

    var statementError by mutableStateOf<String?>(null)
        private set

    private var statementShown by mutableStateOf(false)
    private var chosenAudience by mutableStateOf(StatementAudience.INTERNAL)

    /** A audiência do extrato na tela; trocá-la com o extrato à vista o relê na nova. */
    var audience: StatementAudience
        get() = chosenAudience
        set(value) {
            if (value == chosenAudience) return
            chosenAudience = value
            if (statementShown) showStatement()
        }

    /** O extrato em PDF aberto (o visualizador da ficha). */
    var pdf by mutableStateOf<RegistrationCardState?>(null)
        private set

    val canOpenGuestPdf: Boolean get() = wiring.guestPdf != null
    val canOpenInternalPdf: Boolean get() = wiring.internalPdf != null

    fun start() = reload()

    /** Relê a conta e os lançamentos; a leitura anterior em voo é descartada. */
    fun reload() {
        if (closed) return
        val mine = ++generation
        loading = true
        error = null
        ownScope.launch {
            try {
                val folio = wiring.source.folio(folioId)
                currentCoroutineContext().ensureActive()
                val rows = wiring.source.postings(folioId)
                currentCoroutineContext().ensureActive()
                if (mine != generation) return@launch
                view = viewOf(folio)
                postings = rowsOf(rows)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                if (mine == generation) error = apiErrorOf(e).userMessage
            } finally {
                if (mine == generation) loading = false
            }
        }
    }

    /** Lê o extrato da [audience] escolhida; o pedido anterior em voo é descartado. */
    fun showStatement() {
        if (closed) return
        val mine = ++statementGeneration
        val asked = chosenAudience
        statementShown = true
        statementLoading = true
        statementError = null
        ownScope.launch {
            try {
                val response = wiring.source.statement(folioId, asked)
                currentCoroutineContext().ensureActive()
                if (mine == statementGeneration) statement = statementViewOf(response, asked)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                if (mine == statementGeneration) statementError = apiErrorOf(e).userMessage
            } finally {
                if (mine == statementGeneration) statementLoading = false
            }
        }
    }

    /** Abre o extrato em PDF da [audience] (independente da tela); sem a fábrica dela, nada. */
    fun openPdf(audience: StatementAudience) {
        val number = view?.number
        val make =
            when (audience) {
                StatementAudience.GUEST -> wiring.guestPdf
                StatementAudience.INTERNAL -> wiring.internalPdf
            }
        if (closed || number == null || make == null) return
        pdf?.close()
        pdf = make(folioId, number)
    }

    fun closePdf() {
        pdf?.close()
        pdf = null
    }

    fun close() {
        closed = true
        jobs.cancel()
        closePdf()
    }

    override fun toString() = "FolioState(loading=$loading, view=${view != null}, postings=${postings.size})"
}

private fun viewOf(folio: FolioResponse) =
    FolioView(
        number = folio.number,
        owner = FolioText.owner(folio.owner.type),
        status = FolioText.status(folio.status),
        balance = FolioText.money(folio.balance),
        creditLimit = (folio.creditLimit as? Option.Some)?.value?.let(FolioText::money) ?: FolioText.NO_LIMIT,
        limitExceeded = folio.creditLimitExceeded,
        closed = folio.status == FolioResponse.Status.CLOSED,
    )

/** Os lançamentos na ordem do servidor, com cada taxa de serviço logo abaixo do item a que pertence. */
private fun rowsOf(postings: List<PostingResponse>): List<PostingRow> {
    val ids = postings.map { it.id }.toSet()
    val charges = postings.filter { it.chargedItem() in ids }.groupBy { it.chargedItem() }
    return postings
        .filter { it.chargedItem() !in ids }
        .flatMap { item -> listOf(rowOf(item)) + charges[item.id].orEmpty().map(::rowOf) }
}

private fun PostingResponse.chargedItem() = (serviceChargeForPostingId as? Option.Some)?.value

private fun rowOf(posting: PostingResponse) =
    PostingRow(
        id = posting.id.toString(),
        date = FolioText.date(posting.businessDate),
        description = posting.description,
        type = FolioText.postingType(posting.type),
        quantity = FolioText.quantity(posting.quantity),
        amount = FolioText.money(posting.amount),
        reversed = (posting.reversedBy as? Option.Some)?.value != null,
        serviceCharge = posting.chargedItem() != null,
    )

private fun statementViewOf(
    response: FolioStatementResponse,
    audience: StatementAudience,
) = StatementView(
    audience = FolioText.audience(audience),
    days = response.days.map(::dayOf),
    charges = FolioText.money(response.chargesTotal),
    payments = FolioText.money(response.paymentsTotal),
    balance = FolioText.money(response.balance),
)

private fun dayOf(day: StatementDayResponse) =
    StatementDayView(
        date = FolioText.date(day.businessDate),
        lines = day.lines.map(::lineOf),
        charges = FolioText.money(day.charges),
        payments = FolioText.money(day.payments),
        balanceAtEnd = FolioText.money(day.balanceAtEnd),
    )

/** Só o motivo do bloco interno vem à tela: usuários, aprovadores e demais ids nunca. */
private fun lineOf(line: StatementLineResponse) =
    StatementLineView(
        date = FolioText.date(line.businessDate),
        description = line.description,
        kind = FolioText.statementKind(line.kind),
        amount = FolioText.money(line.amount),
        reason = ((line.internal as? Option.Some)?.value?.reason as? Option.Some)?.value?.takeIf { it.isNotBlank() },
    )

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
