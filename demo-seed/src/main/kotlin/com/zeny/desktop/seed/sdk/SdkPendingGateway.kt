package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.BookingRequest
import com.zeny.desktop.sdk.model.GuaranteePolicyRequest
import com.zeny.desktop.sdk.model.GuestDocumentSearchRequest
import com.zeny.desktop.sdk.model.OpenOutOfOrderRequest
import com.zeny.desktop.sdk.model.PriceValuesRequest
import com.zeny.desktop.sdk.model.RatePlanCreateRequest
import com.zeny.desktop.sdk.model.RatePriceUpdateRequest
import com.zeny.desktop.sdk.model.ReservationCancelRequest
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationRequest
import com.zeny.desktop.sdk.model.RoomAssignRequest
import com.zeny.desktop.sdk.model.StayGuestRequest
import com.zeny.desktop.seed.pending.FutureGateway
import com.zeny.desktop.seed.pending.PendingGateway
import com.zeny.desktop.seed.pending.PendingPlanSpec
import com.zeny.desktop.seed.pending.PendingPriceSpec
import com.zeny.desktop.seed.pending.PendingReservationRef
import com.zeny.desktop.seed.pending.PendingReservationSpec
import de.quati.kotlin.util.Option
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Pendências e reservas futuras sobre o `sdk`: política de garantia por cartão, plano só no site, preços por período,
 * reservas por `externalId`, a UH fora de ordem e o cancelamento. `createBooking` e `cancelReservation` exigem
 * `Idempotency-Key`: cada chamada cria a sua `Intent` (chave nova), como o `SdkBookingGateway`.
 * O CPF vai só no corpo, e o erro dessa busca sai sem ele. Em nenhuma operação o `detail` do servidor sai na
 * mensagem nem na causa do erro (`B-M4`).
 *
 * Os nomes e códigos são comparados sem diferenciar maiúsculas, porque o servidor os trata assim.
 */
@OptIn(RawSdkAccess::class)
@Suppress("TooManyFunctions") // porta que espelha as operações do contrato usadas pelo seeder
class SdkPendingGateway(
    private val seed: SeedCore,
) : PendingGateway,
    FutureGateway {
    private val executor get() = seed.executor
    private val rates get() = seed.apis.rates
    private val guestsApi get() = seed.apis.guests
    private val reservationsApi get() = seed.apis.reservations
    private val roomsApi get() = seed.apis.rooms

    override suspend fun guaranteePolicyId(name: String): String? =
        readAll { cursor, limit ->
            val page = executor.execute { rates.listGuaranteePolicies(null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id?.toString()

    override suspend fun createCardGuaranteePolicy(name: String): String {
        val request = GuaranteePolicyRequest(name = name, type = GuaranteePolicyRequest.Type.CARD)
        return redactingServerDetail {
            executor
                .execute { rates.createGuaranteePolicy(request, it.headers) }
                .value.id
                .toString()
        }
    }

    override suspend fun cancellationPolicyId(name: String): String? =
        readAll { cursor, limit ->
            val page = executor.execute { rates.listCancellationPolicies(null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id?.toString()

    override suspend fun ratePlanId(code: String): String? =
        readAll { cursor, limit ->
            val page = executor.execute { rates.listRatePlans(null, null, null, null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.firstOrNull { it.code.equals(code, ignoreCase = true) }?.id?.toString()

    override suspend fun createWebsiteRatePlan(spec: PendingPlanSpec): String {
        val request =
            RatePlanCreateRequest(
                cancellationPolicyId = Uuid.parse(spec.cancellationPolicyId),
                category = RatePlanCreateRequest.Category.NORMAL,
                channels = listOf(RatePlanCreateRequest.ChannelsItem.WEBSITE),
                code = spec.code,
                guaranteePolicyId = Uuid.parse(spec.guaranteePolicyId),
                mealPlan = RatePlanCreateRequest.MealPlan.ROOM_ONLY,
                name = spec.name,
                packageIds = emptyList(),
                roomTypeIds = listOf(Uuid.parse(spec.roomTypeId)),
            )
        return redactingServerDetail {
            executor
                .execute { rates.createRatePlan(request, it.headers) }
                .value.id
                .toString()
        }
    }

    /**
     * Grava as duas diárias de `from` a `to` (inclusive), todos os dias da semana e sem preço de criança nem de
     * adulto extra. O período não leva `seasonId` (o servidor recusa os dois juntos). Os valores seguem como
     * texto decimal de duas casas; mais de duas casas é erro do seed (`ArithmeticException`), não arredondamento.
     * Regravar valores iguais não pede aprovação (contrato).
     */
    override suspend fun writePrices(
        ratePlanId: String,
        prices: PendingPriceSpec,
    ) {
        val request =
            RatePriceUpdateRequest(
                from = Option.Some(prices.from.toString()),
                prices =
                    PriceValuesRequest(
                        children = emptyList(),
                        oneAdult = prices.oneAdult.money(),
                        twoAdults = Option.Some(prices.twoAdults.money()),
                    ),
                roomTypeIds = listOf(Uuid.parse(prices.roomTypeId)),
                to = Option.Some(prices.to.toString()),
                weekdays = emptyList(),
            )
        redactingServerDetail {
            executor.execute { rates.updateRatePlanPrices(Uuid.parse(ratePlanId), null, request, it.headers) }
        }
    }

    override suspend fun guestId(cpf: String): String? {
        val request = GuestDocumentSearchRequest(number = cpf, type = GuestDocumentSearchRequest.Type.CPF)
        return redactingServerDetail {
            redactingValues(listOf(cpf)) {
                executor
                    .execute { guestsApi.searchGuestsByDocument(request, it.headers) }
                    .value.items
                    .firstOrNull()
                    ?.id
                    ?.toString()
            }
        }
    }

    /**
     * O filtro `externalId` do servidor não é garantia (o contrato só o descreve como número da OTA): o gateway
     * confere o `externalId` de cada item devolvido.
     */
    override suspend fun findReservation(externalId: String): PendingReservationRef? {
        val pager =
            CursorPager<ReservationListItemResponse> { cursor, limit ->
                val page =
                    executor
                        .execute {
                            reservationsApi.searchReservations(
                                externalId = externalId,
                                cursor = cursor,
                                limit = limit,
                                block = it.headers,
                            )
                        }.value
                Page(page.items, (page.nextCursor as? Option.Some)?.value)
            }
        return redactingServerDetail {
            var found: PendingReservationRef? = null
            while (found == null && pager.hasMore) {
                found =
                    pager
                        .next()
                        .firstOrNull { (it.externalId as? Option.Some)?.value == externalId }
                        ?.let {
                            PendingReservationRef(
                                it.id.toString(),
                                it.status,
                                (it.roomAssignment as? Option.Some)?.value?.roomId?.toString(),
                            )
                        }
            }
            found
        }
    }

    /** A origem é um valor de `BookingRequest.Origin`; outro valor lança `IllegalArgumentException`, sem rede. */
    override suspend fun createReservation(spec: PendingReservationSpec): PendingReservationRef {
        val holder = Uuid.parse(spec.holderGuestId)
        val request =
            BookingRequest(
                externalId = Option.Some(spec.externalId),
                holderGuestId = holder,
                origin = BookingRequest.Origin.fromSerial(spec.origin),
                reservations =
                    listOf(
                        ReservationRequest(
                            adults = spec.adults,
                            arrival = spec.arrival.toString(),
                            childAges = emptyList(),
                            departure = spec.departure.toString(),
                            guests = listOf(StayGuestRequest(guestId = holder, primary = true)),
                            nightlyRates = emptyList(),
                            ratePlanId = Uuid.parse(spec.ratePlanId),
                            roomTypeId = Uuid.parse(spec.roomTypeId),
                            segment = ReservationRequest.Segment.LEISURE,
                        ),
                    ),
            )
        val intent = Intent()
        val response =
            redactingServerDetail {
                executor.execute(intent) {
                    reservationsApi.createBooking(it.requireIdempotencyKey(), null, request, it.headers)
                }
            }
        val created = response.value.reservations.single()
        return PendingReservationRef(created.id.toString(), created.status, null)
    }

    override suspend fun assignRoom(
        reservationId: String,
        roomId: String,
    ) {
        val request =
            RoomAssignRequest(roomId = Uuid.parse(roomId), reason = Option.Some(RoomAssignRequest.Reason.INITIAL))
        redactingServerDetail {
            executor.execute { reservationsApi.assignReservationRoom(Uuid.parse(reservationId), request, it.headers) }
        }
    }

    /**
     * Cancela sem isentar a multa, com chave própria, e não devolve nada: o êxito é só não lançar. Numa recusa sobe
     * um `ApiError` com o status e o código do servidor, sem o `detail` (que pode ecoar dado do hóspede).
     */
    override suspend fun cancelReservation(
        reservationId: String,
        reason: String,
    ) {
        val request =
            ReservationCancelRequest(reason = ReservationCancelRequest.Reason.fromSerial(reason), waivePenalty = false)
        redactingServerDetail {
            executor.execute(Intent()) {
                reservationsApi.cancelReservation(
                    Uuid.parse(reservationId),
                    it.requireIdempotencyKey(),
                    null,
                    request,
                    it.headers,
                )
            }
        }
    }

    /** `false` só no `409 OUT_OF_ORDER_OVERLAP` (período já aberto); qualquer outra recusa sobe. */
    override suspend fun openOutOfOrder(
        roomId: String,
        from: LocalDate,
        to: LocalDate,
        reason: String,
    ): Boolean {
        val request =
            OpenOutOfOrderRequest(
                endsOn = to.toString(),
                kind = OpenOutOfOrderRequest.Kind.OUT_OF_ORDER,
                reason = reason,
                responsible = RESPONSIBLE,
                startsOn = from.toString(),
            )
        return try {
            redactingServerDetail {
                executor.execute { roomsApi.openOutOfOrder(Uuid.parse(roomId), null, request, it.headers) }
            }
            true
        } catch (e: ApiError.Problem) {
            if (e.status == CONFLICT && e.code == OVERLAP_CODE) false else throw e
        }
    }

    private suspend fun <I> readAll(fetch: suspend (cursor: String?, limit: Int) -> Page<I>): List<I> {
        val pager = CursorPager(fetch = fetch)
        val all = mutableListOf<I>()
        redactingServerDetail { while (pager.hasMore) all += pager.next() }
        return all
    }

    private fun BigDecimal.money(): String = setScale(MONEY_SCALE, RoundingMode.UNNECESSARY).toPlainString()

    private companion object {
        const val RESPONSIBLE = "Equipe de manutenção (demo)"
        const val OVERLAP_CODE = "OUT_OF_ORDER_OVERLAP"
        const val CONFLICT = 409
        const val MONEY_SCALE = 2
    }
}
