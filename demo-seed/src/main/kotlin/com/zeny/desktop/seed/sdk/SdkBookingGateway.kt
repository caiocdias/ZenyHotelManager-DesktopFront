package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.BookingRequest
import com.zeny.desktop.sdk.model.CheckInGuestRequest
import com.zeny.desktop.sdk.model.CheckInRequest
import com.zeny.desktop.sdk.model.CompanyRequest
import com.zeny.desktop.sdk.model.CompanyResponse
import com.zeny.desktop.sdk.model.CreateGuestRequest
import com.zeny.desktop.sdk.model.GuestDocumentRequest
import com.zeny.desktop.sdk.model.GuestDocumentSearchRequest
import com.zeny.desktop.sdk.model.GuestProfileRequest
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationRequest
import com.zeny.desktop.sdk.model.RoomAssignRequest
import com.zeny.desktop.sdk.model.StayGuestRequest
import com.zeny.desktop.seed.booking.BookingGateway
import com.zeny.desktop.seed.booking.InventoryDay
import com.zeny.desktop.seed.booking.ReservationRef
import com.zeny.desktop.seed.data.CompanySpec
import com.zeny.desktop.seed.data.GuestSpec
import com.zeny.desktop.seed.data.ReservationSpec
import de.quati.kotlin.util.Option
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Empresas, hóspedes e reservas sobre o `sdk`. `createBooking` e `checkInReservation` exigem `Idempotency-Key`:
 * cada chamada deste gateway cria a sua `Intent` (chave nova); uma repetição decidida pelo semeador é chamada
 * nova, porque o servidor guarda por 24 h a resposta de cada chave, a de erro também. O CPF vai só no corpo.
 *
 * A reserva é achada por `searchReservations(externalId)`; como o contrato só descreve `externalId` como número
 * da OTA, o filtro do servidor não é garantia e o gateway confere o `externalId` de cada item devolvido.
 */
@OptIn(RawSdkAccess::class)
@Suppress("TooManyFunctions") // porta que espelha as operações do contrato usadas pelo seed
class SdkBookingGateway(
    private val seed: SeedCore,
) : BookingGateway {
    private val executor get() = seed.executor
    private val guestsApi get() = seed.apis.guests
    private val reservationsApi get() = seed.apis.reservations
    private val frontdeskApi get() = seed.apis.frontdesk

    /** Adultos reservados por reserva já vista, para o `guestCount` do check-in (a porta só passa os hóspedes). */
    private val adultsByReservation = mutableMapOf<String, Int>()

    override suspend fun companyId(cnpj: String): String? = redactingValues(listOf(cnpj)) { findCompany(cnpj) }

    private suspend fun findCompany(cnpj: String): String? {
        val pager =
            CursorPager<CompanyResponse> { cursor, limit ->
                val page =
                    executor
                        .execute {
                            guestsApi.listCompanies(
                                null,
                                cnpj,
                                null,
                                cursor,
                                limit,
                                it.headers,
                            )
                        }.value
                Page(page.items, (page.nextCursor as? Option.Some)?.value)
            }
        while (pager.hasMore) {
            pager.next().firstOrNull { it.cnpj.equals(cnpj, ignoreCase = true) }?.let { return it.id.toString() }
        }
        return null
    }

    override suspend fun createCompany(spec: CompanySpec): String {
        val request =
            CompanyRequest(
                cnpj = spec.cnpj,
                legalName = spec.legalName,
                tradeName = Option.Some(spec.tradeName),
            )
        return redactingValues(listOf(spec.cnpj, spec.legalName, spec.tradeName)) {
            executor
                .execute { guestsApi.createCompany(request, it.headers) }
                .value.id
                .toString()
        }
    }

    override suspend fun guestId(cpf: String): String? {
        val request = GuestDocumentSearchRequest(number = cpf, type = GuestDocumentSearchRequest.Type.CPF)
        return redactingValues(listOf(cpf)) {
            executor
                .execute { guestsApi.searchGuestsByDocument(request, it.headers) }
                .value.items
                .firstOrNull()
                ?.id
                ?.toString()
        }
    }

    override suspend fun createGuest(
        spec: GuestSpec,
        companyId: String?,
    ): String {
        val request =
            CreateGuestRequest(
                documents = listOf(GuestDocumentRequest(number = spec.cpf, type = GuestDocumentRequest.Type.CPF)),
                profile =
                    GuestProfileRequest(
                        birthDate = Option.Some(spec.birthDate.toString()),
                        companyId = if (companyId == null) Option.Undefined else Option.Some(Uuid.parse(companyId)),
                        fullName = spec.fullName,
                        nationality = Option.Some(BRAZIL),
                        residenceCountry = Option.Some(BRAZIL),
                        vip = false,
                    ),
            )
        return redactingValues(listOf(spec.cpf, spec.fullName)) {
            executor
                .execute { guestsApi.createGuest(request, it.headers) }
                .value.id
                .toString()
        }
    }

    override suspend fun inventory(
        roomTypeId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<InventoryDay> =
        executor
            .execute {
                reservationsApi.listRoomTypeInventory(
                    Uuid.parse(roomTypeId),
                    from.toString(),
                    to.toString(),
                    it.headers,
                )
            }.value.items
            .map { InventoryDay(LocalDate.parse(it.date), it.total, it.outOfOrder) }

    override suspend fun findReservation(externalId: String): ReservationRef? {
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
        while (pager.hasMore) {
            val item = pager.next().firstOrNull { (it.externalId as? Option.Some)?.value == externalId }
            if (item != null) {
                adultsByReservation[item.id.toString()] = item.adults
                return ReservationRef(
                    item.id.toString(),
                    item.status,
                    (item.roomAssignment as? Option.Some)?.value?.roomId?.toString(),
                )
            }
        }
        return null
    }

    override suspend fun createReservation(
        spec: ReservationSpec,
        holderGuestId: String,
        roomTypeId: String,
        ratePlanId: String,
    ): ReservationRef {
        val holder = Uuid.parse(holderGuestId)
        val request =
            BookingRequest(
                externalId = Option.Some(spec.externalId),
                holderGuestId = holder,
                origin = BookingRequest.Origin.FRONT_DESK,
                reservations =
                    listOf(
                        ReservationRequest(
                            adults = spec.adults,
                            arrival = spec.arrival.toString(),
                            childAges = emptyList(),
                            departure = spec.departure.toString(),
                            guests = listOf(StayGuestRequest(guestId = holder, primary = true)),
                            nightlyRates = emptyList(),
                            ratePlanId = Uuid.parse(ratePlanId),
                            roomTypeId = Uuid.parse(roomTypeId),
                            segment = ReservationRequest.Segment.LEISURE,
                        ),
                    ),
            )
        val intent = Intent()
        val created =
            executor
                .execute(
                    intent,
                ) { reservationsApi.createBooking(it.requireIdempotencyKey(), null, request, it.headers) }
                .value.reservations
                .single()
        adultsByReservation[created.id.toString()] = spec.adults
        return ReservationRef(created.id.toString(), created.status, null)
    }

    override suspend fun assignRoom(
        reservationId: String,
        roomId: String,
    ) {
        val request =
            RoomAssignRequest(roomId = Uuid.parse(roomId), reason = Option.Some(RoomAssignRequest.Reason.INITIAL))
        executor.execute { reservationsApi.assignReservationRoom(Uuid.parse(reservationId), request, it.headers) }
    }

    override suspend fun checkIn(
        reservationId: String,
        roomId: String,
        guestIds: List<String>,
    ) {
        val request =
            CheckInRequest(
                guestCount = adultsByReservation[reservationId]?.let { Option.Some(it) } ?: Option.Undefined,
                guests =
                    guestIds.mapIndexed {
                        i,
                        id,
                        ->
                        CheckInGuestRequest(guestId = Uuid.parse(id), primary = i == 0)
                    },
                roomId = Option.Some(Uuid.parse(roomId)),
            )
        val intent = Intent()
        executor.execute(intent) {
            frontdeskApi.checkInReservation(Uuid.parse(reservationId), it.requireIdempotencyKey(), request, it.headers)
        }
    }

    private companion object {
        const val BRAZIL = "BR"
    }
}
