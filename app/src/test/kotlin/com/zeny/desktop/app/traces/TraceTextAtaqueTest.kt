package com.zeny.desktop.app.traces

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

/** Ataques à `TraceText`: códigos, textos crus, valores de status estranhos e vazamento do texto do recado. */
class TraceTextAtaqueTest {
    private val secret = "SEGREDO-DO-HOSPEDE-4242"

    private fun problem(
        code: String?,
        status: Int = 422,
        detail: String? = null,
    ) = ApiError.Problem(status = status, code = code, detail = detail, fieldErrors = emptyList())

    private fun trace(
        reservationId: Uuid? = null,
        roomNumber: String? = null,
        roomId: Uuid? = null,
        status: String = "OPEN",
    ) = TraceResponse(
        createdAt = "2026-11-14T10:00:00Z",
        createdBy = Uuid.random(),
        departmentId = Uuid.random(),
        departmentName = "Governança",
        dueOn = "2026-11-15",
        id = Uuid.random(),
        reservationId = reservationId?.let { Option.Some(it) } ?: Option.Undefined,
        roomId = roomId?.let { Option.Some(it) } ?: Option.Undefined,
        roomNumber = roomNumber?.let { Option.Some(it) } ?: Option.Undefined,
        status = status,
        text = secret,
        version = 1,
    )

    private val catalog =
        listOf(
            "TRACE_INVALID",
            "TRACE_ALREADY_RESOLVED",
            "TRACE_NOT_FOUND",
            "UNKNOWN_DEPARTMENT",
            "UNKNOWN_RESERVATION",
            "UNKNOWN_ROOM",
            "ROOM_INACTIVE",
            "UNKNOWN_ASSIGNEE",
        )

    @Test
    fun everyCatalogCodeHasItsOwnDistinctText() {
        val texts = catalog.map { TraceText.message(problem(it)) }
        assertEquals(catalog.size, texts.toSet().size, "dois códigos não podem dividir o mesmo texto")
        texts.forEachIndexed { i, text ->
            assertNotEquals(problem(catalog[i]).userMessage, text, "${catalog[i]} caiu no texto genérico")
            assertFalse(catalog[i] in text, "o código ${catalog[i]} apareceu cru")
        }
    }

    @Test
    fun catalogCodeIsMappedWhateverTheHttpStatus() {
        listOf(400, 404, 409, 422, 500).forEach { status ->
            assertEquals(
                "Este recado já foi resolvido.",
                TraceText.message(problem("TRACE_ALREADY_RESOLVED", status = status)),
                "status $status",
            )
        }
    }

    @Test
    fun unknownCodeNeverAppearsRawNorViaABareDetail() {
        val raw = "CODIGO_QUE_O_SERVIDOR_INVENTOU"
        listOf(
            problem(raw),
            problem(raw, status = 500),
            problem(raw, status = 409, detail = raw),
            problem(raw.lowercase()),
        ).forEach { error ->
            val text = TraceText.message(error)
            assertFalse(raw in text, text)
            assertFalse(raw.lowercase() in text, text)
            assertEquals(error.userMessage, text)
        }
    }

    @Test
    fun catalogCodeIsCaseSensitiveAndNotMatchedByPrefixOrPadding() {
        listOf("trace_invalid", "TRACE_INVALID ", " TRACE_INVALID", "TRACE_INVALID_X", "TRACE", "").forEach { code ->
            val error = problem(code)
            assertEquals(error.userMessage, TraceText.message(error), "'$code' não está no catálogo")
        }
    }

    @Test
    fun preconditionFailedTextIgnoresItsDetail() {
        val text = TraceText.message(ApiError.PreconditionFailed("etag W/\"abc\" não confere"))
        assertEquals("Alguém alterou este recado. Confira e salve de novo.", text)
        assertEquals(text, TraceText.message(ApiError.PreconditionFailed(null)))
    }

    @Test
    fun nonProblemErrorsUseTheCommonReceptionMessage() {
        val errors =
            listOf(
                ApiError.Network(RuntimeException("conexão recusada em 10.0.0.1")),
                ApiError.PasswordChangeRequired,
                ApiError.SessionEnded("TOKEN_REVOGADO"),
            )
        errors.forEach { error ->
            val text = TraceText.message(error)
            assertEquals(error.userMessage, text)
            assertFalse("10.0.0.1" in text)
            assertFalse("TOKEN_REVOGADO" in text)
        }
    }

    @Test
    fun statusOpenAndResolvedHaveDistinctToneAndLabel() {
        val open = TraceText.status("OPEN")
        val resolved = TraceText.status("RESOLVED")
        assertNotEquals(open.label, resolved.label)
        assertNotEquals(open.tone, resolved.tone)
        assertNotEquals(StatusTone.NEUTRAL, open.tone)
        assertNotEquals(StatusTone.NEUTRAL, resolved.tone)
    }

    @Test
    fun unknownStatusIsShownAsOutroWithTheValueAndNeutralTone() {
        assertEquals(ChipModel("Outro (ARCHIVED)", StatusTone.NEUTRAL), TraceText.status("ARCHIVED"))
        assertEquals(ChipModel("Outro (open)", StatusTone.NEUTRAL), TraceText.status("open"))
        assertEquals("Outro (Open )", TraceText.status("Open ").label)
        assertEquals("Outro ()", TraceText.status("").label)
    }

    @Test
    fun linkNeverEmbedsTheTraceTextNorTheIds() {
        val reservationId = Uuid.random()
        val roomId = Uuid.random()
        val links =
            listOf(
                TraceText.link(trace(reservationId = reservationId), "R-2026-0001"),
                TraceText.link(trace(reservationId = reservationId), null),
                TraceText.link(trace(roomNumber = "203", roomId = roomId), null),
                TraceText.link(trace(), null),
            )
        links.forEach { link ->
            assertFalse(secret in link, link)
            assertFalse(reservationId.toString() in link, link)
            assertFalse(roomId.toString() in link, link)
            assertFalse("Governança" in link, link)
        }
    }

    @Test
    fun linkPrefersTheReservationWhenBothArePresent() {
        val both = trace(reservationId = Uuid.random(), roomNumber = "203")
        assertEquals("Reserva R-9", TraceText.link(both, "R-9"))
        assertEquals("Reserva", TraceText.link(both, null))
    }

    @Test
    fun linkOfARoomIgnoresTheReservationCodeArgument() {
        assertEquals("UH 10B", TraceText.link(trace(roomNumber = "10B"), "R-1"))
    }

    @Test
    fun linkOfAResolvedTraceIsTheSameAsOfAnOpenOne() {
        val id = Uuid.random()
        assertEquals(
            TraceText.link(trace(reservationId = id, status = "OPEN"), "R-1"),
            TraceText.link(trace(reservationId = id, status = "RESOLVED"), "R-1"),
        )
    }
}
