package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.folio.FolioSource
import com.zeny.desktop.app.folio.FolioText
import com.zeny.desktop.app.folio.FolioWiring
import com.zeny.desktop.app.folio.FolioWrite
import com.zeny.desktop.app.folio.FolioWriteForm
import com.zeny.desktop.app.folio.PaymentFormState
import com.zeny.desktop.app.folio.PaymentSource
import com.zeny.desktop.app.folio.WriteOutcome
import com.zeny.desktop.app.folio.isCapturable
import com.zeny.desktop.app.folio.moneyOrNull
import com.zeny.desktop.core.facade.CheckOutResult
import com.zeny.desktop.core.facade.FolioDue
import com.zeny.desktop.core.facade.LateCheckOutQuote
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.PaymentResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val RECEIVE = "billing.payments.receive"
private const val LATE_NOT_OFFERED = "EARLY_LATE_NOT_OFFERED"

/** A reserva já saiu (outra recepção fez o check-out) ou sumiu: o assistente fecha e o painel é relido. */
private val RESERVATION_GONE = setOf("RESERVATION_TRANSITION_NOT_ALLOWED", "RESERVATION_NOT_FOUND")

/** O que falta para sair, numa conta da reserva; o [folioId] serve às ações e nunca aparece. */
sealed interface CheckOutPending {
    val number: String
    val text: String
    val folioId: String

    /** A conta com saldo diferente de zero: "Falta receber <valor>" ou "Conta credora: <valor> a devolver". */
    data class Balance(
        override val number: String,
        override val text: String,
        override val folioId: String,
    ) : CheckOutPending

    /** Uma caução ainda capturável na conta. */
    data class PreAuthorization(
        override val number: String,
        override val text: String,
        override val folioId: String,
    ) : CheckOutPending
}

/**
 * Uma conta do saldo devido (`409 FOLIO_BALANCE_NOT_ZERO`), como o servidor a mandou: o número, o texto e, quando
 * "Receber" vale para ela, o rótulo do botão com o devido. O [folioId] nunca aparece.
 */
data class CheckOutDueLine(
    val folioId: String,
    val number: String,
    val text: String,
    val receive: String?,
)

/** A última conferência: o saldo de cada conta e uma pendência por caução capturável. */
private class PendingRead(
    val balances: List<FolioDue>,
    val preAuthorizations: List<CheckOutPending.PreAuthorization>,
)

/**
 * O assistente de check-out da reserva [reservationId]. O [reload] confere o que falta (as contas da reserva e as
 * cauções de cada uma, pelas portas do [folios]) e lê a cotação do late; o front não calcula nada: saldo, devido,
 * total e late são os do servidor. Sem o [folios] ou sem a porta dos pagamentos, não lê pendências ([unreadable]) e
 * ainda conclui (o servidor confere os saldos e libera as cauções). A falha da conferência ([error]) e a caução
 * capturável ([blocked]) bloqueiam "Concluir check-out"; a falha da cotação, não.
 *
 * O check-out ([conclude]) passa pelo `FolioWrite`: uma `Idempotency-Key` por intenção, a mesma enquanto o resultado
 * é desconhecido (a caixa do late trava, e a repetição reenvia o valor guardado quando a intenção nasceu). O saldo
 * devido é resposta definitiva: o [due] mostra o devido por conta e cada conta devedora oferece "Receber", que abre o
 * recebimento ([payment]) com o devido sugerido. Depois de receber, o assistente relê, a conta mostra
 * "Recebido. Conclua o check-out." ([dueReceived]) e, se o devido incluía o late, a caixa trava ligada, até a próxima
 * resposta do check-out. [onClosed] é a única saída para o painel: o aviso e se o painel e o detalhe são relidos.
 * Depois dela (ou de [close]), nenhuma resposta tardia muda o estado.
 */
@Suppress("TooManyFunctions") // leitura, late, check-out, recebimento do devido e as saídas
class CheckOutState(
    private val source: CheckOutSource,
    private val folios: FolioWiring?,
    scope: CoroutineScope,
    private val reservationId: String,
    private val onClosed: (notice: String?, reloadPanel: Boolean) -> Unit,
) : FolioWriteForm {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)
    private val write =
        FolioWrite(ownScope, CheckOutText.UNCONFIRMED, CheckOutText.SERVER_UNCONFIRMED, null, ::refused)

    private var closed by mutableStateOf(false)
    private var generation = 0
    private var readingPending by mutableStateOf(false)
    private var readingQuote by mutableStateOf(false)
    private var read by mutableStateOf<PendingRead?>(null)
    private var charge by mutableStateOf(false)

    /** O late da intenção em curso (o que foi enviado quando ela nasceu). */
    private var sentFee: Money? = null

    /** O late enviado no check-out que devolveu o [due]. */
    private var dueFee: Money? = null

    /** O late que o devido recebido incluía: a caixa fica ligada com ele até a próxima resposta do check-out. */
    private var heldFee by mutableStateOf<Money?>(null)

    private val payments: PaymentSource? = folios?.payments
    private val canReceive = folios != null && RECEIVE in folios.permissions && payments != null

    /** Sem o [folios] ou sem a porta dos pagamentos, o assistente não lê as pendências e diz isso. */
    val unreadable: String? = CheckOutText.UNREADABLE.takeIf { payments == null }

    /** A primeira conferência terminou (bem ou mal). */
    var loaded by mutableStateOf(false)
        private set

    /** A conferência falhou: "Concluir" fica bloqueado até a releitura ("Tentar de novo"). */
    var error by mutableStateOf<String?>(null)
        private set

    /** O late check-out oferecido; nulo sem oferta ou com a leitura falhando. */
    var quote by mutableStateOf<LateCheckOutQuote?>(null)
        private set

    /** O saldo devido da última resposta do check-out, só com as contas de saldo diferente de zero. */
    var due by mutableStateOf<CheckOutResult.BalanceDue?>(null)
        private set

    /** As contas com o devido recebido desde a última resposta do check-out. */
    var dueReceived by mutableStateOf<Set<String>>(emptySet())
        private set

    /** O recebimento do devido aberto (um diálogo por vez). */
    var payment by mutableStateOf<PaymentFormState?>(null)
        private set

    /** O aviso do último recebimento (com o troco do servidor). */
    var notice by mutableStateOf<String?>(null)
        private set

    /** O que falta, na ordem: os saldos e depois as cauções capturáveis. */
    val pending: List<CheckOutPending>
        get() {
            val last = read ?: return emptyList()
            val balances =
                last.balances.filter { it.balance.amount.signum() != 0 }.map {
                    CheckOutPending.Balance(it.number, balanceText(it), it.folioId)
                }
            return balances + last.preAuthorizations
        }

    /** Por que "Concluir" está bloqueado pela caução capturável. */
    val blocked: String?
        get() = CheckOutText.CAPTURE_FIRST.takeIf { read?.preAuthorizations?.isNotEmpty() == true }

    /** A caixa do late está travada: em voo, resultado desconhecido, fechado ou com o late do devido recebido. */
    val lateLocked: Boolean get() = write.locked || heldFee != null

    /** "Cobrar o late check-out": só liga com o late oferecido; mudar sem trava é outra intenção. */
    var chargeLate: Boolean
        get() = charge
        set(value) {
            val offered = !value || quote != null
            if (lateLocked || value == charge || !offered) return
            charge = value
            write.edited()
        }

    /** O late oferecido: "Late check-out até <HH:mm>: <valor>". */
    val lateOffer: String? get() = quote?.let(CheckOutText::lateOffer)

    /** A linha do late que o check-out vai lançar, acima do total; só com a caixa ligada. */
    val lateLine: String? get() = lateFee()?.let(CheckOutText::lateLine)

    /** As contas do [due], cada uma com o texto e, quando vale, "Receber <devido>". */
    val dueLines: List<CheckOutDueLine>
        get() =
            due?.folios.orEmpty().map {
                val received = it.folioId in dueReceived
                val receivable = canReceive && !received && it.balance.amount.signum() > 0
                CheckOutDueLine(
                    folioId = it.folioId,
                    number = it.number,
                    text = if (received) CheckOutText.DUE_RECEIVED else CheckOutText.balance(it.balance),
                    receive = it.balance.takeIf { receivable }?.let(CheckOutText::receive),
                )
            }

    /** O total devido que o servidor mandou. */
    val dueTotal: String? get() = due?.total?.let(CheckOutText::total)

    /** "Concluir check-out": conferido sem falha, sem caução capturável, sem leitura nem diálogo em curso. */
    val canConclude: Boolean
        get() =
            !closed && write.canSend && payment == null && loaded && !readingPending && !readingQuote &&
                error == null && blocked == null

    override val busy: Boolean get() = write.busy

    override val unconfirmed: Boolean get() = write.unconfirmed

    override val closeOnly: Boolean get() = write.closeOnly

    override val message: String? get() = write.message

    override val canConfirm: Boolean get() = canConclude

    override val closesOnEscape: Boolean get() = write.closesOnEscape && payment == null

    override fun confirm() = conclude()

    /** Relê as contas, as cauções e a cotação; a leitura anterior em voo é descartada. */
    fun reload() {
        if (closed) return
        val mine = ++generation
        readQuote(mine)
        readPending(mine)
    }

    /** Envia o check-out (ou repete a intenção em curso, com o late guardado nela). */
    fun conclude() {
        if (!canConclude) return
        val fee = if (write.unconfirmed) sentFee else lateFee()
        sentFee = fee
        notice = null
        write.send({ WriteOutcome.Done(source.checkOut(reservationId, fee, it)) }, ::concluded)
    }

    /** "Receber" na conta devedora [folioId] do [due], com o devido dela sugerido; fora das regras, nada. */
    fun receiveDue(folioId: String) {
        val receivable = dueLines.any { it.folioId == folioId && it.receive != null }
        val owed =
            due
                ?.folios
                .orEmpty()
                .firstOrNull { receivable && it.folioId == folioId }
                ?.balance
        val port = payments
        if (!canOpenPayment() || owed == null || port == null) return
        notice = null
        val shortcut = folios?.openShift?.let { open -> { closeThenOpenShift(open) } }
        payment = PaymentFormState(port, ownScope, folioId, owed, shortcut, { received(folioId, it) }, ::reload)
    }

    /** "Voltar" ou "Fechar" do recebimento; sem resultado conhecido, o assistente é relido. */
    fun finishPayment() {
        val form = payment ?: return
        if (form.busy) return
        form.close()
        payment = null
        if (form.unconfirmed || form.closeOnly) reload()
    }

    /** Fecha o assistente; com um devido recebido e o check-out não concluído, o painel avisa. */
    fun close() = finish(CheckOutText.PAID_NOT_CHECKED_OUT.takeIf { dueReceived.isNotEmpty() }, false)

    /** Um diálogo por vez, nunca com o check-out em voo ou com o resultado desconhecido. */
    private fun canOpenPayment(): Boolean = !closed && payment == null && !write.locked

    private fun lateFee(): Money? =
        when {
            write.unconfirmed -> sentFee
            heldFee != null -> heldFee
            charge -> quote?.fee
            else -> null
        }

    private fun balanceText(due: FolioDue): String =
        if (due.folioId in dueReceived && due.balance.amount.signum() < 0) {
            CheckOutText.DUE_RECEIVED
        } else {
            CheckOutText.balance(due.balance)
        }

    private fun readQuote(mine: Int) {
        readingQuote = true
        ownScope.launch {
            val fresh =
                try {
                    source.lateCheckOutQuote(reservationId)
                } catch (
                    // Sem a cotação, nada de late aparece e o check-out segue sem ele.
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    null
                }
            currentCoroutineContext().ensureActive()
            if (mine != generation) return@launch
            quote = fresh
            readingQuote = false
            if (fresh == null && !lateLocked) charge = false
        }
    }

    private fun readPending(mine: Int) {
        val wiring = folios
        val port = payments
        if (wiring == null || port == null) {
            loaded = true
            return
        }
        readingPending = true
        ownScope.launch {
            val fresh =
                try {
                    readAll(wiring.source, port)
                } catch (
                    // A falha de qualquer leitura bloqueia "Concluir", com o texto e "Tentar de novo".
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    null
                }
            currentCoroutineContext().ensureActive()
            if (mine != generation) return@launch
            read = fresh
            error = if (fresh == null) CheckOutText.CHECK_FAILED else null
            loaded = true
            readingPending = false
        }
    }

    /** As contas da reserva e as cauções de cada uma; um saldo fora do formato do contrato é falha da leitura. */
    private suspend fun readAll(
        source: FolioSource,
        port: PaymentSource,
    ): PendingRead {
        val reservationFolios = source.reservationFolios(reservationId)
        val balances = reservationFolios.map { FolioDue(it.id.toString(), it.number, Money.parse(it.balance)) }
        val text = if (canReceive) CheckOutText.PRE_AUTHORIZATION_PENDING else CheckOutText.ASK_RECEIVER
        val preAuthorizations =
            reservationFolios.flatMap { folio ->
                val id = folio.id.toString()
                port
                    .preAuthorizations(id)
                    .filter { isCapturable(it.status, moneyOrNull(it.capturableAmount)) }
                    .map { CheckOutPending.PreAuthorization(folio.number, text, id) }
            }
        return PendingRead(balances, preAuthorizations)
    }

    private fun concluded(result: CheckOutResult) {
        responded()
        when (result) {
            CheckOutResult.Done -> {
                finish(CheckOutText.DONE, true)
            }

            is CheckOutResult.BalanceDue -> {
                due = result.copy(folios = result.folios.filter { it.balance.amount.signum() != 0 })
                dueFee = sentFee
            }
        }
    }

    /**
     * A recusa definitiva (o texto do catálogo já está em [message]): a reserva que já saiu ou sumiu fecha o assistente
     * e relê o painel; as outras (a reserva alterada, as recusas do late, o devido ilegível) relêem o assistente
     * inteiro, e o late não oferecido desliga a caixa. Nada é reenviado.
     */
    private fun refused(code: String) {
        responded()
        due = null
        if (code in RESERVATION_GONE) {
            finish(write.message, true)
            return
        }
        if (code == LATE_NOT_OFFERED) charge = false
        reload()
    }

    /** A resposta do check-out encerra o "recebido" e a trava do late que vinham do devido anterior. */
    private fun responded() {
        heldFee = null
        dueReceived = emptySet()
    }

    /** O devido da conta [folioId] foi recebido: o aviso com o troco e a releitura do assistente. */
    private fun received(
        folioId: String,
        response: PaymentResponse,
    ) {
        payment?.close()
        payment = null
        dueReceived = dueReceived + folioId
        dueFee?.let {
            heldFee = it
            charge = true
        }
        val change = (response.change as? Option.Some)?.value?.let(::moneyOrNull)?.takeIf { it.amount.signum() > 0 }
        notice = FolioText.received(change?.let(MoneyInput::format))
        reload()
    }

    /** "Abrir turno" do recebimento: o diálogo e o assistente saem antes do atalho. */
    private fun closeThenOpenShift(open: () -> Unit) {
        finishPayment()
        close()
        open()
    }

    private fun finish(
        text: String?,
        reloadPanel: Boolean,
    ) {
        if (closed) return
        closed = true
        payment?.close()
        payment = null
        write.close()
        jobs.cancel()
        onClosed(text, reloadPanel)
    }

    override fun toString(): String =
        "CheckOutState(loaded=$loaded, pending=${pending.size}, busy=$busy, unconfirmed=$unconfirmed)"
}
