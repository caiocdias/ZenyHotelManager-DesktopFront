package com.zeny.desktop.app.traces

import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val LINK_DEPT = Department("40000000-0000-0000-0000-000000000001", "Governança")
private val LINK_RESERVATION = Uuid.parse("20000000-0000-0000-0000-000000000001")

private fun linkTrace(
    reservation: Boolean,
    room: Boolean = false,
) = TraceResponse(
    createdAt = "2026-11-14T10:00:00Z",
    createdBy = Uuid.random(),
    departmentId = Uuid.parse(LINK_DEPT.id),
    departmentName = LINK_DEPT.name,
    dueOn = "2026-11-14",
    id = Uuid.parse("50000000-0000-0000-0000-000000000001"),
    reservationId = if (reservation) Option.Some(LINK_RESERVATION) else Option.Undefined,
    roomId = if (room) Option.Some(Uuid.random()) else Option.Undefined,
    roomNumber = if (room) Option.Some("203") else Option.Undefined,
    status = "OPEN",
    text = "texto",
    version = 1,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TraceFormLinkTest {
    private fun source(trace: TraceResponse) =
        FakeTracesSource().apply {
            departments = listOf(LINK_DEPT)
            reservationCodes[LINK_RESERVATION.toString()] = "R-77"
            seed(trace)
        }

    private fun TestScope.edit(
        source: FakeTracesSource,
        trace: TraceResponse,
    ) = TraceFormState(source, backgroundScope, TraceTarget.Existing(trace.id.toString()), source.departments, false) {}
        .also {
            it.start()
            runCurrent()
        }

    @Test
    fun reservationTraceShowsCode() =
        runTest {
            val trace = linkTrace(reservation = true)
            assertEquals("Reserva R-77", edit(source(trace), trace).linkText)
        }

    @Test
    fun roomTraceShowsRoomNumber() =
        runTest {
            val trace = linkTrace(reservation = false, room = true)
            assertEquals("UH 203", edit(source(trace), trace).linkText)
        }

    @Test
    fun codeFailureFallsBackAndDoesNotBlock() =
        runTest {
            val trace = linkTrace(reservation = true)
            val source = source(trace).apply { failNext(TraceOp.RESERVATION_CODE, traceNotFound()) }
            val form = edit(source, trace)
            assertEquals("Reserva", form.linkText)
            assertTrue(form.canSave)
            assertNull(form.error)
        }

    @Test
    fun linkRefreshesAfterPreconditionReread() =
        runTest {
            val trace = linkTrace(reservation = true)
            val source = source(trace)
            val form = edit(source, trace)
            source.reservationCodes[LINK_RESERVATION.toString()] = "R-99"
            source.externalEdit(trace.id.toString(), "outro")
            form.departmentId = LINK_DEPT.id
            form.save()
            runCurrent()
            assertEquals("Reserva R-99", form.linkText)
        }
}
