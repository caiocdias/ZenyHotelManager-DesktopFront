package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.model.RoomMoveResponse
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

private fun problem(code: String?) = ApiError.Problem(409, code, "detalhe do servidor", emptyList())

private const val ANY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9f01"

class ActionTextStayTest {
    @Test
    fun moveSameRoomText() =
        assertEquals(
            "A UH escolhida é a mesma atual. Escolha outra UH.",
            ActionText.moveError(problem("ROOM_MOVE_SAME_ROOM")),
        )

    @Test
    fun moveInvalidText() =
        assertEquals(
            "O motivo da troca é inválido. Escolha outro motivo.",
            ActionText.moveError(problem("ROOM_MOVE_INVALID")),
        )

    @Test
    fun moveFixedStayText() =
        assertEquals(
            "A tarifa desta reserva de grupo não pode ser recalculada. Mantenha a tarifa.",
            ActionText.moveError(problem("ROOM_BLOCK_RESERVATION_STAY_FIXED")),
        )

    @Test
    fun moveRoomAlreadyAssignedText() =
        assertEquals(
            "A UH já está alocada a outra reserva no período. Escolha outra UH da lista.",
            ActionText.moveError(problem("ROOM_ALREADY_ASSIGNED")),
        )

    @Test
    fun moveRoomNotReadyText() =
        assertEquals(
            "A UH não está pronta. Escolha outra UH da lista ou peça a liberação à governança.",
            ActionText.moveError(problem("ROOM_NOT_READY")),
        )

    @Test
    fun moveRoomUnfitTexts() {
        for (code in listOf("ROOM_TYPE_MISMATCH", "ROOM_OUT_OF_ORDER", "ROOM_INACTIVE", "UNKNOWN_ROOM")) {
            assertEquals(
                "A UH não serve para esta troca. Escolha outra UH da lista.",
                ActionText.moveError(problem(code)),
                code,
            )
        }
    }

    @Test
    fun moveNotInHouseText() =
        assertEquals(
            "A reserva não está mais hospedada. Feche e confira o detalhe.",
            ActionText.moveError(problem("RESERVATION_TRANSITION_NOT_ALLOWED")),
        )

    @Test
    fun moveStayEndedText() =
        assertEquals(
            "A estadia desta reserva não tem mais noites; a UH não é mais trocada.",
            ActionText.moveError(problem("RESERVATION_STAY_ENDED")),
        )

    @Test
    fun movePreconditionFailedText() =
        assertEquals(
            "Alguém alterou esta reserva. Confira e confirme de novo.",
            ActionText.moveError(ApiError.PreconditionFailed("x")),
        )

    @Test
    fun moveOthersFallBackToError() {
        assertEquals(ActionText.error(problem("FOLIO_CLOSED")), ActionText.moveError(problem("FOLIO_CLOSED")))
        val network = ApiError.Network(IOException("x"))
        assertEquals(ActionText.error(network), ActionText.moveError(network))
    }

    @Test
    fun undoExpiredText() =
        assertEquals(
            "O check-in só pode ser desfeito na mesma data operacional em que foi feito.",
            ActionText.undoError(problem("UNDO_CHECK_IN_EXPIRED")),
        )

    @Test
    fun undoNotInHouseText() =
        assertEquals(
            "A reserva não está mais hospedada; o check-in pode já ter sido desfeito. Feche e confira o detalhe.",
            ActionText.undoError(problem("RESERVATION_TRANSITION_NOT_ALLOWED")),
        )

    @Test
    fun undoUnknownOutcomeText() {
        val expected = "Não foi possível confirmar o desfazer. Feche e confira o detalhe antes de tentar de novo."
        assertEquals(expected, ActionText.undoError(ApiError.Network(IOException("x"))))
        assertEquals(expected, ActionText.undoError(ApiError.IncompatibleResponse(IllegalStateException("x"))))
    }

    @Test
    fun undoOthersFallBackToError() {
        assertEquals(ActionText.error(problem("FOLIO_CLOSED")), ActionText.undoError(problem("FOLIO_CLOSED")))
        val ended = ApiError.SessionEnded(null)
        assertEquals(ActionText.error(ended), ActionText.undoError(ended))
    }

    @Test
    fun existingTextsUnchanged() {
        assertEquals(
            "A reserva não está mais confirmada; o check-in pode já ter sido feito. Feche e confira o detalhe.",
            ActionText.error(problem("RESERVATION_TRANSITION_NOT_ALLOWED")),
        )
        assertEquals(
            "A UH ainda não está pronta. Escolha uma UH pronta ou peça a liberação à governança.",
            ActionText.error(problem("ROOM_NOT_READY")),
        )
        assertEquals(
            "A UH não serve para este walk-in. Escolha outra UH da lista.",
            ActionText.walkInError(problem("ROOM_INACTIVE")),
        )
        assertEquals(
            "A UH não está pronta. Escolha outra UH da lista ou peça a liberação à governança.",
            ActionText.walkInError(problem("ROOM_NOT_READY")),
        )
    }

    @Test
    fun tariffTexts() {
        assertEquals("Manter a tarifa", ActionText.tariff("KEEP"))
        assertEquals("Recalcular pela tarifa atual", ActionText.tariff("RECALCULATE"))
        assertEquals("Outro (NOVA)", ActionText.tariff("NOVA"))
    }

    private fun moved(difference: String): RoomMoveResponse {
        fun room(
            id: String,
            number: String,
        ) = """{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"$number","occupancy":"VACANT",
            "roomId":"$id","version":5}"""
        val json =
            """{"difference":"$difference","remainingAfter":"1090.50","remainingBefore":"1000.00","tariff":"KEEP",
            "fromRoom":${room("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a01", "101")},
            "toRoom":${room("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9a02", "102")},
            "reservation":{"adults":2,"arrival":"2026-11-13","bookingId":"$ANY","children":0,"code":"R-1",
            "dayUse":false,"departure":"2026-11-15","folioId":"$ANY","guests":[],"id":"$ANY",
            "roomTypeId":"$ANY","status":"IN_HOUSE","version":3}}"""
        return Json { ignoreUnknownKeys = true }.decodeFromString(RoomMoveResponse.serializer(), json)
    }

    @Test
    fun moveSummaryText() {
        val summary = ActionText.moveSummary(moved("90.50"))
        assertEquals(
            "Troca feita para a UH 102. Noites restantes: " +
                "${ReceptionText.money("1000.00")} → ${ReceptionText.money("1090.50")} " +
                "(diferença ${ReceptionText.money("90.50")}).",
            summary,
        )
        val negative = ActionText.moveSummary(moved("-90.50"))
        assertEquals(
            "Troca feita para a UH 102. Noites restantes: " +
                "${ReceptionText.money("1000.00")} → ${ReceptionText.money("1090.50")} " +
                "(diferença ${ReceptionText.money("-90.50")}).",
            negative,
        )
    }
}
