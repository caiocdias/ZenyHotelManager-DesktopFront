package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private fun problem(code: String?) = ApiError.Problem(422, code, "detalhe do servidor", emptyList())

private fun offerOf(
    roomType: String,
    plan: String,
    total: String,
) = QuoteOfferResponse(
    accommodationTotal = total,
    additionalPackagesTotal = "0.00",
    availableUnits = 2,
    cancellationPolicyId = Uuid.random(),
    category = QuoteOfferResponse.Category.NORMAL,
    guaranteePolicyId = Uuid.random(),
    includedPackagesTotal = "0.00",
    mealPlan = "BREAKFAST",
    nights = emptyList(),
    ratePlanCode = "BAR",
    ratePlanId = Uuid.random(),
    ratePlanName = plan,
    roomTotal = total,
    roomTypeCode = "STD",
    roomTypeId = Uuid.random(),
    roomTypeName = roomType,
    total = total,
)

class ActionTextWalkInTest {
    @Test
    fun walkInTexts() {
        val sale = "A oferta escolhida não existe mais. Refaça a cotação."
        val expected =
            mapOf(
                "ROOM_TYPE_UNAVAILABLE" to
                    "Não há UH desse tipo livre em todas as noites. Mude a saída ou escolha outra oferta.",
                "DEPOSIT_INSUFFICIENT" to
                    "Este plano exige depósito antecipado e não serve ao walk-in. Escolha outra oferta.",
                "STAY_INVALID" to "A estadia está inválida. Confira a saída e o número de hóspedes.",
                "UNKNOWN_RATE_PLAN" to sale,
                "UNKNOWN_ROOM_TYPE" to sale,
            )
        for ((code, text) in expected) assertEquals(text, ActionText.error(problem(code)), code)
    }

    @Test
    fun walkInErrorRoomTextsAreNeutral() {
        val other = "A UH não serve para este walk-in. Escolha outra UH da lista."
        assertEquals(
            "A UH já está alocada a outra reserva no período. Escolha outra UH da lista.",
            ActionText.walkInError(problem("ROOM_ALREADY_ASSIGNED")),
        )
        assertEquals(
            "A UH não está pronta. Escolha outra UH da lista ou peça a liberação à governança.",
            ActionText.walkInError(problem("ROOM_NOT_READY")),
        )
        for (code in listOf("ROOM_OUT_OF_ORDER", "ROOM_TYPE_MISMATCH", "ROOM_INACTIVE")) {
            assertEquals(other, ActionText.walkInError(problem(code)), code)
        }
    }

    @Test
    fun walkInErrorFallsBackToErrorAndUserMessage() {
        assertEquals(
            ActionText.error(problem("GUEST_ANONYMIZED")),
            ActionText.walkInError(problem("GUEST_ANONYMIZED")),
        )
        assertEquals(
            "Não há UH desse tipo livre em todas as noites. Mude a saída ou escolha outra oferta.",
            ActionText.walkInError(problem("ROOM_TYPE_UNAVAILABLE")),
        )
        val unknown = problem("CLOSED_TO_ARRIVAL")
        assertEquals(unknown.userMessage, ActionText.walkInError(unknown))
        val rate = problem("RATE_NOT_PRICED")
        assertEquals(rate.userMessage, ActionText.walkInError(rate))
    }

    @Test
    fun e02TextsUnchanged() {
        assertEquals(
            "A UH já está alocada a outra reserva em parte do período. Escolha outra UH das sugestões.",
            ActionText.error(problem("ROOM_ALREADY_ASSIGNED")),
        )
        assertEquals(
            "A UH ainda não está pronta. Escolha uma UH pronta ou peça a liberação à governança.",
            ActionText.error(problem("ROOM_NOT_READY")),
        )
        assertEquals(
            "A UH está fora de ordem em alguma noite da estadia. Escolha outra UH.",
            ActionText.error(problem("ROOM_OUT_OF_ORDER")),
        )
        assertEquals(
            "A UH não é do tipo da reserva. Escolha uma UH das sugestões.",
            ActionText.error(problem("ROOM_TYPE_MISMATCH")),
        )
        assertEquals("A UH está inativa. Escolha outra UH.", ActionText.error(problem("ROOM_INACTIVE")))
        assertEquals(
            "O hóspede escolhido não existe mais. Busque de novo.",
            ActionText.error(problem("UNKNOWN_GUEST")),
        )
    }

    @Test
    fun offerText() {
        assertEquals(
            "Standard · Tarifa Padrão · " + ReceptionText.money("300.00"),
            ActionText.offer(offerOf("Standard", "Tarifa Padrão", "300.00")),
        )
        assertEquals(true, ActionText.offer(offerOf("Standard", "Tarifa Padrão", "300.00")).contains("R$"))
    }

    @Test
    fun noOffersText() {
        assertEquals(
            "Não há UH disponível para essa estadia. Mude a saída ou o número de hóspedes.",
            ActionText.noOffers(),
        )
    }
}
