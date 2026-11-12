package com.zeny.desktop.seed.booking

import com.zeny.desktop.seed.data.CompanySpec
import com.zeny.desktop.seed.data.GuestSpec
import com.zeny.desktop.seed.data.ReservationSpec
import java.time.LocalDate

internal class Boom : RuntimeException("falha injetada")

internal data class Fault(
    val call: String,
    val nth: Int,
    val after: Boolean,
)

internal data class InvRead(
    val typeId: String,
    val from: LocalDate,
    val to: LocalDate,
    val at: Long,
)

/** Envolve o falso: registra a ordem das chamadas, injeta falhas e adultera leituras de inventário. */
@Suppress("TooManyFunctions") // porta que espelha as operações do contrato usadas pelo seed
internal class Probe(
    private val fake: FakeBookingGateway,
    private val clock: () -> Long,
) : BookingGateway {
    val events = mutableListOf<String>()
    val inventoryReads = mutableListOf<InvRead>()
    val createdSpecs = mutableListOf<ReservationSpec>()
    var fault: Fault? = null
    var transform: (
        typeId: String,
        readNo: Int,
        rows: List<InventoryDay>,
    ) -> List<InventoryDay> = { _, _, rows -> rows }
    var createFailures = ArrayDeque<Throwable>()
    private val counters = mutableMapOf<String, Int>()

    val writes get() = events.filter { it.substringBefore(':') in WRITES }

    private suspend fun <T> guarded(
        call: String,
        label: String = call,
        block: suspend () -> T,
    ): T {
        events += label
        val n = (counters[call] ?: 0) + 1
        counters[call] = n
        val f = fault?.takeIf { it.call == call && it.nth == n }
        if (f != null && !f.after) throw Boom()
        val result = block()
        if (f != null && f.after) throw Boom()
        return result
    }

    override suspend fun companyId(cnpj: String) = fake.companyId(cnpj)

    override suspend fun createCompany(spec: CompanySpec) = guarded("createCompany") { fake.createCompany(spec) }

    override suspend fun guestId(cpf: String) = fake.guestId(cpf)

    override suspend fun createGuest(
        spec: GuestSpec,
        companyId: String?,
    ) = guarded("createGuest") { fake.createGuest(spec, companyId) }

    override suspend fun inventory(
        roomTypeId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<InventoryDay> {
        inventoryReads += InvRead(roomTypeId, from, to, clock())
        events += "inventory:$roomTypeId"
        val readNo = inventoryReads.count { it.typeId == roomTypeId }
        return transform(roomTypeId, readNo, fake.inventory(roomTypeId, from, to))
    }

    override suspend fun findReservation(externalId: String) = fake.findReservation(externalId)

    override suspend fun createReservation(
        spec: ReservationSpec,
        holderGuestId: String,
        roomTypeId: String,
        ratePlanId: String,
    ): ReservationRef {
        createdSpecs += spec
        createFailures.removeFirstOrNull()?.let {
            events += "createReservation:$roomTypeId"
            throw it
        }
        return guarded("createReservation", "createReservation:$roomTypeId") {
            fake.createReservation(spec, holderGuestId, roomTypeId, ratePlanId)
        }
    }

    override suspend fun assignRoom(
        reservationId: String,
        roomId: String,
    ) = guarded("assignRoom", "assignRoom:$reservationId") { fake.assignRoom(reservationId, roomId) }

    override suspend fun checkIn(
        reservationId: String,
        roomId: String,
        guestIds: List<String>,
    ) = guarded("checkIn", "checkIn:$reservationId") { fake.checkIn(reservationId, roomId, guestIds) }

    private companion object {
        val WRITES = setOf("createCompany", "createGuest", "createReservation", "assignRoom", "checkIn")
    }
}
