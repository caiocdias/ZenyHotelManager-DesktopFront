package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.input.DateField
import com.zeny.desktop.design.theme.ZenyColors
import com.zeny.desktop.sdk.model.QuoteNightResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteUnavailableResponse

private const val NO_COMBINATION = "Nenhuma combinação para essa estadia."
private val childAgeField = Regex("""childAges\[\d+]""")
private val formFields = setOf("arrival", "departure", "adults")

private fun isFormField(name: String) = name in formFields || childAgeField.matches(name)

/**
 * A cotação de estadia: o formulário (chegada, saída, adultos, idades), o resultado e os erros.
 *
 * - Os `fieldErrors` do servidor aparecem no campo deles (`childAges[i]` no das idades). Os de um campo que a tela
 *   não tem aparecem acima do resultado. O `error` genérico só aparece quando não há `fieldErrors`.
 * - Os valores são os decimais do servidor formatados por [ReceptionText.money]; nada é somado aqui.
 * - [roomTypeNames] dá o nome do tipo de UH pelo id, para as combinações indisponíveis.
 * - [onReserve] (nulo = sem botão) recebe a oferta escolhida e a estadia **cotada** ([QuoteState.quotedStay]), não a
 *   que está digitada agora.
 */
@Composable
fun QuotePanel(
    quote: QuoteState,
    roomTypeNames: Map<String, String>,
    modifier: Modifier = Modifier,
    onReserve: ((QuoteOfferResponse, QuotedStay) -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Form(quote)
        Messages(quote)
        if (quote.loading) LoadingState()
        if (quote.quoted) Result(quote, roomTypeNames, onReserve)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Form(quote: QuoteState) {
    val submit = onEnter(quote::quote)
    val errors = quote.fieldErrors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.Top,
    ) {
        DateField(
            quote.arrivalText,
            { quote.arrivalText = it },
            "Chegada",
            error = errors["arrival"],
            modifier = submit.testTag("quote-arrival"),
        )
        DateField(
            quote.departureText,
            { quote.departureText = it },
            "Saída",
            error = errors["departure"],
            modifier = submit.testTag("quote-departure"),
        )
        OutlinedTextField(
            value = quote.adultsText,
            onValueChange = { quote.adultsText = it },
            label = { Text("Adultos") },
            singleLine = true,
            isError = errors["adults"] != null,
            supportingText = errors["adults"]?.let { { Text(it) } },
            modifier = submit.testTag("quote-adults"),
        )
        val ageErrors = errors.filterKeys(childAgeField::matches).values.toList()
        OutlinedTextField(
            value = quote.childAgesText,
            onValueChange = { quote.childAgesText = it },
            label = { Text("Idades das crianças") },
            singleLine = true,
            isError = ageErrors.isNotEmpty(),
            supportingText = ageErrors.takeIf { it.isNotEmpty() }?.let { { Text(it.joinToString(" ")) } },
            modifier = submit.testTag("quote-children"),
        )
        Button(onClick = quote::quote, enabled = !quote.loading, modifier = Modifier.testTag("quote-submit")) {
            Text("Cotar")
        }
    }
}

/** O Enter num campo cota. */
private fun onEnter(action: () -> Unit): Modifier =
    Modifier.onPreviewKeyEvent {
        val enter = it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)
        if (enter) action()
        enter
    }

@Composable
private fun Messages(quote: QuoteState) {
    val lines =
        buildList {
            quote.validation?.let { add(it) }
            if (quote.fieldErrors.isEmpty()) {
                quote.error?.let { add(it) }
            } else {
                quote.fieldErrors
                    .filterKeys { !isFormField(it) }
                    .values
                    .forEach { add(it) }
            }
        }
    lines.forEach { Text(it, color = ZenyColors.Danger, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun Result(
    quote: QuoteState,
    roomTypeNames: Map<String, String>,
    onReserve: ((QuoteOfferResponse, QuotedStay) -> Unit)?,
) {
    if (quote.offers.isEmpty() && quote.unavailable.isEmpty()) {
        Text(NO_COMBINATION, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val stay = quote.quotedStay
    quote.offers.forEachIndexed { index, offer ->
        Offer(index, offer, if (onReserve != null && stay != null) ({ onReserve(offer, stay) }) else null)
    }
    if (quote.unavailable.isNotEmpty()) {
        Text("Indisponíveis", style = MaterialTheme.typography.titleMedium)
        quote.unavailable.forEachIndexed { index, item -> Unavailable(index, item, quote.offers, roomTypeNames) }
    }
}

@Composable
private fun Offer(
    index: Int,
    offer: QuoteOfferResponse,
    onReserve: (() -> Unit)?,
) {
    val title = "${offer.roomTypeName} · ${offer.ratePlanName} · ${AvailabilityText.mealPlan(offer.mealPlan)}"
    // O botão fica fora do nó mesclado: o texto da oferta continua um bloco só e o botão é achado à parte.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier.testTag("quote-offer-$index").semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(AvailabilityText.units(offer.availableUnits), style = MaterialTheme.typography.bodyMedium)
            Text("Total ${ReceptionText.money(offer.total)}", style = MaterialTheme.typography.titleMedium)
            offer.nights.forEach { Text(nightLine(it), Modifier.padding(start = 8.dp)) }
        }
        if (onReserve != null) {
            OutlinedButton(onClick = onReserve, modifier = Modifier.testTag("quote-reserve-$index")) {
                Text("Reservar")
            }
        }
    }
}

private fun nightLine(night: QuoteNightResponse): String {
    // "2026-11-16" -> "16/11"; uma data fora do formato aparece como veio.
    val parts = night.date.split('-')
    val day = if (parts.size == 3) "${parts[2]}/${parts[1]}" else night.date
    return "$day ${ReceptionText.money(night.total)}"
}

@Composable
private fun Unavailable(
    index: Int,
    item: QuoteUnavailableResponse,
    offers: List<QuoteOfferResponse>,
    roomTypeNames: Map<String, String>,
) {
    val type = roomTypeNames[item.roomTypeId.toString()] ?: item.roomTypeCode
    val plan = offers.firstOrNull { it.ratePlanId == item.ratePlanId }?.ratePlanName ?: item.ratePlanCode
    Column(
        Modifier.testTag("quote-unavailable-$index").semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("$type · $plan", style = MaterialTheme.typography.titleSmall)
        item.reasons.forEach { Text(AvailabilityText.refusal(it), Modifier.padding(start = 8.dp)) }
    }
}
