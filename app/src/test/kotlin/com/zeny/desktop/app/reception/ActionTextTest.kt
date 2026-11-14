package com.zeny.desktop.app.reception

import com.zeny.desktop.app.rooms.displayStatus
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.sdk.model.EarlyLateOfferResponse
import com.zeny.desktop.sdk.model.EarlyLateQuoteResponse
import com.zeny.desktop.sdk.model.OutOfOrderOverlayResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import com.zeny.desktop.sdk.model.RoomStatusResponse.ServiceFlag
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

private val OOO = OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER

private fun problem(code: String?) = ApiError.Problem(422, code, null, emptyList())

private fun room(
    ready: Boolean = false,
    occupancy: Occupancy = Occupancy.VACANT,
    housekeeping: HousekeepingStatus = HousekeepingStatus.CLEAN,
    outOfOrder: Boolean = false,
) = RoomStatusResponse(
    active = true,
    changedAt = "2026-11-12T10:00:00Z",
    housekeepingStatus = housekeeping,
    inspectionPending = false,
    number = "101",
    occupancy = occupancy,
    outOfOrder =
        if (outOfOrder) {
            Option.Some(OutOfOrderOverlayResponse("2026-11-12", Uuid.random(), OOO, "2026-11-10"))
        } else {
            Option.Undefined
        },
    readyForCheckIn = ready,
    roomId = Uuid.random(),
    roomTypeId = Uuid.random(),
    serviceFlag = ServiceFlag.NONE,
    version = 1,
)

private fun quote(early: EarlyLateOfferResponse) =
    EarlyLateQuoteResponse(
        arrival = "2026-11-14",
        checkInTime = "14:00",
        checkOutTime = "12:00",
        departure = "2026-11-15",
        earlyCheckIn = early,
        lateCheckOut = EarlyLateOfferResponse(offered = false),
        reservationId = Uuid.random(),
        timezone = "America/Sao_Paulo",
    )

class ActionTextTest {
    private fun assertCode(
        code: String,
        text: String,
    ) = assertEquals(text, ActionText.error(problem(code)), code)

    @Test
    fun roomNotReady() =
        assertCode(
            "ROOM_NOT_READY",
            "A UH ainda não está pronta. Escolha uma UH pronta ou peça a liberação à governança.",
        )

    @Test
    fun roomNotAssigned() =
        assertCode("ROOM_NOT_ASSIGNED", "A reserva ainda não tem UH. Aloque uma UH antes do check-in.")

    @Test
    fun roomAlreadyAssigned() =
        assertCode(
            "ROOM_ALREADY_ASSIGNED",
            "A UH já está alocada a outra reserva em parte do período. Escolha outra UH das sugestões.",
        )

    @Test
    fun roomAssignmentLocked() =
        assertCode(
            "ROOM_ASSIGNMENT_LOCKED",
            "A UH desta reserva está marcada como \"não mover\". Desmarque antes de trocar ou soltar.",
        )

    @Test
    fun roomOutOfOrder() =
        assertCode("ROOM_OUT_OF_ORDER", "A UH está fora de ordem em alguma noite da estadia. Escolha outra UH.")

    @Test
    fun roomTypeMismatch() =
        assertCode("ROOM_TYPE_MISMATCH", "A UH não é do tipo da reserva. Escolha uma UH das sugestões.")

    @Test
    fun roomInactive() = assertCode("ROOM_INACTIVE", "A UH está inativa. Escolha outra UH.")

    @Test
    fun guestDataIncomplete() =
        assertCode(
            "GUEST_DATA_INCOMPLETE",
            "Faltam dados do hóspede: nacionalidade, data de nascimento ou um documento aceito " +
                "(CPF para brasileiro adulto). Cadastre o hóspede com esses dados ou complete o perfil " +
                "na área Hóspedes.",
        )

    @Test
    fun minorCodes() {
        val text =
            "Hóspede menor de idade precisa da data de nascimento e da autorização do responsável " +
                "no perfil (área Hóspedes)."
        assertCode("GUEST_BIRTH_DATE_REQUIRED", text)
        assertCode("MINOR_AUTHORIZATION_REQUIRED", text)
        assertCode("MINOR_AUTHORIZATION_INVALID", text)
    }

    @Test
    fun checkInBeforeArrival() =
        assertCode(
            "CHECK_IN_BEFORE_ARRIVAL",
            "O check-in não pode ser feito antes da data de chegada. Confira a data da reserva.",
        )

    @Test
    fun transitionNotAllowed() =
        assertCode(
            "RESERVATION_TRANSITION_NOT_ALLOWED",
            "A reserva não está mais confirmada; o check-in pode já ter sido feito. Feche e confira o detalhe.",
        )

    @Test
    fun earlyFeeChanged() =
        assertCode(
            "EARLY_LATE_FEE_CHANGED",
            "O valor do early check-in mudou. Confira a nova cotação e confirme de novo.",
        )

    @Test
    fun earlyNotOffered() =
        assertCode(
            "EARLY_LATE_NOT_OFFERED",
            "O early check-in não é oferecido agora. Desmarque a cobrança e confirme de novo.",
        )

    @Test
    fun folioClosed() = assertCode("FOLIO_CLOSED", "A conta da reserva está encerrada. Fale com o financeiro.")

    @Test
    fun guestAnonymized() =
        assertCode("GUEST_ANONYMIZED", "Os dados deste hóspede foram anonimizados. Escolha ou cadastre outro hóspede.")

    @Test
    fun unknownGuest() = assertCode("UNKNOWN_GUEST", "O hóspede escolhido não existe mais. Busque de novo.")

    @Test
    fun unknownCodeFallsBackToUserMessage() {
        val error = problem("SOMETHING_NEW")
        assertEquals(error.userMessage, ActionText.error(error))
        val noCode = problem(null)
        assertEquals(noCode.userMessage, ActionText.error(noCode))
    }

    @Test
    fun nonProblemUsesUserMessage() {
        val error = ApiError.PreconditionFailed(null)
        assertEquals(error.userMessage, ActionText.error(error))
        assertEquals(ApiError.Unexpected(500).userMessage, ActionText.error(ApiError.Unexpected(500)))
    }

    @Test
    fun readinessFollowsServerFlag() {
        assertEquals(ChipModel("Pronta", StatusTone.SUCCESS), ActionText.readiness(room(ready = true)))
        // a flag do servidor manda, mesmo com a UH suja
        assertEquals(
            ChipModel("Pronta", StatusTone.SUCCESS),
            ActionText.readiness(room(ready = true, housekeeping = HousekeepingStatus.DIRTY)),
        )
        listOf(
            room(housekeeping = HousekeepingStatus.DIRTY),
            room(occupancy = Occupancy.OCCUPIED),
            room(outOfOrder = true),
        ).forEach {
            val shown = displayStatus(it)
            assertEquals(ChipModel(shown.label, shown.tone), ActionText.readiness(it))
        }
        assertEquals(ChipModel("Status desconhecido", StatusTone.NEUTRAL), ActionText.readiness(null))
    }

    @Test
    fun earlyOfferText() {
        val offered =
            EarlyLateOfferResponse(offered = true, fee = Option.Some("50.00"), limitTime = Option.Some("10:30"))
        assertEquals("Entrada a partir de 10:30 · R$ 50,00", ActionText.earlyOffer(quote(offered)))
        assertNull(ActionText.earlyOffer(quote(EarlyLateOfferResponse(offered = false))))
        assertNull(ActionText.earlyOffer(quote(offered.copy(limitTime = Option.Undefined))))
        assertNull(ActionText.earlyOffer(quote(offered.copy(fee = Option.Undefined))))
        assertNull(ActionText.earlyOffer(quote(offered.copy(fee = Option.Some(null)))))
    }
}
