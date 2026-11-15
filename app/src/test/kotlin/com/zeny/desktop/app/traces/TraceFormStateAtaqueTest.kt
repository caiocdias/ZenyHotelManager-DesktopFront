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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val DEPT = Department("40000000-0000-0000-0000-000000000001", "Governança")
private val OTHER_DEPT = Department("40000000-0000-0000-0000-000000000002", "Manutenção")
private const val RESERVATION = "20000000-0000-0000-0000-000000000001"
private val ROOM = RoomOption("30000000-0000-0000-0000-000000000001", "203")
private const val SECRET = "segredo-do-hospede"
private const val TRACE_ID = "50000000-0000-0000-0000-000000000001"
private const val TEXT_MSG = "Escreva o recado (até 500 caracteres)."
private const val PRECONDITION_MSG = "Alguém alterou este recado. Confira e salve de novo."
private const val RESOLVED_MSG = "Este recado já foi resolvido."

private fun trace(
    status: String = "OPEN",
    dueOn: String = "2026-11-14",
    assignee: Boolean = true,
) = TraceResponse(
    assigneeName = if (assignee) Option.Some("Maria") else Option.Undefined,
    assigneeUserId =
        if (assignee) Option.Some(Uuid.parse("60000000-0000-0000-0000-000000000001")) else Option.Undefined,
    createdAt = "2026-11-14T10:00:00Z",
    createdBy = Uuid.random(),
    departmentId = Uuid.parse(DEPT.id),
    departmentName = DEPT.name,
    dueOn = dueOn,
    id = Uuid.parse(TRACE_ID),
    status = status,
    text = "texto original",
    version = 1,
)

private fun invalid(vararg fields: String) =
    ApiError.Problem(400, "TRACE_INVALID", null, fields.map { FieldError(it, "X", "m") })

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // um teste por comportamento atacado do formulário de recado
class TraceFormStateAtaqueTest {
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
        onSaved: () -> Unit = { savedCount++ },
    ) = TraceFormState(source, backgroundScope, target, source.departments, denied, onSaved).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.unstarted(s: FakeTracesSource) =
        TraceFormState(s, backgroundScope, TraceTarget.Existing(TRACE_ID), s.departments, false) { savedCount++ }

    private fun TestScope.create(source: FakeTracesSource) =
        form(source, TraceTarget.ForReservation(RESERVATION, "R-1"))

    private fun TestScope.edit(
        source: FakeTracesSource,
        t: TraceResponse = trace(),
        denied: Boolean = false,
    ): TraceFormState {
        source.seed(t)
        return form(source, TraceTarget.Existing(t.id.toString()), denied)
    }

    private fun TraceFormState.fill(text: String = "ok") {
        this.text = text
        departmentId = DEPT.id
    }

    private fun FakeTracesSource.creates() = calls.filterIsInstance<TraceCall.Create>()

    private fun FakeTracesSource.updates() = calls.filterIsInstance<TraceCall.Update>()

    // Duas telas no mesmo recado.
    @Test
    fun secondFormGets412ThenSavesWithNewEtag() =
        runTest {
            val s = source()
            val a = edit(s)
            val b = form(s, TraceTarget.Existing(TRACE_ID))
            a.text = "da primeira"
            a.save()
            runCurrent()
            assertTrue(a.saved)
            b.text = "da segunda"
            b.save()
            runCurrent()
            assertEquals(PRECONDITION_MSG, b.notice)
            assertEquals("da primeira", b.text)
            assertFalse(b.saved)
            assertFalse(b.busy)
            assertTrue(b.canSave)
            assertEquals(2, s.updates().size)
            assertEquals(s.updates()[0].etag, s.updates()[1].etag)
            val fresh = s.current(TRACE_ID)!!.etag
            b.text = "da segunda de novo"
            b.save()
            runCurrent()
            assertTrue(b.saved)
            assertEquals(3, s.updates().size)
            assertNotEquals(s.updates()[1].etag, s.updates()[2].etag)
            assertEquals(fresh, s.updates().last().etag)
            assertEquals("da segunda de novo", s.current(TRACE_ID)!!.trace.text)
        }

    @Test
    fun noticeIsClearedOnNextSave() =
        runTest {
            val s = source()
            val f = edit(s)
            s.externalEdit(TRACE_ID, "outro")
            f.save()
            runCurrent()
            assertNotNull(f.notice)
            f.save()
            runCurrent()
            assertNull(f.notice)
            assertNull(f.error)
        }

    @Test
    fun reloadAfter412RefreshesDueDateAndOverdueRuleBase() =
        runTest {
            val s = source()
            val f = edit(s, trace(dueOn = "2026-11-13"))
            // outra pessoa muda o vencimento para ontem (11/11)
            val other = s.current(TRACE_ID)!!.trace.copy(dueOn = "2026-11-12", version = 2)
            s.seed(other)
            f.save()
            runCurrent()
            assertEquals("12/11/2026", f.dueText)
            // vencimento recarregado e mantido: salvar sem mudar não dá erro de vencimento
            f.save()
            runCurrent()
            assertTrue(f.fieldErrors.isEmpty(), f.fieldErrors.toString())
            assertTrue(f.saved)
        }

    @Test
    fun reloadResolvedBlocksEvenAfterTextChange() =
        runTest {
            val s = source()
            val f = edit(s)
            s.resolve(TRACE_ID)
            f.save()
            runCurrent()
            assertEquals(RESOLVED_MSG, f.notice)
            assertFalse(f.canSave)
            assertFalse(f.busy)
            f.text = "tentando"
            f.save()
            runCurrent()
            assertEquals(1, s.updates().size)
            assertEquals(0, savedCount)
        }

    @Test
    fun reloadFailureClearsEtagAndKeepsBlocked() =
        runTest {
            val s = source()
            val f = edit(s)
            s.externalEdit(TRACE_ID, "outra")
            s.failNext(TraceOp.TRACE, traceNotFound())
            f.save()
            runCurrent()
            assertFalse(f.canSave)
            assertNull(f.notice)
            assertNotNull(f.error)
            assertFalse(f.busy)
            assertEquals(0, savedCount)
        }

    @Test
    fun alreadyResolved409OnEditBlocksAndNotifies() =
        runTest {
            val s = source()
            val f = edit(s)
            s.failNext(TraceOp.UPDATE, alreadyResolved())
            f.save()
            runCurrent()
            assertEquals(RESOLVED_MSG, f.error)
            assertEquals(1, savedCount)
            assertFalse(f.saved)
            assertFalse(f.canSave)
            f.save()
            runCurrent()
            assertEquals(1, s.updates().size)
        }

    @Test
    fun updateFailureOnEditIsNotUnknownCreateResult() =
        runTest {
            val s = source()
            val f = edit(s)
            s.failNext(TraceOp.UPDATE, ApiError.Network(IOException("x")))
            f.save()
            runCurrent()
            assertNotEquals(TraceText.UNKNOWN_CREATE_RESULT, f.error)
            assertEquals(0, savedCount)
            assertTrue(f.canSave)
            val etag = s.updates().single().etag
            f.save()
            runCurrent()
            assertEquals(etag, s.updates().last().etag)
            assertTrue(f.saved)
        }

    @Test
    fun editKeepsAssigneeReadFromServerAndSendsNullWhenNone() =
        runTest {
            val s = source()
            val f = edit(s)
            f.text = "x"
            f.save()
            runCurrent()
            assertEquals(
                "60000000-0000-0000-0000-000000000001",
                s
                    .updates()
                    .single()
                    .change.assigneeUserId,
            )
            val s2 = source()
            val g = edit(s2, trace(assignee = false))
            assertNull(g.assigneeName)
            g.text = "x"
            g.save()
            runCurrent()
            assertNull(
                s2
                    .updates()
                    .single()
                    .change.assigneeUserId,
            )
        }

    @Test
    fun editOverdueTextOnlyPassesAndChangeToPastFails() =
        runTest {
            val s = source()
            val f = edit(s, trace(dueOn = "2026-11-01"))
            f.text = "só texto"
            f.save()
            runCurrent()
            assertTrue(f.fieldErrors.isEmpty())
            assertEquals(
                "2026-11-01",
                s
                    .updates()
                    .single()
                    .change.dueOn
                    .toApi(),
            )
            val s2 = source()
            val g = edit(s2, trace(dueOn = "2026-11-01"))
            g.dueText = "02/11/2026"
            g.save()
            runCurrent()
            assertEquals("O vencimento não pode ser antes da data operacional.", g.fieldErrors["dueOn"])
            assertTrue(s2.updates().isEmpty())
            g.dueText = "14/11/2026"
            g.save()
            runCurrent()
            assertTrue(g.saved)
        }

    @Test
    fun createWithPastDueDateIsRejectedEvenWhenSameAsNothing() =
        runTest {
            val s = source()
            val f = create(s)
            f.fill()
            f.dueText = "13/11/2026"
            f.save()
            runCurrent()
            assertNotNull(f.fieldErrors["dueOn"])
            assertTrue(s.creates().isEmpty())
            f.dueText = "14/11/2026"
            f.save()
            runCurrent()
            assertTrue(f.saved)
        }

    @Test
    fun text500AfterTrimPasses501Fails() =
        runTest {
            val s = source()
            val f = create(s)
            f.fill("  " + "x".repeat(500) + "  ")
            f.save()
            runCurrent()
            assertTrue(f.fieldErrors.isEmpty(), f.fieldErrors.toString())
            assertEquals(
                "x".repeat(500),
                s
                    .creates()
                    .single()
                    .input.text,
            )
            val s2 = source()
            val g = create(s2)
            g.fill("x".repeat(501))
            g.save()
            runCurrent()
            assertEquals(TEXT_MSG, g.fieldErrors["text"])
            g.text = " " + "x".repeat(501) + " "
            g.save()
            runCurrent()
            assertEquals(TEXT_MSG, g.fieldErrors["text"])
            assertTrue(s2.creates().isEmpty())
        }

    @Test
    fun whitespaceOnlyTextIsRejected() =
        runTest {
            val s = source()
            val f = create(s)
            f.fill(" \t\n  ")
            f.save()
            runCurrent()
            assertEquals(TEXT_MSG, f.fieldErrors["text"])
            assertTrue(s.creates().isEmpty())
        }

    @Test
    fun invalidDateShapesAreRejectedLocally() =
        runTest {
            val s = source()
            val f = create(s)
            f.fill()
            val shapes = listOf("", "2026-11-14", "1/1/2026", "29/02/2027", "14/13/2026", "14/11/26", "abc")
            for (bad in shapes) {
                f.dueText = bad
                f.save()
                runCurrent()
                assertEquals("Data inválida. Use dd/mm/aaaa.", f.fieldErrors["dueOn"], "entrada: '$bad'")
            }
            assertTrue(s.creates().isEmpty())
            assertFalse(f.busy)
        }

    @Test
    fun dueTodayAndPaddedDateAreAccepted() =
        runTest {
            val s = source()
            val f = create(s)
            f.fill()
            f.dueText = " 14/11/2026 "
            f.save()
            runCurrent()
            assertTrue(f.saved, f.fieldErrors.toString())
            assertEquals(
                "2026-11-14",
                s
                    .creates()
                    .single()
                    .input.dueOn
                    .toApi(),
            )
        }

    @Test
    fun createForReservationAndForRoomFillOnlyTheirLink() =
        runTest {
            val s = source()
            val r = create(s)
            r.fill()
            r.roomId = ROOM.id
            assertNull(r.roomId)
            r.save()
            runCurrent()
            val c = s.creates().single().input
            assertEquals(RESERVATION, c.reservationId)
            assertNull(c.roomId)
            val s2 = source()
            val u = form(s2, TraceTarget.ForRoom)
            u.fill()
            u.save()
            runCurrent()
            assertEquals("Escolha a UH.", u.fieldErrors["roomId"])
            assertTrue(s2.creates().isEmpty())
            u.roomId = "99999999-0000-0000-0000-000000000000"
            assertNull(u.roomId)
            u.roomId = ROOM.id
            u.save()
            runCurrent()
            val d = s2.creates().single().input
            assertNull(d.reservationId)
            assertEquals(ROOM.id, d.roomId)
        }

    @Test
    fun unknownDepartmentIsIgnoredAndMissingOneBlocksCreate() =
        runTest {
            val s = source()
            val f = create(s)
            f.text = "ok"
            f.departmentId = "99999999-0000-0000-0000-000000000000"
            assertNull(f.departmentId)
            f.save()
            runCurrent()
            assertEquals("Escolha o setor.", f.fieldErrors["departmentId"])
            assertTrue(s.creates().isEmpty())
        }

    @Test
    fun unknownResultFailuresCallOnceAndRetryOnlyOnNewClick() =
        runTest {
            val failures =
                listOf(
                    ApiError.Network(IOException("x")),
                    ApiError.IncompatibleResponse(IllegalStateException("x")),
                    ApiError.SessionEnded("SESSION_EXPIRED"),
                )
            for (failure in failures) {
                savedCount = 0
                val s = source()
                val f = create(s)
                f.fill()
                s.failNext(TraceOp.CREATE, failure)
                f.save()
                runCurrent()
                assertEquals(1, s.creates().size, failure::class.simpleName)
                assertEquals(TraceText.UNKNOWN_CREATE_RESULT, f.error)
                assertEquals(1, savedCount)
                assertFalse(f.saved)
                assertFalse(f.busy)
                runCurrent()
                assertEquals(1, s.creates().size)
                f.save()
                runCurrent()
                assertEquals(2, s.creates().size)
                assertTrue(f.saved)
                assertEquals(2, savedCount)
            }
        }

    @Test
    fun definiteCreateFailureDoesNotNotifyList() =
        runTest {
            val s = source()
            val f = create(s)
            f.fill()
            s.failNext(TraceOp.CREATE, ApiError.Problem(404, "UNKNOWN_DEPARTMENT", null, emptyList()))
            f.save()
            runCurrent()
            assertEquals(0, savedCount)
            assertNotEquals(TraceText.UNKNOWN_CREATE_RESULT, f.error)
            assertNotNull(f.error)
            assertTrue(f.canSave)
        }

    @Test
    fun traceInvalidFieldsGoToTheirFields() =
        runTest {
            for ((field, expected) in listOf(
                "text" to TEXT_MSG,
                "dueOn" to "O vencimento não pode ser antes da data operacional.",
                "reservationId" to "A reserva do recado não existe mais.",
            )) {
                val s = source()
                val f = create(s)
                f.fill()
                s.failNext(TraceOp.CREATE, invalid(field))
                f.save()
                runCurrent()
                assertEquals(expected, f.fieldErrors[field], field)
                assertNull(f.error, field)
                assertEquals(0, savedCount)
                assertFalse(f.busy)
            }
        }

    @Test
    fun traceInvalidUnknownFieldBecomesGeneralError() =
        runTest {
            val s = source()
            val f = create(s)
            f.fill()
            s.failNext(TraceOp.CREATE, invalid("mystery"))
            f.save()
            runCurrent()
            assertTrue(f.fieldErrors.isEmpty())
            assertNotNull(f.error)
            val s2 = source()
            val g = create(s2)
            g.fill()
            s2.failNext(TraceOp.CREATE, invalid())
            g.save()
            runCurrent()
            assertTrue(g.fieldErrors.isEmpty())
            assertNotNull(g.error)
        }

    @Test
    fun traceInvalidOnEditGoesToField() =
        runTest {
            val s = source()
            val f = edit(s)
            s.failNext(TraceOp.UPDATE, invalid("text"))
            f.save()
            runCurrent()
            assertEquals(TEXT_MSG, f.fieldErrors["text"])
            assertTrue(f.canSave)
            assertEquals(0, savedCount)
        }

    @Test
    fun departmentsDeniedOnEditKeepsCurrentAndRejectsOthers() =
        runTest {
            val s = source()
            val f = edit(s, denied = true)
            assertEquals(listOf(DEPT), f.departmentOptions)
            f.departmentId = OTHER_DEPT.id
            assertEquals(DEPT.id, f.departmentId)
            f.save()
            runCurrent()
            assertEquals(
                DEPT.id,
                s
                    .updates()
                    .single()
                    .change.departmentId,
            )
        }

    @Test
    fun departmentsGrantedOnEditAllowsChange() =
        runTest {
            val s = source()
            val f = edit(s)
            f.departmentId = OTHER_DEPT.id
            f.save()
            runCurrent()
            assertEquals(
                OTHER_DEPT.id,
                s
                    .updates()
                    .single()
                    .change.departmentId,
            )
        }

    @Test
    fun doubleSaveWhileBusyOnEditSendsOnce() =
        runTest {
            val s = source()
            val gate = CompletableDeferred<Unit>()
            s.updateGate = gate
            val f = edit(s)
            f.text = "x"
            f.save()
            runCurrent()
            f.save()
            f.save()
            runCurrent()
            assertTrue(f.busy)
            assertFalse(f.canSave)
            f.dueText = "30/12/2026"
            assertEquals("14/11/2026", f.dueText)
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, s.updates().size)
            assertFalse(f.busy)
            assertEquals(1, savedCount)
        }

    @Test
    fun savedFormIgnoresFurtherSave() =
        runTest {
            val s = source()
            val f = create(s)
            f.fill()
            f.save()
            runCurrent()
            f.save()
            runCurrent()
            assertEquals(1, s.creates().size)
        }

    @Test
    fun closeDuringPendingUpdateDiscardsResultAndFreesBusy() =
        runTest {
            val s = source()
            val gate = CompletableDeferred<Unit>()
            s.updateGate = gate
            val f = edit(s)
            f.save()
            runCurrent()
            assertTrue(f.busy)
            f.close()
            assertFalse(f.busy)
            gate.complete(Unit)
            runCurrent()
            assertFalse(f.saved)
            assertEquals(0, savedCount)
            assertFalse(f.busy)
        }

    @Test
    fun closeDuringPending412ReloadDiscardsReload() =
        runTest {
            val s = source()
            val f = edit(s)
            s.externalEdit(TRACE_ID, "de outro")
            val gate = CompletableDeferred<Unit>()
            f.text = "meu"
            s.traceGate = gate
            f.save()
            runCurrent()
            assertTrue(f.busy)
            f.close()
            gate.complete(Unit)
            runCurrent()
            assertEquals("meu", f.text)
            assertNull(f.notice)
            assertFalse(f.busy)
            assertEquals(0, savedCount)
        }

    @Test
    fun closeDuringStartReloadDiscardsAndStaysClosed() =
        runTest {
            val s = source()
            s.seed(trace())
            val gate = CompletableDeferred<Unit>()
            s.traceGate = gate
            val f = unstarted(s)
            f.start()
            runCurrent()
            f.close()
            gate.complete(Unit)
            runCurrent()
            assertEquals("", f.text)
            assertFalse(f.canSave)
            f.start()
            runCurrent()
            assertEquals("", f.text)
        }

    @Test
    fun closeBeforeStartMakesStartNoOp() =
        runTest {
            val s = source()
            val f = TraceFormState(s, backgroundScope, TraceTarget.ForRoom, s.departments, false) { savedCount++ }
            f.close()
            f.start()
            runCurrent()
            assertTrue(s.calls.isEmpty())
        }

    @Test
    fun saveWhileStartStillLoadingIsNoOp() =
        runTest {
            val s = source()
            s.seed(trace())
            val gate = CompletableDeferred<Unit>()
            s.traceGate = gate
            val f = unstarted(s)
            f.start()
            runCurrent()
            f.save()
            runCurrent()
            assertTrue(s.updates().isEmpty())
            gate.complete(Unit)
            runCurrent()
            assertTrue(f.canSave)
        }

    @Test
    fun editOfMissingTraceBlocksSave() =
        runTest {
            val s = source()
            val f = form(s, TraceTarget.Existing(TRACE_ID))
            assertFalse(f.canSave)
            assertNotNull(f.error)
            f.save()
            runCurrent()
            assertTrue(s.updates().isEmpty())
        }

    @Test
    fun editOfResolvedTraceAtStartBlocksSave() =
        runTest {
            val s = source()
            val f = edit(s, trace(status = "RESOLVED"))
            assertFalse(f.canSave)
            assertEquals(RESOLVED_MSG, f.notice)
        }

    @Test
    fun businessDayFailureAtStartIsRetriedOnSave() =
        runTest {
            val s = source()
            s.failNext(TraceOp.BUSINESS_DAY, ApiError.Network(IOException("x")))
            val f = create(s)
            assertNotNull(f.error)
            f.fill()
            f.dueText = "20/11/2026"
            f.save()
            runCurrent()
            assertTrue(f.saved)
            assertNull(f.error)
        }

    @Test
    fun toStringNeverContainsTextAcrossStates() =
        runTest {
            val s = source()
            val f = create(s)
            f.fill(SECRET)
            assertFalse(f.toString().contains(SECRET))
            s.failNext(TraceOp.CREATE, ApiError.Network(IOException("x")))
            f.save()
            runCurrent()
            assertFalse(f.toString().contains(SECRET))
            assertFalse(f.error.orEmpty().contains(SECRET))
            f.save()
            runCurrent()
            assertFalse(f.toString().contains(SECRET))
            val g = edit(source(), trace())
            g.text = SECRET
            assertFalse(g.toString().contains(SECRET))
        }
}
