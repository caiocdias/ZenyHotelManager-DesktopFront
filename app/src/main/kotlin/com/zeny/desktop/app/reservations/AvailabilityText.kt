package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.error.ErrorCatalog
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.AvailabilityDayResponse
import com.zeny.desktop.sdk.model.QuoteRefusalResponse
import java.time.format.TextStyle
import java.util.Locale

/** Os textos de pessoa da disponibilidade e da cotação. */
object AvailabilityText {
    private val locale = Locale.forLanguageTag("pt-BR")
    private const val DEFAULT_REFUSAL = "Esta combinação não está disponível para essa estadia."

    private val rawIdentifier = Regex("^[A-Za-z0-9_]+$")

    private val mealPlans =
        mapOf(
            "ROOM_ONLY" to "Só hospedagem",
            "BREAKFAST" to "Café da manhã",
            "HALF_BOARD" to "Meia pensão",
        )

    // Os códigos de recusa da cotação não entram no `ErrorCatalog` (ele é de erros HTTP).
    private val refusals =
        mapOf(
            "SALES_CHANNEL_NOT_ALLOWED" to "O plano não é vendido neste canal.",
            "OCCUPANCY_EXCEEDS_ROOM_TYPE" to "O número de hóspedes passa do que o tipo de UH recebe.",
            "ARRIVAL_BEFORE_BUSINESS_DATE" to "A chegada é antes da data operacional do hotel.",
            "MIN_ADVANCE_NOT_MET" to "A chegada exige mais antecedência.",
            "MAX_ADVANCE_EXCEEDED" to "A chegada está longe demais para reservar agora.",
            "CLOSED_TO_ARRIVAL" to "A data está fechada para chegada.",
            "CLOSED_TO_DEPARTURE" to "A data está fechada para saída.",
            "MIN_STAY_NOT_MET" to "A estadia é mais curta que o mínimo do plano.",
            "MAX_STAY_EXCEEDED" to "A estadia é mais longa que o máximo do plano.",
            "STAY_DATE_CLOSED" to "Há data fechada para venda na estadia.",
            "RATE_NOT_PRICED" to "O plano não tem preço para essa estadia.",
        )

    /** "seg 16/11": dia da semana abreviado em pt-BR, minúsculo e sem ponto, e dia/mês. */
    fun dayHeader(date: BusinessDate): String {
        val weekday =
            date.value.dayOfWeek
                .getDisplayName(TextStyle.SHORT, locale)
                .lowercase(locale)
                .trimEnd('.')
        val day =
            date.value.dayOfMonth
                .toString()
                .padStart(2, '0')
        val month =
            date.value.monthValue
                .toString()
                .padStart(2, '0')
        return "$weekday $day/$month"
    }

    fun mealPlan(value: String): String = mealPlans[value] ?: "Outro ($value)"

    /** A mensagem do servidor, quando é texto de pessoa; senão o texto em português do código, com as datas. */
    fun refusal(reason: QuoteRefusalResponse): String {
        val message = reason.message.trim()
        if (ErrorCatalog.isShowableDetail(message) && !isRawCode(message, reason.code)) return message
        val base = refusals[reason.code] ?: ErrorCatalog.message(reason.code) ?: DEFAULT_REFUSAL
        val dates = reason.dates.joinToString(", ") { ReceptionText.date(it) }
        return if (dates.isEmpty()) base else "$base ($dates)"
    }

    // Identificador cru (letras, dígitos e `_`, sem espaço) em qualquer caixa, ou o próprio código sem caixa.
    private fun isRawCode(
        message: String,
        code: String,
    ) = message.equals(code.trim(), ignoreCase = true) || rawIdentifier.matches(message)

    fun inventory(day: AvailabilityDayResponse): String =
        "Total ${day.total} · Vendidas ${day.sold} · Bloqueadas ${day.blocked} · Fora de ordem ${day.outOfOrder} · " +
            "Limite de overbooking ${day.overbookingLimit} · Disponíveis ${day.available}"

    fun units(n: Int): String = if (n == 1) "1 UH disponível" else "$n UHs disponíveis"
}
