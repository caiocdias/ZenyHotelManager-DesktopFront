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

/** Estado de uma UH no servidor; `housekeeping` é o valor cru (`DIRTY`, `CLEAN`, `INSPECTED` ou outro). */
data class RoomState(
    val id: String,
    val number: String,
    val roomTypeId: String,
    val occupied: Boolean,
    val housekeeping: String,
    val inspectionPending: Boolean,
    val outOfOrder: Boolean,
)

enum class HousekeepingTransition {
    MARK_DIRTY,
    MARK_CLEAN,
    MARK_CLEAN_PENDING_INSPECTION,
    MARK_INSPECTED,
    REJECT_INSPECTION,
}

/**
 * Porta da configuração do hotel. Os mapas usam a chave natural: código em maiúsculas, número da UH,
 * nome da área e da temporada (comparados sem diferenciar maiúsculas).
 */
@Suppress("TooManyFunctions") // porta que espelha as operações do contrato usadas pelo seed
interface ConfigGateway {
    /** Nome fantasia do hotel configurado, ou nulo se o hotel ainda não foi configurado. */
    suspend fun configuredHotelName(): String?

    suspend fun configureHotel(spec: HotelSpec)

    suspend fun guaranteePolicyId(name: String): String?

    suspend fun createGuaranteePolicy(spec: GuaranteePolicySpec): String

    suspend fun cancellationPolicyId(name: String): String?

    suspend fun createCancellationPolicy(spec: CancellationPolicySpec): String

    suspend fun roomTypeIds(): Map<String, String>

    suspend fun createRoomType(spec: RoomTypeSpec): String

    suspend fun areaNames(): Set<String>

    suspend fun createArea(spec: AreaSpec)

    suspend fun rooms(): Map<String, RoomState>

    suspend fun createRoom(
        spec: RoomSpec,
        roomTypeId: String,
    ): String

    suspend fun changeHousekeeping(
        roomId: String,
        transition: HousekeepingTransition,
    )

    suspend fun openOutOfOrder(
        roomId: String,
        from: LocalDate,
        to: LocalDate,
    )

    suspend fun seasonIds(): Map<String, String>

    suspend fun createSeason(spec: SeasonSpec): String

    suspend fun ratePlanId(code: String): String?

    suspend fun createRatePlan(
        spec: RatePlanSpec,
        roomTypeIds: List<String>,
        guaranteePolicyId: String,
        cancellationPolicyId: String,
    ): String

    @Suppress("LongParameterList") // assinatura fixa da porta: espelha a linha de preço do contrato
    suspend fun updatePrices(
        ratePlanId: String,
        roomTypeId: String,
        seasonId: String,
        oneAdult: BigDecimal,
        twoAdults: BigDecimal,
        extraAdult: BigDecimal,
    )
}
