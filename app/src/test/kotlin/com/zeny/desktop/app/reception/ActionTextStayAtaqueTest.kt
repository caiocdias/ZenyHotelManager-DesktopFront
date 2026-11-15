package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.model.RoomMoveResponse
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

private const val ANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

private fun problem(
    code: String?,
    status: Int = 409,
    detail: String? = "detalhe do servidor",
) = ApiError.Problem(status, code, detail, emptyList())

/** Ataques aos textos da troca de UH e do desfazer check-in. */
class ActionTextStayAtaqueTest {
    private val moveCodes =
        listOf(
            "ROOM_MOVE_SAME_ROOM",
            "ROOM_MOVE_INVALID",
            "ROOM_BLOCK_RESERVATION_STAY_FIXED",
            "ROOM_ALREADY_ASSIGNED",
            "ROOM_NOT_READY",
            "ROOM_TYPE_MISMATCH",
            "ROOM_OUT_OF_ORDER",
            "ROOM_INACTIVE",
            "UNKNOWN_ROOM",
            "RESERVATION_TRANSITION_NOT_ALLOWED",
            "RESERVATION_STAY_ENDED",
        )

    @Test
    fun moveTextsNeverShowRawCodeOrJson() {
        for (code in moveCodes) {
            val text = ActionText.moveError(problem(code))
            assertFalse(text.contains(code), text)
            assertFalse(text.contains("{"), text)
            assertFalse(text.contains("detalhe do servidor"), text)
        }
    }

    @Test
    fun moveCodesOutsideCatalogFallToError() {
        for (code in listOf("CODIGO_QUE_NAO_EXISTE", "UNDO_CHECK_IN_EXPIRED", "room_not_ready", "", "FOLIO_CLOSED")) {
            assertEquals(ActionText.error(problem(code)), ActionText.moveError(problem(code)), code)
        }
        assertEquals(ActionText.error(problem(null)), ActionText.moveError(problem(null)))
    }

    @Test
    fun moveNonProblemErrorsUseUserMessage() {
        val errors =
            listOf(
                ApiError.Network(IOException("x")),
                ApiError.IncompatibleResponse(IllegalStateException("x")),
                ApiError.SessionEnded(null),
                ApiError.Unexpected(500),
                ApiError.PasswordChangeRequired,
                ApiError.ApprovalRequired("a", "p", "r", null, null),
            )
        for (error in errors) assertEquals(error.userMessage, ActionText.moveError(error), error::class.simpleName)
    }

    @Test
    fun movePreconditionFailedIgnoresServerDetail() {
        val expected = "Alguém alterou esta reserva. Confira e confirme de novo."
        assertEquals(expected, ActionText.moveError(ApiError.PreconditionFailed(null)))
        assertEquals(expected, ActionText.moveError(ApiError.PreconditionFailed("outro texto")))
    }

    @Test
    fun moveNotInHouseDiffersFromCheckInAndUndoTexts() {
        val code = problem("RESERVATION_TRANSITION_NOT_ALLOWED")
        assertNotEquals(ActionText.error(code), ActionText.moveError(code))
        assertNotEquals(ActionText.undoError(code), ActionText.moveError(code))
    }

    @Test
    fun moveRoomAlreadyAssignedAndNotReadyDifferFromE02Texts() {
        for (code in listOf("ROOM_ALREADY_ASSIGNED", "ROOM_NOT_READY", "ROOM_OUT_OF_ORDER", "ROOM_TYPE_MISMATCH")) {
            assertNotEquals(ActionText.error(problem(code)), ActionText.moveError(problem(code)), code)
        }
    }

    @Test
    fun undoCodesOutsideCatalogFallToError() {
        for (code in moveCodes - "RESERVATION_TRANSITION_NOT_ALLOWED") {
            assertEquals(ActionText.error(problem(code)), ActionText.undoError(problem(code)), code)
        }
        assertEquals(ActionText.error(problem(null)), ActionText.undoError(problem(null)))
        assertEquals(ActionText.error(problem("XYZ")), ActionText.undoError(problem("XYZ")))
    }

    @Test
    fun undoOtherNonProblemErrorsUseUserMessage() {
        val errors =
            listOf(
                ApiError.PreconditionFailed("x"),
                ApiError.SessionEnded("C"),
                ApiError.Unexpected(502),
                ApiError.PasswordChangeRequired,
                ApiError.ApprovalRequired("a", "p", "r", null, null),
            )
        for (error in errors) assertEquals(error.userMessage, ActionText.undoError(error), error::class.simpleName)
    }

    @Test
    fun undoUnknownOutcomeWinsOverServerDetailAndMoveErrorKeepsNetworkText() {
        val network = ApiError.Network(IOException("x"))
        assertNotEquals(network.userMessage, ActionText.undoError(network))
        assertEquals(network.userMessage, ActionText.moveError(network))
    }

    @Test
    fun errorAndWalkInErrorKeepTheirExactTexts() {
        val expected =
            mapOf(
                "ROOM_NOT_READY" to
                    "A UH ainda não está pronta. Escolha uma UH pronta ou peça a liberação à governança.",
                "ROOM_NOT_ASSIGNED" to "A reserva ainda não tem UH. Aloque uma UH antes do check-in.",
                "ROOM_ALREADY_ASSIGNED" to
                    "A UH já está alocada a outra reserva em parte do período. Escolha outra UH das sugestões.",
                "ROOM_OUT_OF_ORDER" to "A UH está fora de ordem em alguma noite da estadia. Escolha outra UH.",
                "ROOM_TYPE_MISMATCH" to "A UH não é do tipo da reserva. Escolha uma UH das sugestões.",
                "ROOM_INACTIVE" to "A UH está inativa. Escolha outra UH.",
                "CHECK_IN_BEFORE_ARRIVAL" to
                    "O check-in não pode ser feito antes da data de chegada. Confira a data da reserva.",
                "RESERVATION_TRANSITION_NOT_ALLOWED" to
                    "A reserva não está mais confirmada; o check-in pode já ter sido feito. " +
                    "Feche e confira o detalhe.",
            )
        for ((code, text) in expected) assertEquals(text, ActionText.error(problem(code)), code)
        val walkIn =
            mapOf(
                "ROOM_ALREADY_ASSIGNED" to
                    "A UH já está alocada a outra reserva no período. Escolha outra UH da lista.",
                "ROOM_NOT_READY" to
                    "A UH não está pronta. Escolha outra UH da lista ou peça a liberação à governança.",
                "ROOM_OUT_OF_ORDER" to "A UH não serve para este walk-in. Escolha outra UH da lista.",
                "ROOM_TYPE_MISMATCH" to "A UH não serve para este walk-in. Escolha outra UH da lista.",
                "ROOM_INACTIVE" to "A UH não serve para este walk-in. Escolha outra UH da lista.",
            )
        for ((code, text) in walkIn) assertEquals(text, ActionText.walkInError(problem(code)), code)
        // os códigos novos da troca não vazam para error nem walkInError
        val newCodes =
            listOf("ROOM_MOVE_SAME_ROOM", "ROOM_MOVE_INVALID", "RESERVATION_STAY_ENDED", "UNDO_CHECK_IN_EXPIRED")
        for (code in newCodes) {
            assertEquals(ActionText.error(problem(code)), ActionText.walkInError(problem(code)), code)
        }
        assertEquals(
            ApiError.PreconditionFailed("x").userMessage,
            ActionText.error(ApiError.PreconditionFailed("x")),
        )
    }

    @Test
    fun tariffOutsideCatalogShowsValueInParentheses() {
        assertEquals("Outro (keep)", ActionText.tariff("keep"))
        assertEquals("Outro ()", ActionText.tariff(""))
        assertEquals("Outro (KEEP )", ActionText.tariff("KEEP "))
        assertEquals("Outro (INITIAL)", ActionText.tariff("INITIAL"))
    }

    private fun moved(
        difference: String,
        before: String = "1000.00",
        after: String = "1000.00",
    ): RoomMoveResponse {
        fun room(
            id: String,
            number: String,
        ) = """{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"$number","occupancy":"VACANT",
            "roomId":"$id","version":5}"""
        val json =
            """{"difference":"$difference","remainingAfter":"$after","remainingBefore":"$before","tariff":"KEEP",
            "fromRoom":${room("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01", "101")},
            "toRoom":${room("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02", "202")},
            "reservation":{"adults":2,"arrival":"2026-11-13","bookingId":"$ANY","children":0,"code":"R-1",
            "dayUse":false,"departure":"2026-11-15","folioId":"$ANY","guests":[],"id":"$ANY",
            "roomTypeId":"$ANY","status":"IN_HOUSE","version":3}}"""
        return Json { ignoreUnknownKeys = true }.decodeFromString(RoomMoveResponse.serializer(), json)
    }

    private fun summary(
        before: String,
        after: String,
        diff: String,
    ) = "Troca feita para a UH 202. Noites restantes: ${ReceptionText.money(before)} → " +
        "${ReceptionText.money(after)} (diferença ${ReceptionText.money(diff)})."

    @Test
    fun moveSummaryZeroDifference() =
        assertEquals(
            summary("1000.00", "1000.00", "0.00"),
            ActionText.moveSummary(moved("0.00")),
        )

    @Test
    fun moveSummaryNegativeDifferenceKeepsTheSignInsideParentheses() {
        val text = ActionText.moveSummary(moved("-250.00", "1000.00", "750.00"))
        assertEquals(summary("1000.00", "750.00", "-250.00"), text)
        assertEquals(true, text.contains("-250"), text)
    }

    @Test
    fun moveSummaryUsesDestinationRoomNumberAndBeforeBeforeAfter() {
        val text = ActionText.moveSummary(moved("10.00", "100.00", "110.00"))
        assertEquals(true, text.contains("UH 202"), text)
        assertFalse(text.contains("101"), text)
        assertEquals(true, text.indexOf(ReceptionText.money("100.00")) < text.indexOf(ReceptionText.money("110.00")))
    }

    @Test
    fun moveSummaryFormatsThousandsInBrazilianStyle() {
        val text = ActionText.moveSummary(moved("1234.50", "1000.00", "2234.50"))
        assertEquals(true, text.contains("R$ 1.234,50"), text)
        assertEquals(true, text.contains("R$ 2.234,50"), text)
    }
}
