package com.zeny.desktop.app.reservations

import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.CancellationTermsResponse.PenaltyType
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class BookingTextTest {
    private val policyId = Uuid.parse("00000000-0000-0000-0000-000000000001")

    private fun guarantee(
        type: String,
        depositPercent: Option<String?> = Option.Undefined,
        dueDaysBefore: Option<Int?> = Option.Undefined,
    ) = GuaranteeTermsResponse(
        depositPercent = depositPercent,
        dueDaysBefore = dueDaysBefore,
        name = "Política",
        policyId = policyId,
        type = type,
        version = 1,
    )

    private fun cancellation(
        penaltyType: PenaltyType,
        deadlineHours: Int = 24,
        penaltyAmount: Option<String?> = Option.Undefined,
        penaltyNights: Option<Int?> = Option.Undefined,
        penaltyPercent: Option<String?> = Option.Undefined,
    ) = CancellationTermsResponse(
        deadlineHours = deadlineHours,
        name = "Flex",
        penaltyAmount = penaltyAmount,
        penaltyNights = penaltyNights,
        penaltyPercent = penaltyPercent,
        penaltyType = penaltyType,
        policyId = policyId,
        version = 1,
    )

    @Test
    fun listsFollowScreenOrder() {
        assertEquals(listOf("FRONT_DESK", "PHONE", "EMAIL", "WHATSAPP", "OTHER"), BookingText.ORIGINS)
        assertEquals(listOf("LEISURE", "BUSINESS", "EVENT", "OTHER"), BookingText.SEGMENTS)
        assertEquals(listOf("CARD", "DEPOSIT"), BookingText.GUARANTEES)
    }

    @Test
    fun originHasPortugueseLabels() {
        assertEquals("Balcão", BookingText.origin("FRONT_DESK"))
        assertEquals("Telefone", BookingText.origin("PHONE"))
        assertEquals("E-mail", BookingText.origin("EMAIL"))
        assertEquals("WhatsApp", BookingText.origin("WHATSAPP"))
        assertEquals("Outra", BookingText.origin("OTHER"))
        assertEquals("Site", BookingText.origin("WEBSITE"))
        assertEquals("OTA", BookingText.origin("OTA"))
        assertEquals("Outro (CARRIER_PIGEON)", BookingText.origin("CARRIER_PIGEON"))
    }

    @Test
    fun segmentHasPortugueseLabels() {
        assertEquals("Lazer", BookingText.segment("LEISURE"))
        assertEquals("Negócios", BookingText.segment("BUSINESS"))
        assertEquals("Evento", BookingText.segment("EVENT"))
        assertEquals("Outro", BookingText.segment("OTHER"))
        assertEquals("Outro (GROUP)", BookingText.segment("GROUP"))
    }

    @Test
    fun guaranteeHasPortugueseLabels() {
        assertEquals("Sem garantia", BookingText.guarantee("NONE"))
        assertEquals("Cartão", BookingText.guarantee("CARD"))
        assertEquals("Depósito", BookingText.guarantee("DEPOSIT"))
        assertEquals("Outro (VOUCHER)", BookingText.guarantee("VOUCHER"))
    }

    @Test
    fun guaranteeTermsIsNullWhenNoneRequired() {
        assertNull(BookingText.guaranteeTerms(guarantee("NONE")))
    }

    @Test
    fun guaranteeTermsForCard() {
        assertEquals("Garantia exigida: Cartão", BookingText.guaranteeTerms(guarantee("CARD")))
    }

    @Test
    fun guaranteeTermsForDepositWithDueDays() {
        val terms = guarantee("DEPOSIT", Option.Some("30"), Option.Some(7))
        assertEquals(
            "Garantia exigida: Depósito de 30% até 7 dia(s) antes da chegada",
            BookingText.guaranteeTerms(terms),
        )
    }

    @Test
    fun guaranteeTermsForDepositWithoutDueDays() {
        assertEquals(
            "Garantia exigida: Depósito de 30%",
            BookingText.guaranteeTerms(guarantee("DEPOSIT", Option.Some("30"))),
        )
        assertEquals(
            "Garantia exigida: Depósito de 30%",
            BookingText.guaranteeTerms(guarantee("DEPOSIT", Option.Some("30"), Option.Some(null))),
        )
    }

    @Test
    fun guaranteeTermsForDepositKeepsServerPercentDigits() {
        assertEquals(
            "Garantia exigida: Depósito de 12,5% até 3 dia(s) antes da chegada",
            BookingText.guaranteeTerms(guarantee("DEPOSIT", Option.Some("12.5"), Option.Some(3))),
        )
    }

    @Test
    fun guaranteeTermsForUnknownType() {
        assertEquals("Garantia exigida: Outro (VOUCHER)", BookingText.guaranteeTerms(guarantee("VOUCHER")))
    }

    @Test
    fun cancellationTermsForNights() {
        val terms = cancellation(PenaltyType.NIGHTS, penaltyNights = Option.Some(1))
        assertEquals(
            "Cancelamento sem multa até 24 h antes do check-in; depois, multa de 1 diária(s).",
            BookingText.cancellationTerms(terms),
        )
    }

    @Test
    fun cancellationTermsForPercentOfStay() {
        val terms = cancellation(PenaltyType.PERCENT_OF_STAY, 48, penaltyPercent = Option.Some("50"))
        assertEquals(
            "Cancelamento sem multa até 48 h antes do check-in; depois, multa de 50% da estadia.",
            BookingText.cancellationTerms(terms),
        )
    }

    @Test
    fun percentKeepsServerDigits() {
        val terms = cancellation(PenaltyType.PERCENT_OF_STAY, penaltyPercent = Option.Some("12.5"))
        assertEquals(
            "Cancelamento sem multa até 24 h antes do check-in; depois, multa de 12,5% da estadia.",
            BookingText.cancellationTerms(terms),
        )
        val padded = cancellation(PenaltyType.PERCENT_OF_STAY, penaltyPercent = Option.Some("12.50"))
        assertEquals(
            "Cancelamento sem multa até 24 h antes do check-in; depois, multa de 12,50% da estadia.",
            BookingText.cancellationTerms(padded),
        )
    }

    @Test
    fun cancellationTermsForFixedAmount() {
        val terms = cancellation(PenaltyType.FIXED_AMOUNT, penaltyAmount = Option.Some("150.00"))
        assertEquals(
            "Cancelamento sem multa até 24 h antes do check-in; depois, multa de R$ 150,00.",
            BookingText.cancellationTerms(terms),
        )
    }

    @Test
    fun cancellationTermsWithZeroDeadlineChargesFromCheckInTime() {
        val terms = cancellation(PenaltyType.NIGHTS, 0, penaltyNights = Option.Some(2))
        assertEquals(
            "Cancelamento com multa a partir do horário de check-in da chegada: multa de 2 diária(s).",
            BookingText.cancellationTerms(terms),
        )
    }

    @Test
    fun cancellationTermsWithMissingValueNamesThePolicy() {
        val absent = cancellation(PenaltyType.NIGHTS).copy(name = "Não reembolsável")
        assertEquals(
            "Cancelamento sem multa até 24 h antes do check-in; depois, multa conforme a política Não reembolsável.",
            BookingText.cancellationTerms(absent),
        )
        val explicitNull =
            cancellation(PenaltyType.FIXED_AMOUNT, penaltyAmount = Option.Some(null)).copy(name = "Rígida")
        assertEquals(
            "Cancelamento sem multa até 24 h antes do check-in; depois, multa conforme a política Rígida.",
            BookingText.cancellationTerms(explicitNull),
        )
        val zeroDeadline = cancellation(PenaltyType.PERCENT_OF_STAY, 0).copy(name = "Rígida")
        assertEquals(
            "Cancelamento com multa a partir do horário de check-in da chegada: multa conforme a política Rígida.",
            BookingText.cancellationTerms(zeroDeadline),
        )
    }
}
