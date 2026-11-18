package com.zeny.desktop.app.cashier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.CashierShiftResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val OPERATE = "billing.cashier.operate"
private const val FORBIDDEN = 403
private const val ALREADY_OPEN = "CASHIER_SHIFT_ALREADY_OPEN"

/** Um ponto de caixa ativo que a abertura pode usar. */
data class CashPointOption(
    val id: String,
    val name: String,
)

/** Uma sangria ou um suprimento do turno, com o valor do servidor em BRL. */
data class MovementLine(
    val type: String,
    val amount: String,
    val reason: String,
)

/** O esperado de uma forma de pagamento, como o servidor o mandou. */
data class ExpectedLine(
    val method: String,
    val amount: String,
)

/**
 * O turno corrente na tela: número, ponto, data de abertura, fundo e movimentos. O esperado ([expected],
 * [expectedTotal]) só vem quando o servidor o mostra (`expectedVisible`); no turno cego, vazio e nulo.
 */
data class ShiftView(
    val id: String,
    val number: String,
    val cashPointName: String,
    val openedOn: String,
    val openingFloat: String,
    val movements: List<MovementLine>,
    val expected: List<ExpectedLine>,
    val expectedTotal: String?,
)

/**
 * Estado da área de caixa: o turno corrente do usuário e, sem turno, o formulário de abertura (ponto de caixa e fundo
 * de troco). Sem `billing.cashier.operate` nas [permissions] da casca, não lê nada e só mostra [notice].
 *
 * A abertura leva uma `Idempotency-Key` por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido
 * (`classifyWriteFailure`), e as entradas ficam travadas até lá; resposta definitiva gera outra. O `5xx` guardado
 * sob a chave deixa o estado [closeOnly]: só resta "Fechar" ([dismiss]), que relê o turno corrente para conferir.
 * O `CASHIER_SHIFT_ALREADY_OPEN` mostra a mensagem e relê o turno corrente.
 *
 * [onShiftChanged] roda depois de abrir e em toda releitura que troca o turno (não na primeira leitura). As mutações
 * correm no escopo da UI; [close] cancela o que é daqui, e a resposta que chega depois dele é descartada.
 */
@Suppress("TooManyFunctions") // leitura, pontos de caixa, abertura, falha e o "Fechar"
class CashierState(
    private val source: CashierSource,
    scope: CoroutineScope,
    permissions: Set<String>,
    private val onShiftChanged: () -> Unit,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    val canOperate: Boolean = OPERATE in permissions

    /** "Seu perfil não opera o caixa." quando o conjunto não tem `billing.cashier.operate`. */
    val notice: String? = if (canOperate) null else CashierText.NO_OPERATE

    var loading by mutableStateOf(false)
        private set

    /** O texto de pessoa da falha da leitura do turno corrente (com "Tentar de novo", [reload]). */
    var loadError by mutableStateOf<String?>(null)
        private set

    /** O turno corrente; nulo sem turno ou antes da leitura. */
    var shift by mutableStateOf<ShiftView?>(null)
        private set

    var cashPoints by mutableStateOf<List<CashPointOption>>(emptyList())
        private set

    /** A falha da lista de pontos de caixa; o `403` diz que o perfil não pode listá-los. */
    var cashPointsError by mutableStateOf<String?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /** O servidor guardou um `5xx` sob a chave: a abertura vira no-op, as entradas travam, e só resta "Fechar". */
    var closeOnly by mutableStateOf(false)
        private set

    /** O resultado da última abertura que não deu certo. */
    var message by mutableStateOf<String?>(null)
        private set

    private var chosenPoint by mutableStateOf<String?>(null)
    private var floatText by mutableStateOf("")
    private var unconfirmed by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private var closed = false
    private var generation = 0
    private var intent: Intent? = null

    /** O ponto de caixa escolhido; só um da lista, e não muda enquanto a abertura não tem resultado. */
    var cashPointId: String?
        get() = chosenPoint
        set(value) {
            if (locked || cashPoints.none { it.id == value }) return
            chosenPoint = value
        }

    /** O fundo de troco digitado (`MoneyField`); não muda enquanto a abertura não tem resultado. */
    var openingFloat: String
        get() = floatText
        set(value) {
            if (!locked) floatText = value
        }

    val showsOpenForm: Boolean
        get() = canOperate && loaded && !loading && loadError == null && shift == null

    val canOpen: Boolean
        get() = !closed && showsOpenForm && !busy && !closeOnly && chosenPoint != null && parsedFloat() != null

    /** As entradas da abertura estão travadas (em voo, resultado desconhecido ou fechado). */
    val locked: Boolean get() = closed || busy || unconfirmed || closeOnly

    fun start() = reload()

    /** Relê o turno corrente e, sem turno, os pontos de caixa; a leitura anterior em voo é descartada. */
    fun reload() {
        if (closed || !canOperate) return
        val mine = ++generation
        loading = true
        loadError = null
        ownScope.launch {
            try {
                val current = source.currentShift()
                currentCoroutineContext().ensureActive()
                if (mine != generation) return@launch
                if (current == null) readCashPoints(mine)
                if (mine == generation) applyRead(current?.let(::viewOf))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                if (mine == generation) loadError = apiErrorOf(e).userMessage
            } finally {
                if (mine == generation) loading = false
            }
        }
    }

    fun open() {
        val point = chosenPoint
        val amount = parsedFloat()
        if (!canOpen || point == null || amount == null) return
        busy = true
        message = null
        val current = intent ?: Intent().also { intent = it }
        ownScope.launch {
            try {
                val response = source.openShift(point, amount, current)
                currentCoroutineContext().ensureActive()
                opened(response)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                fail(apiErrorOf(e))
            } finally {
                busy = false
            }
        }
    }

    /** "Fechar" do [closeOnly]: a próxima abertura é outra intenção, e o turno corrente é relido para conferir. */
    fun dismiss() {
        if (closed || !closeOnly) return
        closeOnly = false
        unconfirmed = false
        message = null
        intent = null
        reload()
    }

    fun close() {
        closed = true
        generation++
        jobs.cancel()
    }

    private suspend fun readCashPoints(mine: Int) {
        try {
            val points = source.cashPoints().map { CashPointOption(it.id.toString(), it.name) }
            currentCoroutineContext().ensureActive()
            if (mine != generation) return
            cashPoints = points
            cashPointsError = null
            if (points.none { it.id == chosenPoint }) chosenPoint = null
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            if (mine != generation) return
            val error = apiErrorOf(e)
            cashPoints = emptyList()
            chosenPoint = null
            cashPointsError =
                if (error is ApiError.Problem && error.status == FORBIDDEN) {
                    CashierText.CASH_POINTS_FORBIDDEN
                } else {
                    error.userMessage
                }
        }
    }

    private fun applyRead(view: ShiftView?) {
        val changed = loaded && view?.id != shift?.id
        shift = view
        loaded = true
        if (changed) onShiftChanged()
    }

    private fun opened(response: CashierShiftResponse) {
        intent = null
        unconfirmed = false
        message = null
        shift = viewOf(response)
        loaded = true
        onShiftChanged()
    }

    private fun fail(failure: ApiError) {
        val outcome = classifyWriteFailure(failure)
        if (outcome != WriteFailure.DEFINITIVE) {
            // A repetição leva a mesma chave; com a resposta guardada, repetir não adianta: feche e confira.
            unconfirmed = true
            closeOnly = outcome == WriteFailure.UNKNOWN_STORED
            message = if (closeOnly) CashierText.OPEN_SERVER_UNCONFIRMED else CashierText.OPEN_UNCONFIRMED
            return
        }
        unconfirmed = false
        intent = null
        message = failure.userMessage
        if ((failure as? ApiError.Problem)?.code == ALREADY_OPEN) reload()
    }

    private fun parsedFloat(): Money? = (MoneyInput.parse(floatText) as? InputResult.Valid)?.value

    private fun viewOf(response: CashierShiftResponse): ShiftView {
        val visible = response.expectedVisible
        val expected = (response.expected as? Option.Some)?.value.orEmpty()
        return ShiftView(
            id = response.id.toString(),
            number = response.number,
            cashPointName = response.cashPointName,
            openedOn = CashierText.date(response.openedOn),
            openingFloat = CashierText.money(response.openingFloat),
            movements =
                response.movements.map {
                    MovementLine(CashierText.movementType(it.type), CashierText.money(it.amount), it.reason)
                },
            expected =
                if (visible) {
                    expected.map { ExpectedLine(CashierText.method(it.method), CashierText.money(it.expected)) }
                } else {
                    emptyList()
                },
            expectedTotal =
                if (visible) (response.expectedTotal as? Option.Some)?.value?.let(CashierText::money) else null,
        )
    }
}

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
