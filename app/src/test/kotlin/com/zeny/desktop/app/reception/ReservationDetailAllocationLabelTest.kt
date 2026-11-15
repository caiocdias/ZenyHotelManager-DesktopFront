package com.zeny.desktop.app.reception

import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val resId = Uuid.parse("20000000-0000-0000-0000-0000000000a1")
private val holder = Uuid.parse("30000000-0000-0000-0000-0000000000a1")
private val plan = Uuid.parse("40000000-0000-0000-0000-0000000000a1")

private fun sameDayAssignment() =
    RoomAssignmentResponse(
        active = false,
        assignedAt = "2026-11-13T15:00:00Z",
        dayUse = true, // o servidor deriva de startsOn == endsOn
        doNotMove = false,
        endsOn = "2026-11-13",
        id = Uuid.random(),
        reason = "INITIAL",
        releaseReason = Option.Some("ROOM_CHANGE"),
        reservationId = resId,
        roomId = Uuid.random(),
        roomNumber = Option.Some("103"),
        startsOn = "2026-11-13",
    )

private fun reservationOf(dayUse: Boolean) =
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
                dayUse = dayUse,
                departure = if (dayUse) "2026-11-13" else "2026-11-15",
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
                status = "IN_HOUSE",
                total = "100.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationDetailAllocationLabelTest {
    private fun TestScope.lineFor(reservationDayUse: Boolean): String {
        val id = resId.toString()
        val source =
            FakeReceptionSource().apply {
                businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
                types += roomType(typeId(1), "Standard")
                reservations[id] = reservationOf(reservationDayUse)
                holders[holder.toString()] = HolderSummary("Maria Souza", "CPF ***.***.***-34")
                ratePlans[plan.toString()] = "Tarifa Balcão"
                assignments[id] = listOf(sameDayAssignment())
            }
        val state = ReservationDetailState(source, backgroundScope)
        state.open(id)
        runCurrent()
        return assertNotNull(state.view).assignments.single().period
    }

    @Test
    fun overnightReservationSameDayAssignmentIsNotLabelledDayUse() =
        runTest {
            val period = lineFor(reservationDayUse = false)
            assertFalse(period.contains("day use"), period)
        }

    @Test
    fun dayUseReservationKeepsDayUseLabel() =
        runTest {
            assertTrue(lineFor(reservationDayUse = true).contains("day use"))
        }
}
