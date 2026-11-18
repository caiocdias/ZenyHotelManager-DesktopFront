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

private fun trace(
    n: Int,
    status: String = "OPEN",
    reservation: Int? = null,
    dueOn: String = "2026-11-14",
    text: String = "texto sigiloso $n",
) = TraceResponse(
    createdAt = "2026-11-14T10:00:00Z",
    createdBy = Uuid.random(),
    departmentId = Uuid.parse(DEPARTMENT.id),
    departmentName = DEPARTMENT.name,
    dueOn = dueOn,
    id = Uuid.parse("50000000-0000-0000-0000-" + n.toString().padStart(12, '0')),
    reservationId = reservation?.let { Option.Some(reservationId(it)) } ?: Option.Undefined,
    status = status,
    text = text,
    version = 1,
)

private fun traceId(n: Int) = trace(n).id.toString()

private fun network() = ApiError.Network(IOException("offline"))

@OptIn(ExperimentalCoroutinesApi::class)
class TracesListStateAtaqueTest {
    private fun source() =
        FakeTracesSource().apply {
            permissions = setOf(MANAGE, RESOLVE)
            departments = listOf(DEPARTMENT)
        }

    private fun TestScope.state(
        source: FakeTracesSource,
        fixed: String? = null,
    ) = TracesListState(source, backgroundScope, source.permissions, fixed).also {
        it.start()
        runCurrent()
    }

    private fun TracesListState.ids() = table.rows.map { it.id }

    private fun pageFilters(source: FakeTracesSource) =
        source.calls.filterIsInstance<TraceCall.Page>().map { it.filter }

    private fun codeReads(source: FakeTracesSource) =
        source.calls.filterIsInstance<TraceCall.ReservationCode>().map { it.id }

    @Test
    fun stalePageOfOldFilterDoesNotOverwriteNewFilterRows() =
        runTest {
            val source =
                source().apply {
                    seed(trace(1))
                    seed(trace(2, "RESOLVED"))
                }
            val state = state(source)
            val slow = CompletableDeferred<Unit>()
            source.pageGates.addLast(slow)

            state.setStatus(TraceStatusFilter.RESOLVED)
            runCurrent()
            state.setStatus(null)
            runCurrent()
            slow.complete(Unit)
            runCurrent()

            assertEquals(listOf(traceId(1), traceId(2)), state.ids())
            assertNull(state.statusFilter)
        }

    @Test
    fun staleDepartmentPageDoesNotOverwriteLaterFilter() =
        runTest {
            val source =
                source().apply {
                    seed(trace(1))
                    seed(trace(2, "RESOLVED"))
                }
            val state = state(source)
            val slow = CompletableDeferred<Unit>()
            source.pageGates.addLast(slow)

            state.setDepartment(DEPARTMENT.id)
            runCurrent()
            state.setDepartment(null)
            state.setStatus(TraceStatusFilter.RESOLVED)
            runCurrent()
            slow.complete(Unit)
            runCurrent()

            assertEquals(listOf(traceId(2)), state.ids())
            assertNull(state.departmentFilter)
        }

    @Test
    fun refreshWhileResolveRunsEndsWithResolvedGoneAndNotBusy() =
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
            state.refresh()
            runCurrent()
            assertTrue(state.busy)
            source.resolveGate?.complete(Unit)
            runCurrent()

            assertEquals(listOf(traceId(2)), state.ids())
            assertFalse(state.busy)
            assertNull(state.error)
        }

    @Test
    fun resolveTwiceSynchronouslyCallsSourceOnce() =
        runTest {
            val source = source().apply { seed(trace(1)) }
            val state = state(source)
            source.resolveGate = CompletableDeferred()

            state.resolve(traceId(1))
            state.resolve(traceId(1))
            runCurrent()
            source.resolveGate?.complete(Unit)
            runCurrent()

            assertEquals(1, source.calls.filterIsInstance<TraceCall.Resolve>().size)
            assertNull(state.error)
            assertFalse(state.busy)
        }

    @Test
    fun resolveAllowedAgainAfterPreviousFinished() =
        runTest {
            val source =
                source().apply {
                    seed(trace(1))
                    seed(trace(2))
                }
            val state = state(source)

            state.resolve(traceId(1))
            runCurrent()
            state.resolve(traceId(2))
            runCurrent()

            assertEquals(2, source.calls.filterIsInstance<TraceCall.Resolve>().size)
            assertTrue(state.ids().isEmpty())
        }

    @Test
    fun alreadyResolvedErrorIsClearedByLaterSuccess() =
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
            assertNotNull(state.error)
            state.resolve(traceId(2))
            runCurrent()

            assertNull(state.error)
            assertTrue(state.ids().isEmpty())
        }

    @Test
    fun resolveForbiddenShowsErrorAndLeavesNotBusy() =
        runTest {
            val source = source().apply { seed(trace(1)) }
            val state = state(source)
            source.failNext(TraceOp.RESOLVE, ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList()))

            state.resolve(traceId(1))
            runCurrent()

            assertNotNull(state.error)
            assertFalse(state.busy)
            assertEquals(listOf(traceId(1)), state.ids())
        }

    @Test
    fun resolveNetworkFailureShowsErrorAndAllowsRetry() =
        runTest {
            val source = source().apply { seed(trace(1)) }
            val state = state(source)
            source.failNext(TraceOp.RESOLVE, network())

            state.resolve(traceId(1))
            runCurrent()
            assertTrue(state.error is ApiError.Network)
            assertFalse(state.busy)
            state.resolve(traceId(1))
            runCurrent()

            assertNull(state.error)
            assertTrue(state.ids().isEmpty())
        }

    @Test
    fun refreshFailingAfterResolveStillReleasesBusy() =
        runTest {
            val source =
                source().apply {
                    seed(trace(1))
                    seed(trace(2))
                }
            val state = state(source)
            source.failNext(TraceOp.PAGE, network())

            state.resolve(traceId(1))
            runCurrent()

            assertFalse(state.busy)
            assertNotNull(state.error)
            assertEquals(listOf(traceId(1), traceId(2)), state.ids())
        }

    @Test
    fun closeDuringPendingCodeLookupsDiscardsResultsQuietly() =
        runTest {
            val source = source()
            (1..6).forEach {
                source.seed(trace(it, reservation = it))
                source.reservationCodes[reservationId(it).toString()] = "R-$it"
            }
            source.reservationCodeGate = CompletableDeferred()
            val state = TracesListState(source, backgroundScope, source.permissions)
            state.start()
            runCurrent()
            assertEquals(4, source.reservationCodeInFlight)

            state.close()
            source.reservationCodeGate?.complete(Unit)
            runCurrent()

            assertTrue(state.ids().isEmpty())
            assertEquals(0, source.reservationCodeInFlight)
            assertEquals(4, codeReads(source).size)
        }

    @Test
    fun closeDuringPendingPageKeepsRowsEmpty() =
        runTest {
            val source = source().apply { seed(trace(1)) }
            val gate = CompletableDeferred<Unit>()
            source.pageGates.addLast(gate)
            val state = TracesListState(source, backgroundScope, source.permissions)
            state.start()
            runCurrent()

            state.close()
            gate.complete(Unit)
            runCurrent()

            assertTrue(state.ids().isEmpty())
        }

    @Test
    fun actionsAfterCloseAreNoOps() =
        runTest {
            val source = source().apply { seed(trace(1)) }
            val state = state(source)
            val calls = source.calls.size

            state.close()
            state.setDepartment(DEPARTMENT.id)
            state.setStatus(null)
            state.setDue(DueFilter.TODAY)
            state.refresh()
            state.retryDepartments()
            state.start()
            runCurrent()

            assertEquals(calls, source.calls.size)
        }

    @Test
    fun departmentsRetryAfterForbiddenSucceedsAndClearsDenied() =
        runTest {
            val source = source()
            source.failNext(TraceOp.DEPARTMENTS, ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList()))
            val state = state(source)
            assertTrue(state.departmentsDenied)

            state.retryDepartments()
            runCurrent()

            assertFalse(state.departmentsDenied)
            assertTrue(state.canCreate)
        }

    @Test
    fun departmentsRetryFailingWithForbiddenSwitchesFromErrorToDenied() =
        runTest {
            val source = source()
            source.failNext(TraceOp.DEPARTMENTS, network())
            source.failNext(TraceOp.DEPARTMENTS, ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList()))
            val state = state(source)
            assertNotNull(state.departmentsError)

            state.retryDepartments()
            runCurrent()

            assertNull(state.departmentsError)
            assertTrue(state.departmentsDenied)
            assertFalse(state.canCreate)
        }

    @Test
    fun departmentsRetryFailingAgainKeepsCanCreateFalse() =
        runTest {
            val source = source()
            source.failNext(TraceOp.DEPARTMENTS, network())
            source.failNext(TraceOp.DEPARTMENTS, network())
            val state = state(source)

            state.retryDepartments()
            runCurrent()

            assertNotNull(state.departmentsError)
            assertFalse(state.canCreate)
        }

    @Test
    fun departmentsFailureDoesNotBlockTableNorPermissions() =
        runTest {
            val source = source().apply { seed(trace(1)) }
            source.failNext(TraceOp.DEPARTMENTS, network())
            val state = state(source)

            assertEquals(listOf(traceId(1)), state.ids())
            assertEquals(setOf(MANAGE, RESOLVE), state.permissions)
            assertNull(state.error)
        }

    @Test
    fun todayFilterUsesBusinessDateNotJvmClock() =
        runTest {
            val source =
                source().apply {
                    businessDay = businessDay.copy(date = BusinessDate.parse("2031-01-02"))
                    seed(trace(1, dueOn = "2031-01-02"))
                    seed(trace(2, dueOn = "2026-11-14"))
                }
            val state = state(source)

            state.setDue(DueFilter.TODAY)
            runCurrent()

            assertEquals("2031-01-02", pageFilters(source).last().dueOn?.toApi())
            assertEquals(listOf(traceId(1)), state.ids())
            state.setDue(DueFilter.ANY)
            runCurrent()
            assertNull(pageFilters(source).last().dueOn)
            assertEquals(listOf(traceId(1), traceId(2)), state.ids())
        }

    @Test
    fun todayFilterFailureReadingBusinessDayShowsErrorAndRecovers() =
        runTest {
            val source = source().apply { seed(trace(1)) }
            val state = state(source)
            source.failNext(TraceOp.BUSINESS_DAY, network())

            state.setDue(DueFilter.TODAY)
            runCurrent()
            assertNotNull(state.error)
            state.refresh()
            runCurrent()

            assertNull(state.error)
            assertEquals(listOf(traceId(1)), state.ids())
        }

    @Test
    fun codeCacheSurvivesFilterChangesAndRefresh() =
        runTest {
            val source = source()
            source.seed(trace(1, reservation = 1))
            source.seed(trace(2, "RESOLVED", reservation = 2))
            source.reservationCodes[reservationId(1).toString()] = "R-1"
            source.reservationCodes[reservationId(2).toString()] = "R-2"
            val state = state(source)

            state.setStatus(null)
            runCurrent()
            state.refresh()
            runCurrent()
            state.setDue(DueFilter.TODAY)
            runCurrent()

            assertEquals(codeReads(source).distinct().size, codeReads(source).size)
            assertEquals(
                listOf("Reserva R-1", "Reserva R-2"),
                state.table.rows.map { it.link },
            )
        }

    @Test
    fun codeReadsNeverExceedFourEvenAcrossReloadsMidLookup() =
        runTest {
            val source = source()
            (1..12).forEach {
                source.seed(trace(it, reservation = it))
                source.reservationCodes[reservationId(it).toString()] = "R-$it"
            }
            source.reservationCodeGate = CompletableDeferred()
            val state = TracesListState(source, backgroundScope, source.permissions)
            state.start()
            runCurrent()
            state.setDue(DueFilter.TODAY)
            runCurrent()
            state.setStatus(null)
            runCurrent()
            source.reservationCodeGate?.complete(Unit)
            runCurrent()

            assertEquals(4, source.maxReservationCodeInFlight)
            assertEquals(12, state.table.rows.size)
            assertTrue(state.table.rows.all { it.link.startsWith("Reserva R-") })
        }

    @Test
    fun failingCodeReadDoesNotPoisonOtherRows() =
        runTest {
            val source = source()
            (1..3).forEach {
                source.seed(trace(it, reservation = it))
                source.reservationCodes[reservationId(it).toString()] = "R-$it"
            }
            source.failNext(TraceOp.RESERVATION_CODE, network())
            val state = state(source)

            val links = state.table.rows.map { it.link }
            assertEquals(3, links.size)
            assertEquals(1, links.count { it == "Reserva" })
            assertEquals(2, links.count { it.startsWith("Reserva R-") })
            assertNull(state.error)
        }

    @Test
    fun failedCodeReadIsRetriedOnNextLoad() =
        runTest {
            val source = source()
            source.seed(trace(1, reservation = 1))
            source.reservationCodes[reservationId(1).toString()] = "R-1"
            source.failNext(TraceOp.RESERVATION_CODE, network())
            val state = state(source)
            assertEquals(
                "Reserva",
                state.table.rows
                    .single()
                    .link,
            )

            state.refresh()
            runCurrent()

            assertEquals(
                "Reserva R-1",
                state.table.rows
                    .single()
                    .link,
            )
        }

    @Test
    fun rowWithoutReservationNeverReadsCode() =
        runTest {
            val source = source().apply { seed(trace(1)) }
            state(source)

            assertTrue(codeReads(source).isEmpty())
        }

    @Test
    fun fixedReservationFilterSurvivesEveryFilterChange() =
        runTest {
            val fixed = reservationId(7).toString()
            val source =
                source().apply {
                    seed(trace(1, reservation = 7))
                    seed(trace(2, "RESOLVED", reservation = 7))
                    seed(trace(3, reservation = 8))
                }
            val state = state(source, fixed)

            state.setStatus(TraceStatusFilter.OPEN)
            runCurrent()
            assertEquals(listOf(traceId(1)), state.ids())
            state.setDepartment(DEPARTMENT.id)
            runCurrent()
            state.setDue(DueFilter.TODAY)
            runCurrent()
            state.setStatus(null)
            runCurrent()
            state.refresh()
            runCurrent()

            assertTrue(pageFilters(source).all { it.reservationId == fixed })
            assertEquals(listOf(traceId(1), traceId(2)), state.ids())
            assertTrue(codeReads(source).isEmpty())
            assertTrue(state.table.rows.all { it.link.isEmpty() && it.reservationId == fixed })
        }

    @Test
    fun fixedReservationNeverReadsCodeEvenOnLoadMore() =
        runTest {
            val fixed = reservationId(7).toString()
            val source = source()
            (1..60).forEach { source.seed(trace(it, reservation = 7)) }
            val state = state(source, fixed)

            state.table.loadMore()
            runCurrent()

            assertEquals(60, state.table.rows.size)
            assertTrue(codeReads(source).isEmpty())
        }

    @Test
    fun anyNonOpenStatusHasNoActions() =
        runTest {
            val source = source()
            listOf("RESOLVED", "open", "", "CANCELLED").forEachIndexed { i, status ->
                source.seed(trace(i + 1, status))
            }
            val state = state(source)
            state.setStatus(null)
            runCurrent()

            assertEquals(4, state.table.rows.size)
            state.table.rows.forEach {
                assertFalse(it.open, it.status.label)
                assertFalse(state.canEdit(it), it.status.label)
                assertFalse(state.canResolve(it), it.status.label)
            }
            assertTrue(state.table.rows.any { it.status.label == "Outro (open)" })
        }

    @Test
    fun noToStringLeaksTraceText() =
        runTest {
            val source =
                source().apply {
                    seed(trace(1, text = "segredo-um", reservation = 1))
                    seed(trace(2, text = "segredo-dois"))
                }
            source.reservationCodes[reservationId(1).toString()] = "R-1"
            val state = state(source)
            source.resolve(traceId(2))
            state.resolve(traceId(2))
            runCurrent()

            val dumps =
                listOf(state.toString(), state.table.toString(), state.table.rows.toString()) +
                    state.table.rows.map { it.toString() } +
                    listOfNotNull(state.error?.toString(), state.error?.let(TraceText::message))
            dumps.forEach { assertFalse(it.contains("segredo"), it) }
        }
}
