package com.zeny.desktop.app.session

import com.zeny.desktop.app.rooms.RoomsSource
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
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

    override fun close() = core.close()
}
