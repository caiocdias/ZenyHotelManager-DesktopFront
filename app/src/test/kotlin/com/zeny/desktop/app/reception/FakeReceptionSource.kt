package com.zeny.desktop.app.reception

import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.GuestDocumentResponse
import com.zeny.desktop.sdk.model.GuestPreferencesDto
import com.zeny.desktop.sdk.model.GuestResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.uuid.Uuid

/** Um hóspede fictício para os testes: só os campos que o resumo do titular lê variam. */
fun guestOf(
    fullName: String,
    socialName: String? = null,
    documents: List<Pair<GuestDocumentResponse.Type, String>> = emptyList(),
) = GuestResponse(
    createdAt = "2026-11-01T10:00:00Z",
    documents =
        documents.map { (type, masked) ->
            GuestDocumentResponse(
                createdAt = "2026-11-01T10:00:00Z",
                id = Uuid.random(),
                issuingCountry = "BR",
                maskedNumber = masked,
                type = type,
            )
        },
    fullName = fullName,
    hasSensitiveData = false,
    id = Uuid.random(),
    preferences = GuestPreferencesDto(),
    socialName = socialName?.let { Option.Some(it) } ?: Option.Undefined,
    status = GuestResponse.Status.ACTIVE,
    updatedAt = "2026-11-01T10:00:00Z",
    vip = false,
)

/** Uma chamada de `dayPage` registrada pelo falso. */
data class DayPageCall(
    val list: DayList,
    val date: BusinessDate,
    val cursor: String?,
)

/**
 * Servidor de mentira para a recepção. As respostas ficam nos mapas e variáveis abaixo; cada chamada é registrada.
 * Com `hold*` ligado, a leitura só responde quando o teste solta o portão (respostas atrasadas, fora de ordem).
 * Os eventos são postos com [emit] e a assinatura termina com [end] (com erro: falha do fluxo).
 */
class FakeReceptionSource : ReceptionSource {
    var permissions: Set<String> =
        setOf(
            "reservations.reservations.read",
            "guests.profiles.read",
            "rates.plans.read",
            "rooms.status.read",
            "endofday.runs.read",
        )
    var businessDay: BusinessDay = BusinessDay(BusinessDate.parse("2026-11-13"), null)

    /** Páginas por lista e cursor de entrada (`null` é a primeira página). */
    val pages = mutableMapOf<Pair<DayList, String?>, Page<ReservationListItemResponse>>()
    val reservations = mutableMapOf<String, ReservationDetailResponse>()
    val histories = mutableMapOf<String, List<ReservationHistoryResponse>>()
    val assignments = mutableMapOf<String, List<RoomAssignmentResponse>>()
    val holders = mutableMapOf<String, HolderSummary>()
    val ratePlans = mutableMapOf<String, String>()
    val types = mutableListOf<RoomTypeResponse>()

    var permissionsError: Exception? = null
    var businessDayError: Exception? = null
    var dayPageError: Exception? = null
    var reservationError: Exception? = null
    var historyError: Exception? = null
    var assignmentsError: Exception? = null
    var holderError: Exception? = null
    var ratePlanError: Exception? = null
    var roomTypesError: Exception? = null

    var holdDayPage = false
    var holdReservation = false
    var holdHolder = false
    val heldDayPages = mutableListOf<CompletableDeferred<Unit>>()
    val heldReservations = mutableListOf<Pair<String, CompletableDeferred<Unit>>>()
    val heldHolders = mutableListOf<Pair<String, CompletableDeferred<Unit>>>()

    val dayPageCalls = mutableListOf<DayPageCall>()
    val reservationCalls = mutableListOf<String>()
    val historyCalls = mutableListOf<String>()
    val assignmentCalls = mutableListOf<String>()
    val holderCalls = mutableListOf<String>()
    val ratePlanCalls = mutableListOf<String>()
    var permissionsCalls = 0
    var businessDayCalls = 0
    var roomTypesCalls = 0

    var subscriptions = 0
    var activeSubscriptions = 0
    var cancelledSubscriptions = 0
    val subscribedTopics = mutableListOf<Set<String>>()
    private var channel = Channel<RealtimeEvent>(Channel.UNLIMITED)

    fun emit(event: RealtimeEvent) {
        channel.trySend(event)
    }

    fun end(error: Throwable? = null) {
        channel.close(error)
        // A assinatura seguinte (um `retry`) recebe um canal novo.
        channel = Channel(Channel.UNLIMITED)
    }

    override suspend fun permissions(): Set<String> {
        permissionsCalls++
        permissionsError?.let { throw it }
        return permissions
    }

    override suspend fun businessDay(): BusinessDay {
        businessDayCalls++
        businessDayError?.let { throw it }
        return businessDay
    }

    override suspend fun dayPage(
        list: DayList,
        date: BusinessDate,
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse> {
        dayPageCalls += DayPageCall(list, date, cursor)
        val snapshot = pages[list to cursor] ?: Page(emptyList(), null)
        val error = dayPageError
        if (holdDayPage) CompletableDeferred<Unit>().also { heldDayPages += it }.await()
        error?.let { throw it }
        return snapshot
    }

    override suspend fun reservation(id: String): ReservationDetailResponse {
        reservationCalls += id
        val snapshot = reservations[id]
        val error = reservationError
        if (holdReservation) CompletableDeferred<Unit>().also { heldReservations += id to it }.await()
        error?.let { throw it }
        return checkNotNull(snapshot) { "Reserva $id fora do servidor de mentira" }
    }

    override suspend fun history(id: String): List<ReservationHistoryResponse> {
        historyCalls += id
        historyError?.let { throw it }
        return histories[id].orEmpty()
    }

    override suspend fun roomAssignments(id: String): List<RoomAssignmentResponse> {
        assignmentCalls += id
        assignmentsError?.let { throw it }
        return assignments[id].orEmpty()
    }

    override suspend fun holder(guestId: String): HolderSummary {
        holderCalls += guestId
        val snapshot = holders[guestId]
        val error = holderError
        if (holdHolder) CompletableDeferred<Unit>().also { heldHolders += guestId to it }.await()
        error?.let { throw it }
        return checkNotNull(snapshot) { "Titular $guestId fora do servidor de mentira" }
    }

    override suspend fun ratePlanName(id: String): String {
        ratePlanCalls += id
        ratePlanError?.let { throw it }
        return checkNotNull(ratePlans[id]) { "Plano $id fora do servidor de mentira" }
    }

    override suspend fun roomTypes(): List<RoomTypeResponse> {
        roomTypesCalls++
        roomTypesError?.let { throw it }
        return types.toList()
    }

    override fun events(topics: Set<String>): Flow<RealtimeEvent> =
        flow {
            subscriptions++
            activeSubscriptions++
            subscribedTopics += topics
            val events = channel
            try {
                for (event in events) emit(event)
            } catch (cancelled: CancellationException) {
                cancelledSubscriptions++
                throw cancelled
            } finally {
                activeSubscriptions--
            }
        }
}
