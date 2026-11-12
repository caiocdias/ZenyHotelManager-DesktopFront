package com.zeny.desktop.seed.config

import com.zeny.desktop.seed.data.AreaSpec
import com.zeny.desktop.seed.data.CancellationPolicySpec
import com.zeny.desktop.seed.data.GuaranteePolicySpec
import com.zeny.desktop.seed.data.HotelSpec
import com.zeny.desktop.seed.data.RatePlanSpec
import com.zeny.desktop.seed.data.RoomSpec
import com.zeny.desktop.seed.data.RoomTypeSpec
import com.zeny.desktop.seed.data.SeasonSpec
import java.math.BigDecimal
import java.time.LocalDate

/** Falso em memória que recusa duplicados (lança), como o 409 do servidor. */
@Suppress("TooManyFunctions") // porta que espelha as operações do contrato usadas pelo seed
class FakeConfigGateway : ConfigGateway {
    var hotelName: String? = null
    val guaranteePolicies = mutableMapOf<String, String>()
    val cancellationPolicies = mutableMapOf<String, String>()
    val roomTypes = mutableMapOf<String, String>()
    val areas = mutableSetOf<String>()
    val roomStates = mutableMapOf<String, RoomState>()
    val seasons = mutableMapOf<String, String>()
    val ratePlans = mutableMapOf<String, String>()
    val priceWrites = mutableListOf<List<Any>>()
    val outOfOrderOpened = mutableListOf<String>()
    var writeCalls = 0
        private set
    private var sequence = 0

    private fun nextId(prefix: String) = "$prefix-${++sequence}"

    private fun write() {
        writeCalls++
    }

    private fun <V> MutableMap<String, V>.insertUnique(
        key: String,
        value: V,
    ) {
        check(keys.none { it.equals(key, ignoreCase = true) }) { "409 duplicado: $key" }
        put(key, value)
    }

    override suspend fun configuredHotelName() = hotelName

    override suspend fun configureHotel(spec: HotelSpec) {
        write()
        hotelName = spec.tradeName
    }

    override suspend fun guaranteePolicyId(name: String) = guaranteePolicies[name]

    override suspend fun createGuaranteePolicy(spec: GuaranteePolicySpec): String {
        write()
        return nextId("gp").also { guaranteePolicies.insertUnique(spec.name, it) }
    }

    override suspend fun cancellationPolicyId(name: String) = cancellationPolicies[name]

    override suspend fun createCancellationPolicy(spec: CancellationPolicySpec): String {
        write()
        return nextId("cp").also { cancellationPolicies.insertUnique(spec.name, it) }
    }

    override suspend fun roomTypeIds(): Map<String, String> = roomTypes.toMap()

    override suspend fun createRoomType(spec: RoomTypeSpec): String {
        write()
        return nextId("rt").also { roomTypes.insertUnique(spec.code.uppercase(), it) }
    }

    override suspend fun areaNames(): Set<String> = areas.toSet()

    override suspend fun createArea(spec: AreaSpec) {
        write()
        check(areas.none { it.equals(spec.name, ignoreCase = true) }) { "409 duplicado: ${spec.name}" }
        areas += spec.name
    }

    override suspend fun rooms(): Map<String, RoomState> = roomStates.toMap()

    override suspend fun createRoom(
        spec: RoomSpec,
        roomTypeId: String,
    ): String {
        write()
        check(spec.number !in roomStates) { "409 duplicado: ${spec.number}" }
        val id = nextId("room")
        roomStates[spec.number] = RoomState(id, spec.number, roomTypeId, false, "DIRTY", false, false)
        return id
    }

    override suspend fun changeHousekeeping(
        roomId: String,
        transition: HousekeepingTransition,
    ) {
        write()
        val room = roomStates.values.first { it.id == roomId }
        roomStates[room.number] =
            when (transition) {
                HousekeepingTransition.MARK_DIRTY -> {
                    room.copy(housekeeping = "DIRTY", inspectionPending = false)
                }

                HousekeepingTransition.MARK_CLEAN -> {
                    room.copy(housekeeping = "CLEAN", inspectionPending = false)
                }

                HousekeepingTransition.MARK_CLEAN_PENDING_INSPECTION -> {
                    room.copy(housekeeping = "CLEAN", inspectionPending = true)
                }

                HousekeepingTransition.MARK_INSPECTED -> {
                    room.copy(housekeeping = "INSPECTED", inspectionPending = false)
                }

                HousekeepingTransition.REJECT_INSPECTION -> {
                    room.copy(housekeeping = "DIRTY", inspectionPending = false)
                }
            }
    }

    override suspend fun openOutOfOrder(
        roomId: String,
        from: LocalDate,
        to: LocalDate,
    ) {
        write()
        val room = roomStates.values.first { it.id == roomId }
        roomStates[room.number] = room.copy(outOfOrder = true)
        outOfOrderOpened += room.number
    }

    override suspend fun seasonIds(): Map<String, String> = seasons.toMap()

    override suspend fun createSeason(spec: SeasonSpec): String {
        write()
        return nextId("se").also { seasons.insertUnique(spec.name, it) }
    }

    override suspend fun ratePlanId(code: String) = ratePlans[code.uppercase()]

    override suspend fun createRatePlan(
        spec: RatePlanSpec,
        roomTypeIds: List<String>,
        guaranteePolicyId: String,
        cancellationPolicyId: String,
    ): String {
        write()
        return nextId("rp").also { ratePlans.insertUnique(spec.code.uppercase(), it) }
    }

    @Suppress("LongParameterList") // assinatura fixa da porta: espelha a linha de preço do contrato
    override suspend fun updatePrices(
        ratePlanId: String,
        roomTypeId: String,
        seasonId: String,
        oneAdult: BigDecimal,
        twoAdults: BigDecimal,
        extraAdult: BigDecimal,
    ) {
        priceWrites += listOf(ratePlanId, roomTypeId, seasonId, oneAdult, twoAdults, extraAdult)
    }
}
