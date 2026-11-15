package com.zeny.desktop.app.session

import com.zeny.desktop.app.reception.GuestPick
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reception.RegistrationCards
import com.zeny.desktop.app.reception.toGuestPick
import com.zeny.desktop.app.reception.toHolderSummary
import com.zeny.desktop.app.rooms.NaturalOrder
import com.zeny.desktop.app.rooms.RoomsSource
import com.zeny.desktop.app.traces.RoomOption
import com.zeny.desktop.app.traces.TracesSource
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.CheckInInput
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.NewGuest
import com.zeny.desktop.core.facade.NewTrace
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.facade.RoomMoveInput
import com.zeny.desktop.core.facade.TraceChange
import com.zeny.desktop.core.facade.TraceFilter
import com.zeny.desktop.core.facade.TraceVersion
import com.zeny.desktop.core.facade.WalkInInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.EarlyLateQuoteResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomMoveResponse
import com.zeny.desktop.sdk.model.RoomResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomSuggestionResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse
import com.zeny.desktop.sdk.model.TraceResponse
import com.zeny.desktop.sdk.model.UndoCheckInResponse
import com.zeny.desktop.sdk.model.WalkInResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

private const val GUEST_SEARCH_LIMIT = 20

/** O que o `app` usa de um servidor: a sessão e as fachadas do `core`. Os testes do `app` usam um falso. */
@Suppress("TooManyFunctions") // uma porta opcional por área do `core`
interface ServerConnection : AutoCloseable {
    val state: StateFlow<SessionState>

    suspend fun login(
        login: String,
        password: String,
    )

    suspend fun changePassword(
        current: String,
        new: String,
    )

    suspend fun retryPasswordChangeRefresh()

    suspend fun logout()

    suspend fun me(): Me

    suspend fun hotel(): HotelInfo

    suspend fun businessDate(): BusinessDate

    suspend fun grant(
        required: ApiError.ApprovalRequired,
        input: ApprovalInput,
    ): String

    /** As leituras e o stream do mapa de UHs; `null` quando a conexão não os oferece. */
    fun roomsSource(): RoomsSource? = null

    /** As leituras da recepção do dia; `null` quando a conexão não as oferece. */
    fun receptionSource(): ReceptionSource? = null

    /** Os recados; `null` quando a conexão não os oferece. */
    fun tracesSource(): TracesSource? = null

    /** A ficha de registro em PDF; `null` quando a conexão não a oferece. */
    fun registrationCards(): RegistrationCards? = null
}

/** Adaptador sobre o `ZenyCore`: só repassa ao `SessionManager` e às fachadas. Fecha o `core` no `close()`. */
@Suppress("TooManyFunctions") // adaptador que repassa cada operação do `core`
class CoreConnection(
    private val core: ZenyCore,
) : ServerConnection {
    override val state: StateFlow<SessionState> get() = core.session.state

    override suspend fun login(
        login: String,
        password: String,
    ) {
        core.session.login(login, password)
    }

    override suspend fun changePassword(
        current: String,
        new: String,
    ) = core.session.changePassword(current, new)

    override suspend fun retryPasswordChangeRefresh() = core.session.retryPasswordChangeRefresh()

    override suspend fun logout() = core.session.logout()

    override suspend fun me(): Me = core.account.me()

    override suspend fun hotel(): HotelInfo = core.hotel.hotel()

    override suspend fun businessDate(): BusinessDate = core.hotel.businessDate()

    override suspend fun grant(
        required: ApiError.ApprovalRequired,
        input: ApprovalInput,
    ): String = core.approvals.grant(required, input)

    private val rooms =
        object : RoomsSource {
            override suspend fun statuses(): List<RoomStatusResponse> = core.rooms.statuses()

            override suspend fun roomTypes(): List<RoomTypeResponse> = core.rooms.roomTypes()

            override suspend fun room(id: String): RoomResponse = core.rooms.room(id)

            override suspend fun status(id: String): RoomStatusResponse = core.rooms.status(id)

            override fun events(topics: Set<String>): Flow<RealtimeEvent> = core.realtime.subscribe(topics)

            override val realtimeStatus: StateFlow<RealtimeStatus> get() = core.realtime.status
        }

    /** O mapa sobre `core.rooms` e `core.realtime`. */
    override fun roomsSource(): RoomsSource = rooms

    @Suppress("TooManyFunctions") // adaptador que repassa cada operação da porta ao `core`
    private val reception =
        object : ReceptionSource {
            override suspend fun permissions(): Set<String> = core.account.me().permissions

            override suspend fun businessDay(): BusinessDay = core.hotel.businessDay()

            override suspend fun dayPage(
                list: DayList,
                date: BusinessDate,
                cursor: String?,
                limit: Int,
            ): Page<ReservationListItemResponse> = core.reservations.dayPage(list, date, cursor, limit)

            override suspend fun reservation(id: String): ReservationDetailResponse = core.reservations.reservation(id)

            override suspend fun history(id: String): List<ReservationHistoryResponse> = core.reservations.history(id)

            override suspend fun roomAssignments(id: String): List<RoomAssignmentResponse> =
                core.reservations.roomAssignments(id)

            override suspend fun holder(guestId: String): HolderSummary = core.guests.guest(guestId).toHolderSummary()

            override suspend fun ratePlanName(id: String): String = core.rates.ratePlan(id).name

            override suspend fun roomTypes(): List<RoomTypeResponse> = core.rooms.roomTypes()

            override suspend fun roomSuggestions(id: String): List<RoomSuggestionResponse> =
                core.reservations.roomSuggestions(id)

            override suspend fun roomStatuses(): List<RoomStatusResponse> = core.rooms.statuses()

            override suspend fun assignRoom(
                id: String,
                roomId: String,
                doNotMove: Boolean?,
            ): ReservationDetailResponse = core.reservations.assignRoom(id, roomId, doNotMove)

            override suspend fun unassignRoom(id: String): ReservationDetailResponse =
                core.reservations.unassignRoom(id)

            override suspend fun setDoNotMove(
                id: String,
                doNotMove: Boolean,
            ): ReservationDetailResponse = core.reservations.setDoNotMove(id, doNotMove)

            override suspend fun earlyLateQuote(id: String): EarlyLateQuoteResponse =
                core.reservations.earlyLateQuote(id)

            override fun checkIn(
                id: String,
                input: CheckInInput,
                intent: Intent,
            ): ApprovableAction<CheckInResponse> = core.reservations.checkIn(id, input, intent)

            override suspend fun reservationVersion(id: String): ReservationVersion =
                core.reservations.reservationVersion(id)

            override fun moveRoom(
                id: String,
                input: RoomMoveInput,
                etag: String,
                intent: Intent,
            ): ApprovableAction<RoomMoveResponse> = core.reservations.moveRoom(id, input, etag, intent)

            override fun undoCheckIn(id: String): ApprovableAction<UndoCheckInResponse> =
                core.reservations.undoCheckIn(id)

            override suspend fun quoteStay(
                arrival: BusinessDate,
                departure: BusinessDate,
                adults: Int,
                childAges: List<Int>,
            ): QuoteResponse = core.reservations.quoteStay(arrival, departure, adults, childAges)

            override suspend fun assignedRoomIds(
                from: BusinessDate,
                to: BusinessDate,
            ): Set<String> = core.reservations.assignedRoomIds(from, to)

            override fun walkIn(
                input: WalkInInput,
                intent: Intent,
            ): ApprovableAction<WalkInResponse> = core.reservations.walkIn(input, intent)

            override suspend fun searchGuests(name: String): List<GuestPick> =
                core.guests.searchByName(name, GUEST_SEARCH_LIMIT).map { it.toGuestPick() }

            override suspend fun searchGuestsByDocument(
                type: String,
                number: String,
                issuingCountry: String?,
            ): List<GuestPick> = core.guests.searchByDocument(type, number, issuingCountry).map { it.toGuestPick() }

            override suspend fun createGuest(input: NewGuest): GuestPick = core.guests.create(input).toGuestPick()

            override fun events(topics: Set<String>): Flow<RealtimeEvent> = core.realtime.subscribe(topics)
        }

    /** A recepção do dia sobre `core.reservations`, `core.guests`, `core.rates` e `core.realtime`. */
    override fun receptionSource(): ReceptionSource = reception

    @Suppress("TooManyFunctions") // adaptador que repassa cada operação da porta ao `core`
    private val traces =
        object : TracesSource {
            override suspend fun permissions(): Set<String> = core.account.me().permissions

            override suspend fun businessDay(): BusinessDay = core.hotel.businessDay()

            override suspend fun page(
                filter: TraceFilter,
                cursor: String?,
                limit: Int,
            ): Page<TraceResponse> = core.traces.page(filter, cursor, limit)

            override suspend fun trace(id: String): TraceVersion = core.traces.trace(id)

            override suspend fun create(input: NewTrace): TraceVersion = core.traces.create(input)

            override suspend fun update(
                id: String,
                change: TraceChange,
                etag: String,
            ): TraceVersion = core.traces.update(id, change, etag)

            override suspend fun resolve(id: String): TraceResponse = core.traces.resolve(id)

            override suspend fun departments(): List<Department> = core.traces.departments()

            override suspend fun reservationCode(id: String): String =
                core.reservations
                    .reservation(id)
                    .reservation.code

            override suspend fun rooms(): List<RoomOption> =
                core.rooms
                    .statuses()
                    .filter { it.active }
                    .sortedWith(compareBy(NaturalOrder) { it.number })
                    .map { RoomOption(it.roomId.toString(), it.number) }
        }

    /** Os recados sobre `core.traces`, `core.reservations`, `core.rooms`, `core.account` e `core.hotel`. */
    override fun tracesSource(): TracesSource = traces

    private val registrationCards = RegistrationCards { id -> core.documents.registrationCard(id) }

    /** A ficha de registro sobre `core.documents`. */
    override fun registrationCards(): RegistrationCards = registrationCards

    override fun close() = core.close()
}
