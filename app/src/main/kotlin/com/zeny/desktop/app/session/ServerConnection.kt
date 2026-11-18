package com.zeny.desktop.app.session

import com.zeny.desktop.app.cashier.CashierSource
import com.zeny.desktop.app.folio.FolioSource
import com.zeny.desktop.app.folio.PaymentSource
import com.zeny.desktop.app.reception.GuestPick
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reception.RegistrationCards
import com.zeny.desktop.app.reception.toGuestPick
import com.zeny.desktop.app.reception.toHolderSummary
import com.zeny.desktop.app.reservations.AvailabilitySource
import com.zeny.desktop.app.reservations.NewReservationSource
import com.zeny.desktop.app.reservations.PendingSource
import com.zeny.desktop.app.reservations.ReservationCancelSource
import com.zeny.desktop.app.reservations.ReservationChangeSource
import com.zeny.desktop.app.reservations.ReservationConfirmSource
import com.zeny.desktop.app.reservations.ReservationConfirmations
import com.zeny.desktop.app.reservations.ReservationReactivateSource
import com.zeny.desktop.app.reservations.ReservationSearchSource
import com.zeny.desktop.app.rooms.NaturalOrder
import com.zeny.desktop.app.rooms.RoomsSource
import com.zeny.desktop.app.traces.RoomOption
import com.zeny.desktop.app.traces.TracesSource
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.CancelInput
import com.zeny.desktop.core.facade.CashCount
import com.zeny.desktop.core.facade.CashMovementType
import com.zeny.desktop.core.facade.CheckInInput
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.core.facade.DocumentQuery
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.NewGuest
import com.zeny.desktop.core.facade.NewTrace
import com.zeny.desktop.core.facade.PaymentInput
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.core.facade.PostingInput
import com.zeny.desktop.core.facade.RefundInput
import com.zeny.desktop.core.facade.ReservationChangeInput
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.facade.RoomMoveInput
import com.zeny.desktop.core.facade.StatementAudience
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
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.AvailabilityResponse
import com.zeny.desktop.sdk.model.CashMovementResponse
import com.zeny.desktop.sdk.model.CashPointResponse
import com.zeny.desktop.sdk.model.CashierShiftReportResponse
import com.zeny.desktop.sdk.model.CashierShiftResponse
import com.zeny.desktop.sdk.model.CheckInResponse
import com.zeny.desktop.sdk.model.DepositShortfallResponse
import com.zeny.desktop.sdk.model.EarlyLateQuoteResponse
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.FolioStatementResponse
import com.zeny.desktop.sdk.model.PaymentResponse
import com.zeny.desktop.sdk.model.PendingGuaranteeResponse
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.ReallocationRequiredResponse
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
import com.zeny.desktop.sdk.model.TransactionCodeResponse
import com.zeny.desktop.sdk.model.UndoCheckInResponse
import com.zeny.desktop.sdk.model.WalkInResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

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

    /** A busca de reservas; `null` quando a conexão não a oferece. */
    fun reservationSearchSource(): ReservationSearchSource? = null

    /** A disponibilidade e a cotação; `null` quando a conexão não as oferece. */
    fun availabilitySource(): AvailabilitySource? = null

    /** A ficha de registro em PDF; `null` quando a conexão não a oferece. */
    fun registrationCards(): RegistrationCards? = null

    /** A criação de reserva a partir da cotação; `null` quando a conexão não a oferece. */
    fun newReservationSource(): NewReservationSource? = null

    /** A alteração de reserva; `null` quando a conexão não a oferece. */
    fun reservationChangeSource(): ReservationChangeSource? = null

    /** A confirmação da pré-reserva; `null` quando a conexão não a oferece. */
    fun reservationConfirmSource(): ReservationConfirmSource? = null

    /** A confirmação da reserva em PDF; `null` quando a conexão não a oferece. */
    fun reservationConfirmations(): ReservationConfirmations? = null

    /** O cancelamento de reserva; `null` quando a conexão não o oferece. */
    fun reservationCancelSource(): ReservationCancelSource? = null

    /** A reativação de reserva; `null` quando a conexão não a oferece. */
    fun reservationReactivateSource(): ReservationReactivateSource? = null

    /** As listas de pendências de reservas; `null` quando a conexão não as oferece. */
    fun pendingSource(): PendingSource? = null

    /** O turno de caixa; `null` quando a conexão não o oferece. */
    fun cashierSource(): CashierSource? = null

    /** As contas da hospedagem; `null` quando a conexão não as oferece. */
    fun folioSource(): FolioSource? = null

    /** Os pagamentos da conta; `null` quando a conexão não os oferece. */
    fun paymentSource(): PaymentSource? = null
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

    private val reservationSearch =
        object : ReservationSearchSource {
            override suspend fun businessDay(): BusinessDay = core.hotel.businessDay()

            override suspend fun search(
                filter: ReservationFilter,
                cursor: String?,
                limit: Int,
            ): Page<ReservationListItemResponse> = core.reservations.search(filter, cursor, limit)

            override suspend fun searchByDocument(
                query: DocumentQuery,
                statuses: Set<String>,
                cursor: String?,
                limit: Int,
            ): Page<ReservationListItemResponse> = core.reservations.searchByDocument(query, statuses, cursor, limit)

            override suspend fun holder(guestId: String): HolderSummary = core.guests.guest(guestId).toHolderSummary()

            override suspend fun roomTypes(): List<RoomTypeResponse> = core.rooms.roomTypes()
        }

    /** A busca de reservas sobre `core.hotel`, `core.reservations`, `core.guests` e `core.rooms`. */
    override fun reservationSearchSource(): ReservationSearchSource = reservationSearch

    private val availability =
        object : AvailabilitySource {
            override suspend fun businessDay(): BusinessDay = core.hotel.businessDay()

            override suspend fun availability(
                from: BusinessDate,
                to: BusinessDate,
            ): AvailabilityResponse = core.reservations.availability(from, to)

            override suspend fun quote(
                arrival: BusinessDate,
                departure: BusinessDate,
                adults: Int,
                childAges: List<Int>,
            ): QuoteResponse =
                core.reservations.quoteStay(arrival, departure, adults, childAges, includeUnavailable = true)
        }

    /** A disponibilidade e a cotação sobre `core.hotel` e `core.reservations`. */
    override fun availabilitySource(): AvailabilitySource = availability

    private val registrationCards = RegistrationCards { id -> core.documents.registrationCard(id) }

    /** A ficha de registro sobre `core.documents`. */
    override fun registrationCards(): RegistrationCards = registrationCards

    private val newReservation =
        NewReservationSource { input, intent -> core.bookings.createBooking(input, intent) }

    /** A criação de reserva sobre `core.bookings`. */
    override fun newReservationSource(): NewReservationSource = newReservation

    private val reservationChange =
        object : ReservationChangeSource {
            override suspend fun businessDay(): BusinessDay = core.hotel.businessDay()

            override suspend fun reservationVersion(id: String): ReservationVersion =
                core.reservations.reservationVersion(id)

            override suspend fun quote(
                arrival: BusinessDate,
                departure: BusinessDate,
                adults: Int,
                childAges: List<Int>,
            ): QuoteResponse = core.reservations.quoteStay(arrival, departure, adults, childAges)

            override fun changeReservation(
                id: String,
                input: ReservationChangeInput,
                etag: String,
            ): ApprovableAction<ReservationDetailResponse> = core.bookings.changeReservation(id, input, etag)
        }

    /** A alteração de reserva sobre `core.bookings`, `core.reservations` e `core.hotel`. */
    override fun reservationChangeSource(): ReservationChangeSource = reservationChange

    private val reservationConfirm =
        object : ReservationConfirmSource {
            override suspend fun reservation(id: String): ReservationDetailResponse = core.reservations.reservation(id)

            override suspend fun confirmReservation(
                id: String,
                guarantee: String,
                intent: Intent,
            ): ReservationDetailResponse = core.bookings.confirmReservation(id, guarantee, intent)
        }

    /** A confirmação da pré-reserva sobre `core.reservations` e `core.bookings`. */
    override fun reservationConfirmSource(): ReservationConfirmSource = reservationConfirm

    private val reservationConfirmations = ReservationConfirmations { id -> core.documents.reservationConfirmation(id) }

    /** A confirmação da reserva em PDF sobre `core.documents`. */
    override fun reservationConfirmations(): ReservationConfirmations = reservationConfirmations

    private val reservationCancel =
        object : ReservationCancelSource {
            override suspend fun reservation(id: String): ReservationDetailResponse = core.reservations.reservation(id)

            override fun cancelReservation(
                id: String,
                input: CancelInput,
                intent: Intent,
            ): ApprovableAction<ReservationDetailResponse> = core.bookings.cancelReservation(id, input, intent)
        }

    /** O cancelamento de reserva sobre `core.reservations` e `core.bookings`. */
    override fun reservationCancelSource(): ReservationCancelSource = reservationCancel

    private val reservationReactivate =
        ReservationReactivateSource { id, intent -> core.bookings.reactivateReservation(id, intent) }

    /** A reativação de reserva sobre `core.bookings`. */
    override fun reservationReactivateSource(): ReservationReactivateSource = reservationReactivate

    private val pending =
        object : PendingSource {
            override suspend fun businessDay(): BusinessDay = core.hotel.businessDay()

            override suspend fun pendingGuarantees(
                to: Instant,
                cursor: String?,
                limit: Int,
            ): Page<PendingGuaranteeResponse> = core.pending.pendingGuarantees(to, cursor, limit)

            override suspend fun depositShortfalls(): List<DepositShortfallResponse> = core.pending.depositShortfalls()

            override suspend fun expectedNoShows(
                cursor: String?,
                limit: Int,
            ): Page<ReservationListItemResponse> = core.pending.expectedNoShows(cursor, limit)

            override suspend fun reallocationRequired(
                cursor: String?,
                limit: Int,
            ): Page<ReallocationRequiredResponse> = core.pending.reallocationRequired(cursor, limit)

            override suspend fun holder(guestId: String): HolderSummary = core.guests.guest(guestId).toHolderSummary()
        }

    /** As listas de pendências sobre `core.pending`, `core.hotel` e `core.guests`. */
    override fun pendingSource(): PendingSource = pending

    private val cashier =
        object : CashierSource {
            override suspend fun cashPoints(): List<CashPointResponse> = core.cashier.cashPoints()

            override suspend fun currentShift(): CashierShiftResponse? = core.cashier.currentShift()

            override suspend fun openShift(
                cashPointId: String,
                openingFloat: Money,
                intent: Intent,
            ): CashierShiftResponse = core.cashier.openShift(cashPointId, openingFloat, intent)

            override suspend fun recordMovement(
                shiftId: String,
                type: CashMovementType,
                amount: Money,
                reason: String,
                intent: Intent,
            ): CashMovementResponse = core.cashier.recordMovement(shiftId, type, amount, reason, intent)

            override suspend fun closeShift(
                shiftId: String,
                counts: List<CashCount>,
                intent: Intent,
            ): CashierShiftReportResponse = core.cashier.closeShift(shiftId, counts, intent)

            override suspend fun report(shiftId: String): CashierShiftReportResponse = core.cashier.report(shiftId)
        }

    /** O turno de caixa sobre `core.cashier`. */
    override fun cashierSource(): CashierSource = cashier

    private val folios =
        object : FolioSource {
            override suspend fun openFolioPage(
                cursor: String?,
                limit: Int,
            ): Page<FolioResponse> = core.folios.openFolioPage(cursor, limit)

            override suspend fun reservationFolios(reservationId: String): List<FolioResponse> =
                core.folios.reservationFolios(reservationId)

            override suspend fun folio(folioId: String): FolioResponse = core.folios.folio(folioId)

            override suspend fun postings(folioId: String): List<PostingResponse> = core.folios.postings(folioId)

            override suspend fun transactionCodes(): List<TransactionCodeResponse> = core.folios.transactionCodes()

            override suspend fun post(
                folioId: String,
                input: PostingInput,
                intent: Intent,
            ): PostingResponse = core.folios.post(folioId, input, intent)

            override fun reverse(
                folioId: String,
                postingId: String,
                reason: String,
                intent: Intent,
            ): ApprovableAction<PostingResponse> = core.folios.reverse(folioId, postingId, reason, intent)

            override suspend fun statement(
                folioId: String,
                audience: StatementAudience,
            ): FolioStatementResponse = core.folios.statement(folioId, audience)

            override suspend fun statementPdf(
                folioId: String,
                audience: StatementAudience,
            ): PdfDocument = core.documents.folioStatement(folioId, audience)
        }

    /** As contas da hospedagem sobre `core.folios` e `core.documents`. */
    override fun folioSource(): FolioSource = folios

    private val payments =
        object : PaymentSource {
            override suspend fun payments(folioId: String): List<PaymentResponse> = core.payments.payments(folioId)

            override suspend fun receive(
                folioId: String,
                input: PaymentInput,
                intent: Intent,
            ): PaymentResponse = core.payments.receive(folioId, input, intent)

            override fun refund(
                folioId: String,
                paymentId: String,
                input: RefundInput,
                intent: Intent,
            ): ApprovableAction<PaymentResponse> = core.payments.refund(folioId, paymentId, input, intent)
        }

    /** Os pagamentos da conta sobre `core.payments`. */
    override fun paymentSource(): PaymentSource = payments

    override fun close() = core.close()
}
