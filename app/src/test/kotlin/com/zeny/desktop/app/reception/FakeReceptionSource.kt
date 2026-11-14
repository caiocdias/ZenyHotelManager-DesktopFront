package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.CheckInInput
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.NewGuest
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.EarlyLateQuoteResponse
import com.zeny.desktop.sdk.model.GuestDocumentResponse
import com.zeny.desktop.sdk.model.GuestPreferencesDto
import com.zeny.desktop.sdk.model.GuestResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomSuggestionResponse
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

/** Uma execução de `checkIn` registrada pelo falso: a `Intent.key` em uso e o token de aprovação (se houve). */
data class CheckInRun(
    val key: String,
    val token: String?,
)

/** Uma chamada de `assignRoom` registrada pelo falso. */
data class AssignCall(
    val id: String,
    val roomId: String,
    val doNotMove: Boolean?,
)

/** Uma chamada de `checkIn` (a criação da ação) registrada pelo falso; o `Intent` é o da tela. */
data class CheckInCall(
    val id: String,
    val input: CheckInInput,
    val intent: Intent,
)

/**
 * Servidor de mentira para a recepção. As respostas ficam nos mapas e variáveis abaixo; cada chamada é registrada.
 * Com `hold*` ligado, a leitura só responde quando o teste solta o portão (respostas atrasadas, fora de ordem).
 * Os eventos são postos com [emit] e a assinatura termina com [end] (com erro: falha do fluxo).
 */
@Suppress("TooManyFunctions") // falso da porta, que espelha as operações dela
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

    /** Erro de uma lista só; [dayPageError], se houver, vale para todas. */
    val dayPageErrors = mutableMapOf<DayList, Exception>()
    var reservationError: Exception? = null
    var historyError: Exception? = null
    var assignmentsError: Exception? = null
    var holderError: Exception? = null

    /** Erro de um titular só (pelo id); [holderError], se houver, vale para todos. */
    val holderErrors = mutableMapOf<String, Exception>()
    var ratePlanError: Exception? = null
    var roomTypesError: Exception? = null

    var holdBusinessDay = false
    var holdDayPage = false
    var holdReservation = false
    var holdHolder = false
    var holdHistory = false
    var holdAssignments = false
    var holdRatePlan = false
    val heldBusinessDays = mutableListOf<CompletableDeferred<Unit>>()
    val heldDayPages = mutableListOf<CompletableDeferred<Unit>>()
    val heldReservations = mutableListOf<Pair<String, CompletableDeferred<Unit>>>()
    val heldHolders = mutableListOf<Pair<String, CompletableDeferred<Unit>>>()
    val heldHistories = mutableListOf<Pair<String, CompletableDeferred<Unit>>>()
    val heldAssignments = mutableListOf<Pair<String, CompletableDeferred<Unit>>>()
    val heldRatePlans = mutableListOf<Pair<String, CompletableDeferred<Unit>>>()

    val dayPageCalls = mutableListOf<DayPageCall>()

    /** O `limit` de cada chamada de `dayPage`, na ordem de [dayPageCalls]. */
    val dayPageLimits = mutableListOf<Int>()
    val reservationCalls = mutableListOf<String>()
    val historyCalls = mutableListOf<String>()
    val assignmentCalls = mutableListOf<String>()
    val holderCalls = mutableListOf<String>()
    val ratePlanCalls = mutableListOf<String>()
    var permissionsCalls = 0
    var businessDayCalls = 0
    var roomTypesCalls = 0

    // As ações da recepção. Respostas por operação; erro por operação (`*Error`); `hold*` prende a chamada até o teste
    // soltar o portão em `held*`. As reservas devolvidas por alocar, soltar e "não mover" saem de [reservations].
    val suggestions = mutableMapOf<String, List<RoomSuggestionResponse>>()
    val roomStatusList = mutableListOf<RoomStatusResponse>()
    val quotes = mutableMapOf<String, EarlyLateQuoteResponse>()
    val guestsByName = mutableMapOf<String, List<GuestPick>>()
    val guestsByDocument = mutableMapOf<String, List<GuestPick>>()
    var createdGuest: GuestPick? = null
    var checkInResponse: CheckInResponse? = null

    var suggestionsError: Exception? = null
    var roomStatusesError: Exception? = null
    var assignError: Exception? = null
    var unassignError: Exception? = null
    var doNotMoveError: Exception? = null
    var quoteError: Exception? = null
    var searchError: Exception? = null
    var createGuestError: Exception? = null

    /** `checkIn`: a primeira execução sem token falha com `ApprovalRequired`; a seguinte, com token, passa. */
    var checkInRequiresApproval: ApiError.ApprovalRequired? = null

    /** `checkIn`: o resultado de cada execução, na ordem (`null` = sucesso); acabando a fila, sucesso. */
    val checkInOutcomes = ArrayDeque<Throwable?>()

    var holdSuggestions = false
    var holdAssign = false
    var holdCheckIn = false
    var holdSearch = false
    var holdCreateGuest = false
    val heldSuggestions = mutableListOf<CompletableDeferred<Unit>>()
    val heldAssigns = mutableListOf<CompletableDeferred<Unit>>()
    val heldCheckIns = mutableListOf<CompletableDeferred<Unit>>()
    val heldSearches = mutableListOf<CompletableDeferred<Unit>>()
    val heldCreateGuests = mutableListOf<CompletableDeferred<Unit>>()

    val suggestionCalls = mutableListOf<String>()
    val assignCalls = mutableListOf<AssignCall>()
    val unassignCalls = mutableListOf<String>()
    val doNotMoveCalls = mutableListOf<Pair<String, Boolean>>()
    val quoteCalls = mutableListOf<String>()
    val checkInCalls = mutableListOf<CheckInCall>()

    /** Cada execução (`run`) de uma ação de `checkIn`, na ordem. */
    val checkInRuns = mutableListOf<CheckInRun>()
    val searchCalls = mutableListOf<String>()

    /** As buscas por documento: tipo e país emissor (o número nunca fica registrado). */
    val documentSearchCalls = mutableListOf<Pair<String, String?>>()
    var createGuestCalls = 0
    var roomStatusesCalls = 0

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
        val snapshot = businessDay
        val error = businessDayError
        if (holdBusinessDay) CompletableDeferred<Unit>().also { heldBusinessDays += it }.await()
        error?.let { throw it }
        return snapshot
    }

    override suspend fun dayPage(
        list: DayList,
        date: BusinessDate,
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse> {
        dayPageCalls += DayPageCall(list, date, cursor)
        dayPageLimits += limit
        val snapshot = pages[list to cursor] ?: Page(emptyList(), null)
        val error = dayPageError ?: dayPageErrors[list]
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
        val snapshot = histories[id].orEmpty()
        val error = historyError
        if (holdHistory) CompletableDeferred<Unit>().also { heldHistories += id to it }.await()
        error?.let { throw it }
        return snapshot
    }

    override suspend fun roomAssignments(id: String): List<RoomAssignmentResponse> {
        assignmentCalls += id
        val snapshot = assignments[id].orEmpty()
        val error = assignmentsError
        if (holdAssignments) CompletableDeferred<Unit>().also { heldAssignments += id to it }.await()
        error?.let { throw it }
        return snapshot
    }

    override suspend fun holder(guestId: String): HolderSummary {
        holderCalls += guestId
        val snapshot = holders[guestId]
        val error = holderError ?: holderErrors[guestId]
        if (holdHolder) CompletableDeferred<Unit>().also { heldHolders += guestId to it }.await()
        error?.let { throw it }
        return checkNotNull(snapshot) { "Titular $guestId fora do servidor de mentira" }
    }

    override suspend fun ratePlanName(id: String): String {
        ratePlanCalls += id
        val error = ratePlanError
        if (holdRatePlan) CompletableDeferred<Unit>().also { heldRatePlans += id to it }.await()
        error?.let { throw it }
        return checkNotNull(ratePlans[id]) { "Plano $id fora do servidor de mentira" }
    }

    override suspend fun roomTypes(): List<RoomTypeResponse> {
        roomTypesCalls++
        roomTypesError?.let { throw it }
        return types.toList()
    }

    override suspend fun roomSuggestions(id: String): List<RoomSuggestionResponse> {
        suggestionCalls += id
        val snapshot = suggestions[id].orEmpty()
        val error = suggestionsError
        if (holdSuggestions) CompletableDeferred<Unit>().also { heldSuggestions += it }.await()
        error?.let { throw it }
        return snapshot
    }

    override suspend fun roomStatuses(): List<RoomStatusResponse> {
        roomStatusesCalls++
        roomStatusesError?.let { throw it }
        return roomStatusList.toList()
    }

    override suspend fun assignRoom(
        id: String,
        roomId: String,
        doNotMove: Boolean?,
    ): ReservationDetailResponse {
        assignCalls += AssignCall(id, roomId, doNotMove)
        val error = assignError
        if (holdAssign) CompletableDeferred<Unit>().also { heldAssigns += it }.await()
        error?.let { throw it }
        return checkNotNull(reservations[id]) { "Reserva $id fora do servidor de mentira" }
    }

    override suspend fun unassignRoom(id: String): ReservationDetailResponse {
        unassignCalls += id
        unassignError?.let { throw it }
        return checkNotNull(reservations[id]) { "Reserva $id fora do servidor de mentira" }
    }

    override suspend fun setDoNotMove(
        id: String,
        doNotMove: Boolean,
    ): ReservationDetailResponse {
        doNotMoveCalls += id to doNotMove
        doNotMoveError?.let { throw it }
        return checkNotNull(reservations[id]) { "Reserva $id fora do servidor de mentira" }
    }

    override suspend fun earlyLateQuote(id: String): EarlyLateQuoteResponse {
        quoteCalls += id
        quoteError?.let { throw it }
        return checkNotNull(quotes[id]) { "Cotação de $id fora do servidor de mentira" }
    }

    override fun checkIn(
        id: String,
        input: CheckInInput,
        intent: Intent,
    ): ApprovableAction<CheckInResponse> {
        checkInCalls += CheckInCall(id, input, intent)
        return ApprovableAction { token ->
            checkInRuns += CheckInRun(intent.key, token)
            val approval = checkInRequiresApproval
            if (holdCheckIn) CompletableDeferred<Unit>().also { heldCheckIns += it }.await()
            if (token == null && approval != null) throw approval
            checkInOutcomes.removeFirstOrNull()?.let { throw it }
            ApiResponse(checkNotNull(checkInResponse) { "Resposta do check-in fora do servidor de mentira" }, null, 200)
        }
    }

    override suspend fun searchGuests(name: String): List<GuestPick> {
        searchCalls += name
        val snapshot = guestsByName[name].orEmpty()
        val error = searchError
        if (holdSearch) CompletableDeferred<Unit>().also { heldSearches += it }.await()
        error?.let { throw it }
        return snapshot
    }

    override suspend fun searchGuestsByDocument(
        type: String,
        number: String,
        issuingCountry: String?,
    ): List<GuestPick> {
        documentSearchCalls += type to issuingCountry
        val snapshot = guestsByDocument[number].orEmpty()
        val error = searchError
        if (holdSearch) CompletableDeferred<Unit>().also { heldSearches += it }.await()
        error?.let { throw it }
        return snapshot
    }

    override suspend fun createGuest(input: NewGuest): GuestPick {
        createGuestCalls++
        val error = createGuestError
        if (holdCreateGuest) CompletableDeferred<Unit>().also { heldCreateGuests += it }.await()
        error?.let { throw it }
        return checkNotNull(createdGuest) { "Hóspede criado fora do servidor de mentira" }
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
