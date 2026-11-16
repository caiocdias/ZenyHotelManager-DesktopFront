package com.zeny.desktop.app.reception

import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.ReservationStateResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

private val resId = Uuid.parse("20000000-0000-0000-0000-0000000000b1")
private val holder = Uuid.parse("30000000-0000-0000-0000-0000000000b1")
private val plan = Uuid.parse("40000000-0000-0000-0000-0000000000b1")

private fun noShowAssignment() =
    RoomAssignmentResponse(
        active = false,
        assignedAt = "2026-11-13T15:00:00Z",
        dayUse = false,
        doNotMove = false,
        endsOn = "2026-11-15",
        id = Uuid.random(),
        reason = "INITIAL",
        releaseReason = Option.Some("RESERVATION_NO_SHOW"),
        reservationId = resId,
        roomId = Uuid.random(),
        roomNumber = Option.Some("103"),
        startsOn = "2026-11-13",
    )

private fun noShowReservation() =
    ReservationDetailResponse(
        bookingCode = "B-1",
        holderGuestId = holder,
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = 2,
                arrival = "2026-11-13",
                bookingId = Uuid.random(),
                cancellationTerms =
                    CancellationTermsResponse(
                        24,
                        "Flexível",
                        penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                        policyId = Uuid.random(),
                        version = 1,
                    ),
                childAges = emptyList(),
                code = "R-A",
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                departure = "2026-11-15",
                folioId = Uuid.random(),
                guaranteeTerms =
                    GuaranteeTermsResponse(
                        name = "Sem garantia",
                        policyId = Uuid.random(),
                        type = "NONE",
                        version = 1,
                    ),
                guests = emptyList(),
                id = resId,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
                ratePlanId = plan,
                roomTotal = "100.00",
                roomTypeId = Uuid.parse(typeId(1)),
                segment = "LEISURE",
                status = "NO_SHOW",
                total = "100.00",
                version = 2,
            ),
        restrictionAlerts = emptyList(),
    )

private fun snapshot(status: String) =
    ReservationStateResponse(
        additionalPackagesTotal = "0.00",
        adults = 2,
        arrival = "2026-11-13",
        cancellationPolicyId = Uuid.random(),
        cancellationPolicyVersion = 1,
        childAges = emptyList(),
        departure = "2026-11-15",
        guaranteePolicyId = Uuid.random(),
        guaranteePolicyVersion = 1,
        hasSpecialRequests = false,
        nights = emptyList(),
        ratePlanId = plan,
        roomTotal = "100.00",
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = status,
        stayGuests = emptyList(),
        total = "100.00",
    )

private fun noShowEntry() =
    ReservationHistoryResponse(
        actorId = Option.Some(Uuid.random()),
        after = snapshot("NO_SHOW"),
        approvalId = Option.Undefined,
        before = snapshot("CONFIRMED"),
        businessDate = "2026-11-13",
        changedFields = emptyList(),
        id = Uuid.random(),
        kind = "NO_SHOW",
        occurredAt = "2026-11-14T06:00:00Z",
        version = 2,
    )

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationDetailNoShowLabelTest {
    @Test
    fun noShowReservationDetailHasNoOtherLabel() =
        runTest {
            val id = resId.toString()
            val source =
                FakeReceptionSource().apply {
                    businessDay = BusinessDay(BusinessDate.parse("2026-11-14"), ZoneId.of("America/Sao_Paulo"))
                    types += roomType(typeId(1), "Standard")
                    reservations[id] = noShowReservation()
                    holders[holder.toString()] = HolderSummary("Maria Souza", "CPF ***.***.***-34")
                    ratePlans[plan.toString()] = "Tarifa Balcão"
                    histories[id] = listOf(noShowEntry())
                    assignments[id] = listOf(noShowAssignment())
                }
            val state = ReservationDetailState(source, backgroundScope)
            state.open(id)
            runCurrent()
            val view = assertNotNull(state.view)

            assertEquals("Não compareceu", view.history.last().title)
            assertEquals("Solta · Não compareceu", view.assignments.single().state)
            val texts =
                view.history.flatMap { listOf(it.at, it.title, it.detail) } +
                    view.assignments.flatMap { listOf(it.room, it.period, it.reason, it.state) }
            texts.forEach { assertFalse(it.contains("Outro ("), it) }
        }
}
