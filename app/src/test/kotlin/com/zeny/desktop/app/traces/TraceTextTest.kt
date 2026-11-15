package com.zeny.desktop.app.traces

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class TraceTextTest {
    private fun problem(code: String?) =
        ApiError.Problem(status = 422, code = code, detail = null, fieldErrors = emptyList())

    private fun trace(
        reservationId: Uuid? = null,
        roomNumber: String? = null,
    ) = TraceResponse(
        createdAt = "2026-11-14T10:00:00Z",
        createdBy = Uuid.random(),
        departmentId = Uuid.random(),
        departmentName = "Governança",
        dueOn = "2026-11-15",
        id = Uuid.random(),
        reservationId = reservationId?.let { Option.Some(it) } ?: Option.Undefined,
        roomNumber = roomNumber?.let { Option.Some(it) } ?: Option.Undefined,
        status = "OPEN",
        text = "texto secreto do recado",
        version = 1,
    )

    @Test
    fun statusLabels() {
        assertEquals("Aberto", TraceText.status("OPEN").label)
        assertEquals("Resolvido", TraceText.status("RESOLVED").label)
        assertEquals(ChipModel("Outro (XYZ)", StatusTone.NEUTRAL), TraceText.status("XYZ"))
    }

    @Test
    fun problemTexts() {
        val expected =
            mapOf(
                "TRACE_INVALID" to
                    "O recado está inválido: confira o texto (até 500 caracteres) e o vencimento " +
                    "(não antes da data operacional).",
                "TRACE_ALREADY_RESOLVED" to "Este recado já foi resolvido.",
                "TRACE_NOT_FOUND" to "O recado não existe mais. Atualize a lista.",
                "UNKNOWN_DEPARTMENT" to "O setor escolhido não existe mais. Escolha outro.",
                "UNKNOWN_RESERVATION" to "A reserva do recado não existe mais.",
                "UNKNOWN_ROOM" to "A UH escolhida não existe mais. Escolha outra.",
                "ROOM_INACTIVE" to "A UH está inativa. Escolha outra.",
                "UNKNOWN_ASSIGNEE" to "O responsável do recado não está mais vinculado a um colaborador.",
            )
        expected.forEach { (code, text) -> assertEquals(text, TraceText.message(problem(code)), code) }
        assertEquals(
            "Alguém alterou este recado. Confira e salve de novo.",
            TraceText.message(ApiError.PreconditionFailed(null)),
        )
        val unknown = problem("ALGO_NOVO_DO_SERVIDOR")
        assertEquals(unknown.userMessage, TraceText.message(unknown))
        assertFalse("ALGO_NOVO_DO_SERVIDOR" in TraceText.message(unknown))
        assertEquals(ApiError.PasswordChangeRequired.userMessage, TraceText.message(ApiError.PasswordChangeRequired))
        assertEquals(problem(null).userMessage, TraceText.message(problem(null)))
    }

    @Test
    fun linkTexts() {
        val id = Uuid.random()
        assertEquals("Reserva R-2026-0042", TraceText.link(trace(reservationId = id), "R-2026-0042"))
        assertEquals("Reserva", TraceText.link(trace(reservationId = id), null))
        assertEquals("UH 203", TraceText.link(trace(roomNumber = "203"), null))
        val text = TraceText.link(trace(reservationId = id), "R-1")
        assertFalse("secreto" in text)
        assertFalse(id.toString() in TraceText.link(trace(reservationId = id), null))
        assertTrue(TraceText.DEPARTMENTS_DENIED.contains("Consultar setores, colaboradores e turnos"))
        assertTrue(TraceText.UNKNOWN_CREATE_RESULT.startsWith("Não foi possível confirmar"))
    }
}
