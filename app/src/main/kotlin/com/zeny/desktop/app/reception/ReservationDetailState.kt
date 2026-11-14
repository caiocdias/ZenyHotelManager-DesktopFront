package com.zeny.desktop.app.reception

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.parseApiInstant
import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

private const val UNKNOWN = "—"
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val SEPARATOR = " · "

/** Uma linha do histórico, já em texto: nunca autor, id nem retrato cru da reserva. */
data class HistoryLine(
    val at: String,
    val title: String,
    val detail: String,
)

/** Uma alocação de UH, já em texto. */
data class AssignmentLine(
    val room: String,
    val period: String,
    val reason: String,
    val state: String,
    val doNotMove: Boolean,
)

/** Um hóspede da estadia, já em texto; o documento é só o mascarado pelo servidor. */
data class GuestLine(
    val name: String,
    val document: String?,
    val primary: Boolean,
) {
    // Dado pessoal: nunca em log nem em `toString`.
    override fun toString(): String = "GuestLine(primary=$primary)"
}

/** O detalhe da reserva, todo em texto pronto para a tela. */
@Suppress("LongParameterList") // o detalhe é um quadro de campos da tela; cada um é um texto
data class ReservationDetailView(
    val code: String,
    val status: ChipModel,
    val holder: String,
    val document: String?,
    val period: String,
    val roomType: String,
    val room: String,
    val guests: String,
    val plan: String,
    val total: String,
    val alerts: List<String>,
    val assignments: List<AssignmentLine>,
    val assignmentsError: ApiError?,
    val history: List<HistoryLine>,
    val historyError: ApiError?,
    val stayGuests: List<GuestLine> = emptyList(),
    val stayGuestsError: ApiError? = null,
) {
    // Titular, documento e alertas são dados pessoais: nunca em log nem em `toString`.
    override fun toString(): String =
        "ReservationDetailView(code=$code, status=$status, period=$period, roomType=$roomType, room=$room, " +
            "guests=$guests, plan=$plan, total=$total, assignments=${assignments.size}, " +
            "assignmentsError=${assignmentsError != null}, history=${history.size}, " +
            "historyError=${historyError != null}, stayGuests=${stayGuests.size}, " +
            "stayGuestsError=${stayGuestsError != null})"
}

/** O que uma carga completa entrega: a reserva crua (para as ações) e o detalhe em texto. */
private class Loaded(
    val reservation: ReservationDetailResponse,
    val view: ReservationDetailView,
)

/** O que a montagem do detalhe lê de uma vez, antes do titular e do plano. */
private class FirstReads(
    val reservation: ReservationDetailResponse,
    val history: Result<List<ReservationHistoryResponse>>,
    val assignments: Result<List<RoomAssignmentResponse>>,
    val zone: ZoneId?,
    val roomTypes: Map<String, String>,
)

/**
 * Estado do detalhe de uma reserva: lê a reserva, o histórico e as alocações em paralelo e, depois da reserva, o
 * titular e o plano. Abrir outra reserva cancela a carga anterior e a resposta atrasada dela nunca entra. A falha do
 * histórico ou das alocações é só da seção; a da reserva é [error].
 *
 * Como o `DayPanelState`: as mutações correm no escopo da UI (uma thread só). [close] cancela só a carga daqui.
 */
class ReservationDetailState(
    private val source: ReceptionSource,
    private val scope: CoroutineScope,
) {
    var openId by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    /** Só da leitura da reserva; o erro de uma seção fica na [view]. */
    var error by mutableStateOf<ApiError?>(null)
        private set
    var view by mutableStateOf<ReservationDetailView?>(null)
        private set

    /** A última resposta de `reservation(id)` da aberta (nula fechado ou com outro id carregando); fora da [view]. */
    var reservation by mutableStateOf<ReservationDetailResponse?>(null)
        private set

    private var job: Job? = null
    private var generation = 0

    fun open(id: String) {
        openId = id
        view = null
        reservation = null
        start(id)
    }

    /** Relê a reserva aberta, mantendo o que já está na tela; sem nada aberto, nada faz. */
    fun reload() {
        openId?.let(::start)
    }

    fun close() {
        generation++
        job?.cancel()
        job = null
        openId = null
        view = null
        reservation = null
        error = null
        loading = false
    }

    private fun start(id: String) {
        job?.cancel()
        val mine = ++generation
        error = null
        loading = true
        job =
            scope.launch {
                try {
                    val result = load(id)
                    if (mine == generation) {
                        view = result.first?.view ?: view
                        reservation = result.first?.reservation ?: reservation
                        error = result.second
                    }
                } finally {
                    if (mine == generation) loading = false
                }
            }
    }

    private suspend fun load(id: String): Pair<Loaded?, ApiError?> =
        coroutineScope {
            val first =
                try {
                    readFirst(id)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    currentCoroutineContext().ensureActive()
                    return@coroutineScope null to apiErrorOf(e)
                }
            try {
                val holder = async { holderOf(first.reservation.holderGuestId.toString()) }
                val plan = async { planName(first.reservation) }
                val stay = async { stayGuestLines(first.reservation) }
                Loaded(first.reservation, assemble(first, holder.await(), plan.await(), stay.await())) to null
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                currentCoroutineContext().ensureActive()
                null to apiErrorOf(e)
            }
        }

    /** Os hóspedes da estadia, na ordem do servidor; outro erro da API falha só a seção. */
    private suspend fun stayGuestLines(detail: ReservationDetailResponse): Result<List<GuestLine>> =
        attempt {
            coroutineScope {
                detail.reservation.guests
                    .map { guest -> guest to async { holderOf(guest.guestId.toString()) } }
                    .map { (guest, summary) ->
                        val holder = summary.await()
                        GuestLine(holder?.name ?: UNKNOWN, holder?.document, guest.primary)
                    }
            }
        }

    private suspend fun readFirst(id: String): FirstReads =
        coroutineScope {
            val reservation = async { source.reservation(id) }
            val history = async { attempt { source.history(id) } }
            val assignments = async { attempt { source.roomAssignments(id) } }
            val zone = async { attempt { source.businessDay() }.getOrNull()?.zone }
            val types = async { roomTypeNames() }
            FirstReads(reservation.await(), history.await(), assignments.await(), zone.await(), types.await())
        }

    private suspend fun roomTypeNames(): Map<String, String> =
        attempt { source.roomTypes() }
            .getOrDefault(emptyList())
            .filter { it.name.isNotBlank() }
            .associate { it.id.toString() to it.name }

    /** O titular; sem acesso a ele (`403`) ou apagado (`404`), nulo. Os demais erros falham o detalhe. */
    private suspend fun holderOf(id: String): HolderSummary? =
        try {
            source.holder(id)
        } catch (e: ApiError.Problem) {
            if (e.status != HTTP_FORBIDDEN && e.status != HTTP_NOT_FOUND) throw e
            null
        }

    /** O nome do plano; se a leitura falhar com erro da API, a categoria dele. */
    private suspend fun planName(reservation: ReservationDetailResponse): String =
        try {
            source.ratePlanName(reservation.reservation.ratePlanId.toString())
        } catch (_: ApiError.Problem) {
            ReceptionText.planCategory(reservation.reservation.ratePlanCategory.value)
        }

    private fun assemble(
        first: FirstReads,
        holder: HolderSummary?,
        plan: String,
        stay: Result<List<GuestLine>>,
    ): ReservationDetailView {
        val detail = first.reservation
        val reservation = detail.reservation
        val assignments = first.assignments.getOrDefault(emptyList())
        val current =
            assignments.firstOrNull { it.active }
                ?: (reservation.roomAssignment as? Option.Some)?.value?.takeIf { it.active }
        val roomNumber = (current?.roomNumber as? Option.Some)?.value?.takeIf { it.isNotBlank() }
        return ReservationDetailView(
            code = reservation.code,
            status = ReceptionText.status(reservation.status),
            holder = holder?.name ?: UNKNOWN,
            document = holder?.document,
            period = ReceptionText.period(reservation.arrival, reservation.departure, reservation.dayUse),
            roomType = first.roomTypes[reservation.roomTypeId.toString()] ?: UNKNOWN,
            room = if (current == null) "Sem UH" else "UH ${roomNumber ?: UNKNOWN}",
            guests = ReceptionText.guests(reservation.adults, reservation.childAges.size),
            plan = plan,
            total = ReceptionText.money(reservation.total),
            alerts = detail.restrictionAlerts.map { "Hóspede com restrição: ${it.reason}" },
            assignments = assignments.map { assignmentLine(it, reservation.dayUse) },
            assignmentsError = first.assignments.exceptionOrNull()?.let(::apiErrorOf),
            history = historyLines(reservation.createdAt, first.history.getOrDefault(emptyList()), first.zone),
            historyError = first.history.exceptionOrNull()?.let(::apiErrorOf),
            stayGuests = stay.getOrDefault(emptyList()),
            stayGuestsError = stay.exceptionOrNull()?.let(::apiErrorOf),
        )
    }
}

/** Falha de leitura como [Result]; o cancelamento nunca vira resultado. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }

private fun apiErrorOf(e: Throwable): ApiError = e as? ApiError ?: ApiError.Network(e)

private fun assignmentLine(
    a: RoomAssignmentResponse,
    reservationDayUse: Boolean,
): AssignmentLine {
    val number = (a.roomNumber as? Option.Some)?.value?.takeIf { it.isNotBlank() }
    val release = (a.releaseReason as? Option.Some)?.value
    return AssignmentLine(
        room = "UH ${number ?: UNKNOWN}",
        period = ReceptionText.period(a.startsOn, a.endsOn, a.dayUse && reservationDayUse),
        reason = ReceptionText.assignmentReason(a.reason),
        state =
            when {
                a.active -> "Ativa"
                release == null -> "Solta"
                else -> "Solta$SEPARATOR${ReceptionText.releaseReason(release)}"
            },
        doNotMove = a.doNotMove,
    )
}

/** A criação não gera entrada no servidor: uma linha sintetizada abre o histórico, e as demais vêm da mais antiga. */
private fun historyLines(
    createdAt: String,
    entries: List<ReservationHistoryResponse>,
    zone: ZoneId?,
): List<HistoryLine> {
    val created = HistoryLine(ReceptionText.instant(createdAt, zone) ?: UNKNOWN, "Reserva criada", "")
    val ordered = entries.sortedWith(compareBy({ instantOf(it.occurredAt) }, { it.version }))
    return listOf(created) +
        ordered.map { entry ->
            HistoryLine(
                at =
                    ReceptionText.instant(entry.occurredAt, zone)
                        ?: "${ReceptionText.date(entry.businessDate)} (data operacional)",
                title = ReceptionText.historyKind(entry.kind),
                detail = historyDetail(entry),
            )
        }
}

private fun historyDetail(entry: ReservationHistoryResponse): String {
    val fields = ReceptionText.changedFields(entry.changedFields)
    val situation =
        "Situação: ${ReceptionText.status(
            entry.before.status,
        ).label} → ${ReceptionText.status(entry.after.status).label}"
    return listOfNotNull(
        "Alterou: $fields".takeIf { fields.isNotEmpty() },
        situation.takeIf { entry.before.status != entry.after.status },
        "Com aprovação de superior".takeIf { (entry.approvalId as? Option.Some)?.value != null },
    ).joinToString(SEPARATOR)
}

private fun instantOf(iso: String): Instant =
    try {
        parseApiInstant(iso)
    } catch (_: IllegalArgumentException) {
        Instant.EPOCH
    }
