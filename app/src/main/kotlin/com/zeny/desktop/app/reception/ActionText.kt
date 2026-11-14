package com.zeny.desktop.app.reception

import com.zeny.desktop.app.rooms.displayStatus
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.sdk.model.EarlyLateQuoteResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.RoomMoveResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import de.quati.kotlin.util.Option
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Textos puros das ações da recepção (alocação e check-in), em pt-BR. */
@Suppress("TooManyFunctions") // textos puros de cada ação da recepção, num só lugar
object ActionText {
    private const val MINOR_TEXT =
        "Hóspede menor de idade precisa da data de nascimento e da autorização do responsável " +
            "no perfil (área Hóspedes)."

    private const val STALE_OFFER = "A oferta escolhida não existe mais. Refaça a cotação."
    private const val WALK_IN_ROOM_UNFIT = "A UH não serve para este walk-in. Escolha outra UH da lista."

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    private val problemTexts =
        mapOf(
            "ROOM_NOT_READY" to
                "A UH ainda não está pronta. Escolha uma UH pronta ou peça a liberação à governança.",
            "ROOM_NOT_ASSIGNED" to "A reserva ainda não tem UH. Aloque uma UH antes do check-in.",
            "ROOM_ALREADY_ASSIGNED" to
                "A UH já está alocada a outra reserva em parte do período. Escolha outra UH das sugestões.",
            "ROOM_ASSIGNMENT_LOCKED" to
                "A UH desta reserva está marcada como \"não mover\". Desmarque antes de trocar ou soltar.",
            "ROOM_OUT_OF_ORDER" to "A UH está fora de ordem em alguma noite da estadia. Escolha outra UH.",
            "ROOM_TYPE_MISMATCH" to "A UH não é do tipo da reserva. Escolha uma UH das sugestões.",
            "ROOM_INACTIVE" to "A UH está inativa. Escolha outra UH.",
            "GUEST_DATA_INCOMPLETE" to
                "Faltam dados do hóspede: nacionalidade, data de nascimento ou um documento aceito " +
                "(CPF para brasileiro adulto). Cadastre o hóspede com esses dados ou complete o perfil " +
                "na área Hóspedes.",
            "GUEST_BIRTH_DATE_REQUIRED" to MINOR_TEXT,
            "MINOR_AUTHORIZATION_REQUIRED" to MINOR_TEXT,
            "MINOR_AUTHORIZATION_INVALID" to MINOR_TEXT,
            "CHECK_IN_BEFORE_ARRIVAL" to
                "O check-in não pode ser feito antes da data de chegada. Confira a data da reserva.",
            "RESERVATION_TRANSITION_NOT_ALLOWED" to
                "A reserva não está mais confirmada; o check-in pode já ter sido feito. " +
                "Feche e confira o detalhe.",
            "EARLY_LATE_FEE_CHANGED" to
                "O valor do early check-in mudou. Confira a nova cotação e confirme de novo.",
            "EARLY_LATE_NOT_OFFERED" to
                "O early check-in não é oferecido agora. Desmarque a cobrança e confirme de novo.",
            "FOLIO_CLOSED" to "A conta da reserva está encerrada. Fale com o financeiro.",
            "GUEST_ANONYMIZED" to
                "Os dados deste hóspede foram anonimizados. Escolha ou cadastre outro hóspede.",
            "UNKNOWN_GUEST" to "O hóspede escolhido não existe mais. Busque de novo.",
            "ROOM_TYPE_UNAVAILABLE" to
                "Não há UH desse tipo livre em todas as noites. Mude a saída ou escolha outra oferta.",
            "DEPOSIT_INSUFFICIENT" to
                "Este plano exige depósito antecipado e não serve ao walk-in. Escolha outra oferta.",
            "STAY_INVALID" to "A estadia está inválida. Confira a saída e o número de hóspedes.",
            "UNKNOWN_RATE_PLAN" to STALE_OFFER,
            "UNKNOWN_ROOM_TYPE" to STALE_OFFER,
        )

    // No walk-in não há sugestões de UH: os `ROOM_*` ganham textos que mandam escolher da lista.
    private val walkInRoomTexts =
        mapOf(
            "ROOM_ALREADY_ASSIGNED" to
                "A UH já está alocada a outra reserva no período. Escolha outra UH da lista.",
            "ROOM_NOT_READY" to
                "A UH não está pronta. Escolha outra UH da lista ou peça a liberação à governança.",
            "ROOM_OUT_OF_ORDER" to WALK_IN_ROOM_UNFIT,
            "ROOM_TYPE_MISMATCH" to WALK_IN_ROOM_UNFIT,
            "ROOM_INACTIVE" to WALK_IN_ROOM_UNFIT,
        )

    private const val UNDO_UNKNOWN_OUTCOME =
        "Não foi possível confirmar o desfazer. Feche e confira o detalhe antes de tentar de novo."
    private const val MOVE_ROOM_UNFIT = "A UH não serve para esta troca. Escolha outra UH da lista."

    private val moveTexts =
        mapOf(
            "ROOM_MOVE_SAME_ROOM" to "A UH escolhida é a mesma atual. Escolha outra UH.",
            "ROOM_MOVE_INVALID" to "O motivo da troca é inválido. Escolha outro motivo.",
            "ROOM_BLOCK_RESERVATION_STAY_FIXED" to
                "A tarifa desta reserva de grupo não pode ser recalculada. Mantenha a tarifa.",
            "ROOM_ALREADY_ASSIGNED" to walkInRoomTexts.getValue("ROOM_ALREADY_ASSIGNED"),
            "ROOM_NOT_READY" to walkInRoomTexts.getValue("ROOM_NOT_READY"),
            "ROOM_TYPE_MISMATCH" to MOVE_ROOM_UNFIT,
            "ROOM_OUT_OF_ORDER" to MOVE_ROOM_UNFIT,
            "ROOM_INACTIVE" to MOVE_ROOM_UNFIT,
            "UNKNOWN_ROOM" to MOVE_ROOM_UNFIT,
            "RESERVATION_TRANSITION_NOT_ALLOWED" to "A reserva não está mais hospedada. Feche e confira o detalhe.",
            "RESERVATION_STAY_ENDED" to "A estadia desta reserva não tem mais noites; a UH não é mais trocada.",
        )

    private val undoTexts =
        mapOf(
            "UNDO_CHECK_IN_EXPIRED" to
                "O check-in só pode ser desfeito na mesma data operacional em que foi feito.",
            "RESERVATION_TRANSITION_NOT_ALLOWED" to
                "A reserva não está mais hospedada; o check-in pode já ter sido desfeito. " +
                "Feche e confira o detalhe.",
        )

    fun error(error: ApiError): String =
        (error as? ApiError.Problem)?.code?.let { problemTexts[it] } ?: error.userMessage

    /** O erro do walk-in: como [error], mas com os textos de UH sem sugestões. */
    fun walkInError(error: ApiError): String =
        (error as? ApiError.Problem)?.code?.let { walkInRoomTexts[it] } ?: error(error)

    /** O erro da troca de UH: como [error], mas com os textos próprios da troca. */
    fun moveError(error: ApiError): String =
        when (error) {
            is ApiError.PreconditionFailed -> "Alguém alterou esta reserva. Confira e confirme de novo."
            is ApiError.Problem -> error.code?.let { moveTexts[it] } ?: error(error)
            else -> error(error)
        }

    /** O erro do desfazer check-in: rede e resposta incompatível deixam o resultado desconhecido. */
    fun undoError(error: ApiError): String =
        when (error) {
            is ApiError.Network, is ApiError.IncompatibleResponse -> UNDO_UNKNOWN_OUTCOME
            is ApiError.Problem -> error.code?.let { undoTexts[it] } ?: error(error)
            else -> error(error)
        }

    fun tariff(value: String): String =
        when (value) {
            "KEEP" -> "Manter a tarifa"
            "RECALCULATE" -> "Recalcular pela tarifa atual"
            else -> "Outro ($value)"
        }

    fun moveSummary(response: RoomMoveResponse): String =
        "Troca feita para a UH ${response.toRoom.number}. Noites restantes: " +
            "${ReceptionText.money(response.remainingBefore)} → ${ReceptionText.money(response.remainingAfter)} " +
            "(diferença ${ReceptionText.money(response.difference)})."

    fun offer(offer: QuoteOfferResponse): String =
        "${offer.roomTypeName} · ${offer.ratePlanName} · ${ReceptionText.money(offer.total)}"

    @Suppress("FunctionOnlyReturningConstant") // parte da API de textos, ao lado de `offer`
    fun noOffers(): String = "Não há UH disponível para essa estadia. Mude a saída ou o número de hóspedes."

    @Suppress("FunctionOnlyReturningConstant") // parte da API de textos, ao lado de `noOffers`
    fun noRooms(): String =
        "Nenhuma UH desse tipo está pronta e livre agora. " +
            "Escolha outra oferta ou peça a liberação à governança."

    /** A prontidão é a flag do servidor (RN-05); o cliente não a recalcula. */
    fun readiness(status: RoomStatusResponse?): ChipModel =
        when {
            status == null -> {
                ChipModel("Status desconhecido", StatusTone.NEUTRAL)
            }

            status.readyForCheckIn -> {
                ChipModel("Pronta", StatusTone.SUCCESS)
            }

            else -> {
                displayStatus(status).let { ChipModel(it.label, it.tone) }
            }
        }

    fun earlyOffer(quote: EarlyLateQuoteResponse): String? {
        val offer = quote.earlyCheckIn
        val time = (offer.limitTime as? Option.Some)?.value?.let(::hourAndMinute)
        val fee = (offer.fee as? Option.Some)?.value?.takeIf(::isMoney)
        return if (offer.offered && time != null && fee != null) {
            "Entrada a partir de $time · ${ReceptionText.money(fee)}"
        } else {
            null
        }
    }

    private fun isMoney(value: String): Boolean =
        try {
            Money.parse(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun hourAndMinute(value: String): String? =
        try {
            LocalTime.parse(value).format(timeFormat)
        } catch (_: DateTimeParseException) {
            null
        }
}
