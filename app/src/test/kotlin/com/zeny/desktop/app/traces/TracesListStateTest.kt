package com.zeny.desktop.app.traces

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.core.facade.TraceStatusFilter
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val MANAGE = "frontdesk.traces.manage"
private const val RESOLVE = "frontdesk.traces.resolve"
private val DEPARTMENT = Department(Uuid.parse("40000000-0000-0000-0000-000000000001").toString(), "Governança")

private fun reservationId(n: Int) = Uuid.parse("20000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

@Suppress("LongParameterList") // fixture: cada campo que os testes variam, com padrão
private fun trace(
    n: Int,
    status: String = "OPEN",
    reservation: Int? = null,
    room: String? = null,
    dueOn: String = "2026-11-14",
    text: String = "texto sigiloso $n",
) = TraceResponse(
    assigneeName = Option.Some("Maria"),
    createdAt = "2026-11-14T10:00:00Z",
    createdBy = Uuid.random(),
    departmentId = Uuid.parse(DEPARTMENT.id),
    departmentName = DEPARTMENT.name,
    dueOn = dueOn,
    id = Uuid.parse("50000000-0000-0000-0000-" + n.toString().padStart(12, '0')),
    reservationId = reservation?.let { Option.Some(reservationId(it)) } ?: Option.Undefined,
    roomId = room?.let { Option.Some(Uuid.random()) } ?: Option.Undefined,
    roomNumber = room?.let { Option.Some(it) } ?: Option.Undefined,
    status = status,
    text = text,
    version = 1,
)

private fun traceId(n: Int) = trace(n).id.toString()

@OptIn(ExperimentalCoroutinesApi::class)
class TracesListStateTest {
    private fun source() =
        FakeTracesSource().apply {
            permissions = setOf(MANAGE, RESOLVE)
            departments = listOf(DEPARTMENT)
        }

    private fun TestScope.state(
        source: FakeTracesSource,
        fixed: String? = null,
    ) = TracesListState(source, backgroundScope, fixed).also {
        it.start()
        runCurrent()
    }

    private fun TracesListState.ids() = table.rows.map { it.id }

    private fun pageFilters(source: FakeTracesSource) =
        source.calls.filterIsInstance<TraceCall.Page>().map { it.filter }

    @Test
    fun startLoadsOpenTracesByDefault() =
        runTest {
            val source =
                source().apply {
                    seed(trace(1))
                    seed(trace(2, "RESOLVED"))
                    seed(trace(3, room = "203"))
                }
            val state = state(source)

            assertEquals(listOf(traceId(1), traceId(3)), state.ids())
            assertEquals(TraceStatusFilter.OPEN, state.statusFilter)
            assertEquals(DueFilter.ANY, state.dueFilter)
            assertEquals(
                50,
                source.calls
                    .filterIsInstance<TraceCall.Page>()
                    .first()
                    .limit,
            )
            val row = state.table.rows.last()
            assertEquals("UH 203", row.link)
            assertEquals("14/11/2026", row.due)
            assertEquals("Governança", row.department)
            assertEquals("Maria", row.assignee)
            assertTrue(row.open)
            assertEquals("Aberto", row.status.label)
            assertEquals(setOf(MANAGE, RESOLVE), state.permissions)
            assertTrue(state.canCreate)
            assertEquals(listOf(DEPARTMENT), state.departments)
        }

    @Test
    fun fixedReservationListsAllOfIt() =
        runTest {
            val source =
                source().apply {
                    seed(trace(1, reservation = 7))
                    seed(trace(2, "RESOLVED", reservation = 7))
                    seed(trace(3, reservation = 8))
                    reservationCodes[reservationId(7).toString()] = "R-7"
                }
            val state = state(source, fixed = reservationId(7).toString())

            assertEquals(listOf(traceId(1), traceId(2)), state.ids())
            assertNull(state.statusFilter)
            assertEquals(reservationId(7).toString(), pageFilters(source).single().reservationId)
            assertNull(pageFilters(source).single().status)
            assertTrue(state.table.rows.all { it.link.isEmpty() })
            assertTrue(source.calls.none { it is TraceCall.ReservationCode })
        }

    @Test
    fun filtersGoToSource() =
        runTest {
            val source =
                source().apply {
                    businessDay = businessDay.copy(date = BusinessDate.parse("2026-11-20"))
                    seed(trace(1, dueOn = "2026-11-20"))
                    seed(trace(2, "RESOLVED", dueOn = "2026-11-20"))
                    seed(trace(3, dueOn = "2026-11-21"))
                }
            val state = state(source)

            state.setDepartment(DEPARTMENT.id)
            runCurrent()
            state.setStatus(null)
            runCurrent()
            state.setDue(DueFilter.TODAY)
            runCurrent()

            val last = pageFilters(source).last()
            assertEquals(DEPARTMENT.id, last.departmentId)
            assertNull(last.status)
            assertEquals("2026-11-20", last.dueOn?.toApi())
            assertEquals(listOf(traceId(1), traceId(2)), state.ids())
            state.setStatus(TraceStatusFilter.RESOLVED)
            runCurrent()
            assertEquals(TraceStatusFilter.RESOLVED, pageFilters(source).last().status)
            assertEquals(listOf(traceId(2)), state.ids())
        }

    @Test
    fun departmentsForbiddenKeepsList() =
        runTest {
            val source = source().apply { seed(trace(1)) }
            source.failNext(TraceOp.DEPARTMENTS, ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList()))
            val state = state(source)

            assertTrue(state.departmentsDenied)
            assertNull(state.departmentsError)
            assertFalse(state.canCreate)
            assertEquals(listOf(traceId(1)), state.ids())
        }

    @Test
    fun departmentsFailureCanRetry() =
        runTest {
            val source = source().apply { seed(trace(1)) }
            source.failNext(TraceOp.DEPARTMENTS, ApiError.Network(IOException("offline")))
            val state = state(source)

            assertNotNull(state.departmentsError)
            assertFalse(state.departmentsDenied)
            assertFalse(state.canCreate)
            state.retryDepartments()
            runCurrent()

            assertNull(state.departmentsError)
            assertTrue(state.canCreate)
            assertEquals(listOf(DEPARTMENT), state.departments)
        }

    @Test
    fun canCreateNeedsManagePermission() =
        runTest {
            val source = source().apply { permissions = setOf(RESOLVE) }
            assertFalse(state(source).canCreate)
        }

    @Test
    fun resolveRefreshesList() =
        runTest {
            val source =
                source().apply {
                    seed(trace(1))
                    seed(trace(2))
                }
            val state = state(source)

            state.resolve(traceId(1))
            runCurrent()

            assertEquals(listOf(traceId(2)), state.ids())
            assertNull(state.error)
            assertFalse(state.busy)
        }

    @Test
    fun resolveAlreadyResolvedRefreshes() =
        runTest {
            val source =
                source().apply {
                    seed(trace(1))
                    seed(trace(2))
                }
            val state = state(source)
            source.resolve(traceId(1))

            state.resolve(traceId(1))
            runCurrent()

            assertEquals(TraceText.message(alreadyResolved()), state.error?.let(TraceText::message))
            assertEquals(listOf(traceId(2)), state.ids())
            assertFalse(state.busy)
        }

    @Test
    fun resolveWhileBusyIsNoOp() =
        runTest {
            val source =
                source().apply {
                    seed(trace(1))
                    seed(trace(2))
                }
            val state = state(source)
            source.resolveGate = CompletableDeferred()

            state.resolve(traceId(1))
            runCurrent()
            assertTrue(state.busy)
            state.resolve(traceId(2))
            runCurrent()
            source.resolveGate?.complete(Unit)
            runCurrent()

            assertEquals(listOf(TraceCall.Resolve(traceId(1))), source.calls.filterIsInstance<TraceCall.Resolve>())
            assertFalse(state.busy)
        }

    @Test
    fun resultAfterCloseIsDiscarded() =
        runTest {
            val source = source().apply { seed(trace(1)) }
            val state = state(source)
            source.resolveGate = CompletableDeferred()
            state.resolve(traceId(1))
            runCurrent()
            val pagesBefore = pageFilters(source).size

            state.close()
            source.resolveGate?.complete(Unit)
            runCurrent()

            assertEquals(pagesBefore, pageFilters(source).size)
            assertEquals(listOf(traceId(1)), state.ids())
            state.resolve(traceId(1))
            runCurrent()
            assertEquals(1, source.calls.filterIsInstance<TraceCall.Resolve>().size)
        }

    @Test
    fun reservationCodesAreCachedAndBounded() =
        runTest {
            val source = source()
            (1..60).forEach { n ->
                val reservation = if (n <= 50) n else 1
                source.seed(trace(n, reservation = reservation))
                source.reservationCodes[reservationId(reservation).toString()] = "R-$reservation"
            }
            source.reservationCodeGate = CompletableDeferred()
            val state = TracesListState(source, backgroundScope)
            state.start()
            runCurrent()
            assertEquals(4, source.reservationCodeInFlight)
            source.reservationCodeGate?.complete(Unit)
            runCurrent()
            assertTrue(source.maxReservationCodeInFlight in 1..4)
            assertEquals(50, state.table.rows.size)
            assertEquals(
                "Reserva R-1",
                state.table.rows
                    .first()
                    .link,
            )

            state.table.loadMore()
            runCurrent()

            assertEquals(60, state.table.rows.size)
            val reads = source.calls.filterIsInstance<TraceCall.ReservationCode>()
            assertEquals(reads.map { it.id }.distinct().size, reads.size)
            assertEquals(
                "Reserva R-1",
                state.table.rows
                    .last()
                    .link,
            )
        }

    @Test
    fun reservationCodeFailureShowsGenericLink() =
        runTest {
            val source = source().apply { seed(trace(1, reservation = 9)) }
            val state = state(source)

            assertEquals(listOf(traceId(1)), state.ids())
            assertEquals(
                "Reserva",
                state.table.rows
                    .single()
                    .link,
            )
            assertNull(state.error)
        }

    @Test
    fun unknownStatusHasNoActions() =
        runTest {
            val source = source().apply { seed(trace(1, status = "SNOOZED")) }
            val state = state(source)
            state.setStatus(null)
            runCurrent()

            val row = state.table.rows.single()
            assertEquals("Outro (SNOOZED)", row.status.label)
            assertFalse(row.open)
            assertFalse(state.canEdit(row))
            assertFalse(state.canResolve(row))
        }

    @Test
    fun openRowActionsFollowPermissions() =
        runTest {
            val source =
                source().apply {
                    seed(trace(1))
                    permissions = setOf(RESOLVE)
                }
            val state = state(source)
            val row = state.table.rows.single()

            assertFalse(state.canEdit(row))
            assertTrue(state.canResolve(row))
        }

    @Test
    fun rowToStringHidesText() =
        runTest {
            val source = source().apply { seed(trace(1, text = "segredo do hóspede")) }
            val state = state(source)

            assertEquals(
                "segredo do hóspede",
                state.table.rows
                    .single()
                    .text,
            )
            assertFalse(
                state.table.rows
                    .single()
                    .toString()
                    .contains("segredo"),
            )
            assertFalse(state.toString().contains("segredo"))
        }
}
