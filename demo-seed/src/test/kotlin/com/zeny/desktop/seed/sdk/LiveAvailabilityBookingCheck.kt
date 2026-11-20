package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.error.ErrorCatalog
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.BookingRequest
import com.zeny.desktop.sdk.model.QuoteUnavailableResponse
import com.zeny.desktop.sdk.model.ReservationCancelRequest
import com.zeny.desktop.sdk.model.ReservationRequest
import com.zeny.desktop.sdk.model.RestrictionUpdateRequest
import com.zeny.desktop.sdk.model.RestrictionValuesDto
import com.zeny.desktop.sdk.model.StayGuestRequest
import com.zeny.desktop.seed.cli.ParseResult
import com.zeny.desktop.seed.cli.SeedArgs
import com.zeny.desktop.seed.cli.SeedConfig
import com.zeny.desktop.seed.data.FictitiousDocuments
import com.zeny.desktop.seed.people.AdminSignIn
import com.zeny.desktop.seed.people.shutDown
import de.quati.kotlin.util.Option
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

private const val BOOKING_OFFSET_DAYS = 20L
private const val RESTRICTION_OFFSET_DAYS = 25L
private const val MIN_STAY_NIGHTS = 2
private const val GUEST_CPF_BASE = "202600101"
private const val PLAN_CODE = "BALCAO"
private const val ROOM_TYPE_CODE = "STD"

/**
 * Verificação ao vivo do "outro cliente" (M3 E02, critérios 1 e 3). Escreve e desfaz no servidor de demo, como
 * Administrador: só roda com `ZENY_SEED_SERVER` e `ZENY_SEED_LIVE_AVAILABILITY=1` (sem elas, pula). Credenciais só
 * do ambiente (nunca impressas). Comando, só nesse subshell:
 * `(set -a; . .server/seed-secrets.env; set +a; ZENY_SEED_LIVE_AVAILABILITY=1 ./gradlew :demo-seed:test
 * --tests '*LiveAvailabilityBookingCheck*' --rerun --max-workers=2)`
 */
@OptIn(RawSdkAccess::class)
@Suppress("TooManyFunctions")
class LiveAvailabilityBookingCheck {
    private fun optedIn() =
        assumeTrue(System.getenv("ZENY_SEED_SERVER") != null && System.getenv("ZENY_SEED_LIVE_AVAILABILITY") == "1")

    private fun config(): SeedConfig {
        val parsed = SeedArgs.parse(emptyList(), System.getenv())
        assertTrue(parsed is ParseResult.Ok, "variáveis ZENY_SEED_* incompletas")
        return parsed.config
    }

    private fun cancelBody() =
        ReservationCancelRequest(
            reason = ReservationCancelRequest.Reason.HOTEL_REQUEST,
            waivePenalty = Option.Some(false),
        )

    private suspend fun cancel(
        seed: SeedCore,
        id: Uuid,
    ) {
        val api = seed.apis.reservations
        val body = cancelBody()
        seed.executor.execute(
            Intent(),
        ) { api.cancelReservation(id, it.requireIdempotencyKey(), null, body, it.headers) }
    }

    private fun bookingRequest(
        arrival: BusinessDate,
        holder: Uuid,
        ratePlanId: Uuid,
        roomTypeId: Uuid,
    ) = BookingRequest(
        externalId = Option.Some("DEMO-E02-${Uuid.random()}"),
        holderGuestId = holder,
        origin = BookingRequest.Origin.FRONT_DESK,
        reservations =
            listOf(
                ReservationRequest(
                    adults = 1,
                    arrival = arrival.toApi(),
                    childAges = Option.Some(emptyList()),
                    departure = BusinessDate(arrival.value.plusDays(1)).toApi(),
                    guests = Option.Some(listOf(StayGuestRequest(guestId = holder, primary = Option.Some(true)))),
                    nightlyRates = Option.Some(emptyList()),
                    ratePlanId = ratePlanId,
                    roomTypeId = roomTypeId,
                    segment = ReservationRequest.Segment.LEISURE,
                ),
            ),
    )

    @Test
    fun otherClientBookingReducesAvailability() {
        optedIn()
        val config = config()
        runBlocking {
            val admin = AdminSignIn(SdkSessionFactory(config.server)).open(config)
            var createdId: Uuid? = null
            var cancelled = false
            try {
                val seed = admin.seed
                val day =
                    seed.core.hotel
                        .businessDate()
                        .value
                        .plusDays(BOOKING_OFFSET_DAYS)
                val date = BusinessDate(day)
                val configGateway = SdkConfigGateway(seed)
                val roomTypeId =
                    requireNotNull(
                        configGateway.roomTypeIds()[ROOM_TYPE_CODE],
                    ) { "NEED_SEED: sem tipo $ROOM_TYPE_CODE" }
                val planId = requireNotNull(configGateway.ratePlanId(PLAN_CODE)) { "NEED_SEED: sem plano $PLAN_CODE" }
                val guestId =
                    requireNotNull(SdkBookingGateway(seed).guestId(FictitiousDocuments.cpf(GUEST_CPF_BASE))) {
                        "NEED_SEED: sem o hóspede G1"
                    }

                suspend fun available(): Int =
                    seed.core.reservations
                        .availability(date, date)
                        .roomTypes
                        .single { it.roomTypeId.toString() == roomTypeId }
                        .days
                        .single { it.date == date.toApi() }
                        .available

                val before = available()
                if (before < 1) fail("NEED_SEED: sem UH $ROOM_TYPE_CODE disponível em d+$BOOKING_OFFSET_DAYS")
                val request = bookingRequest(date, Uuid.parse(guestId), Uuid.parse(planId), Uuid.parse(roomTypeId))
                val api = seed.apis.reservations
                val created =
                    seed.executor
                        .execute(Intent()) { api.createBooking(it.requireIdempotencyKey(), null, request, it.headers) }
                        .value.reservations
                        .single()
                createdId = created.id
                println("reserva criada: ${created.id} (disponível antes: $before)")
                assertEquals(before - 1, available(), "a reserva de outro cliente reduz a disponibilidade")
                cancel(seed, created.id)
                cancelled = true
                assertEquals(before, available(), "o cancelamento devolve a disponibilidade")
            } finally {
                try {
                    val id = createdId
                    if (id != null && !cancelled) cancel(admin.seed, id)
                } finally {
                    admin.shutDown()
                }
            }
        }
    }

    private fun stored(values: RestrictionValuesDto?): Boolean =
        values != null &&
            listOf(
                values.closed,
                values.closedToArrival,
                values.closedToDeparture,
                values.minStay,
                values.maxStay,
                values.minAdvanceDays,
                values.maxAdvanceDays,
            ).any { (it as? Option.Some)?.value != null }

    private fun update(
        date: BusinessDate,
        set: RestrictionValuesDto,
        clear: List<RestrictionUpdateRequest.ClearItem>,
    ) = RestrictionUpdateRequest(
        clear = Option.Some(clear),
        from = Option.Some(date.toApi()),
        roomTypeIds = Option.Some(emptyList()),
        `set` = Option.Some(set),
        to = Option.Some(date.toApi()),
        weekdays = Option.Some(emptyList()),
    )

    @Test
    fun minStayRestrictionRefusesInPortuguese() {
        optedIn()
        val config = config()
        runBlocking {
            val admin = AdminSignIn(SdkSessionFactory(config.server)).open(config)
            var written = false
            try {
                val seed = admin.seed
                val day =
                    seed.core.hotel
                        .businessDate()
                        .value
                        .plusDays(RESTRICTION_OFFSET_DAYS)
                val date = BusinessDate(day)
                val planId =
                    Uuid.parse(
                        requireNotNull(
                            SdkConfigGateway(seed).ratePlanId(PLAN_CODE),
                        ) { "NEED_SEED: sem plano $PLAN_CODE" },
                    )
                val rates = seed.apis.rates

                if (hasStored(seed, planId, date)) {
                    fail(
                        "NEED_SEED: há restrição em d+$RESTRICTION_OFFSET_DAYS no $PLAN_CODE, talvez de uma execução " +
                            "interrompida; retire com clear = [MIN_STAY] nessa data ou recrie a demo",
                    )
                }
                written = true
                val set = update(date, RestrictionValuesDto(minStay = Option.Some(MIN_STAY_NIGHTS)), emptyList())
                seed.executor.execute { rates.updateRatePlanRestrictions(planId, set, it.headers) }
                val quote =
                    seed.core.reservations
                        .quoteStay(date, BusinessDate(day.plusDays(1)), 2, emptyList(), includeUnavailable = true)
                assertTrue(quote.offers.none { it.ratePlanCode == PLAN_CODE }, "nenhuma oferta do $PLAN_CODE")
                val unavailable =
                    (quote.unavailable as? Option.Some)
                        ?.value
                        .orEmpty()
                        .filterNotNull()
                        .filter { it.ratePlanCode == PLAN_CODE }
                assertTrue(unavailable.isNotEmpty(), "o $PLAN_CODE aparece em unavailable")
                unavailable.forEach { assertMinStayRefusal(it) }
                println("combinações recusadas por estadia mínima: ${unavailable.size}")
            } finally {
                try {
                    if (written) clearMinStay(admin.seed)
                } finally {
                    admin.shutDown()
                }
            }
        }
    }

    private suspend fun hasStored(
        seed: SeedCore,
        planId: Uuid,
        date: BusinessDate,
    ): Boolean {
        val rates = seed.apis.rates
        return seed.executor
            .execute { rates.listRatePlanRestrictions(planId, date.toApi(), date.toApi(), null, it.headers) }
            .value.items
            .any { item ->
                stored((item.general as? Option.Some)?.value) ||
                    item.roomTypes.any { stored((it.specific as? Option.Some)?.value) }
            }
    }

    private suspend fun clearMinStay(seed: SeedCore) {
        val date =
            BusinessDate(
                seed.core.hotel
                    .businessDate()
                    .value
                    .plusDays(RESTRICTION_OFFSET_DAYS),
            )
        val planId = Uuid.parse(requireNotNull(SdkConfigGateway(seed).ratePlanId(PLAN_CODE)))
        val rates = seed.apis.rates
        val clear = update(date, RestrictionValuesDto(), listOf(RestrictionUpdateRequest.ClearItem.MIN_STAY))
        seed.executor.execute { rates.updateRatePlanRestrictions(planId, clear, it.headers) }
        val left = hasStored(seed, planId, date)
        assertTrue(!left, "a data voltou sem restrição")
    }

    private fun assertMinStayRefusal(item: QuoteUnavailableResponse) {
        val reason = item.reasons.firstOrNull { it.code == "MIN_STAY_NOT_MET" }
        assertTrue(reason != null, "motivo MIN_STAY_NOT_MET em ${item.roomTypeCode}")
        assertTrue(ErrorCatalog.isShowableDetail(reason.message), "a mensagem é texto de pessoa")
        assertTrue(
            reason.message.contains("exige ao menos $MIN_STAY_NIGHTS noite(s)"),
            "a mensagem em português diz a regra",
        )
    }
}
