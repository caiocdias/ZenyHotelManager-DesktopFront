package com.zeny.desktop.app.traces

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.core.facade.NewTrace
import com.zeny.desktop.core.facade.TraceChange
import com.zeny.desktop.core.facade.TraceFilter
import com.zeny.desktop.core.facade.TraceStatusFilter
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class FakeTracesSourceTest {
    private val dept = Department(Uuid.random().toString(), "Governança")
    private val due = BusinessDate(LocalDate.of(2026, 11, 15))

    private fun fake() = FakeTracesSource().apply { departments = listOf(dept) }

    private fun newTrace(text: String = "t") =
        NewTrace(dept.id, due, text, reservationId = null, roomId = Uuid.random().toString())

    private fun change(text: String) = TraceChange(dept.id, due, text, null)

    @Test
    fun etagChangesOnEveryChangeAndStaleEtagIs412() =
        runTest {
            val fake = fake()
            val created = fake.create(newTrace())
            val edited = fake.update(created.trace.id.toString(), change("novo"), created.etag)
            assertNotEquals(created.etag, edited.etag)
            assertEquals(2L, edited.trace.version)
            assertFailsWith<ApiError.PreconditionFailed> {
                fake.update(created.trace.id.toString(), change("outro"), created.etag)
            }
            assertEquals("novo", fake.trace(created.trace.id.toString()).trace.text)
            val other = fake.externalEdit(created.trace.id.toString(), "de outro")
            assertNotEquals(edited.etag, other.etag)
            assertFailsWith<ApiError.PreconditionFailed> {
                fake.update(created.trace.id.toString(), change("meu"), edited.etag)
            }
        }

    @Test
    fun resolvedTraceRejectsResolveAndUpdateWith409() =
        runTest {
            val fake = fake()
            val created = fake.create(newTrace())
            val id = created.trace.id.toString()
            assertEquals("RESOLVED", fake.resolve(id).status)
            val again = assertFailsWith<ApiError.Problem> { fake.resolve(id) }
            assertEquals("TRACE_ALREADY_RESOLVED", again.code)
            val etag = fake.current(id)!!.etag
            val edit = assertFailsWith<ApiError.Problem> { fake.update(id, change("x"), etag) }
            assertEquals(409, edit.status)
        }

    @Test
    fun injectedFailureHitsOnlyTheNextCall() =
        runTest {
            val fake = fake()
            fake.failNext(TraceOp.CREATE, ApiError.Network(RuntimeException("x")))
            assertFailsWith<ApiError.Network> { fake.create(newTrace()) }
            fake.create(newTrace())
            assertEquals(2, fake.calls.size)
        }

    @Test
    fun pageFiltersAndPaginates() =
        runTest {
            val fake = fake()
            repeat(3) { fake.create(newTrace()) }
            val first = fake.page(TraceFilter(status = TraceStatusFilter.OPEN), null, 2)
            assertEquals(2, first.items.size)
            val second = fake.page(TraceFilter(), first.nextCursor, 2)
            assertEquals(1, second.items.size)
            assertEquals(null, second.nextCursor)
            assertEquals(0, fake.page(TraceFilter(status = TraceStatusFilter.RESOLVED), null, 10).items.size)
        }

    @Test
    fun reservationCodeGateHoldsAndCountsReads() =
        runTest {
            val fake = fake()
            fake.reservationCodes["a"] = "R-1"
            fake.reservationCodes["b"] = "R-2"
            val gate = CompletableDeferred<Unit>()
            fake.reservationCodeGate = gate
            val reads = listOf("a", "b").map { async { fake.reservationCode(it) } }
            testScheduler.runCurrent()
            assertEquals(2, fake.reservationCodeInFlight)
            gate.complete(Unit)
            assertEquals(listOf("R-1", "R-2"), reads.awaitAll())
            assertEquals(0, fake.reservationCodeInFlight)
            assertEquals(2, fake.maxReservationCodeInFlight)
        }
}
