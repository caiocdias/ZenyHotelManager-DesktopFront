package com.zeny.desktop.seed.booking

import com.zeny.desktop.seed.data.CompanySpec
import com.zeny.desktop.seed.data.GuestSpec
import com.zeny.desktop.seed.data.ReservationSpec
import java.time.LocalDate

/** Reserva no servidor; `status` é o valor cru (`CONFIRMED`, `IN_HOUSE` ou outro). */
data class ReservationRef(
    val id: String,
    val status: String,
    val roomId: String?,
)

data class InventoryDay(
    val date: LocalDate,
    val total: Int,
    val outOfOrder: Int,
)

/** Porta de empresas, hóspedes e reservas. Hóspedes e empresas são buscados por documento, nunca por nome. */
@Suppress("TooManyFunctions") // porta que espelha as operações do contrato usadas pelo seed
interface BookingGateway {
    suspend fun companyId(cnpj: String): String?

    suspend fun createCompany(spec: CompanySpec): String

    suspend fun guestId(cpf: String): String?

    suspend fun createGuest(
        spec: GuestSpec,
        companyId: String?,
    ): String

    suspend fun inventory(
        roomTypeId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<InventoryDay>

    suspend fun findReservation(externalId: String): ReservationRef?

    suspend fun createReservation(
        spec: ReservationSpec,
        holderGuestId: String,
        roomTypeId: String,
        ratePlanId: String,
    ): ReservationRef

    suspend fun assignRoom(
        reservationId: String,
        roomId: String,
    )

    /** `guestIds` com o hóspede principal primeiro. */
    suspend fun checkIn(
        reservationId: String,
        roomId: String,
        guestIds: List<String>,
    )
}
