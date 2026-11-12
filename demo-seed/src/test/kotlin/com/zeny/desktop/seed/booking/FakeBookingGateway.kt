package com.zeny.desktop.seed.booking

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.CompanySpec
import com.zeny.desktop.seed.data.GuestSpec
import com.zeny.desktop.seed.data.ReservationSpec
import java.time.LocalDate

data class CheckInCall(
    val reservationId: String,
    val roomId: String,
    val guestIds: List<String>,
)

/** Falso em memória que recusa CPF, CNPJ e `externalId` repetidos e as regras de check-in do servidor. */
@Suppress("TooManyFunctions") // porta que espelha as operações do contrato usadas pelo seed
class FakeBookingGateway(
    private val config: SeededConfig,
    private val businessDate: LocalDate,
) : BookingGateway {
    val companies = mutableMapOf<String, String>()
    val guests = mutableMapOf<String, String>()
    val guestCompany = mutableMapOf<String, String?>()
    val reservations = mutableMapOf<String, ReservationRef>()
    val checkIns = mutableListOf<CheckInCall>()
    var assignments = 0
        private set
    var inventoryReads = 0
        private set
    var createReservationCalls = 0
        private set

    /** Quantas leituras de inventário ainda voltam sem as linhas prontas. */
    var inventoryStaleReads = 0

    /** Erros lançados, em ordem, pelas próximas chamadas de `createReservation`. */
    val createFailures = ArrayDeque<ApiError>()
    private var sequence = 0

    private fun nextId(prefix: String) = "$prefix-${++sequence}"

    override suspend fun companyId(cnpj: String) = companies[cnpj]

    override suspend fun createCompany(spec: CompanySpec): String {
        check(spec.cnpj !in companies) { "409 CNPJ duplicado" }
        return nextId("co").also { companies[spec.cnpj] = it }
    }

    override suspend fun guestId(cpf: String) = guests[cpf]

    override suspend fun createGuest(
        spec: GuestSpec,
        companyId: String?,
    ): String {
        check(spec.cpf !in guests) { "409 CPF duplicado" }
        return nextId("gu").also {
            guests[spec.cpf] = it
            guestCompany[it] = companyId
        }
    }

    override suspend fun inventory(
        roomTypeId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<InventoryDay> {
        inventoryReads++
        if (inventoryStaleReads > 0) {
            inventoryStaleReads--
            return emptyList()
        }
        val code =
            config.roomTypeIds.entries
                .first { it.value == roomTypeId }
                .key
        val ooo = config.outOfOrderRoomsByType.getValue(code)
        val total = config.activeRoomsByType.getValue(code) + ooo
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
        return days.map { InventoryDay(it, total, if (it < businessDate.plusDays(OOO_DAYS)) ooo else 0) }.toList()
    }

    override suspend fun findReservation(externalId: String) = reservations[externalId]

    override suspend fun createReservation(
        spec: ReservationSpec,
        holderGuestId: String,
        roomTypeId: String,
        ratePlanId: String,
    ): ReservationRef {
        createReservationCalls++
        createFailures.removeFirstOrNull()?.let { throw it }
        check(spec.externalId !in reservations) { "409 externalId duplicado" }
        check(holderGuestId in guests.values) { "404 hóspede" }
        return ReservationRef(nextId("re"), "CONFIRMED", null).also { reservations[spec.externalId] = it }
    }

    private fun byId(id: String) = reservations.entries.first { it.value.id == id }

    override suspend fun assignRoom(
        reservationId: String,
        roomId: String,
    ) {
        val (key, ref) = byId(reservationId).let { it.key to it.value }
        check(roomId in config.roomIds.values) { "404 UH" }
        assignments++
        reservations[key] = ref.copy(roomId = roomId)
    }

    override suspend fun checkIn(
        reservationId: String,
        roomId: String,
        guestIds: List<String>,
    ) {
        val (key, ref) = byId(reservationId).let { it.key to it.value }
        check(ref.status == "CONFIRMED") { "409 só reserva CONFIRMED faz check-in" }
        check(ref.roomId == roomId) { "409 UH não alocada" }
        checkIns += CheckInCall(reservationId, roomId, guestIds)
        reservations[key] = ref.copy(status = "IN_HOUSE")
    }

    private companion object {
        const val OOO_DAYS = 3L
    }
}
