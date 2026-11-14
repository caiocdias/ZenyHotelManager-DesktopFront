package com.zeny.desktop.app.reception

import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationHistoryResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.ReservationStateResponse
import com.zeny.desktop.sdk.model.RestrictionAlertResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val reservationUuid = Uuid.parse("20000000-0000-0000-0000-000000000001")
private val otherReservationUuid = Uuid.parse("20000000-0000-0000-0000-000000000002")
private val holderUuid = Uuid.parse("30000000-0000-0000-0000-000000000001")
private val planUuid = Uuid.parse("40000000-0000-0000-0000-000000000001")
private val uuidShape = Regex("[0-9a-f]{8}-[0-9a-f]{4}-")
private val saoPaulo = ZoneId.of("America/Sao_Paulo")

private fun state(status: String) =
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
        ratePlanId = planUuid,
        roomTotal = "500.00",
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = status,
        stayGuests = emptyList(),
        total = "500.00",
    )

@Suppress("LongParameterList") // fixture: cada campo que os testes variam, com padrão
private fun entry(
    kind: String,
    occurredAt: String,
    version: Long,
    fields: List<String> = emptyList(),
    before: String = "CONFIRMED",
    after: String = "CONFIRMED",
    approved: Boolean = false,
    businessDate: String = "2026-11-13",
) = ReservationHistoryResponse(
    actorId = Option.Some(Uuid.random()),
    after = state(after),
    approvalId = if (approved) Option.Some(Uuid.random()) else Option.Undefined,
    before = state(before),
    businessDate = businessDate,
    changedFields = fields,
    id = Uuid.random(),
    kind = kind,
    occurredAt = occurredAt,
    version = version,
)

private fun assignment(
    active: Boolean,
    room: String = "101",
    release: String? = null,
    doNotMove: Boolean = false,
) = RoomAssignmentResponse(
    active = active,
    assignedAt = "2026-11-13T15:00:00Z",
    dayUse = false,
    doNotMove = doNotMove,
    endsOn = "2026-11-15",
    id = Uuid.random(),
    reason = "INITIAL",
    releaseReason = release?.let { Option.Some(it) } ?: Option.Undefined,
    reservationId = reservationUuid,
    roomId = Uuid.random(),
    roomNumber = Option.Some(room),
    startsOn = "2026-11-13",
)

private fun detail(
    id: Uuid = reservationUuid,
    status: String = "CONFIRMED",
    alerts: List<String> = emptyList(),
) = ReservationDetailResponse(
    bookingCode = "B-1",
    holderGuestId = holderUuid,
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
            childAges = listOf(5),
            code = "R-${id.toString().takeLast(1)}",
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
            id = id,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
            ratePlanId = planUuid,
            roomTotal = "1234.50",
            roomTypeId = Uuid.parse(typeId(1)),
            segment = "LEISURE",
            status = status,
            total = "1234.50",
            version = 1,
        ),
    restrictionAlerts = alerts.map { RestrictionAlertResponse(holderUuid, it, "2026-01-01T00:00:00Z") },
)

private fun problem(
    status: Int,
    code: String? = null,
) = ApiError.Problem(status, code, null, emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationDetailStateTest {
    private val id = reservationUuid.toString()

    private fun source(zone: ZoneId? = saoPaulo) =
        FakeReceptionSource().apply {
            businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), zone)
            types += roomType(typeId(1), "Standard")
            reservations[id] = detail()
            holders[holderUuid.toString()] = HolderSummary("Maria Souza", "CPF ***.***.***-34")
            ratePlans[planUuid.toString()] = "Tarifa Balcão"
        }

    private fun TestScope.opened(
        source: FakeReceptionSource,
        openId: String = id,
    ) = ReservationDetailState(source, backgroundScope).also {
        it.open(openId)
        runCurrent()
    }

    @Test
    fun buildsDetailWithMaskedDocument() =
        runTest {
            val state = opened(source().apply { assignments[id] = listOf(assignment(true)) })
            val view = assertNotNull(state.view)

            assertNull(state.error)
            assertFalse(state.loading)
            assertEquals("R-1", view.code)
            assertEquals("Maria Souza", view.holder)
            assertEquals("CPF ***.***.***-34", view.document)
            assertEquals("Confirmada", view.status.label)
            assertEquals("Standard", view.roomType)
            assertEquals("Tarifa Balcão", view.plan)
            assertEquals("R$ 1.234,50", view.total)
            assertEquals("2 adultos e 1 criança", view.guests)
            assertEquals("13/11/2026 a 15/11/2026 · 2 noites", view.period)
        }

    @Test
    fun historyIsChronologicalWithCreation() =
        runTest {
            val source =
                source().apply {
                    histories[id] =
                        listOf(
                            entry("CHECK_IN", "2026-11-13T18:00:00Z", 3),
                            entry("CHANGE", "2026-11-12T18:00:00Z", 2),
                            entry("CONFIRM", "2026-11-12T18:00:00Z", 1),
                        )
                }
            val lines = assertNotNull(opened(source).view).history

            assertEquals(
                listOf("Reserva criada", "Pré-reserva confirmada", "Reserva alterada", "Check-in"),
                lines.map { it.title },
            )
            assertEquals("10/11/2026 09:00", lines.first().at)
            assertEquals("13/11/2026 15:00", lines.last().at)
        }

    @Test
    fun historyDetailInPortuguese() =
        runTest {
            val source =
                source().apply {
                    histories[id] =
                        listOf(
                            entry(
                                "CHECK_IN",
                                "2026-11-13T18:00:00Z",
                                1,
                                fields = listOf("status", "checkIn", "guests"),
                                before = "CONFIRMED",
                                after = "IN_HOUSE",
                                approved = true,
                            ),
                        )
                }
            val line = assertNotNull(opened(source).view).history.last()

            assertEquals(
                "Alterou: situação, check-in, hóspedes · Situação: Confirmada → Hospedada · Com aprovação de superior",
                line.detail,
            )
        }

    @Test
    fun noUuidAnywhere() =
        runTest {
            val source =
                source().apply {
                    assignments[id] = listOf(assignment(true), assignment(false, release = "ROOM_MOVED"))
                    histories[id] =
                        listOf(
                            entry(
                                "ROOM_MOVE",
                                "2026-11-13T18:00:00Z",
                                1,
                                fields = listOf("roomAssignment"),
                                approved = true,
                            ),
                        )
                }
            val view = assertNotNull(opened(source).view)
            val texts =
                listOf(
                    view.code,
                    view.status.label,
                    view.holder,
                    view.document.orEmpty(),
                    view.period,
                    view.roomType,
                    view.room,
                    view.guests,
                    view.plan,
                    view.total,
                ) +
                    view.alerts +
                    view.assignments.flatMap { listOf(it.room, it.period, it.reason, it.state) } +
                    view.history.flatMap { listOf(it.at, it.title, it.detail) }

            assertTrue(texts.none { uuidShape.containsMatchIn(it) }, texts.toString())
        }

    @Test
    fun noZoneFallsBackToBusinessDate() =
        runTest {
            val source =
                source(zone = null).apply {
                    histories[id] = listOf(entry("CHECK_IN", "2026-11-13T18:00:00Z", 1, businessDate = "2026-11-13"))
                }
            val lines = assertNotNull(opened(source).view).history

            assertEquals("—", lines.first().at)
            assertEquals("13/11/2026 (data operacional)", lines.last().at)
        }

    @Test
    fun planFallsBackToCategory() =
        runTest {
            val source = source().apply { ratePlanError = problem(403) }
            assertEquals("Cortesia", assertNotNull(opened(source).view).plan)
        }

    @Test
    fun activeAssignmentGivesRoom() =
        runTest {
            val withRoom =
                opened(
                    source().apply {
                        assignments[id] =
                            listOf(assignment(false, "100", "ROOM_MOVED"), assignment(true, "202"))
                    },
                )
            assertEquals("UH 202", assertNotNull(withRoom.view).room)
            assertEquals(listOf("UH 100", "UH 202"), assertNotNull(withRoom.view).assignments.map { it.room })

            val without = opened(source().apply { assignments[id] = listOf(assignment(false, "100", "CHECKED_OUT")) })
            assertEquals("Sem UH", assertNotNull(without.view).room)
        }

    @Test
    fun releasedByRoomMoveShowsLabel() =
        runTest {
            val source =
                source().apply {
                    assignments[id] =
                        listOf(assignment(true), assignment(false, release = "ROOM_MOVED", doNotMove = true))
                }
            val lines = assertNotNull(opened(source).view).assignments

            assertEquals(listOf("Ativa", "Solta · Troca de UH"), lines.map { it.state })
            assertEquals(listOf(false, true), lines.map { it.doNotMove })
        }

    @Test
    fun holderForbiddenShowsDash() =
        runTest {
            val source = source().apply { holderError = problem(403, "PERMISSION_DENIED") }
            val view = assertNotNull(opened(source).view)

            assertEquals("—", view.holder)
            assertNull(view.document)
        }

    @Test
    fun historyErrorKeepsDetail() =
        runTest {
            val source = source().apply { historyError = problem(500) }
            val state = opened(source)
            val view = assertNotNull(state.view)

            assertNull(state.error)
            assertNotNull(view.historyError)
            assertEquals(listOf("Reserva criada"), view.history.map { it.title })
            assertEquals("Maria Souza", view.holder)
            assertNull(view.assignmentsError)
        }

    @Test
    fun notFoundShowsError() =
        runTest {
            val source = source().apply { reservationError = problem(404, "RESERVATION_NOT_FOUND") }
            val state = opened(source)

            assertNull(state.view)
            assertEquals("A reserva não foi encontrada.", state.error?.userMessage)
            assertFalse(state.loading)
        }

    @Test
    fun staleDetailDropped() =
        runTest {
            val other = otherReservationUuid.toString()
            val source =
                source().apply {
                    reservations[other] = detail(otherReservationUuid)
                    holdReservation = true
                }
            val state = ReservationDetailState(source, backgroundScope)
            state.open(id)
            runCurrent()
            state.open(other)
            runCurrent()
            source.heldReservations.reversed().forEach { it.second.complete(Unit) }
            runCurrent()

            assertEquals(other, state.openId)
            assertEquals("R-2", assertNotNull(state.view).code)
            assertFalse(state.loading)
        }

    @Test
    fun reloadRefetches() =
        runTest {
            val source = source()
            val state = opened(source)
            state.reload()
            runCurrent()

            assertEquals(listOf(id, id), source.reservationCalls)
            assertEquals(listOf(id, id), source.historyCalls)
            assertNotNull(state.view)

            val empty = ReservationDetailState(source, backgroundScope)
            empty.reload()
            runCurrent()
            assertNull(empty.openId)
            assertEquals(2, source.reservationCalls.size)
        }

    @Test
    fun viewToStringHidesPersonalData() =
        runTest {
            val source = source().apply { reservations[id] = detail(alerts = listOf("Cliente litigioso")) }
            val view = assertNotNull(opened(source).view)
            val text = view.toString()

            assertTrue(view.alerts.single().contains("Cliente litigioso"))
            assertFalse(text.contains("Maria"))
            assertFalse(text.contains("***"))
            assertFalse(text.contains("litigioso"))
        }
}
