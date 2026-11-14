package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private fun problem(
    code: String?,
    status: Int = 422,
    detail: String? = "detalhe do servidor",
) = ApiError.Problem(status, code, detail, emptyList())

private fun offer(
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

/** Ataques aos textos do walk-in: o desvio de `walkInError` e a linha da oferta. */
class ActionTextWalkInAtaqueTest {
    private val walkInRoomCodes =
        listOf("ROOM_ALREADY_ASSIGNED", "ROOM_NOT_READY", "ROOM_OUT_OF_ORDER", "ROOM_TYPE_MISMATCH", "ROOM_INACTIVE")

    @Test
    fun walkInErrorOnlyChangesRoomCodes() {
        val others =
            listOf(
                "ROOM_NOT_ASSIGNED",
                "ROOM_ASSIGNMENT_LOCKED",
                "GUEST_DATA_INCOMPLETE",
                "GUEST_BIRTH_DATE_REQUIRED",
                "MINOR_AUTHORIZATION_REQUIRED",
                "MINOR_AUTHORIZATION_INVALID",
                "CHECK_IN_BEFORE_ARRIVAL",
                "EARLY_LATE_FEE_CHANGED",
                "EARLY_LATE_NOT_OFFERED",
                "FOLIO_CLOSED",
                "GUEST_ANONYMIZED",
                "UNKNOWN_GUEST",
                "ROOM_TYPE_UNAVAILABLE",
                "DEPOSIT_INSUFFICIENT",
                "STAY_INVALID",
                "UNKNOWN_RATE_PLAN",
                "UNKNOWN_ROOM_TYPE",
                "CLOSED_TO_ARRIVAL",
                "RATE_NOT_PRICED",
                "CODIGO_QUE_NAO_EXISTE",
            )
        for (code in others) assertEquals(ActionText.error(problem(code)), ActionText.walkInError(problem(code)), code)
    }

    @Test
    fun walkInErrorRoomCodesDifferFromE02AndSuggestNothing() {
        for (code in walkInRoomCodes) {
            val text = ActionText.walkInError(problem(code))
            assertTrue(text != ActionText.error(problem(code)), "$code deve ter texto próprio")
            assertFalse(text.contains("sugest", ignoreCase = true), "$code não pode citar sugestões: $text")
            assertTrue(text.contains("UH"), text)
            assertFalse(text.contains("ROOM_"), text)
        }
    }

    @Test
    fun walkInErrorUnknownCodeFallsToServerDetail() {
        val unknown = problem("CODIGO_QUE_NAO_EXISTE", detail = "Restrição própria do servidor.")
        val text = ActionText.walkInError(unknown)
        assertEquals(unknown.userMessage, text)
        assertFalse(text.contains("CODIGO_QUE_NAO_EXISTE"), text)
    }

    @Test
    fun walkInErrorProblemWithoutCodeFallsToUserMessage() {
        val noCode = problem(null, status = 500, detail = null)
        assertEquals(noCode.userMessage, ActionText.walkInError(noCode))
        assertTrue(ActionText.walkInError(noCode).isNotBlank())
    }

    @Test
    fun walkInErrorNonProblemTypesUseUserMessage() {
        val errors =
            listOf(
                ApiError.Network(IOException("x")),
                ApiError.IncompatibleResponse(IllegalStateException("x")),
                ApiError.SessionEnded("TOKEN"),
                ApiError.PreconditionFailed("d"),
                ApiError.Unexpected(502),
                ApiError.PasswordChangeRequired,
                ApiError.ApprovalRequired("a", "p", "r", null, "d"),
            )
        for (error in errors) {
            assertEquals(error.userMessage, ActionText.walkInError(error), error::class.simpleName)
        }
    }

    @Test
    fun walkInErrorNeverShowsRawCodeOrJson() {
        val codes = walkInRoomCodes + listOf("ROOM_TYPE_UNAVAILABLE", "DEPOSIT_INSUFFICIENT", "STAY_INVALID")
        for (code in codes) {
            val text = ActionText.walkInError(problem(code))
            assertFalse(text.contains(code), text)
            assertFalse(text.contains("{") || text.contains("}"), text)
        }
    }

    @Test
    fun walkInSaleTextsEndWithNextStepAndAreSingleSentenceBlocks() {
        for (code in listOf("ROOM_TYPE_UNAVAILABLE", "DEPOSIT_INSUFFICIENT", "STAY_INVALID", "UNKNOWN_RATE_PLAN")) {
            val text = ActionText.error(problem(code))
            assertTrue(text.endsWith("."), text)
            assertEquals(text.trim(), text)
        }
    }

    @Test
    fun unknownRatePlanAndRoomTypeShareTheSameText() {
        assertEquals(ActionText.error(problem("UNKNOWN_RATE_PLAN")), ActionText.error(problem("UNKNOWN_ROOM_TYPE")))
    }

    @Test
    fun offerHasNoDoubleCurrencyAndFormatsTotal() {
        val text = ActionText.offer(offer("Standard", "Tarifa Padrão", "1234.50"))
        assertEquals("Standard · Tarifa Padrão · R$ 1.234,50", text)
        assertFalse(text.contains("R$ R$"), text)
        assertEquals(1, Regex("R\\$").findAll(text).count(), text)
    }

    @Test
    fun offerKeepsTypeAndPlanAsTheyCame() {
        val text = ActionText.offer(offer("  Suíte  Luxo · Vista ", "BAR/Não-Reembolsável (NR)", "10"))
        assertEquals("  Suíte  Luxo · Vista  · BAR/Não-Reembolsável (NR) · R$ 10,00", text)
        assertEquals(" ·  · R$ 0,50", ActionText.offer(offer("", "", "0.5")))
    }

    @Test
    fun offerWithUnreadableTotalShowsDashWithoutCurrency() {
        val text = ActionText.offer(offer("Standard", "Tarifa", "abc"))
        assertEquals("Standard · Tarifa · —", text)
        assertFalse(text.contains("R$"), text)
        assertEquals("Standard · Tarifa · —", ActionText.offer(offer("Standard", "Tarifa", "")))
    }

    @Test
    fun offerDoesNotRoundTheTotalSilently() {
        // O total vem do servidor em duas casas; mais casas não pode virar outro valor sem aviso.
        assertEquals("Standard · Tarifa · R$ 300,00", ActionText.offer(offer("Standard", "Tarifa", "300")))
        assertEquals("Standard · Tarifa · R$ 0,00", ActionText.offer(offer("Standard", "Tarifa", "0.00")))
    }
}
