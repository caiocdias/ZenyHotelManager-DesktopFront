package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.AvailabilityDayResponse
import com.zeny.desktop.sdk.model.RoomTypeAvailabilityResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val DEFAULT_DAYS = 30

/** Uma linha do calendário: um tipo de UH e as células dos dias que o servidor devolveu para ele. */
data class CalendarRow(
    val roomTypeId: String,
    val name: String,
    val cells: Map<BusinessDate, AvailabilityDayResponse>,
)

/**
 * Estado do calendário de disponibilidade: uma janela de [days] datas a partir de [from], uma linha por tipo de UH.
 *
 * - As colunas ([dates]) seguem a janela, não o que o servidor devolveu: um tipo sem dias ainda tem a sua linha.
 * - Cada carga tem uma geração: a resposta de uma carga velha, ou que chega depois do [close], é descartada.
 * - A seleção sobrevive à recarga enquanto o tipo e a data seguem na janela.
 *
 * Como o `ReservationSearchState`: as mutações correm no escopo da UI; [close] cancela só o que é daqui.
 */
class AvailabilityState(
    private val source: AvailabilitySource,
    scope: CoroutineScope,
    private val days: Int = DEFAULT_DAYS,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)

    private var started = false
    private var closed = false
    private var generation = 0
    private var load: Job? = null

    var businessDay by mutableStateOf<BusinessDate?>(null)
        private set

    var from by mutableStateOf<BusinessDate?>(null)
        private set

    var rows by mutableStateOf<List<CalendarRow>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    /** O texto de pessoa da última falha; `null` quando a carga vigente deu certo. */
    var error by mutableStateOf<String?>(null)
        private set

    var selected by mutableStateOf<Pair<String, BusinessDate>?>(null)
        private set

    /** As [days] datas da janela, a partir de [from]; vazia até o dia operacional ser lido. */
    val dates: List<BusinessDate>
        get() {
            val first = from ?: return emptyList()
            return (0 until days).map { BusinessDate(first.value.plusDays(it.toLong())) }
        }

    val selectedDay: AvailabilityDayResponse?
        get() {
            val (typeId, date) = selected ?: return null
            return rows.firstOrNull { it.roomTypeId == typeId }?.cells?.get(date)
        }

    val canGoBack: Boolean
        get() = from != null && from != businessDay

    /** Lê o dia operacional e carrega a primeira janela; chamado de novo, não faz nada. */
    fun start() {
        if (closed || started) return
        started = true
        reload()
    }

    /** Relê a janela; sem [from] (o [start] falhou), relê antes o dia operacional. */
    fun reload() {
        if (closed) return
        launchLoad(from)
    }

    fun previous() {
        val current = from ?: return
        val first = businessDay ?: return
        val target = BusinessDate(current.value.minusDays(days.toLong()))
        moveTo(if (target.value.isBefore(first.value)) first else target)
    }

    fun next() {
        val current = from ?: return
        moveTo(BusinessDate(current.value.plusDays(days.toLong())))
    }

    fun select(
        roomTypeId: String,
        date: BusinessDate,
    ) {
        selected = roomTypeId to date
    }

    /** A área saiu de cena: cancela a carga e não carrega mais. */
    fun close() {
        closed = true
        generation++
        jobs.cancel()
        loading = false
    }

    private fun moveTo(target: BusinessDate) {
        if (closed || target == from) return
        from = target
        dropSelectionOutsideWindow()
        launchLoad(target)
    }

    /** Uma carga nova invalida a anterior; [start] é `null` quando falta ler o dia operacional. */
    private fun launchLoad(start: BusinessDate?) {
        val mine = ++generation
        load?.cancel()
        loading = true
        error = null
        load =
            ownScope.launch {
                try {
                    val first = start ?: source.businessDay().date.also { if (mine == generation) adopt(it) }
                    val last = BusinessDate(first.value.plusDays(days.toLong() - 1))
                    val response = source.availability(first, last)
                    if (mine != generation) return@launch
                    rows = response.roomTypes.map { it.toRow() }
                    loading = false
                    dropSelectionOutsideWindow()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    if (mine != generation) return@launch
                    error = (e as? ApiError ?: ApiError.Network(e)).userMessage
                    loading = false
                }
            }
    }

    private fun adopt(day: BusinessDate) {
        businessDay = day
        from = day
    }

    private fun dropSelectionOutsideWindow() {
        val (typeId, date) = selected ?: return
        if (date !in dates || rows.none { it.roomTypeId == typeId }) selected = null
    }

    private fun RoomTypeAvailabilityResponse.toRow() =
        CalendarRow(
            roomTypeId = roomTypeId.toString(),
            name = name,
            cells = days.associateBy { BusinessDate.parse(it.date) },
        )
}
