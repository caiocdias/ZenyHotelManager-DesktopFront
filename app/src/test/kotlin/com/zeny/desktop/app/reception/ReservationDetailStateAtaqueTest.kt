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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.util.TimeZone
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
class ReservationDetailStateAtaqueTest {
    private val id = reservationUuid.toString()
    private val other = otherReservationUuid.toString()
    private val clockShape = Regex("\\d{2}:\\d{2}")

    private fun source(zone: ZoneId? = saoPaulo) =
        FakeReceptionSource().apply {
            businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), zone)
            types += roomType(typeId(1), "Standard")
            reservations[id] = detail()
            reservations[other] = detail(otherReservationUuid)
            holders[holderUuid.toString()] = HolderSummary("Maria Souza", "CPF ***.***.***-34")
            ratePlans[planUuid.toString()] = "Tarifa Balcão"
        }

    private fun TestScope.opened(
        source: FakeReceptionSource,
        scope: CoroutineScope = backgroundScope,
    ) = ReservationDetailState(source, scope).also {
        it.open(id)
        runCurrent()
    }

    private fun FakeReceptionSource.holdNothing() {
        holdReservation = false
        holdHistory = false
        holdAssignments = false
        holdHolder = false
        holdRatePlan = false
    }

    private fun FakeReceptionSource.releaseAll() {
        heldReservations.forEach { it.second.complete(Unit) }
        heldHistories.forEach { it.second.complete(Unit) }
        heldAssignments.forEach { it.second.complete(Unit) }
        heldHolders.forEach { it.second.complete(Unit) }
        heldRatePlans.forEach { it.second.complete(Unit) }
    }

    private fun TestScope.assertLateResponseNeverEntersAfterOpeningOther(hold: FakeReceptionSource.() -> Unit) {
        val source = source().apply(hold)
        val state = ReservationDetailState(source, backgroundScope)
        state.open(id)
        runCurrent()
        source.holdNothing()
        state.open(other)
        runCurrent()
        assertEquals("R-2", assertNotNull(state.view).code)

        source.releaseAll()
        runCurrent()

        assertEquals(other, state.openId)
        assertEquals("R-2", assertNotNull(state.view).code)
        assertNull(state.error)
        assertFalse(state.loading)
    }

    @Test
    fun lateHistoryOfPreviousIdNeverEnters() =
        runTest { assertLateResponseNeverEntersAfterOpeningOther { holdHistory = true } }

    @Test
    fun lateAssignmentsOfPreviousIdNeverEnter() =
        runTest { assertLateResponseNeverEntersAfterOpeningOther { holdAssignments = true } }

    @Test
    fun lateHolderOfPreviousIdNeverEnters() =
        runTest { assertLateResponseNeverEntersAfterOpeningOther { holdHolder = true } }

    @Test
    fun lateRatePlanOfPreviousIdNeverEnters() =
        runTest { assertLateResponseNeverEntersAfterOpeningOther { holdRatePlan = true } }

    @Test
    fun lateReservationOfPreviousIdNeverEnters() =
        runTest { assertLateResponseNeverEntersAfterOpeningOther { holdReservation = true } }

    @Test
    fun lateFailureOfPreviousIdDoesNotSetError() =
        runTest {
            val source = source().apply { holdHistory = true }
            val state = ReservationDetailState(source, backgroundScope)
            state.open(id)
            runCurrent()
            source.holdNothing()
            state.open(other)
            runCurrent()
            source.reservationError = problem(500)
            source.releaseAll()
            runCurrent()

            assertNull(state.error)
            assertEquals("R-2", assertNotNull(state.view).code)
        }

    @Test
    fun lateResponseAfterCloseNeverEnters() =
        runTest {
            val source = source().apply { holdHistory = true }
            val state = ReservationDetailState(source, backgroundScope)
            state.open(id)
            runCurrent()
            state.close()
            source.releaseAll()
            runCurrent()

            assertNull(state.openId)
            assertNull(state.view)
            assertNull(state.error)
            assertFalse(state.loading)
        }

    @Test
    fun lateResponseAfterCloseAndReopenOfOtherIdNeverEnters() =
        runTest {
            val source = source().apply { holdHolder = true }
            val state = ReservationDetailState(source, backgroundScope)
            state.open(id)
            runCurrent()
            state.close()
            source.holdNothing()
            state.open(other)
            runCurrent()
            source.releaseAll()
            runCurrent()

            assertEquals(other, state.openId)
            assertEquals("R-2", assertNotNull(state.view).code)
        }

    @Test
    fun reopenAfterCloseShowsDetail() =
        runTest {
            val state = opened(source())
            state.close()
            assertNull(state.view)
            state.open(id)
            runCurrent()

            assertEquals("R-1", assertNotNull(state.view).code)
            assertNull(state.error)
        }

    @Test
    fun openClearsPreviousViewWhileLoading() =
        runTest {
            val source = source()
            val state = opened(source)
            source.holdReservation = true
            state.open(other)
            runCurrent()

            assertNull(state.view)
            assertTrue(state.loading)
            assertEquals(other, state.openId)
        }

    @Test
    fun closeKeepsCallerScopeAlive() =
        runTest {
            val callerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
            val state = opened(source(), callerScope)
            var sibling = false
            callerScope.launch {
                delay(10)
                sibling = true
            }
            state.close()
            advanceUntilIdle()

            assertTrue(callerScope.isActive)
            assertTrue(sibling)
            state.open(other)
            runCurrent()
            assertEquals("R-2", assertNotNull(state.view).code)
        }

    @Test
    fun cancellingTheScopeIsNotSwallowedAsError() =
        runTest {
            val source = source().apply { holdHistory = true }
            val own = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
            val state = ReservationDetailState(source, own)
            state.open(id)
            runCurrent()
            own.cancel()
            runCurrent()

            assertNull(state.error)
            assertNull(state.view)
        }

    @Test
    fun cancellingTheScopeDuringHolderReadIsNotSwallowed() =
        runTest {
            val source = source().apply { holdHolder = true }
            val own = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
            val state = ReservationDetailState(source, own)
            state.open(id)
            runCurrent()
            own.cancel()
            runCurrent()

            assertNull(state.error)
            assertNull(state.view)
        }

    @Test
    fun historySortsByInstantNotByText() =
        runTest {
            val source =
                source().apply {
                    histories[id] =
                        listOf(
                            entry("CHECK_IN", "2026-11-13T12:00:00Z", 2),
                            entry("CHANGE", "2026-11-13T10:00:00-03:00", 1),
                        )
                }
            val lines = assertNotNull(opened(source).view).history

            // 10:00-03:00 é 13:00Z, depois de 12:00Z; ordenar pelo texto inverteria as duas.
            assertEquals(listOf("Reserva criada", "Check-in", "Reserva alterada"), lines.map { it.title })
        }

    @Test
    fun sameInstantTiesBreakByVersion() =
        runTest {
            val source =
                source().apply {
                    histories[id] =
                        listOf(
                            entry("CHECK_OUT", "2026-11-13T18:00:00Z", 3),
                            entry("CHECK_IN", "2026-11-13T18:00:00.000Z", 1),
                            entry("CHANGE", "2026-11-13T18:00:00Z", 2),
                        )
                }
            val lines = assertNotNull(opened(source).view).history

            assertEquals(listOf("Reserva criada", "Check-in", "Reserva alterada", "Check-out"), lines.map { it.title })
        }

    @Test
    fun creationLineIsAlwaysFirstAndEmptyWithoutEntries() =
        runTest {
            val lines = assertNotNull(opened(source()).view).history

            assertEquals(1, lines.size)
            assertEquals(HistoryLine("10/11/2026 09:00", "Reserva criada", ""), lines.single())
        }

    @Test
    fun situationShownOnlyWhenStatusChanged() =
        runTest {
            val source =
                source().apply {
                    histories[id] =
                        listOf(
                            entry("CHANGE", "2026-11-13T18:00:00Z", 1, fields = listOf("adults")),
                            entry("CHECK_IN", "2026-11-13T19:00:00Z", 2, before = "CONFIRMED", after = "IN_HOUSE"),
                        )
                }
            val lines = assertNotNull(opened(source).view).history

            assertEquals("Alterou: adultos", lines[1].detail)
            assertEquals("Situação: Confirmada → Hospedada", lines[2].detail)
        }

    @Test
    fun approvalAloneShowsOnlyApproval() =
        runTest {
            val approved = listOf(entry("CHANGE", "2026-11-13T18:00:00Z", 1, approved = true))
            val source = source().apply { histories[id] = approved }
            assertEquals("Com aprovação de superior", assertNotNull(opened(source).view).history.last().detail)
        }

    @Test
    fun noChangeNoApprovalGivesEmptyDetail() =
        runTest {
            val source = source().apply { histories[id] = listOf(entry("CHANGE", "2026-11-13T18:00:00Z", 1)) }
            assertEquals("", assertNotNull(opened(source).view).history.last().detail)
        }

    @Test
    fun noZoneNeverUsesJvmZone() =
        runTest {
            val saved = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            try {
                val source =
                    source(zone = null).apply {
                        histories[id] =
                            listOf(entry("CHECK_IN", "2026-11-13T18:00:00Z", 1, businessDate = "2026-11-13"))
                    }
                val lines = assertNotNull(opened(source).view).history

                assertEquals(listOf("—", "13/11/2026 (data operacional)"), lines.map { it.at })
                assertTrue(lines.none { clockShape.containsMatchIn(it.at) })
            } finally {
                TimeZone.setDefault(saved)
            }
        }

    @Test
    fun businessDayFailureMeansNoZone() =
        runTest {
            val source =
                source().apply {
                    businessDayError = problem(500)
                    histories[id] = listOf(entry("CHECK_IN", "2026-11-13T18:00:00Z", 1, businessDate = "2026-11-14"))
                }
            val state = opened(source)
            val lines = assertNotNull(state.view).history

            assertNull(state.error)
            assertEquals(listOf("—", "14/11/2026 (data operacional)"), lines.map { it.at })
        }

    @Test
    fun unparseableInstantFallsBackToBusinessDate() =
        runTest {
            val bad = listOf(entry("CHANGE", "ontem", 1, businessDate = "2026-11-12"))
            val source = source().apply { histories[id] = bad }
            assertEquals("12/11/2026 (data operacional)", assertNotNull(opened(source).view).history.last().at)
        }

    @Test
    fun noUuidEvenWithUnknownCodes() =
        runTest {
            val source =
                source().apply {
                    reservations[id] = detail(alerts = listOf("Sem registro"))
                    assignments[id] = listOf(assignment(false, release = "CODIGO_NOVO"))
                    histories[id] =
                        listOf(
                            entry(
                                "TIPO_NOVO",
                                "2026-11-13T18:00:00Z",
                                1,
                                fields = listOf("campoNovo"),
                                approved = true,
                            ),
                        )
                    ratePlanError = problem(404)
                }
            val view = assertNotNull(opened(source).view)
            val texts =
                listOf(view.code, view.holder, view.document.orEmpty(), view.room, view.plan, view.roomType) +
                    view.alerts +
                    view.assignments.flatMap { listOf(it.room, it.period, it.reason, it.state) } +
                    view.history.flatMap { listOf(it.at, it.title, it.detail) }

            assertTrue(texts.none { uuidShape.containsMatchIn(it) }, texts.toString())
        }

    @Test
    fun releasedWithoutReasonShowsPlainReleased() =
        runTest {
            val source = source().apply { assignments[id] = listOf(assignment(false)) }
            assertEquals("Solta", assertNotNull(opened(source).view).assignments.single().state)
        }

    @Test
    fun holderNotFoundShowsDash() =
        runTest {
            val view = assertNotNull(opened(source().apply { holderError = problem(404) }).view)

            assertEquals("—", view.holder)
            assertNull(view.document)
        }

    @Test
    fun unknownRoomTypeAndFailedRoomTypesShowDash() =
        runTest {
            val unknown = source().apply { types.clear() }
            assertEquals("—", assertNotNull(opened(unknown).view).roomType)

            val failed = source().apply { roomTypesError = problem(500) }
            val state = opened(failed)
            assertNull(state.error)
            assertEquals("—", assertNotNull(state.view).roomType)
        }

    @Test
    fun reservationFailureWithoutApiErrorBecomesNetworkError() =
        runTest {
            val state = opened(source().apply { reservationError = IllegalStateException("boom") })

            assertNull(state.view)
            assertTrue(state.error is ApiError.Network)
            assertFalse(state.loading)
        }

    @Test
    fun historyFailureIsOnlyTheSectionError() =
        runTest {
            val source =
                source().apply {
                    historyError = problem(500)
                    assignments[id] = listOf(assignment(true, "303"))
                }
            val state = opened(source)
            val view = assertNotNull(state.view)

            assertNull(state.error)
            assertNotNull(view.historyError)
            assertNull(view.assignmentsError)
            assertEquals("UH 303", view.room)
            assertEquals(1, view.assignments.size)
        }

    @Test
    fun assignmentsFailureIsOnlyTheSectionError() =
        runTest {
            val source =
                source().apply {
                    assignmentsError = problem(500)
                    histories[id] = listOf(entry("CHECK_IN", "2026-11-13T18:00:00Z", 1))
                }
            val state = opened(source)
            val view = assertNotNull(state.view)

            assertNull(state.error)
            assertNotNull(view.assignmentsError)
            assertNull(view.historyError)
            assertTrue(view.assignments.isEmpty())
            assertEquals(listOf("Reserva criada", "Check-in"), view.history.map { it.title })
            assertEquals("Maria Souza", view.holder)
        }

    @Test
    fun bothSectionsFailingStillShowsDetail() =
        runTest {
            val source =
                source().apply {
                    historyError = IllegalStateException("x")
                    assignmentsError = problem(503)
                }
            val state = opened(source)
            val view = assertNotNull(state.view)

            assertNull(state.error)
            assertTrue(view.historyError is ApiError.Network)
            assertNotNull(view.assignmentsError)
            assertEquals("R-1", view.code)
        }

    @Test
    fun reloadWithoutOpenDoesNothing() =
        runTest {
            val source = source()
            val state = ReservationDetailState(source, backgroundScope)
            state.reload()
            runCurrent()

            assertNull(state.openId)
            assertNull(state.view)
            assertFalse(state.loading)
            assertTrue(source.reservationCalls.isEmpty())
            assertTrue(source.historyCalls.isEmpty())
        }

    @Test
    fun reloadAfterCloseDoesNothing() =
        runTest {
            val source = source()
            val state = opened(source)
            state.close()
            state.reload()
            runCurrent()

            assertNull(state.view)
            assertNull(state.openId)
            assertEquals(1, source.reservationCalls.size)
        }

    @Test
    fun reloadKeepsViewWhileLoadingAndRefreshesIt() =
        runTest {
            val source = source()
            val state = opened(source)
            source.histories[id] = listOf(entry("CHECK_IN", "2026-11-13T18:00:00Z", 1))
            source.holdHistory = true
            state.reload()
            runCurrent()

            assertTrue(state.loading)
            assertEquals("R-1", assertNotNull(state.view).code)
            assertEquals(1, state.view?.history?.size)

            source.holdHistory = false
            source.heldHistories.forEach { it.second.complete(Unit) }
            runCurrent()

            assertFalse(state.loading)
            assertEquals(2, assertNotNull(state.view).history.size)
        }

    @Test
    fun reloadClearsPreviousError() =
        runTest {
            val source = source().apply { reservationError = problem(404, "RESERVATION_NOT_FOUND") }
            val state = opened(source)
            assertNotNull(state.error)

            source.reservationError = null
            state.reload()
            runCurrent()

            assertNull(state.error)
            assertEquals("R-1", assertNotNull(state.view).code)
        }

    @Test
    fun toStringHidesEveryPersonalField() =
        runTest {
            val source =
                source().apply {
                    reservations[id] = detail(alerts = listOf("Motivo secreto A", "Motivo secreto B"))
                    holders[holderUuid.toString()] = HolderSummary("Joana Prado", "RG 12.345.678-9")
                }
            val text = assertNotNull(opened(source).view).toString()

            listOf("Joana", "Prado", "RG", "12.345", "secreto", "Motivo").forEach {
                assertFalse(text.contains(it), "toString vazou '$it': $text")
            }
        }

    @Test
    fun toStringWithErrorsHidesPersonalData() =
        runTest {
            val source =
                source().apply {
                    historyError = problem(500)
                    assignmentsError = problem(500)
                }
            val text = assertNotNull(opened(source).view).toString()

            assertFalse(text.contains("Maria"))
            assertFalse(text.contains("***"))
        }
}
