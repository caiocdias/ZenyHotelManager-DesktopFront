package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.GuestPick
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.BookingResponse
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

/** Fixtures do testador de etapa da M3 E06: a oferta custa 300,00 e o servidor devolve 655,37 (para distingui-los). */
internal const val ET_GUEST_ID = "70000000-0000-0000-0000-0000000000e6"
internal const val ET_OFFER_TOTAL_TEXT = "R$ 300,00"
internal const val ET_SERVER_TOTAL_TEXT = "R$ 655,37"
internal val etGuest = GuestPick(ET_GUEST_ID, HolderSummary("Ana Etapa", null))
internal val etOtherGuest = GuestPick("70000000-0000-0000-0000-0000000000e7", HolderSummary("Bia Etapa", null))
internal val etStay = QuotedStay(BusinessDate.parse("2026-11-13"), BusinessDate.parse("2026-11-16"), 2, emptyList())
private val etType = Uuid.parse("10000000-0000-0000-0000-0000000000e6")
private val etPlan = Uuid.parse("20000000-0000-0000-0000-0000000000e6")

internal fun etOffer() =
    QuoteOfferResponse(
        accommodationTotal = "300.00",
        additionalPackagesTotal = "0.00",
        availableUnits = 2,
        cancellationPolicyId = Uuid.random(),
        category = QuoteOfferResponse.Category.NORMAL,
        guaranteePolicyId = Uuid.random(),
        includedPackagesTotal = "0.00",
        mealPlan = "BREAKFAST",
        nights = emptyList(),
        ratePlanCode = "BAR",
        ratePlanId = etPlan,
        ratePlanName = "Balcão",
        roomTotal = "300.00",
        roomTypeCode = "STD",
        roomTypeId = etType,
        roomTypeName = "Standard",
        total = "300.00",
    )

internal fun etResponse(total: String = "655.37") =
    BookingResponse(
        channel = "DIRECT",
        code = "B-E6",
        createdAt = "2026-11-12T10:00:00Z",
        holderGuestId = Uuid.parse(ET_GUEST_ID),
        id = Uuid.random(),
        origin = "FRONT_DESK",
        reservations =
            listOf(
                ReservationResponse(
                    additionalPackagesTotal = "0.00",
                    adults = 2,
                    arrival = "2026-11-13",
                    bookingId = Uuid.random(),
                    cancellationTerms =
                        CancellationTermsResponse(
                            deadlineHours = 24,
                            name = "Flex",
                            penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                            penaltyNights = Option.Some(1),
                            policyId = Uuid.random(),
                            version = 1,
                        ),
                    childAges = emptyList(),
                    code = "R-E6",
                    createdAt = "2026-11-12T10:00:00Z",
                    dayUse = false,
                    departure = "2026-11-16",
                    folioId = Uuid.random(),
                    guaranteeTerms =
                        GuaranteeTermsResponse(name = "Sem", policyId = Uuid.random(), type = "NONE", version = 1),
                    guests = emptyList(),
                    id = Uuid.parse("30000000-0000-0000-0000-0000000000e6"),
                    nightlyRates = emptyList(),
                    penaltiesCharged = "0.00",
                    ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                    ratePlanId = etPlan,
                    roomTotal = total,
                    roomTypeId = etType,
                    segment = "LEISURE",
                    status = "CONFIRMED",
                    total = total,
                    version = 1,
                ),
            ),
        restrictionAlerts = emptyList(),
        version = 1,
    )

internal fun etApproval() = ApiError.ApprovalRequired("DISCOUNT", "rates.discounts.apply", "res", null, null)
