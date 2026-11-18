package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.reception.RegistrationCardState
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.FolioStatementResponse
import com.zeny.desktop.sdk.model.PaymentResponse
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

private const val CREATE = "billing.postings.create"
private const val REVERSE = "billing.postings.reverse"
private const val RECEIVE = "billing.payments.receive"
private const val REFUND = "billing.payments.refund"
private const val REGULAR = "REGULAR"
private const val PAYMENT = "PAYMENT"

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

/**
 * Um lançamento na tabela; o [id] serve às ações e nunca aparece. A taxa de serviço vem logo abaixo do item dela.
 * [serviceCharge]: a taxa que vem recuada sob o item dela. [reversible]: um `REGULAR` ainda não estornado (só esse
 * oferece "Estornar").
 */
data class PostingRow(
    val id: String,
    val date: String,
    val description: String,
    val type: String,
    val quantity: String,
    val amount: String,
    val reversed: Boolean,
    val serviceCharge: Boolean,
    val reversible: Boolean,
)

/**
 * Um pagamento na tabela, na ordem do servidor; o [id] serve às ações e nunca aparece. [method] é o rótulo da forma e
 * [methodCode] a forma reconhecida (nula quando desconhecida); [card] é "<bandeira> final <últimos>" quando vem;
 * [refunded], o reembolsado formatado só quando maior que zero; [refundable], o reembolsável do servidor só num
 * recebimento em que é maior que zero. O `toString()` não mostra [card].
 */
data class PaymentRow(
    val id: String,
    val date: String,
    val type: String,
    val method: String,
    val methodCode: PaymentMethod?,
    val card: String?,
    val amount: String,
    val refunded: String?,
    val refundable: Money?,
    val deposit: Boolean,
) {
    override fun toString(): String = "PaymentRow(date=$date, type=$type, method=$method, amount=$amount)"
}

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
 *
 * "Lançar" ([startPosting], com `billing.postings.create`) e "Estornar" ([startReversal], com
 * `billing.postings.create` ou `billing.postings.reverse`, num `REGULAR` não estornado) abrem um diálogo por vez, com
 * a porta e a aprovação do [wiring], e nunca com a conta encerrada. Depois do sucesso, e no `FOLIO_CLOSED`, a conta é
 * relida; fechar um diálogo sem resultado conhecido também a relê, para conferir. O lançamento que o servidor roteou
 * para outra conta deixa o [notice] com o número dela. [onWritten] avisa quem lista as contas (a aba "Contas") depois
 * de cada releitura dessas, para a lista também reler saldo, limite e situação.
 *
 * Com a porta dos pagamentos no [wiring], os pagamentos são relidos junto da conta; a falha deles não esconde a conta.
 * "Receber" ([startPayment], com `billing.payments.receive` e a conta aberta) sugere o saldo do servidor quando
 * positivo. "Reembolsar" ([startRefund], com `billing.payments.receive` ou `billing.payments.refund`, num
 * recebimento com reembolsável e a conta aberta) passa pela aprovação do reembolso do [wiring]. O atalho "Abrir turno"
 * que os dois diálogos recebem fecha o diálogo antes de chamar o do [wiring].
 */
@Suppress("TooManyFunctions") // leitura, extrato, PDF e os diálogos de escrita (abrir, fechar e o resultado)
class FolioState(
    private val wiring: FolioWiring,
    scope: CoroutineScope,
    val folioId: String,
    private val onWritten: () -> Unit = {},
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

    /**
     * A audiência do extrato na tela; trocá-la tira da tela o extrato da anterior (o interno traz o motivo) e, com o
     * extrato à vista, o relê na nova.
     */
    var audience: StatementAudience
        get() = chosenAudience
        set(value) {
            if (value == chosenAudience) return
            chosenAudience = value
            statementGeneration++
            statement = null
            statementError = null
            statementLoading = false
            if (statementShown) showStatement()
        }

    /** O aviso do último lançamento roteado para outra conta. */
    var notice by mutableStateOf<String?>(null)
        private set

    var payments by mutableStateOf<List<PaymentRow>>(emptyList())
        private set

    /** A falha da leitura dos pagamentos; a conta continua à vista. */
    var paymentsError by mutableStateOf<String?>(null)
        private set

    /** O diálogo "Receber" aberto. */
    var payment by mutableStateOf<PaymentFormState?>(null)
        private set

    /** O diálogo "Reembolsar" aberto. */
    var refund by mutableStateOf<RefundState?>(null)
        private set

    /** O saldo da conta como o servidor o devolveu, para sugerir o valor do recebimento (nunca calculado). */
    private var balance: Money? = null

    /** O diálogo "Lançar" aberto. */
    var posting by mutableStateOf<PostingFormState?>(null)
        private set

    /** O diálogo "Estornar" aberto e o lançamento dele. */
    var reversal by mutableStateOf<ReversalState?>(null)
        private set

    var reversing by mutableStateOf<PostingRow?>(null)
        private set

    private val canCreate = CREATE in wiring.permissions
    private val canReverseAny = canCreate || REVERSE in wiring.permissions

    private val canReceiveAny = RECEIVE in wiring.permissions && wiring.payments != null
    private val canRefundAny =
        (RECEIVE in wiring.permissions || REFUND in wiring.permissions) && wiring.payments != null

    private val dialogOpen: Boolean
        get() = posting != null || reversal != null || payment != null || refund != null

    /** "Lançar": com `billing.postings.create` e a conta aberta. */
    val canPost: Boolean get() = !closed && canCreate && view?.closed == false

    /** "Estornar" na linha: um `REGULAR` não estornado, com uma das duas permissões e a conta aberta. */
    fun canReverse(row: PostingRow): Boolean = !closed && canReverseAny && view?.closed == false && row.reversible

    /** A seção "Pagamentos" existe: o [wiring] tem a porta deles. */
    val hasPayments: Boolean get() = wiring.payments != null

    /** "Receber": com a porta dos pagamentos, `billing.payments.receive` e a conta aberta. */
    val canReceive: Boolean get() = !closed && canReceiveAny && view?.closed == false

    /** "Reembolsar" na linha: um recebimento com reembolsável, com uma das duas permissões e a conta aberta. */
    fun canRefund(row: PaymentRow): Boolean = !closed && canRefundAny && view?.closed == false && row.refundable != null

    /** O extrato em PDF aberto (o visualizador da ficha). */
    var pdf by mutableStateOf<RegistrationCardState?>(null)
        private set

    val canOpenGuestPdf: Boolean get() = wiring.guestPdf != null
    val canOpenInternalPdf: Boolean get() = wiring.internalPdf != null

    fun start() = reload()

    /** Relê a conta, os lançamentos e os pagamentos; a leitura anterior em voo é descartada. */
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
                balance = moneyOrNull(folio.balance)
                postings = rowsOf(rows)
                readPayments(mine)
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

    /** Os pagamentos, com a mesma geração da conta; a falha deles não esconde a conta já à vista. */
    private suspend fun readPayments(mine: Int) {
        val source = wiring.payments ?: return
        val read =
            try {
                source.payments(folioId).map(::paymentRowOf)
            } catch (
                // A conta já está à vista: a falha dos pagamentos só diz isso.
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                null
            }
        currentCoroutineContext().ensureActive()
        if (mine != generation) return
        payments = read.orEmpty()
        paymentsError = if (read == null) FolioText.PAYMENTS_FAILED else null
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

    /** Abre "Lançar"; com outro diálogo aberto, nada. */
    fun startPosting() {
        if (!canPost || dialogOpen) return
        notice = null
        posting =
            PostingFormState(wiring.source, ownScope, folioId, ::posted, onFolioClosed = ::rereadAfterWrite).also {
                it.start()
            }
    }

    /** "Voltar" ou "Fechar" do lançamento; sem resultado conhecido, a conta é relida para conferir. */
    fun dismissPosting() {
        val form = posting ?: return
        if (form.busy) return
        form.close()
        posting = null
        if (form.unconfirmed || form.closeOnly) rereadAfterWrite()
    }

    /** Abre "Estornar" para o lançamento [postingId] da tabela; fora das regras ou com outro diálogo aberto, nada. */
    fun startReversal(postingId: String) {
        val row = postings.firstOrNull { it.id == postingId }
        if (row == null || !canReverse(row) || dialogOpen) return
        reversing = row
        reversal = ReversalState(wiring.source, ownScope, folioId, postingId, wiring.approve, ::reversalChanged)
    }

    /** "Voltar" ou "Fechar" do estorno; sem resultado conhecido, a conta é relida para conferir. */
    fun dismissReversal() {
        val form = reversal ?: return
        if (form.busy) return
        form.close()
        reversal = null
        reversing = null
        if (form.unconfirmed || form.closeOnly) rereadAfterWrite()
    }

    /** Abre "Receber", com o saldo positivo da conta sugerido; com outro diálogo aberto, nada. */
    fun startPayment() {
        val source = wiring.payments
        if (!canReceive || dialogOpen || source == null) return
        notice = null
        val shortcut = wiring.openShift?.let { open -> { closeThenOpenShift(::finishPayment, open) } }
        payment = PaymentFormState(source, ownScope, folioId, balance, shortcut, ::received, ::rereadAfterWrite)
    }

    /** "Voltar" ou "Fechar" do recebimento (e o atalho do turno); sem resultado conhecido, a conta é relida. */
    fun finishPayment() {
        val form = payment ?: return
        if (form.busy) return
        form.close()
        payment = null
        if (form.unconfirmed || form.closeOnly) rereadAfterWrite()
    }

    /** Abre "Reembolsar" para o recebimento [row], com o reembolsável sugerido; com outro diálogo aberto, nada. */
    fun startRefund(row: PaymentRow) {
        val source = wiring.payments
        if (!canRefund(row) || dialogOpen || source == null) return
        notice = null
        val shortcut = wiring.openShift?.let { open -> { closeThenOpenShift(::finishRefund, open) } }
        refund =
            RefundState(source, ownScope, folioId, row, wiring.approveRefund, shortcut, ::refunded, ::rereadAfterWrite)
    }

    /** "Voltar" ou "Fechar" do reembolso (e o atalho do turno); sem resultado conhecido, a conta é relida. */
    fun finishRefund() {
        val form = refund ?: return
        if (form.busy) return
        form.close()
        refund = null
        if (form.unconfirmed || form.closeOnly) rereadAfterWrite()
    }

    fun close() {
        closed = true
        jobs.cancel()
        posting?.close()
        reversal?.close()
        payment?.close()
        refund?.close()
        closePdf()
    }

    /** O diálogo sai antes do atalho: voltando à conta, não há diálogo morto. */
    private fun closeThenOpenShift(
        finish: () -> Unit,
        open: () -> Unit,
    ) {
        finish()
        open()
    }

    /** O reembolso deu certo: o diálogo sai, a conta e os pagamentos são relidos, e o aviso diz que foi feito. */
    private fun refunded() {
        refund?.close()
        refund = null
        rereadAfterWrite()
        notice = FolioText.REFUND_RECORDED
    }

    /** O recebimento deu certo: o diálogo sai, a conta é relida e o aviso traz o troco que o servidor devolveu. */
    private fun received(response: PaymentResponse) {
        payment?.close()
        payment = null
        rereadAfterWrite()
        val change = (response.change as? Option.Some)?.value?.let(::moneyOrNull)?.takeIf { it.amount.signum() > 0 }
        notice = FolioText.received(change?.let(MoneyInput::format))
    }

    /** O lançamento deu certo: o diálogo sai, a conta é relida e, roteado para outra conta, o aviso diz qual. */
    private fun posted(response: PostingResponse) {
        posting?.close()
        posting = null
        rereadAfterWrite()
        val target = response.folioId.toString()
        if (target != folioId) readRoutedNumber(target)
    }

    /** O estorno deu certo (o diálogo sai) ou a conta fechou (o diálogo fica com a mensagem): a conta é relida. */
    private fun reversalChanged() {
        val form = reversal
        if (form?.done == true) {
            form.close()
            reversal = null
            reversing = null
        }
        rereadAfterWrite()
    }

    /**
     * Depois de uma escrita (feita, recusada com a conta encerrada ou sem resultado conhecido): saldo, taxa e
     * lançamentos vêm do servidor, o extrato à vista é relido, e a lista das contas também.
     */
    private fun rereadAfterWrite() {
        if (closed) return
        reload()
        if (statementShown) showStatement()
        onWritten()
    }

    private fun readRoutedNumber(target: String) {
        if (closed) return
        ownScope.launch {
            val text =
                try {
                    FolioText.routedTo(wiring.source.folio(target).number)
                } catch (
                    // A releitura falhou: o aviso diz só que foi para outra conta.
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    FolioText.ROUTED_ELSEWHERE
                }
            currentCoroutineContext().ensureActive()
            notice = text
        }
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

/**
 * Os lançamentos na ordem do servidor, com cada taxa de serviço logo abaixo do item a que pertence. Os itens do topo
 * são os que não são taxa de um lançamento presente; a taxa que aponta outra taxa (ou um lançamento ausente) fica no
 * topo, nunca some.
 */
private fun rowsOf(postings: List<PostingResponse>): List<PostingRow> {
    val ids = postings.map { it.id }.toSet()
    val items = postings.filter { it.chargedItem() !in ids }.map { it.id }.toSet()
    val charges = postings.filter { it.chargedItem() in items }.groupBy { it.chargedItem() }
    return postings
        .filter { it.chargedItem() !in items }
        .flatMap { item ->
            listOf(rowOf(item, nested = false)) + charges[item.id].orEmpty().map { rowOf(it, nested = true) }
        }
}

private fun PostingResponse.chargedItem() = (serviceChargeForPostingId as? Option.Some)?.value

private fun rowOf(
    posting: PostingResponse,
    nested: Boolean,
) = PostingRow(
    id = posting.id.toString(),
    date = FolioText.date(posting.businessDate),
    description = posting.description,
    type = FolioText.postingType(posting.type),
    quantity = FolioText.quantity(posting.quantity),
    amount = FolioText.money(posting.amount),
    reversed = posting.isReversed(),
    serviceCharge = nested,
    reversible = posting.type == REGULAR && !posting.isReversed(),
)

private fun PostingResponse.isReversed() = (reversedBy as? Option.Some)?.value != null

private fun paymentRowOf(payment: PaymentResponse): PaymentRow {
    val brand = (payment.cardBrand as? Option.Some)?.value?.takeIf { it.isNotBlank() }
    val last4 = (payment.cardLast4 as? Option.Some)?.value?.takeIf { it.isNotBlank() }
    val refunded =
        (payment.refundedAmount as? Option.Some)?.value?.let(::moneyOrNull)?.takeIf { it.amount.signum() > 0 }
    val refundable =
        (payment.refundableAmount as? Option.Some)?.value?.let(::moneyOrNull)?.takeIf {
            payment.type == PAYMENT && it.amount.signum() > 0
        }
    return PaymentRow(
        id = payment.id.toString(),
        date = FolioText.date(payment.businessDate),
        type = FolioText.paymentType(payment.type),
        method = FolioText.paymentMethod(payment.method),
        methodCode = PaymentMethod.entries.firstOrNull { it.name == payment.method },
        card = listOfNotNull(brand, last4?.let { "final $it" }).joinToString(" ").takeIf { it.isNotEmpty() },
        amount = FolioText.money(payment.amount),
        refunded = refunded?.let(MoneyInput::format),
        refundable = refundable,
        deposit = payment.deposit,
    )
}

/** O decimal do servidor como `Money`; fora do formato do contrato, nulo. */
private fun moneyOrNull(decimal: String): Money? =
    try {
        Money.parse(decimal)
    } catch (_: IllegalArgumentException) {
        null
    }

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
