package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.types.ExtensibleEnumLabels
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.CancellationTermsResponse.PenaltyType
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import de.quati.kotlin.util.Option

/** Os textos de pessoa da criação e da alteração de reserva; o valor e o percentual saem como o servidor mandou. */
object BookingText {
    /** Na ordem da tela. `WEBSITE` e `OTA` vendem em outro canal, que a cotação do app não consulta. */
    val ORIGINS: List<String> = listOf("FRONT_DESK", "PHONE", "EMAIL", "WHATSAPP", "OTHER")
    val SEGMENTS: List<String> = listOf("LEISURE", "BUSINESS", "EVENT", "OTHER")
    val GUARANTEES: List<String> = listOf("CARD", "DEPOSIT")

    private val origins =
        ExtensibleEnumLabels(
            mapOf(
                "FRONT_DESK" to "Balcão",
                "PHONE" to "Telefone",
                "EMAIL" to "E-mail",
                "WHATSAPP" to "WhatsApp",
                "WEBSITE" to "Site",
                "OTA" to "OTA",
                "OTHER" to "Outra",
            ),
        )
    private val segments =
        ExtensibleEnumLabels(
            mapOf(
                "LEISURE" to "Lazer",
                "BUSINESS" to "Negócios",
                "EVENT" to "Evento",
                "OTHER" to "Outro",
            ),
        )
    private val guarantees =
        ExtensibleEnumLabels(
            mapOf("NONE" to "Sem garantia", "CARD" to "Cartão", "DEPOSIT" to "Depósito"),
        )

    fun origin(value: String): String = origins.label(value)

    fun segment(value: String): String = segments.label(value)

    fun guarantee(value: String): String = guarantees.label(value)

    /**
     * "Garantia exigida: Cartão" · "Garantia exigida: Depósito de 30% até 7 dia(s) antes da chegada" ·
     * null se `NONE`.
     */
    fun guaranteeTerms(terms: GuaranteeTermsResponse): String? {
        if (terms.type == "NONE") return null
        val label = guarantee(terms.type)
        val percent = present(terms.depositPercent)?.let { " de ${percent(it)}" }.orEmpty()
        val due = present(terms.dueDaysBefore)?.let { " até $it dia(s) antes da chegada" }.orEmpty()
        val detail = if (terms.type == "DEPOSIT") percent + due else ""
        return "Garantia exigida: $label$detail"
    }

    /** "Cancelamento sem multa até 24 h antes do check-in; depois, multa de 1 diária(s)." */
    fun cancellationTerms(terms: CancellationTermsResponse): String {
        val penalty = penalty(terms)
        return if (terms.deadlineHours == 0) {
            "Cancelamento com multa a partir do horário de check-in da chegada: $penalty."
        } else {
            "Cancelamento sem multa até ${terms.deadlineHours} h antes do check-in; depois, $penalty."
        }
    }

    private fun penalty(terms: CancellationTermsResponse): String {
        val fallback = "multa conforme a política ${terms.name}"
        return when (terms.penaltyType) {
            PenaltyType.NIGHTS -> present(terms.penaltyNights)?.let { "multa de $it diária(s)" }
            PenaltyType.PERCENT_OF_STAY -> present(terms.penaltyPercent)?.let { "multa de ${percent(it)} da estadia" }
            PenaltyType.FIXED_AMOUNT -> present(terms.penaltyAmount)?.let { "multa de ${ReceptionText.money(it)}" }
        } ?: fallback
    }

    // O servidor manda `null` explícito no que não tem valor (`Option.Some(null)`); campo ausente é `Undefined`.
    private fun <T : Any> present(option: Option<T?>): T? =
        (option as? Option.Some)?.value?.takeUnless { it is String && it.isBlank() }

    // O percentual é do servidor: só a vírgula decimal troca o ponto, sem arredondar.
    private fun percent(serverValue: String): String = "${serverValue.trim().replace('.', ',')}%"
}
