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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

private val rid = Uuid.parse("20000000-0000-0000-0000-0000000000c1")
private val holderC = Uuid.parse("30000000-0000-0000-0000-0000000000b1")
private val planC = Uuid.parse("40000000-0000-0000-0000-0000000000b1")

private fun noShowAssignmentC(
    active: Boolean = false,
    release: Option<String> = Option.Some("RESERVATION_NO_SHOW"),
) = RoomAssignmentResponse(
    active = active,
    assignedAt = "2026-11-13T15:00:00Z",
    dayUse = false,
    doNotMove = false,
    endsOn = "2026-11-15",
    id = Uuid.random(),
    reason = "INITIAL",
    releaseReason = release,
    reservationId = rid,
    roomId = Uuid.random(),
    roomNumber = Option.Some("103"),
    startsOn = "2026-11-13",
)

private fun noShowReservationC() =
    ReservationDetailResponse(
        bookingCode = "B-1",
        holderGuestId = holderC,
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
                id = rid,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
                ratePlanId = planC,
                roomTotal = "100.00",
                roomTypeId = Uuid.parse(typeId(1)),
                segment = "LEISURE",
                status = "NO_SHOW",
                total = "100.00",
                version = 2,
            ),
        restrictionAlerts = emptyList(),
    )

private fun snapshotC(status: String) =
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
        ratePlanId = planC,
        roomTotal = "100.00",
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = status,
        stayGuests = emptyList(),
        total = "100.00",
    )

private fun noShowEntryC() =
    ReservationHistoryResponse(
        actorId = Option.Some(Uuid.random()),
        after = snapshotC("NO_SHOW"),
        approvalId = Option.Undefined,
        before = snapshotC("CONFIRMED"),
        businessDate = "2026-11-13",
        changedFields = emptyList(),
        id = Uuid.random(),
        kind = "NO_SHOW",
        occurredAt = "2026-11-14T06:00:00Z",
        version = 2,
    )

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationDetailNoShowAtaqueTest {
    private suspend fun TestScope.open(
        assignments: List<RoomAssignmentResponse>,
        history: List<ReservationHistoryResponse>,
    ): ReservationDetailView {
        val id = rid.toString()
        val source =
            FakeReceptionSource().apply {
                businessDay = BusinessDay(BusinessDate.parse("2026-11-14"), ZoneId.of("America/Sao_Paulo"))
                types += roomType(typeId(1), "Standard")
                reservations[id] = noShowReservationC()
                holders[holderC.toString()] = HolderSummary("Maria Souza", "CPF ***.***.***-34")
                ratePlans[planC.toString()] = "Tarifa Balcao"
                histories[id] = history
                this.assignments[id] = assignments
            }
        val state = ReservationDetailState(source, backgroundScope)
        state.open(id)
        runCurrent()
        return assertNotNull(state.view)
    }

    @Test
    fun historyDetailShowsSituationFromConfirmedToNoShow() =
        runTest {
            val view = open(emptyList(), listOf(noShowEntryC()))
            assertEquals("Situação: Confirmada → Não compareceu", view.history.last().detail)
            assertEquals("Não compareceu", view.status.label)
        }

    @Test
    fun noShowEntryAmongOtherEntriesLabelsEachOneAndOnlyTheNoShowSaysNaoCompareceu() =
        runTest {
            val earlier =
                ReservationHistoryResponse(
                    actorId = Option.Undefined,
                    after = snapshotC("CONFIRMED"),
                    approvalId = Option.Undefined,
                    before = snapshotC("PROVISIONAL"),
                    businessDate = "2026-11-10",
                    changedFields = listOf("status"),
                    id = Uuid.random(),
                    kind = "CONFIRM",
                    occurredAt = "2026-11-10T13:00:00Z",
                    version = 1,
                )
            val view = open(emptyList(), listOf(noShowEntryC(), earlier))
            assertEquals(
                listOf("Reserva criada", "Pré-reserva confirmada", "Não compareceu"),
                view.history.map { it.title },
            )
        }

    @Test
    fun activeAssignmentIsAtivaEvenWithNoShowReleaseReason() =
        runTest {
            val view = open(listOf(noShowAssignmentC(active = true)), emptyList())
            assertEquals("Ativa", view.assignments.single().state)
        }

    @Test
    fun releasedAssignmentWithoutReasonIsPlainSoltaAndUnknownReasonStaysOutro() =
        runTest {
            val view =
                open(
                    listOf(
                        noShowAssignmentC(release = Option.Undefined),
                        noShowAssignmentC(release = Option.Some("NO_SHOW")),
                    ),
                    emptyList(),
                )
            assertEquals(listOf("Solta", "Solta · Outro (NO_SHOW)"), view.assignments.map { it.state })
        }

    @Test
    fun unknownHistoryKindStaysOutroNextToNoShow() =
        runTest {
            val odd = noShowEntryC().copy(kind = "AUTO_CLOSE")
            val view = open(emptyList(), listOf(odd))
            assertEquals("Outro (AUTO_CLOSE)", view.history.last().title)
        }
}
