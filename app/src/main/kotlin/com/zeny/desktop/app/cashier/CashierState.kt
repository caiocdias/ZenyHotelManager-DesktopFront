package com.zeny.desktop.app.cashier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.WriteFailure
import com.zeny.desktop.core.error.classifyWriteFailure
import com.zeny.desktop.core.facade.CashMovementType
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.input.InputResult
import com.zeny.desktop.design.input.MoneyInput
import com.zeny.desktop.sdk.model.CashierShiftReportResponse
import com.zeny.desktop.sdk.model.CashierShiftResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val OPERATE = "billing.cashier.operate"
private const val REPORT = "billing.shifts.read"
private const val FORBIDDEN = 403
private const val ALREADY_OPEN = "CASHIER_SHIFT_ALREADY_OPEN"

/**
 * Estado da área de caixa: o turno corrente do usuário e, sem turno, o formulário de abertura (ponto de caixa e fundo
 * de troco). Sem `billing.cashier.operate` nas [permissions] da casca, não lê nada e só mostra [notice].
 *
 * A abertura leva uma `Idempotency-Key` por intenção: a mesma `Intent` vale enquanto o resultado é desconhecido
 * (`classifyWriteFailure`), e as entradas ficam travadas até lá; resposta definitiva gera outra. O `5xx` guardado
 * sob a chave deixa o estado [closeOnly]: só resta "Fechar" ([dismiss]), que relê o turno corrente para conferir.
 * O `CASHIER_SHIFT_ALREADY_OPEN` mostra a mensagem e relê o turno corrente.
 *
 * Com turno, a sangria e o suprimento ([startMovement]) e o fechamento cego ([startClose]) abrem diálogos com a
 * própria intenção ([CashierWriteForm]); o `CASHIER_SHIFT_CLOSED` fecha o diálogo, mostra [message] e relê. Fechado, o
 * [report] do servidor fica na tela; o "Ver relatório" ([showReport], com `billing.shifts.read`) o relê.
 *
 * [onShiftChanged] roda depois de abrir e de fechar, na primeira leitura que dá certo e em toda releitura que troca o
 * turno. As mutações correm no escopo da UI; [close] cancela o que é daqui, e a resposta que chega depois dele é
 * descartada.
 */
@Suppress("TooManyFunctions") // leitura, pontos de caixa, abertura, diálogos, relatório e o "Fechar"
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

    /** A falha da última abertura, o turno que fechou noutro lugar ou a falha da releitura do relatório. */
    var message by mutableStateOf<String?>(null)
        private set

    private var chosenPoint by mutableStateOf<String?>(null)
    private var floatText by mutableStateOf("")
    private var unconfirmed by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private var cashPointsForbidden by mutableStateOf(false)

    /** O servidor disse que o turno fechou: a próxima releitura avisa a casca, dê certo ou falhe (uma vez só). */
    private var shiftEnded = false
    private var closed = false
    private var generation = 0
    private var intent: Intent? = null

    /** O diálogo de sangria ou suprimento aberto. */
    var movement by mutableStateOf<MovementForm?>(null)
        private set

    /** O formulário de fechamento aberto. */
    var closing by mutableStateOf<CloseShiftForm?>(null)
        private set

    /** O relatório na tela: o do fechamento que acabou de dar certo ou o relido por [showReport]. */
    var report by mutableStateOf<ReportView?>(null)
        private set

    var reportLoading by mutableStateOf(false)
        private set

    /** O turno que fechou (ou sumiu na releitura) nesta área: o "Ver relatório" relê o dele. */
    private var lastShiftId by mutableStateOf<String?>(null)

    private val canReadReport: Boolean = REPORT in permissions

    /** "Ver relatório": com `billing.shifts.read`, um turno fechado aqui e nenhum relatório na tela. */
    val canViewReport: Boolean
        get() = !closed && canReadReport && lastShiftId != null && report == null && !reportLoading

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

    /** O fundo negativo é recusado aqui; o zero vale (o contrato aceita 0,00). */
    val floatError: String?
        get() = if ((typedFloat()?.amount?.signum() ?: 0) < 0) CashierText.NEGATIVE_FLOAT else null

    /** A lista de pontos falhou por um motivo que repetir pode resolver (o `403` não). */
    val cashPointsRetryable: Boolean
        get() = cashPointsError != null && !cashPointsForbidden

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
                if (mine == generation) applyRead(current?.let(::shiftViewOf))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                if (mine == generation) {
                    loadError = apiErrorOf(e).userMessage
                    if (consumeShiftEnded()) onShiftChanged()
                }
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

    /** Abre o diálogo de sangria ([CashMovementType.WITHDRAWAL]) ou de suprimento do turno corrente. */
    fun startMovement(type: CashMovementType) {
        val id = shift?.id
        if (!canStartDialog() || id == null) return
        message = null
        movement =
            MovementForm(
                type,
                id,
                source,
                ownScope,
                hooks(onDone = { _ ->
                    movement = null
                    reload()
                }),
            )
    }

    /** Abre o fechamento cego do turno corrente. */
    fun startClose() {
        val id = shift?.id
        if (!canStartDialog() || id == null) return
        message = null
        closing = CloseShiftForm(id, source, ownScope, hooks(onDone = { response -> afterClose(id, response) }))
    }

    /** "Ver relatório": relê o relatório do turno fechado aqui. */
    fun showReport() {
        val id = lastShiftId
        if (!canViewReport || id == null) return
        reportLoading = true
        message = null
        ownScope.launch {
            try {
                val response = source.report(id)
                currentCoroutineContext().ensureActive()
                report = reportViewOf(response)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                message = apiErrorOf(e).userMessage
            } finally {
                reportLoading = false
            }
        }
    }

    /** Sai do relatório; o "Ver relatório" o relê. */
    fun dismissReport() {
        report = null
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
            cashPointsForbidden = false
            if (points.none { it.id == chosenPoint }) chosenPoint = null
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            if (mine != generation) return
            val error = apiErrorOf(e)
            cashPoints = emptyList()
            chosenPoint = null
            cashPointsForbidden = error is ApiError.Problem && error.status == FORBIDDEN
            cashPointsError = if (cashPointsForbidden) CashierText.CASH_POINTS_FORBIDDEN else error.userMessage
        }
    }

    private fun applyRead(view: ShiftView?) {
        // A primeira leitura também avisa: o topo pode estar em "não verificado" ou velho.
        val changed = consumeShiftEnded() || !loaded || view?.id != shift?.id
        shift?.id?.takeIf { it != view?.id }?.let { lastShiftId = it }
        shift = view
        loaded = true
        if (changed) onShiftChanged()
    }

    private fun opened(response: CashierShiftResponse) {
        intent = null
        unconfirmed = false
        message = null
        shift = shiftViewOf(response)
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

    private fun canStartDialog(): Boolean =
        !closed && canOperate && movement == null && closing == null && report == null

    private fun <T> hooks(onDone: (T) -> Unit) =
        FormHooks(
            isClosed = { closed },
            onDone = onDone,
            onLeave = { reread ->
                movement = null
                closing = null
                if (reread) reload()
            },
            onShiftClosed = { text ->
                movement = null
                closing = null
                message = text
                shiftEnded = true
                reload()
            },
        )

    private fun consumeShiftEnded(): Boolean = shiftEnded.also { shiftEnded = false }

    /** O fechamento deu certo: o relatório do servidor na tela, o topo avisado e, sem turno, a abertura relida. */
    private fun afterClose(
        shiftId: String,
        response: CashierShiftReportResponse,
    ) {
        closing = null
        shift = null
        lastShiftId = shiftId
        report = reportViewOf(response)
        onShiftChanged()
        reload()
    }

    private fun typedFloat(): Money? = (MoneyInput.parse(floatText) as? InputResult.Valid)?.value

    private fun parsedFloat(): Money? = typedFloat()?.takeIf { it.amount.signum() >= 0 }
}

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)
