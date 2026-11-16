package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A disponibilidade: o calendário em cima e, embaixo, a cotação, que o calendário pode preencher. */
@Composable
fun AvailabilityScreen(
    calendar: AvailabilityState,
    quote: QuoteState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CalendarPanel(calendar, onQuoteFrom = quote::prefill)
        QuotePanel(quote, calendar.rows.associate { it.roomTypeId to it.name })
    }
}
