package com.zeny.desktop.app.reception

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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

private val CODES =
    listOf(
        "ROOM_NOT_READY",
        "ROOM_NOT_ASSIGNED",
        "ROOM_ALREADY_ASSIGNED",
        "ROOM_ASSIGNMENT_LOCKED",
        "ROOM_OUT_OF_ORDER",
        "ROOM_TYPE_MISMATCH",
        "ROOM_INACTIVE",
        "GUEST_DATA_INCOMPLETE",
        "GUEST_BIRTH_DATE_REQUIRED",
        "MINOR_AUTHORIZATION_REQUIRED",
        "MINOR_AUTHORIZATION_INVALID",
        "CHECK_IN_BEFORE_ARRIVAL",
        "RESERVATION_TRANSITION_NOT_ALLOWED",
        "EARLY_LATE_FEE_CHANGED",
        "EARLY_LATE_NOT_OFFERED",
        "FOLIO_CLOSED",
        "GUEST_ANONYMIZED",
        "UNKNOWN_GUEST",
    )

private fun problem(
    code: String?,
    status: Int = 422,
    detail: String? = null,
) = ApiError.Problem(status, code, detail, emptyList())

private fun status(
    ready: Boolean = false,
    occupancy: Occupancy = Occupancy.VACANT,
    housekeeping: HousekeepingStatus = HousekeepingStatus.CLEAN,
    inspectionPending: Boolean = false,
    overlay: OutOfOrderOverlayResponse.Kind? = null,
) = RoomStatusResponse(
    active = true,
    changedAt = "2026-11-12T10:00:00Z",
    housekeepingStatus = housekeeping,
    inspectionPending = inspectionPending,
    number = "101",
    occupancy = occupancy,
    outOfOrder =
        if (overlay != null) {
            Option.Some(OutOfOrderOverlayResponse("2026-11-12", Uuid.random(), overlay, "2026-11-10"))
        } else {
            Option.Undefined
        },
    readyForCheckIn = ready,
    roomId = Uuid.random(),
    roomTypeId = Uuid.random(),
    serviceFlag = ServiceFlag.NONE,
    version = 1,
)

private fun early(
    offered: Boolean = true,
    fee: Option<String?> = Option.Some("50.00"),
    limit: Option<String?> = Option.Some("10:00"),
) = EarlyLateQuoteResponse(
    arrival = "2026-11-14",
    checkInTime = "14:00",
    checkOutTime = "12:00",
    departure = "2026-11-15",
    earlyCheckIn = EarlyLateOfferResponse(offered = offered, fee = fee, limitTime = limit),
    lateCheckOut = EarlyLateOfferResponse(offered = false),
    reservationId = Uuid.random(),
    timezone = "America/Sao_Paulo",
)

class ActionTextAttackTest {
    @Test
    fun noKnownCodeLeaksRawCodeOrBlank() {
        CODES.forEach { code ->
            val text = ActionText.error(problem(code))
            assertFalse(text.contains(code), "texto de $code mostra o code cru: $text")
            assertFalse(text.isBlank(), code)
            assertNotEquals(problem(code).userMessage, text, "$code caiu no userMessage")
        }
    }

    @Test
    fun knownCodeWinsOverStatusAndDetail() {
        listOf(400, 403, 409, 422, 500).forEach { s ->
            assertEquals(
                ActionText.error(problem("FOLIO_CLOSED")),
                ActionText.error(problem("FOLIO_CLOSED", status = s, detail = "texto do servidor")),
                "status $s",
            )
        }
    }

    @Test
    fun codeMatchIsExactCaseAndWhitespaceSensitive() {
        listOf(
            "room_not_ready",
            "Room_Not_Ready",
            " ROOM_NOT_READY",
            "ROOM_NOT_READY ",
            "ROOM_NOT_READY2",
            "",
        ).forEach {
            val error = problem(it)
            assertEquals(error.userMessage, ActionText.error(error), "'$it'")
        }
    }

    @Test
    fun minorCodesShareTheSameTextAndDifferFromGuestIncomplete() {
        val minor = ActionText.error(problem("GUEST_BIRTH_DATE_REQUIRED"))
        assertEquals(minor, ActionText.error(problem("MINOR_AUTHORIZATION_REQUIRED")))
        assertEquals(minor, ActionText.error(problem("MINOR_AUTHORIZATION_INVALID")))
        assertNotEquals(minor, ActionText.error(problem("GUEST_DATA_INCOMPLETE")))
    }

    @Test
    fun otherApiErrorTypesUseUserMessage() {
        listOf<ApiError>(
            ApiError.PreconditionFailed(null),
            ApiError.Unexpected(500),
            ApiError.PasswordChangeRequired,
            ApiError.SessionEnded("ROOM_NOT_READY"),
        ).forEach { assertEquals(it.userMessage, ActionText.error(it)) }
    }

    @Test
    fun readyFlagWinsOverEveryStrangeStatus() {
        val ready = ChipModel("Pronta", StatusTone.SUCCESS)
        listOf(
            status(ready = true, occupancy = Occupancy.OCCUPIED),
            status(ready = true, housekeeping = HousekeepingStatus.DIRTY),
            status(ready = true, overlay = OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER),
            status(ready = true, overlay = OutOfOrderOverlayResponse.Kind.OUT_OF_SERVICE),
            status(ready = true, inspectionPending = true),
        ).forEach { assertEquals(ready, ActionText.readiness(it)) }
    }

    @Test
    fun notReadyShowsDisplayStatusForEachBlocker() {
        mapOf(
            status(housekeeping = HousekeepingStatus.DIRTY) to ChipModel("Suja", StatusTone.WARNING),
            status(occupancy = Occupancy.OCCUPIED) to ChipModel("Ocupada", StatusTone.INFO),
            status(overlay = OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER) to
                ChipModel("Fora de ordem", StatusTone.DANGER),
            status(overlay = OutOfOrderOverlayResponse.Kind.OUT_OF_SERVICE) to
                ChipModel("Fora de serviço", StatusTone.NEUTRAL),
            status(inspectionPending = true) to ChipModel("Limpa, aguardando inspeção", StatusTone.NEUTRAL),
        ).forEach { (room, chip) -> assertEquals(chip, ActionText.readiness(room)) }
    }

    @Test
    fun nullStatusIsUnknownNeutral() {
        assertEquals(ChipModel("Status desconhecido", StatusTone.NEUTRAL), ActionText.readiness(null))
    }

    @Test
    fun notOfferedIsNullEvenWithFeeAndTime() {
        assertNull(ActionText.earlyOffer(early(offered = false)))
    }

    @Test
    fun offeredWithoutFeeOrTimeIsNull() {
        assertNull(ActionText.earlyOffer(early(fee = Option.Undefined)))
        assertNull(ActionText.earlyOffer(early(fee = Option.Some(null))))
        assertNull(ActionText.earlyOffer(early(limit = Option.Undefined)))
        assertNull(ActionText.earlyOffer(early(limit = Option.Some(null))))
    }

    @Test
    fun feeWithZeroOrOneDecimalIsShownWithTwo() {
        mapOf("50" to "50,00", "50.5" to "50,50", "0.00" to "0,00", "1234.50" to "1.234,50").forEach { (fee, shown) ->
            assertEquals(
                "Entrada a partir de 10:00 · R$ $shown",
                ActionText.earlyOffer(early(fee = Option.Some(fee))),
                fee,
            )
        }
    }

    @Test
    fun limitTimeAsHhMmIsShownAsIs() {
        assertEquals("Entrada a partir de 08:00 · R$ 50,00", ActionText.earlyOffer(early(limit = Option.Some("08:00"))))
    }

    /**
     * `limitTime` é `format: time-local`; o openapi.yaml traz o exemplo `10:00:00` e o servidor admite que o JSON
     * pode trazer os segundos (HotelSettingsApiTest compara sem depender deles). A tela deve mostrar HH:mm.
     */
    @Test
    fun limitTimeWithSecondsIsShownWithoutSeconds() {
        assertEquals(
            "Entrada a partir de 10:00 · R$ 50,00",
            ActionText.earlyOffer(early(limit = Option.Some("10:00:00"))),
        )
        assertEquals(
            "Entrada a partir de 08:30 · R$ 50,00",
            ActionText.earlyOffer(early(limit = Option.Some("08:30:00"))),
        )
    }
}
