package com.zeny.desktop.app.folio

import com.zeny.desktop.app.cashier.CashierSource
import com.zeny.desktop.app.reception.CheckOutSource
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reservations.ReservationSearchSource
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

/** Uma conexão de mentira já na sessão [sessionId], com as portas que os testes das contas usam. */
@Suppress("LongParameterList") // uma porta opcional por área, com padrão nulo
class FolioConnection(
    private val folios: FolioSource?,
    private val cashier: CashierSource? = null,
    private val reception: ReceptionSource? = null,
    private val search: ReservationSearchSource? = null,
    sessionId: String = "A",
    private val payments: PaymentSource? = null,
    private val checkOut: CheckOutSource? = null,
) : ServerConnection {
    override val state = MutableStateFlow<SessionState>(SessionState.Active("u-1", sessionId, false))

    override suspend fun login(
        login: String,
        password: String,
    ) = error("não usado")

    override suspend fun changePassword(
        current: String,
        new: String,
    ) = error("não usado")

    override suspend fun retryPasswordChangeRefresh() = Unit

    override suspend fun logout() = Unit

    override suspend fun me(): Me = Me("u-1", "ana", "Ana Souza", emptySet())

    override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

    override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 11, 18))

    override suspend fun grant(
        required: ApiError.ApprovalRequired,
        input: ApprovalInput,
    ): String = error("não usado")

    override fun folioSource(): FolioSource? = folios

    override fun paymentSource(): PaymentSource? = payments

    override fun cashierSource(): CashierSource? = cashier

    override fun receptionSource(): ReceptionSource? = reception

    override fun reservationSearchSource(): ReservationSearchSource? = search

    override fun checkOutSource(): CheckOutSource? = checkOut

    override fun close() = Unit
}
