package com.zeny.desktop.app.traces

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.error.FieldError
import com.zeny.desktop.core.facade.Department
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val DEPT = Department("40000000-0000-0000-0000-000000000001", "Governança")
private val OTHER_DEPT = Department("40000000-0000-0000-0000-000000000002", "Manutenção")
private const val RESERVATION = "20000000-0000-0000-0000-000000000001"
private val ROOM = RoomOption("30000000-0000-0000-0000-000000000001", "203")
private const val SECRET = "texto sigiloso"

private fun existing(
    status: String = "OPEN",
    dueOn: String = "2026-11-14",
) = TraceResponse(
    assigneeName = Option.Some("Maria"),
    assigneeUserId = Option.Some(Uuid.parse("60000000-0000-0000-0000-000000000001")),
    createdAt = "2026-11-14T10:00:00Z",
    createdBy = Uuid.random(),
    departmentId = Uuid.parse(DEPT.id),
    departmentName = DEPT.name,
    dueOn = dueOn,
    id = Uuid.parse("50000000-0000-0000-0000-000000000001"),
    status = status,
    text = SECRET,
    version = 1,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TraceFormStateTest {
    private var savedCount = 0

    private fun source() =
        FakeTracesSource().apply {
            departments = listOf(DEPT, OTHER_DEPT)
            rooms = listOf(ROOM)
        }

    private fun TestScope.form(
        source: FakeTracesSource,
        target: TraceTarget,
        denied: Boolean = false,
    ) = TraceFormState(source, backgroundScope, target, source.departments, denied) { savedCount++ }.also {
        it.start()
        runCurrent()
    }

    private fun TestScope.forReservation(source: FakeTracesSource) =
        form(source, TraceTarget.ForReservation(RESERVATION, "R-1"))

    private fun TestScope.edit(
        source: FakeTracesSource,
        trace: TraceResponse = existing(),
        denied: Boolean = false,
    ): TraceFormState {
        source.seed(trace)
        return form(source, TraceTarget.Existing(trace.id.toString()), denied)
    }

    private fun TraceFormState.fill() {
        text = "Levar toalhas"
        departmentId = DEPT.id
    }

    @Test
    fun createForReservationSendsLink() =
        runTest {
            val s = source()
            val f = forReservation(s)
            assertEquals("14/11/2026", f.dueText)
            f.fill()
            f.save()
            runCurrent()
            val call =
                s.calls
                    .filterIsInstance<TraceCall.Create>()
                    .single()
                    .input
            assertEquals(RESERVATION, call.reservationId)
            assertNull(call.roomId)
            assertEquals("Levar toalhas", call.text)
            assertTrue(f.saved)
            assertEquals(1, savedCount)
            assertFalse(f.busy)
        }

    @Test
    fun createForRoomNeedsRoom() =
        runTest {
            val s = source()
            val f = form(s, TraceTarget.ForRoom)
            assertEquals(listOf(ROOM), f.rooms)
            f.fill()
            f.save()
            runCurrent()
            assertEquals("Escolha a UH.", f.fieldErrors["roomId"])
            assertTrue(s.calls.none { it is TraceCall.Create })
            f.roomId = ROOM.id
            f.save()
            runCurrent()
            assertEquals(
                ROOM.id,
                s.calls
                    .filterIsInstance<TraceCall.Create>()
                    .single()
                    .input.roomId,
            )
            assertTrue(f.saved)
        }

    @Test
    fun localValidationBeforeNetwork() =
        runTest {
            val s = source()
            val f = forReservation(s)
            f.save()
            runCurrent()
            assertEquals("Escreva o recado (até 500 caracteres).", f.fieldErrors["text"])
            assertEquals("Escolha o setor.", f.fieldErrors["departmentId"])
            f.fill()
            f.text = "x".repeat(501)
            f.save()
            runCurrent()
            assertEquals("Escreva o recado (até 500 caracteres).", f.fieldErrors["text"])
            f.text = "ok"
            f.dueText = "31/02/2026"
            f.save()
            runCurrent()
            assertEquals("Data inválida. Use dd/mm/aaaa.", f.fieldErrors["dueOn"])
            f.dueText = "13/11/2026"
            f.save()
            runCurrent()
            assertEquals("O vencimento não pode ser antes da data operacional.", f.fieldErrors["dueOn"])
            assertTrue(s.calls.none { it is TraceCall.Create })
            assertFalse(f.busy)
        }

    @Test
    fun networkFailureOnCreateDoesNotRetry() =
        runTest {
            val s = source()
            val f = forReservation(s)
            f.fill()
            s.failNext(TraceOp.CREATE, ApiError.Network(IOException("caiu")))
            f.save()
            runCurrent()
            assertEquals(1, s.calls.filterIsInstance<TraceCall.Create>().size)
            assertEquals(TraceText.UNKNOWN_CREATE_RESULT, f.error)
            assertEquals(1, savedCount)
            assertFalse(f.saved)
            assertTrue(f.canSave)
            f.save()
            runCurrent()
            assertEquals(2, s.calls.filterIsInstance<TraceCall.Create>().size)
        }

    @Test
    fun problem5xxGivesUnknownCreateResultAndReloads() =
        runTest {
            val s = source()
            val f = forReservation(s)
            f.fill()
            s.failNext(TraceOp.CREATE, ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList()))
            f.save()
            runCurrent()
            assertEquals(1, s.calls.filterIsInstance<TraceCall.Create>().size)
            assertEquals(TraceText.UNKNOWN_CREATE_RESULT, f.error)
            assertEquals(1, savedCount)
            assertFalse(f.saved)
        }

    @Test
    fun unexpected5xxGivesUnknownCreateResultAndReloads() =
        runTest {
            val s = source()
            val f = forReservation(s)
            f.fill()
            s.failNext(TraceOp.CREATE, ApiError.Unexpected(502))
            f.save()
            runCurrent()
            assertEquals(1, s.calls.filterIsInstance<TraceCall.Create>().size)
            assertEquals(TraceText.UNKNOWN_CREATE_RESULT, f.error)
            assertEquals(1, savedCount)
        }

    @Test
    fun problem5xxOnEditKeepsTheMessage() =
        runTest {
            val s = source()
            val f = edit(s)
            val failure = ApiError.Problem(500, "INTERNAL_ERROR", null, emptyList())
            s.failNext(TraceOp.UPDATE, failure)
            f.save()
            runCurrent()
            assertEquals(TraceText.message(failure), f.error)
            assertEquals(0, savedCount)
        }

    @Test
    fun editLoadsCurrentValues() =
        runTest {
            val f = edit(source())
            assertEquals(SECRET, f.text)
            assertEquals("14/11/2026", f.dueText)
            assertEquals(DEPT.id, f.departmentId)
            assertEquals("Maria", f.assigneeName)
            assertTrue(f.canSave)
        }

    @Test
    fun updateKeepsAssignee() =
        runTest {
            val s = source()
            val f = edit(s)
            f.text = "Novo texto"
            f.save()
            runCurrent()
            val call = s.calls.filterIsInstance<TraceCall.Update>().single()
            assertEquals("60000000-0000-0000-0000-000000000001", call.change.assigneeUserId)
            assertEquals("Novo texto", call.change.text)
            assertTrue(f.saved)
            assertEquals(1, savedCount)
        }

    @Test
    fun preconditionFailedReloadsAndWarns() =
        runTest {
            val s = source()
            val f = edit(s)
            val id = existing().id.toString()
            s.externalEdit(id, "texto de outra pessoa")
            f.text = "minha edição"
            f.save()
            runCurrent()
            assertEquals("Alguém alterou este recado. Confira e salve de novo.", f.notice)
            assertEquals("texto de outra pessoa", f.text)
            assertFalse(f.saved)
            assertTrue(f.canSave)
            assertEquals(0, savedCount)
        }

    @Test
    fun saveAfterReloadUsesNewEtag() =
        runTest {
            val s = source()
            val f = edit(s)
            val id = existing().id.toString()
            s.externalEdit(id, "de outra pessoa")
            f.save()
            runCurrent()
            val fresh = s.current(id)!!.etag
            f.save()
            runCurrent()
            val updates = s.calls.filterIsInstance<TraceCall.Update>()
            assertEquals(2, updates.size)
            assertEquals(fresh, updates.last().etag)
            assertTrue(f.saved)
        }

    @Test
    fun reloadFindsResolvedBlocksSave() =
        runTest {
            val s = source()
            val f = edit(s)
            s.resolve(existing().id.toString())
            f.save()
            runCurrent()
            assertEquals("Este recado já foi resolvido.", f.notice)
            assertFalse(f.canSave)
            f.save()
            runCurrent()
            assertEquals(1, s.calls.filterIsInstance<TraceCall.Update>().size)
        }

    @Test
    fun reloadFailureBlocksSave() =
        runTest {
            val s = source()
            val f = edit(s)
            s.externalEdit(existing().id.toString(), "outra")
            s.failNext(TraceOp.TRACE, ApiError.Network(IOException("caiu")))
            f.save()
            runCurrent()
            assertFalse(f.canSave)
            assertEquals(TraceText.message(ApiError.Network(IOException())), f.error)
            f.save()
            runCurrent()
            assertEquals(1, s.calls.filterIsInstance<TraceCall.Update>().size)
        }

    @Test
    fun editOverdueKeepsDueDate() =
        runTest {
            val s = source()
            val f = edit(s, existing(dueOn = "2026-11-13"))
            f.text = "só o texto"
            f.save()
            runCurrent()
            assertTrue(f.fieldErrors.isEmpty())
            assertTrue(f.saved)
            val g = edit(source(), existing(dueOn = "2026-11-13"))
            g.dueText = "12/11/2026"
            g.save()
            runCurrent()
            assertEquals("O vencimento não pode ser antes da data operacional.", g.fieldErrors["dueOn"])
        }

    @Test
    fun traceInvalidGoesToField() =
        runTest {
            val s = source()
            val f = forReservation(s)
            f.fill()
            val invalid = ApiError.Problem(400, "TRACE_INVALID", null, listOf(FieldError("text", "TOO_LONG", "x")))
            s.failNext(TraceOp.CREATE, invalid)
            f.save()
            runCurrent()
            assertEquals("Escreva o recado (até 500 caracteres).", f.fieldErrors["text"])
            assertNull(f.error)
            assertFalse(f.busy)
        }

    @Test
    fun alreadyResolvedOnEditShowsMessage() =
        runTest {
            val s = source()
            val f = edit(s)
            s.failNext(TraceOp.UPDATE, alreadyResolved())
            f.save()
            runCurrent()
            assertEquals("Este recado já foi resolvido.", f.error)
            assertEquals(1, savedCount)
            assertFalse(f.canSave)
        }

    @Test
    fun editWithoutDepartmentsKeepsCurrent() =
        runTest {
            val s = source()
            s.departments = emptyList()
            val f = edit(s, denied = true)
            assertEquals(listOf(DEPT), f.departmentOptions)
            f.text = "novo"
            f.save()
            runCurrent()
            assertEquals(
                DEPT.id,
                s.calls
                    .filterIsInstance<TraceCall.Update>()
                    .single()
                    .change.departmentId,
            )
        }

    @Test
    fun saveWhileBusyIsNoOp() =
        runTest {
            val s = source()
            val gate = CompletableDeferred<Unit>()
            s.createGate = gate
            val f = forReservation(s)
            f.fill()
            f.save()
            f.save()
            runCurrent()
            assertTrue(f.busy)
            f.text = "mudou"
            assertEquals("Levar toalhas", f.text)
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, s.calls.filterIsInstance<TraceCall.Create>().size)
            assertFalse(f.busy)
        }

    @Test
    fun resultAfterCloseIsDiscarded() =
        runTest {
            val s = source()
            val gate = CompletableDeferred<Unit>()
            s.createGate = gate
            val f = forReservation(s)
            f.fill()
            f.save()
            runCurrent()
            f.close()
            gate.complete(Unit)
            runCurrent()
            assertFalse(f.saved)
            assertEquals(0, savedCount)
            assertFalse(f.busy)
        }

    @Test
    fun toStringHidesText() =
        runTest {
            val f = edit(source())
            assertFalse(f.toString().contains(SECRET))
        }
}
