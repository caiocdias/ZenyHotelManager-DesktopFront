package com.zeny.desktop.seed.config

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.data.DemoData
import com.zeny.desktop.seed.data.HousekeepingTarget
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import java.time.LocalDate

object HousekeepingPlan {
    private enum class Level { D, CP, C, I }

    fun transitions(
        current: RoomState,
        target: HousekeepingTarget,
    ): List<HousekeepingTransition> {
        val from = levelOf(current)
        return if (current.occupied || from == null) emptyList() else path(from, target.level())
    }

    private fun levelOf(room: RoomState): Level? =
        when (room.housekeeping) {
            "DIRTY" -> Level.D
            "CLEAN" -> if (room.inspectionPending) Level.CP else Level.C
            "INSPECTED" -> Level.I
            else -> null
        }

    private fun HousekeepingTarget.level() =
        when (this) {
            HousekeepingTarget.DIRTY -> Level.D
            HousekeepingTarget.CLEAN_PENDING_INSPECTION -> Level.CP
            HousekeepingTarget.CLEAN -> Level.C
            HousekeepingTarget.INSPECTED -> Level.I
        }

    private fun path(
        from: Level,
        to: Level,
    ): List<HousekeepingTransition> {
        val dirty = HousekeepingTransition.MARK_DIRTY
        val clean = HousekeepingTransition.MARK_CLEAN
        val pending = HousekeepingTransition.MARK_CLEAN_PENDING_INSPECTION
        val inspected = HousekeepingTransition.MARK_INSPECTED
        val reject = HousekeepingTransition.REJECT_INSPECTION
        return when (from to to) {
            Level.D to Level.CP -> listOf(pending)
            Level.D to Level.C -> listOf(clean)
            Level.D to Level.I -> listOf(clean, inspected)
            Level.CP to Level.D -> listOf(reject)
            Level.CP to Level.C -> listOf(reject, clean)
            Level.CP to Level.I -> listOf(inspected)
            Level.C to Level.D -> listOf(dirty)
            Level.C to Level.CP -> listOf(dirty, pending)
            Level.C to Level.I -> listOf(inspected)
            Level.I to Level.D -> listOf(dirty)
            Level.I to Level.CP -> listOf(dirty, pending)
            Level.I to Level.C -> listOf(dirty, clean)
            else -> emptyList()
        }
    }
}

data class SeededConfig(
    val roomTypeIds: Map<String, String>,
    val roomIds: Map<String, String>,
    val ratePlanId: String,
    val activeRoomsByType: Map<String, Int>,
    val outOfOrderRoomsByType: Map<String, Int>,
)

/** Semeia a configuração do hotel conferindo antes de criar, para que repetir a execução não crie nada. */
@Suppress("TooManyFunctions") // uma função por etapa do roteiro de semeadura, na ordem em que roda
class ConfigSeeder(
    private val gateway: ConfigGateway,
    private val report: SeedReport,
) {
    suspend fun seed(
        data: DemoData,
        businessDate: LocalDate,
    ): SeededConfig {
        seedHotel(data)
        val guaranteeId = seedGuaranteePolicy(data)
        val cancellationId = seedCancellationPolicy(data)
        val typeIds = seedRoomTypes(data)
        seedAreas(data)
        seedRooms(data, typeIds)
        seedRoomStatus(data)
        seedOutOfOrder(data, businessDate)
        val seasonIds = seedSeasons(data)
        val planExisted = gateway.ratePlanId(data.ratePlan.code) != null
        val planId = seedRatePlan(data, typeIds, guaranteeId, cancellationId)
        seedPrices(data, planId, planExisted, typeIds, seasonIds)
        return result(typeIds, planId)
    }

    private suspend fun seedHotel(data: DemoData) {
        when (gateway.configuredHotelName()) {
            null -> {
                gateway.configureHotel(data.hotel)
                report.created(SeedItem.HOTEL)
            }

            data.hotel.tradeName -> {
                report.kept(SeedItem.HOTEL)
            }

            else -> {
                throw SeedFailure(
                    "Este servidor já tem outro hotel configurado; o demo-seed só roda num servidor da demo.",
                )
            }
        }
    }

    private suspend fun seedGuaranteePolicy(data: DemoData): String {
        val existing = gateway.guaranteePolicyId(data.guaranteePolicy.name)
        if (existing != null) {
            report.kept(SeedItem.POLICY)
            return existing
        }
        return gateway.createGuaranteePolicy(data.guaranteePolicy).also { report.created(SeedItem.POLICY) }
    }

    private suspend fun seedCancellationPolicy(data: DemoData): String {
        val existing = gateway.cancellationPolicyId(data.cancellationPolicy.name)
        if (existing != null) {
            report.kept(SeedItem.POLICY)
            return existing
        }
        return gateway.createCancellationPolicy(data.cancellationPolicy).also { report.created(SeedItem.POLICY) }
    }

    private suspend fun seedRoomTypes(data: DemoData): Map<String, String> {
        val ids = gateway.roomTypeIds().mapKeys { it.key.uppercase() }.toMutableMap()
        for (spec in data.roomTypes) {
            if (spec.code.uppercase() in ids) {
                report.kept(SeedItem.ROOM_TYPE)
            } else {
                ids[spec.code.uppercase()] = gateway.createRoomType(spec)
                report.created(SeedItem.ROOM_TYPE)
            }
        }
        return ids
    }

    private suspend fun seedAreas(data: DemoData) {
        val existing = gateway.areaNames().map { it.lowercase() }.toSet()
        for (spec in data.areas) {
            if (spec.name.lowercase() in existing) {
                report.kept(SeedItem.AREA)
            } else {
                gateway.createArea(spec)
                report.created(SeedItem.AREA)
            }
        }
    }

    private suspend fun seedRooms(
        data: DemoData,
        typeIds: Map<String, String>,
    ) {
        val existing = gateway.rooms()
        for (spec in data.rooms) {
            if (spec.number in existing) {
                report.kept(SeedItem.ROOM)
            } else {
                gateway.createRoom(spec, typeIds.getValue(spec.roomTypeCode.uppercase()))
                report.created(SeedItem.ROOM)
            }
        }
    }

    private suspend fun seedRoomStatus(data: DemoData) {
        val current = gateway.rooms()
        for (spec in data.rooms) {
            val state = current.getValue(spec.number)
            val steps = HousekeepingPlan.transitions(state, spec.target)
            steps.forEach { gateway.changeHousekeeping(state.id, it) }
            if (steps.isEmpty()) report.kept(SeedItem.ROOM_STATUS) else report.created(SeedItem.ROOM_STATUS)
        }
    }

    private suspend fun seedOutOfOrder(
        data: DemoData,
        businessDate: LocalDate,
    ) {
        val current = gateway.rooms()
        for (spec in data.rooms.filter { it.outOfOrder }) {
            val state = current.getValue(spec.number)
            if (state.outOfOrder) {
                report.kept(SeedItem.OUT_OF_ORDER)
            } else {
                gateway.openOutOfOrder(state.id, businessDate, businessDate.plusDays(OUT_OF_ORDER_LAST_DAY))
                report.created(SeedItem.OUT_OF_ORDER)
            }
        }
    }

    private suspend fun seedSeasons(data: DemoData): Map<String, String> {
        val ids = gateway.seasonIds().mapKeys { it.key.lowercase() }.toMutableMap()
        for (spec in data.seasons) {
            if (spec.name.lowercase() in ids) {
                report.kept(SeedItem.SEASON)
            } else {
                ids[spec.name.lowercase()] = gateway.createSeason(spec)
                report.created(SeedItem.SEASON)
            }
        }
        return ids
    }

    private suspend fun seedRatePlan(
        data: DemoData,
        typeIds: Map<String, String>,
        guaranteeId: String,
        cancellationId: String,
    ): String {
        val spec = data.ratePlan
        val existing = gateway.ratePlanId(spec.code)
        if (existing != null) {
            report.kept(SeedItem.RATE_PLAN)
            return existing
        }
        val planTypeIds = spec.roomTypeCodes.map { typeIds.getValue(it.uppercase()) }
        return gateway.createRatePlan(spec, planTypeIds, guaranteeId, cancellationId).also {
            report.created(SeedItem.RATE_PLAN)
        }
    }

    private suspend fun seedPrices(
        data: DemoData,
        planId: String,
        planExisted: Boolean,
        typeIds: Map<String, String>,
        seasonIds: Map<String, String>,
    ) {
        for (price in data.prices) {
            gateway.updatePrices(
                planId,
                typeIds.getValue(price.roomTypeCode.uppercase()),
                seasonIds.getValue(price.seasonName.lowercase()),
                price.oneAdult,
                price.twoAdults,
                price.extraAdult,
            )
            if (planExisted) report.kept(SeedItem.PRICES) else report.created(SeedItem.PRICES)
        }
    }

    private suspend fun result(
        typeIds: Map<String, String>,
        planId: String,
    ): SeededConfig {
        val rooms = gateway.rooms()
        val codeById = typeIds.entries.associate { it.value to it.key }

        fun countBy(outOfOrder: Boolean) =
            typeIds.keys.associateWith { code ->
                rooms.values.count { codeById[it.roomTypeId] == code && it.outOfOrder == outOfOrder }
            }
        return SeededConfig(
            roomTypeIds = typeIds,
            roomIds = rooms.mapValues { it.value.id },
            ratePlanId = planId,
            activeRoomsByType = countBy(outOfOrder = false),
            outOfOrderRoomsByType = countBy(outOfOrder = true),
        )
    }

    private companion object {
        const val OUT_OF_ORDER_LAST_DAY = 2L
    }
}
