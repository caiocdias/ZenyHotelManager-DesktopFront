package com.zeny.desktop.app.cashier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.CashCount
import com.zeny.desktop.core.facade.CashMovementType
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.CashMovementResponse
import com.zeny.desktop.sdk.model.CashierShiftReportResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val SHIFT_CLOSED = "CASHIER_SHIFT_CLOSED"
private const val REASON_MAX = 200

/** Uma linha da contagem do fechamento: a forma e o texto digitado; nunca o esperado. */
data class CountEntry(
    val method: PaymentMethod,
    val label: String,
    val text: String,
)

/** O que a área faz com o resultado de um diálogo do caixa. */
internal class FormHooks<T>(
    /** A área fechou: nada mais é enviado nem aplicado. */
    val isClosed: () -> Boolean,
    /** A escrita deu certo. */
    val onDone: (T) -> Unit,
    /** O diálogo saiu; com `reread`, o resultado ficou desconhecido e o turno é relido para conferir. */
    val onLeave: (reread: Boolean) -> Unit,
    /** O servidor respondeu `CASHIER_SHIFT_CLOSED`, com o texto de pessoa. */
    val onShiftClosed: (String) -> Unit,
)

/** Os textos de uma escrita sem resultado: a repetir ([unknown]) e a conferir ([stored]). */
internal class UnconfirmedTexts(
    val unknown: String,
    val stored: String,
)

/**
 * Uma escrita idempotente de um diálogo do caixa (movimento ou fechamento). Leva uma `Idempotency-Key` por intenção:
 * a mesma `Intent` vale enquanto o resultado é desconhecido (`classifyWriteFailure`), e as entradas ficam travadas até
 * lá; resposta definitiva gera outra. O `5xx` guardado sob a chave deixa o estado [closeOnly]: só resta "Fechar"
 * ([leave]), que relê o turno para conferir. O `CASHIER_SHIFT_CLOSED` volta à área, que mostra a mensagem e relê.
 */
abstract class CashierWriteForm<T> internal constructor(
    private val scope: CoroutineScope,
    private val hooks: FormHooks<T>,
    private val texts: UnconfirmedTexts,
) {
    var busy by mutableStateOf(false)
        private set

    /** O resultado ficou desconhecido: repetir é a mesma intenção ("Tentar de novo"). */
    var unconfirmed by mutableStateOf(false)
        private set

    /** O servidor guardou um `5xx` sob a chave: o envio vira no-op, as entradas travam, e só resta "Fechar". */
    var closeOnly by mutableStateOf(false)
        private set

    /** A validação recusada ou o resultado do último envio que não deu certo. */
    var message by mutableStateOf<String?>(null)
        private set

    private var intent: Intent? = null

    /** As entradas estão travadas (em voo, resultado desconhecido ou fechado). */
    val locked: Boolean get() = hooks.isClosed() || busy || unconfirmed || closeOnly

    val canConfirm: Boolean get() = !hooks.isClosed() && !busy && !closeOnly

    /** O Esc fecha fora de uma escrita em curso e sem resultado a repetir (com [closeOnly], fecha). */
    val closesOnEscape: Boolean get() = !busy && (!unconfirmed || closeOnly)

    fun confirm() {
        if (!canConfirm) return
        val refused = refusal()
        if (refused != null) {
            message = refused
            return
        }
        busy = true
        message = null
        val current = intent ?: Intent().also { intent = it }
        scope.launch {
            try {
                val result = send(current)
                currentCoroutineContext().ensureActive()
                intent = null
                unconfirmed = false
                hooks.onDone(result)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                fail(e as? ApiError ?: ApiError.Network(e))
            } finally {
                busy = false
            }
        }
    }

    /** "Voltar" ou "Fechar": sai do diálogo; sem resultado conhecido, a área relê o turno para conferir. */
    fun leave() {
        if (busy) return
        hooks.onLeave(unconfirmed || closeOnly)
    }

    /** O texto que impede o envio, ou `null` quando as entradas valem. */
    protected abstract fun refusal(): String?

    protected abstract suspend fun send(intent: Intent): T

    private fun fail(failure: ApiError) {
        val outcome = classifyWriteFailure(failure)
        if (outcome != WriteFailure.DEFINITIVE) {
            // A repetição leva a mesma chave; com a resposta guardada, repetir não adianta: feche e confira.
            unconfirmed = true
            closeOnly = outcome == WriteFailure.UNKNOWN_STORED
            message = if (closeOnly) texts.stored else texts.unknown
            return
        }
        unconfirmed = false
        intent = null
        if ((failure as? ApiError.Problem)?.code == SHIFT_CLOSED) {
            hooks.onShiftClosed(failure.userMessage)
        } else {
            message = failure.userMessage
        }
    }
}

/**
 * O diálogo de sangria ou suprimento: valor (`MoneyField`, maior que zero, decimal exato) e motivo (obrigatório, até
 * 200 caracteres, enviado sem os espaços das pontas).
 */
class MovementForm internal constructor(
    val type: CashMovementType,
    private val shiftId: String,
    private val source: CashierSource,
    scope: CoroutineScope,
    hooks: FormHooks<CashMovementResponse>,
) : CashierWriteForm<CashMovementResponse>(
        scope,
        hooks,
        UnconfirmedTexts(CashierText.MOVEMENT_UNCONFIRMED, CashierText.MOVEMENT_SERVER_UNCONFIRMED),
    ) {
    private var amountText by mutableStateOf("")
    private var reasonText by mutableStateOf("")

    val title: String = CashierText.movementType(type)

    /** O valor digitado; não muda enquanto o envio não tem resultado. */
    var amount: String
        get() = amountText
        set(value) {
            if (!locked) amountText = value
        }

    /** O motivo digitado; não muda enquanto o envio não tem resultado. */
    var reason: String
        get() = reasonText
        set(value) {
            if (!locked) reasonText = value
        }

    /** Um valor bem formado, mas zero ou negativo. */
    val amountError: String?
        get() =
            typedAmount()?.takeIf { it.amount.signum() <= 0 }?.let { CashierText.AMOUNT_REQUIRED }

    override fun refusal(): String? =
        when {
            positiveAmount() == null -> CashierText.AMOUNT_REQUIRED
            reasonText.isBlank() -> CashierText.REASON_REQUIRED
            reasonText.trim().length > REASON_MAX -> CashierText.REASON_TOO_LONG
            else -> null
        }

    override suspend fun send(intent: Intent): CashMovementResponse =
        source.recordMovement(shiftId, type, checkNotNull(positiveAmount()), reasonText.trim(), intent)

    private fun typedAmount(): Money? = (MoneyInput.parse(amountText) as? InputResult.Valid)?.value

    private fun positiveAmount(): Money? = typedAmount()?.takeIf { it.amount.signum() > 0 }
}

/**
 * O fechamento cego: o valor contado de cada uma das seis formas do contrato. A forma vazia vale zero e não vai no
 * pedido. O formulário nunca carrega o esperado: só a forma e o texto digitado.
 */
class CloseShiftForm internal constructor(
    private val shiftId: String,
    private val source: CashierSource,
    scope: CoroutineScope,
    hooks: FormHooks<CashierShiftReportResponse>,
) : CashierWriteForm<CashierShiftReportResponse>(
        scope,
        hooks,
        UnconfirmedTexts(CashierText.CLOSE_UNCONFIRMED, CashierText.CLOSE_SERVER_UNCONFIRMED),
    ) {
    private var texts by mutableStateOf(PaymentMethod.entries.associateWith { "" })

    val entries: List<CountEntry>
        get() = PaymentMethod.entries.map { CountEntry(it, CashierText.method(it.name), texts.getValue(it)) }

    /** O valor contado de [method]; não muda enquanto o envio não tem resultado. */
    fun setCount(
        method: PaymentMethod,
        text: String,
    ) {
        if (!locked) texts = texts + (method to text)
    }

    override fun refusal(): String? =
        if (texts.values.any { it.isNotBlank() && MoneyInput.parse(it) !is InputResult.Valid }) {
            CashierText.COUNTS_INVALID
        } else {
            null
        }

    override suspend fun send(intent: Intent): CashierShiftReportResponse = source.closeShift(shiftId, counts(), intent)

    private fun counts(): List<CashCount> =
        PaymentMethod.entries.mapNotNull { method ->
            (MoneyInput.parse(texts.getValue(method)) as? InputResult.Valid)?.value?.let { CashCount(method, it) }
        }
}
