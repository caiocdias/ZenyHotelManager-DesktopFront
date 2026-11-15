package com.zeny.desktop.app.traces

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.core.facade.NewTrace
import com.zeny.desktop.core.facade.TraceChange
import com.zeny.desktop.core.types.BusinessDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Fidelidade do `FakeTracesSource`: as Tasks 3 e 4 provam a concorrência com ele, então a semântica de ETag, 412,
 * 409, falha injetada e portão precisa ser a do servidor.
 */
class FakeTracesSourceAtaqueTest {
    private val dept = Department(Uuid.random().toString(), "Governança")
    private val due = BusinessDate(LocalDate.of(2026, 11, 15))

    private fun fake() = FakeTracesSource().apply { departments = listOf(dept) }

    private fun newTrace(text: String = "t") =
        NewTrace(dept.id, due, text, reservationId = null, roomId = Uuid.random().toString())

    private fun change(text: String) = TraceChange(dept.id, due, text, null)

    private fun network() = ApiError.Network(RuntimeException("x"))

    @Test
    fun resolveChangesTheEtagSoALateEditIsA412NotA409() =
        runTest {
            val fake = fake()
            val created = fake.create(newTrace())
            val id = created.trace.id.toString()
            fake.resolve(id)
            val etagAfter = fake.current(id)!!.etag
            assertNotEquals(created.etag, etagAfter)
            assertFailsWith<ApiError.PreconditionFailed> { fake.update(id, change("x"), created.etag) }
        }

    @Test
    fun resolveBumpsVersionAndSetsResolvedAt() =
        runTest {
            val fake = fake()
            val created = fake.create(newTrace())
            val resolved = fake.resolve(created.trace.id.toString())
            assertEquals("RESOLVED", resolved.status)
            assertEquals(created.trace.version + 1, resolved.version)
            assertEquals(resolved, fake.current(created.trace.id.toString())!!.trace)
            assertTrue(resolved.resolvedAt is de.quati.kotlin.util.Option.Some)
        }

    @Test
    fun staleEtagLeavesTheTraceAndItsEtagUntouched() =
        runTest {
            val fake = fake()
            val created = fake.create(newTrace("original"))
            val id = created.trace.id.toString()
            val fresh = fake.externalEdit(id, "de outro")
            assertFailsWith<ApiError.PreconditionFailed> { fake.update(id, change("meu"), created.etag) }
            assertEquals(fresh, fake.current(id))
        }

    @Test
    fun updateOfAResolvedTraceWithTheCurrentEtagIs409AndChangesNothing() =
        runTest {
            val fake = fake()
            val created = fake.create(newTrace("original"))
            val id = created.trace.id.toString()
            fake.resolve(id)
            val before = fake.current(id)!!
            val error = assertFailsWith<ApiError.Problem> { fake.update(id, change("tarde"), before.etag) }
            assertEquals(409, error.status)
            assertEquals("TRACE_ALREADY_RESOLVED", error.code)
            assertEquals(before, fake.current(id))
        }

    @Test
    fun secondResolveIs409AndKeepsTheEtag() =
        runTest {
            val fake = fake()
            val id =
                fake
                    .create(newTrace())
                    .trace.id
                    .toString()
            fake.resolve(id)
            val before = fake.current(id)!!
            val error = assertFailsWith<ApiError.Problem> { fake.resolve(id) }
            assertEquals(409, error.status)
            assertEquals(before, fake.current(id))
        }

    @Test
    fun unknownIdIs404TraceNotFoundOnEveryOperation() =
        runTest {
            val fake = fake()
            val missing = Uuid.random().toString()
            listOf<suspend () -> Unit>(
                { fake.trace(missing) },
                { fake.update(missing, change("x"), "etag-1") },
                { fake.resolve(missing) },
            ).forEach { op ->
                val error = assertFailsWith<ApiError.Problem> { op() }
                assertEquals(404, error.status)
                assertEquals("TRACE_NOT_FOUND", error.code)
            }
        }

    @Test
    fun etagsAreUniqueAcrossTracesAndAcrossEveryChange() =
        runTest {
            val fake = fake()
            val a = fake.create(newTrace())
            val b = fake.create(newTrace())
            val etags = mutableListOf(a.etag, b.etag)
            etags += fake.update(a.trace.id.toString(), change("1"), a.etag).etag
            etags += fake.externalEdit(b.trace.id.toString(), "2").etag
            fake.resolve(a.trace.id.toString())
            etags += fake.current(a.trace.id.toString())!!.etag
            assertEquals(etags.size, etags.toSet().size, "ETags repetidos: $etags")
        }

    @Test
    fun getReturnsTheSameEtagUntilSomethingChanges() =
        runTest {
            val fake = fake()
            val created = fake.create(newTrace())
            val id = created.trace.id.toString()
            assertEquals(created.etag, fake.trace(id).etag)
            assertEquals(created.etag, fake.trace(id).etag)
        }

    @Test
    fun twoEditsWithTheSameEtagOnlyTheFirstWins() =
        runTest {
            val fake = fake()
            val created = fake.create(newTrace())
            val id = created.trace.id.toString()
            fake.update(id, change("primeira"), created.etag)
            assertFailsWith<ApiError.PreconditionFailed> { fake.update(id, change("segunda"), created.etag) }
            assertEquals("primeira", fake.trace(id).trace.text)
        }

    @Test
    fun updateAppliesDepartmentDueDateAndTextAndKeepsTheLink() =
        runTest {
            val other = Department(Uuid.random().toString(), "Manutenção")
            val fake = fake().apply { departments = listOf(dept, other) }
            val created = fake.create(newTrace("a"))
            val newDue = BusinessDate(LocalDate.of(2026, 11, 20))
            val edited =
                fake.update(created.trace.id.toString(), TraceChange(other.id, newDue, "b", null), created.etag).trace
            assertEquals(other.id, edited.departmentId.toString())
            assertEquals("Manutenção", edited.departmentName)
            assertEquals(newDue.toApi(), edited.dueOn)
            assertEquals("b", edited.text)
            assertEquals(created.trace.roomId, edited.roomId)
            assertEquals("OPEN", edited.status)
        }

    @Test
    fun injectedFailuresAreFifoPerOperationAndDoNotChangeState() =
        runTest {
            val fake = fake()
            val created = fake.create(newTrace("original"))
            val id = created.trace.id.toString()
            val first = network()
            val second = ApiError.PasswordChangeRequired
            fake.failNext(TraceOp.UPDATE, first)
            fake.failNext(TraceOp.UPDATE, second)
            assertEquals(first, assertFailsWith<ApiError.Network> { fake.update(id, change("x"), created.etag) })
            assertFailsWith<ApiError.PasswordChangeRequired> { fake.update(id, change("x"), created.etag) }
            assertEquals(created, fake.current(id))
            assertEquals("novo", fake.update(id, change("novo"), created.etag).trace.text)
        }

    @Test
    fun injectedFailureOfOneOperationDoesNotLeakIntoAnother() =
        runTest {
            val fake = fake()
            val created = fake.create(newTrace())
            fake.failNext(TraceOp.RESOLVE, network())
            fake.trace(created.trace.id.toString())
            fake.page(
                com.zeny.desktop.core.facade
                    .TraceFilter(),
                null,
                10,
            )
            assertFailsWith<ApiError.Network> { fake.resolve(created.trace.id.toString()) }
            assertEquals("OPEN", fake.current(created.trace.id.toString())!!.trace.status)
        }

    @Test
    fun everyOperationAcceptsAnInjectedFailure() =
        runTest {
            val fake = fake()
            val id =
                fake
                    .create(newTrace())
                    .trace.id
                    .toString()
            val ops: Map<TraceOp, suspend () -> Unit> =
                mapOf(
                    TraceOp.PERMISSIONS to { fake.permissions() },
                    TraceOp.BUSINESS_DAY to { fake.businessDay() },
                    TraceOp.PAGE to {
                        fake.page(
                            com.zeny.desktop.core.facade
                                .TraceFilter(),
                            null,
                            5,
                        )
                    },
                    TraceOp.TRACE to { fake.trace(id) },
                    TraceOp.CREATE to { fake.create(newTrace()) },
                    TraceOp.UPDATE to { fake.update(id, change("x"), "etag-0") },
                    TraceOp.RESOLVE to { fake.resolve(id) },
                    TraceOp.DEPARTMENTS to { fake.departments() },
                    TraceOp.RESERVATION_CODE to { fake.reservationCode("r") },
                    TraceOp.ROOMS to { fake.rooms() },
                )
            assertEquals(TraceOp.entries.toSet(), ops.keys, "uma operação nova ficou sem teste de falha")
            ops.forEach { (op, call) ->
                fake.failNext(op, network())
                assertFailsWith<ApiError.Network>("$op") { call() }
            }
        }

    @Test
    fun callsAreRecordedInOrderEvenWhenTheyFail() =
        runTest {
            val fake = fake()
            fake.failNext(TraceOp.TRACE, network())
            assertFailsWith<ApiError.Network> { fake.trace("a") }
            assertFailsWith<ApiError.Problem> { fake.resolve("b") }
            assertEquals(listOf(TraceCall.Trace("a"), TraceCall.Resolve("b")), fake.calls.toList())
        }

    @Test
    fun gateHoldsEveryReadUntilOpenedAndInFlightReturnsToZeroAfterFailuresToo() =
        runTest {
            val fake = fake()
            ('a'..'e').forEach { fake.reservationCodes[it.toString()] = "R-$it" }
            val gate = CompletableDeferred<Unit>()
            fake.reservationCodeGate = gate
            fake.failNext(TraceOp.RESERVATION_CODE, network())
            val reads = ('a'..'e').map { id -> async { runCatching { fake.reservationCode(id.toString()) } } }
            testScheduler.runCurrent()
            assertEquals(5, fake.reservationCodeInFlight)
            assertTrue(reads.none { it.isCompleted }, "o portão fechado não pode deixar nenhuma leitura passar")
            gate.complete(Unit)
            val results = reads.awaitAll()
            assertEquals(0, fake.reservationCodeInFlight)
            assertEquals(5, fake.maxReservationCodeInFlight)
            assertEquals(1, results.count { it.exceptionOrNull() is ApiError.Network })
            assertEquals(4, results.count { it.getOrNull()?.startsWith("R-") == true })
        }

    @Test
    fun cancelledGatedReadReleasesItsInFlightSlot() =
        runTest {
            val fake = fake()
            fake.reservationCodes["a"] = "R-1"
            fake.reservationCodeGate = CompletableDeferred()
            val read = async(start = CoroutineStart.UNDISPATCHED) { fake.reservationCode("a") }
            assertEquals(1, fake.reservationCodeInFlight)
            read.cancelAndJoin()
            assertEquals(0, fake.reservationCodeInFlight)
            assertEquals(1, fake.maxReservationCodeInFlight)
        }

    @Test
    fun withoutAGateReadsAnswerAtOnceAndTheMaxStaysAtOne() =
        runTest {
            val fake = fake()
            fake.reservationCodes["a"] = "R-1"
            assertEquals("R-1", fake.reservationCode("a"))
            assertEquals("R-1", fake.reservationCode("a"))
            assertEquals(0, fake.reservationCodeInFlight)
            assertEquals(1, fake.maxReservationCodeInFlight)
        }

    @Test
    fun seedingTheSameIdAgainReplacesTheTraceAndRenewsTheEtag() =
        runTest {
            val fake = fake()
            val created = fake.create(newTrace())
            val reseeded = fake.seed(created.trace.copy(text = "outro"))
            assertNotEquals(created.etag, reseeded.etag)
            assertEquals(
                1,
                fake
                    .page(
                        com.zeny.desktop.core.facade
                            .TraceFilter(),
                        null,
                        10,
                    ).items.size,
            )
            assertFailsWith<ApiError.PreconditionFailed> {
                fake.update(created.trace.id.toString(), change("x"), created.etag)
            }
        }
}
