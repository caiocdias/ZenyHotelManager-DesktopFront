package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeny.desktop.sdk.model.QuoteOfferResponse

/**
 * A disponibilidade: o calendário em cima e, embaixo, a cotação, que o calendário pode preencher. [onReserve] (nulo =
 * sem botão "Reservar") recebe a oferta escolhida e a estadia cotada.
 */
@Composable
fun AvailabilityScreen(
    calendar: AvailabilityState,
    quote: QuoteState,
    modifier: Modifier = Modifier,
    onReserve: ((QuoteOfferResponse, QuotedStay) -> Unit)? = null,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CalendarPanel(calendar, onQuoteFrom = quote::prefill)
        QuotePanel(quote, calendar.rows.associate { it.roomTypeId to it.name }, onReserve = onReserve)
    }
}
