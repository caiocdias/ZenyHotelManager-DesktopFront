package com.zeny.desktop.app.traces

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val formDept = Department("40000000-0000-0000-0000-000000000001", "Governança")
private const val FORM_RESERVATION = "20000000-0000-0000-0000-000000000001"

private fun formExisting() =
    TraceResponse(
        assigneeName = Option.Some("Maria"),
        createdAt = "2026-11-14T10:00:00Z",
        createdBy = Uuid.random(),
        departmentId = Uuid.parse(formDept.id),
        departmentName = formDept.name,
        dueOn = "2026-11-14",
        id = Uuid.parse("50000000-0000-0000-0000-000000000001"),
        status = "OPEN",
        text = "Texto original",
        version = 1,
    )

@OptIn(ExperimentalTestApi::class)
class TraceFormDialogTest {
    private var closed = 0

    private fun source() = FakeTracesSource().apply { departments = listOf(formDept) }

    private fun ComposeUiTest.dialog(
        source: FakeTracesSource,
        target: TraceTarget,
    ): TraceFormState {
        lateinit var form: TraceFormState
        setContent {
            val scope = rememberCoroutineScope()
            form = remember { TraceFormState(source, scope, target, source.departments, false) {} }
            ZenyTheme { TraceFormDialog(form) { closed++ } }
        }
        waitForIdle()
        return form
    }

    private val reservation = TraceTarget.ForReservation(FORM_RESERVATION, "R-77")

    @Test
    fun formShowsHintCounterAndLink() =
        runComposeUiTest {
            dialog(source(), reservation)
            onNodeWithTag("trace-form").assertExists()
            onNodeWithText("Novo recado").assertExists()
            onNodeWithText("Não escreva dados pessoais do hóspede no recado.").assertExists()
            onNodeWithText("0/500").assertExists()
            onNodeWithText("Reserva R-77").assertExists()
        }

    @Test
    fun formShowsFieldErrors() =
        runComposeUiTest {
            dialog(source(), reservation)
            onNodeWithTag("trace-save").performClick()
            waitForIdle()
            onNodeWithText("Escreva o recado (até 500 caracteres).").assertExists()
            onNodeWithText("Escolha o setor.").assertExists()
        }

    @Test
    fun formShowsPreconditionNotice() =
        runComposeUiTest {
            val source = source()
            val trace = formExisting()
            source.seed(trace)
            dialog(source, TraceTarget.Existing(trace.id.toString()))
            onNodeWithText("Editar recado").assertExists()
            onNodeWithText("Responsável: Maria").assertExists()
            source.externalEdit(trace.id.toString(), "Outro texto")
            onNodeWithTag("trace-text").performTextInput(" mais")
            onNodeWithTag("trace-save").performClick()
            waitForIdle()
            onNodeWithText("Alguém alterou este recado. Confira e salve de novo.").assertExists()
        }

    @Test
    fun formClosesAfterSave() =
        runComposeUiTest {
            val source = source()
            dialog(source, reservation)
            onNodeWithTag("trace-text").performTextInput("Levar toalhas")
            onNodeWithTag("department-0").performClick()
            onNodeWithTag("trace-save").performClick()
            waitForIdle()
            assertTrue(source.calls.any { it is TraceCall.Create })
            assertEquals(1, closed)
        }

    @Test
    fun editShowsCurrentLink() =
        runComposeUiTest {
            val source = source().apply { reservationCodes[FORM_RESERVATION] = "R-77" }
            val trace = formExisting().copy(reservationId = Option.Some(Uuid.parse(FORM_RESERVATION)))
            source.seed(trace)
            dialog(source, TraceTarget.Existing(trace.id.toString()))
            onNodeWithText("Reserva R-77").assertExists()
        }
}
