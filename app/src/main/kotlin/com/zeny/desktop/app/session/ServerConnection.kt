package com.zeny.desktop.app.session

import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reception.toHolderSummary
import com.zeny.desktop.app.rooms.RoomsSource
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** O que o `app` usa de um servidor: a sessão e as fachadas do `core`. Os testes do `app` usam um falso. */
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
}

/** Adaptador sobre o `ZenyCore`: só repassa ao `SessionManager` e às fachadas. Fecha o `core` no `close()`. */
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

            override fun events(topics: Set<String>): Flow<RealtimeEvent> = core.realtime.subscribe(topics)
        }

    /** A recepção do dia sobre `core.reservations`, `core.guests`, `core.rates` e `core.realtime`. */
    override fun receptionSource(): ReceptionSource = reception

    override fun close() = core.close()
}
