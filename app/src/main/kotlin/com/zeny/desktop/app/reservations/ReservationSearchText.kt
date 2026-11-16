package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.app.reception.BAD_COUNTRY as GUEST_BAD_COUNTRY
import com.zeny.desktop.app.reception.BAD_TYPE as GUEST_BAD_TYPE
import com.zeny.desktop.app.reception.NEED_COUNTRY as GUEST_NEED_COUNTRY
import com.zeny.desktop.app.reception.NEED_NUMBER as GUEST_NEED_NUMBER
import com.zeny.desktop.app.reception.SHORT_NAME as GUEST_SHORT_NAME

private const val CODE_PREFIX = "R-"
private const val BOOKING_CODE_PREFIX = "B-"
private const val VISIBLE_DOCUMENT_CHARS = 4
private const val HIDDEN = "***"

/**
 * Os textos e as conversões da busca de reservas. As mensagens da busca de hóspede são as do `GuestPickerState`, e a
 * de data ilegível é a do `DateInput.parse`.
 */
object ReservationSearchText {
    const val PERIOD_INVERTED = "O fim do período não pode ser antes do início."
    const val NUMBER_TOO_LONG = "O número tem caracteres demais."
    const val NAME_TOO_LONG = "O nome tem caracteres demais (até 200)."
    const val DOCUMENT_TOO_LONG = "O número do documento tem caracteres demais (até 40)."

    const val SHORT_NAME = GUEST_SHORT_NAME
    const val NEED_NUMBER = GUEST_NEED_NUMBER
    const val NEED_COUNTRY = GUEST_NEED_COUNTRY
    const val BAD_COUNTRY = GUEST_BAD_COUNTRY
    const val BAD_TYPE = GUEST_BAD_TYPE

    /**
     * O filtro só com o número, aparado: `R-…` é o código da reserva, `B-…` o da reserva-mãe (o prefixo sem
     * diferenciar maiúsculas) e o resto, o id externo (OTA). Em branco, o filtro vazio.
     */
    fun numberFilter(text: String): ReservationFilter {
        val number = text.trim()
        return when {
            number.isEmpty() -> ReservationFilter()
            number.startsWith(CODE_PREFIX, ignoreCase = true) -> ReservationFilter(code = number)
            number.startsWith(BOOKING_CODE_PREFIX, ignoreCase = true) -> ReservationFilter(bookingCode = number)
            else -> ReservationFilter(externalId = number)
        }
    }

    /** O documento buscado só com os últimos 4 caracteres alfanuméricos; com 4 ou menos, nenhum. */
    fun maskedDocument(
        type: String,
        number: String,
    ): String {
        val significant = number.filter { it.isLetterOrDigit() }
        val tail = if (significant.length > VISIBLE_DOCUMENT_CHARS) significant.takeLast(VISIBLE_DOCUMENT_CHARS) else ""
        return ReceptionText.document(type, HIDDEN + tail)
    }
}
