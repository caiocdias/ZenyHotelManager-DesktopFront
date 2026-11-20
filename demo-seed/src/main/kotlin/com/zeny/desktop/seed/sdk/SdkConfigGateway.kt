package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.AddressDto
import com.zeny.desktop.sdk.model.AreaRequest
import com.zeny.desktop.sdk.model.CancellationPolicyRequest
import com.zeny.desktop.sdk.model.CashPointRequest
import com.zeny.desktop.sdk.model.GuaranteePolicyRequest
import com.zeny.desktop.sdk.model.HotelRequest
import com.zeny.desktop.sdk.model.HousekeepingStatusRequest
import com.zeny.desktop.sdk.model.HousekeepingStatusRequest.Transition
import com.zeny.desktop.sdk.model.OpenOutOfOrderRequest
import com.zeny.desktop.sdk.model.OutOfOrderOverlayResponse
import com.zeny.desktop.sdk.model.PriceValuesRequest
import com.zeny.desktop.sdk.model.RatePlanCreateRequest
import com.zeny.desktop.sdk.model.RatePriceUpdateRequest
import com.zeny.desktop.sdk.model.RoomRequest
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomTypeRequest
import com.zeny.desktop.sdk.model.SeasonRequest
import com.zeny.desktop.sdk.model.TaxSettingsDto
import com.zeny.desktop.seed.config.ConfigGateway
import com.zeny.desktop.seed.config.HousekeepingTransition
import com.zeny.desktop.seed.config.RoomState
import com.zeny.desktop.seed.config.TaxSettings
import com.zeny.desktop.seed.data.AreaSpec
import com.zeny.desktop.seed.data.CancellationPolicySpec
import com.zeny.desktop.seed.data.CashPointSpec
import com.zeny.desktop.seed.data.GuaranteePolicySpec
import com.zeny.desktop.seed.data.HotelSpec
import com.zeny.desktop.seed.data.RatePlanSpec
import com.zeny.desktop.seed.data.RoomSpec
import com.zeny.desktop.seed.data.RoomTypeSpec
import com.zeny.desktop.seed.data.SeasonSpec
import de.quati.kotlin.util.Option
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Cadastros da configuração sobre o `sdk`: hotel, políticas, tipos de UH, áreas e UHs. Nenhuma dessas operações
 * declara `Idempotency-Key` no contrato, então nenhuma leva `Intent`. Toda listagem lê todas as páginas, sem
 * filtro de `active`: o servidor recusa nome ou código repetido também de um item desativado.
 *
 * Os nomes de política (`guaranteePolicyId`, `cancellationPolicyId`) são comparados sem diferenciar maiúsculas,
 * como as áreas e as temporadas, porque o servidor os trata assim (409 `*_NAME_ALREADY_IN_USE`).
 */
@OptIn(RawSdkAccess::class)
@Suppress("TooManyFunctions") // porta que espelha as operações do contrato usadas pelo seed
class SdkConfigGateway(
    private val seed: SeedCore,
) : ConfigGateway {
    private val executor get() = seed.executor
    private val rates get() = seed.apis.rates
    private val roomsApi get() = seed.apis.rooms
    private val hotelApi get() = seed.apis.hotel
    private val billing get() = seed.apis.billing

    private suspend fun <I> readAll(fetch: suspend (cursor: String?, limit: Int) -> Page<I>): List<I> {
        val pager = CursorPager(fetch = fetch)
        val all = mutableListOf<I>()
        while (pager.hasMore) all += pager.next()
        return all
    }

    /**
     * Só `configured` falso é hotel ainda não configurado (nulo). Com `configured` verdadeiro e nome fantasia
     * ausente ou nulo, devolve "" (nunca nulo): o semeador vê "outro hotel configurado" e não o sobrescreve.
     */
    override suspend fun configuredHotelName(): String? {
        val hotel = executor.execute { hotelApi.getHotel(it.headers) }.value
        return if (hotel.configured) (hotel.tradeName as? Option.Some)?.value.orEmpty() else null
    }

    /**
     * O `PUT` leva o `ETag` do `GET` no `If-Match`, que o seed exige. Sem `ETag` (ausente ou em branco) na
     * resposta, falha com `ApiError.IncompatibleResponse` (mensagem pt-BR por `userMessage`) antes de enviar o
     * `PUT`: o servidor não cumpriu o contrato, e sobrescrever às cegas apagaria uma edição concorrente.
     */
    override suspend fun configureHotel(spec: HotelSpec) {
        val current = executor.execute { hotelApi.getHotel(it.headers) }
        val etag =
            current.etag
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiError.IncompatibleResponse(IllegalStateException("GET do hotel sem ETag"))
        val request =
            HotelRequest(
                address =
                    AddressDto(
                        city = spec.address.city,
                        district = spec.address.district,
                        ibgeCityCode = spec.address.ibgeCityCode,
                        number = spec.address.number,
                        postalCode = spec.address.postalCode,
                        state = AddressDto.State.fromSerial(spec.address.state),
                        street = spec.address.street,
                    ),
                cnpj = spec.cnpj,
                legalName = spec.legalName,
                taxRegime = HotelRequest.TaxRegime.fromSerial(spec.taxRegime),
                timezone = spec.timezone,
                tradeName = spec.tradeName,
            )
        executor.execute { hotelApi.updateHotel(etag, request, it.headers) }
    }

    /** Sem `ETag` (ausente ou em branco) falha com `ApiError.IncompatibleResponse`: o `PUT` exige o `If-Match`. */
    override suspend fun taxSettings(): TaxSettings {
        val current = executor.execute { hotelApi.getHotelTaxSettings(it.headers) }
        val etag =
            current.etag
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiError.IncompatibleResponse(IllegalStateException("GET das taxas do hotel sem ETag"))
        val dto = current.value
        return TaxSettings(
            issRate = dto.issRate,
            serviceChargeRate = dto.serviceChargeRate,
            tourismFeePerPersonNight = (dto.tourismFeePerPersonNight as? Option.Some)?.value,
            etag = etag,
        )
    }

    override suspend fun updateTaxSettings(settings: TaxSettings) {
        val dto =
            TaxSettingsDto(
                issRate = settings.issRate,
                serviceChargeRate = settings.serviceChargeRate,
                tourismFeePerPersonNight = Option.Some(settings.tourismFeePerPersonNight),
            )
        executor.execute { hotelApi.updateHotelTaxSettings(settings.etag, dto, it.headers) }
    }

    override suspend fun guaranteePolicyId(name: String): String? =
        readAll { cursor, limit ->
            val page = executor.execute { rates.listGuaranteePolicies(null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id?.toString()

    override suspend fun createGuaranteePolicy(spec: GuaranteePolicySpec): String {
        val request = GuaranteePolicyRequest(name = spec.name, type = GuaranteePolicyRequest.Type.NONE)
        return executor
            .execute { rates.createGuaranteePolicy(request, it.headers) }
            .value.id
            .toString()
    }

    override suspend fun cancellationPolicyId(name: String): String? =
        readAll { cursor, limit ->
            val page = executor.execute { rates.listCancellationPolicies(null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id?.toString()

    override suspend fun createCancellationPolicy(spec: CancellationPolicySpec): String {
        val request =
            CancellationPolicyRequest(
                deadlineHours = spec.deadlineHours,
                name = spec.name,
                penaltyNights = Option.Some(spec.penaltyNights),
                penaltyType = CancellationPolicyRequest.PenaltyType.NIGHTS,
            )
        return executor
            .execute { rates.createCancellationPolicy(request, it.headers) }
            .value.id
            .toString()
    }

    override suspend fun roomTypeIds(): Map<String, String> =
        readAll { cursor, limit ->
            val page = executor.execute { roomsApi.listRoomTypes(null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.associate { it.code.uppercase() to it.id.toString() }

    override suspend fun createRoomType(spec: RoomTypeSpec): String {
        val request =
            RoomTypeRequest(
                code = spec.code,
                displayOrder = Option.Some(spec.displayOrder),
                featureIds = Option.Some(emptyList()),
                maxAdults = spec.maxAdults,
                maxChildren = spec.maxChildren,
                name = spec.name,
                standardAdults = spec.standardAdults,
                standardChildren = spec.standardChildren,
            )
        return executor
            .execute { roomsApi.createRoomType(request, it.headers) }
            .value.id
            .toString()
    }

    override suspend fun areaNames(): Set<String> =
        readAll { cursor, limit ->
            val page = executor.execute { roomsApi.listAreas(null, null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.map { it.name }.toSet()

    override suspend fun createArea(spec: AreaSpec) {
        val request = AreaRequest(kind = AreaRequest.Kind.fromSerial(spec.kind), name = spec.name)
        executor.execute { roomsApi.createArea(request, it.headers) }
    }

    /**
     * As UHs ativas, pelo painel de status. `outOfOrder` é só o período OOO em vigor hoje; um OOS (fora de
     * serviço) não conta.
     */
    override suspend fun rooms(): Map<String, RoomState> =
        readAll { cursor, limit ->
            val page = executor.execute { roomsApi.listRoomStatuses(null, null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.filter { it.active }.associate { it.number to it.toState() }

    private fun RoomStatusResponse.toState() =
        RoomState(
            id = roomId.toString(),
            number = number,
            roomTypeId = roomTypeId.toString(),
            occupied = occupancy == RoomStatusResponse.Occupancy.OCCUPIED,
            housekeeping = housekeepingStatus.value,
            inspectionPending = inspectionPending,
            outOfOrder = (outOfOrder as? Option.Some)?.value?.kind == OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER,
        )

    override suspend fun createRoom(
        spec: RoomSpec,
        roomTypeId: String,
    ): String {
        val request =
            RoomRequest(
                featureIds = Option.Some(emptyList()),
                floor = Option.Some(spec.floor.toString()),
                number = spec.number,
                roomTypeId = Uuid.parse(roomTypeId),
            )
        return executor
            .execute { roomsApi.createRoom(request, it.headers) }
            .value.id
            .toString()
    }

    /**
     * Lê o `ETag` do status da UH e o manda no `If-Match` (o servidor responde 428 sem ele e 412 se a versão
     * mudou). Sem `ETag` (ausente ou em branco) na resposta, falha com `ApiError.IncompatibleResponse` antes do `POST`.
     * `MARK_CLEAN_PENDING_INSPECTION` é `MARK_CLEAN` com `requiresInspection`; não há `Idempotency-Key`.
     */
    override suspend fun changeHousekeeping(
        roomId: String,
        transition: HousekeepingTransition,
    ) {
        val id = Uuid.parse(roomId)
        val etag =
            executor
                .execute { roomsApi.getRoomStatus(id, it.headers) }
                .etag
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiError.IncompatibleResponse(IllegalStateException("GET do status da UH sem ETag"))
        val requiresInspection = transition == HousekeepingTransition.MARK_CLEAN_PENDING_INSPECTION
        val request =
            HousekeepingStatusRequest(
                requiresInspection = if (requiresInspection) Option.Some(true) else Option.Undefined,
                transition = Transition.fromSerial(transition.contractName()),
            )
        executor.execute { roomsApi.changeRoomHousekeepingStatus(id, etag, request, it.headers) }
    }

    override suspend fun openOutOfOrder(
        roomId: String,
        from: LocalDate,
        to: LocalDate,
    ) {
        val request =
            OpenOutOfOrderRequest(
                endsOn = to.toString(),
                kind = OpenOutOfOrderRequest.Kind.OUT_OF_ORDER,
                reason = OUT_OF_ORDER_REASON,
                responsible = OUT_OF_ORDER_RESPONSIBLE,
                startsOn = from.toString(),
            )
        executor.execute { roomsApi.openOutOfOrder(Uuid.parse(roomId), null, request, it.headers) }
    }

    /** Lê todas as páginas sem filtro de `active`, para ver também o desativado (o nome continua tomado). */
    override suspend fun cashPointActive(name: String): Boolean? =
        readAll { cursor, limit ->
            val page = executor.execute { billing.listCashPoints(null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.firstOrNull { it.name.equals(name, ignoreCase = true) }?.active

    /** `false` só no `409 CASH_POINT_NAME_ALREADY_IN_USE` (alguém criou entre a leitura e a escrita). */
    override suspend fun createCashPoint(spec: CashPointSpec): Boolean {
        val request = CashPointRequest(blindClose = Option.Some(spec.blindClose), name = spec.name)
        return try {
            executor.execute { billing.createCashPoint(request, it.headers) }
            true
        } catch (e: ApiError.Problem) {
            if (e.status == CONFLICT && e.code == CASH_POINT_NAME_IN_USE) false else throw e
        }
    }

    override suspend fun seasonIds(): Map<String, String> =
        readAll { cursor, limit ->
            val page = executor.execute { rates.listSeasons(null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.associate { it.name to it.id.toString() }

    override suspend fun createSeason(spec: SeasonSpec): String {
        val request =
            SeasonRequest(endsOn = spec.endsOn.toString(), name = spec.name, startsOn = spec.startsOn.toString())
        return executor
            .execute { rates.createSeason(request, it.headers) }
            .value.id
            .toString()
    }

    override suspend fun ratePlanId(code: String): String? =
        readAll { cursor, limit ->
            val page = executor.execute { rates.listRatePlans(null, null, null, null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.firstOrNull { it.code.equals(code, ignoreCase = true) }?.id?.toString()

    override suspend fun createRatePlan(
        spec: RatePlanSpec,
        roomTypeIds: List<String>,
        guaranteePolicyId: String,
        cancellationPolicyId: String,
    ): String {
        val request =
            RatePlanCreateRequest(
                cancellationPolicyId = Uuid.parse(cancellationPolicyId),
                category = RatePlanCreateRequest.Category.NORMAL,
                channels = spec.channels.map { RatePlanCreateRequest.ChannelsItem.fromSerial(it) },
                code = spec.code,
                guaranteePolicyId = Uuid.parse(guaranteePolicyId),
                mealPlan = RatePlanCreateRequest.MealPlan.fromSerial(spec.mealPlan),
                name = spec.name,
                packageIds = Option.Some(emptyList()),
                roomTypeIds = roomTypeIds.map { Uuid.parse(it) },
            )
        return executor
            .execute { rates.createRatePlan(request, it.headers) }
            .value.id
            .toString()
    }

    /**
     * Grava os três valores da temporada para um tipo de UH, todos os dias da semana e sem preço de criança. Os
     * valores seguem como texto decimal de duas casas (`toPlainString`, nunca notação científica); mais de duas
     * casas é erro do catálogo (`ArithmeticException`), não arredondamento de dinheiro. Regravar valores iguais
     * não pede aprovação (contrato).
     */
    @Suppress("LongParameterList") // assinatura fixa da porta: espelha a linha de preço do contrato
    override suspend fun updatePrices(
        ratePlanId: String,
        roomTypeId: String,
        seasonId: String,
        oneAdult: BigDecimal,
        twoAdults: BigDecimal,
        extraAdult: BigDecimal,
    ) {
        val request =
            RatePriceUpdateRequest(
                prices =
                    PriceValuesRequest(
                        children = emptyList(),
                        extraAdult = Option.Some(extraAdult.money()),
                        oneAdult = oneAdult.money(),
                        twoAdults = Option.Some(twoAdults.money()),
                    ),
                roomTypeIds = listOf(Uuid.parse(roomTypeId)),
                seasonId = Option.Some(Uuid.parse(seasonId)),
                weekdays = Option.Some(emptyList()),
            )
        executor.execute { rates.updateRatePlanPrices(Uuid.parse(ratePlanId), null, request, it.headers) }
    }

    private fun HousekeepingTransition.contractName() =
        if (this == HousekeepingTransition.MARK_CLEAN_PENDING_INSPECTION) "MARK_CLEAN" else name

    private fun BigDecimal.money(): String = setScale(MONEY_SCALE, RoundingMode.UNNECESSARY).toPlainString()

    private companion object {
        const val OUT_OF_ORDER_REASON = "Manutenção programada (demo)"
        const val OUT_OF_ORDER_RESPONSIBLE = "Equipe de manutenção (demo)"
        const val MONEY_SCALE = 2
        const val CONFLICT = 409
        const val CASH_POINT_NAME_IN_USE = "CASH_POINT_NAME_ALREADY_IN_USE"
    }
}
