package com.zeny.desktop.app.reservations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.zeny.desktop.app.reception.RegistrationCardDialog
import com.zeny.desktop.app.reception.RegistrationCardState
import com.zeny.desktop.sdk.model.QuoteOfferResponse

/**
 * A nova reserva aberta a partir de uma oferta da cotação e, depois dela, a confirmação em PDF da reserva criada. Quem
 * hospeda é dono do holder: sair da composição (área, sessão) fecha o que estiver aberto. A fábrica do PDF é nula sem
 * a porta, e então o diálogo da nova reserva não oferece "Ver confirmação".
 */
@Stable
internal class NewReservationHost(
    private val newReservation: () -> ((QuoteOfferResponse, QuotedStay) -> NewReservationState)?,
    private val confirmation: () -> ((reservationId: String, code: String) -> RegistrationCardState)?,
) {
    private var state by mutableStateOf<NewReservationState?>(null)
    private var card by mutableStateOf<RegistrationCardState?>(null)

    /** Abre o diálogo da nova reserva para a oferta escolhida; cada abertura cria um estado novo. */
    fun open(
        offer: QuoteOfferResponse,
        stay: QuotedStay,
    ) {
        val make = newReservation() ?: return
        state?.close()
        state = make(offer, stay)
    }

    fun close() {
        state?.close()
        state = null
        card?.close()
        card = null
    }

    @Composable
    fun Show() {
        DisposableEffect(this) { onDispose(::close) }
        state?.let { open ->
            NewReservationDialog(
                open,
                onConfirmation = confirmation()?.let { make -> { id, code -> showConfirmation(make, id, code) } },
            ) {
                open.close()
                state = null
            }
        }
        card?.let { open ->
            RegistrationCardDialog(open) {
                open.close()
                card = null
            }
        }
    }

    /** "Ver confirmação" da reserva criada: o diálogo da nova reserva fecha e o PDF dela abre. */
    private fun showConfirmation(
        make: (String, String) -> RegistrationCardState,
        reservationId: String,
        code: String,
    ) {
        state?.close()
        state = null
        card?.close()
        card = make(reservationId, code)
    }
}

/** O [NewReservationHost] da área, sobrevivendo às fábricas novas que cada recomposição traz. */
@Composable
internal fun rememberNewReservationHost(
    newReservation: ((QuoteOfferResponse, QuotedStay) -> NewReservationState)?,
    confirmation: ((reservationId: String, code: String) -> RegistrationCardState)?,
): NewReservationHost {
    val currentNew by rememberUpdatedState(newReservation)
    val currentConfirmation by rememberUpdatedState(confirmation)
    return remember { NewReservationHost({ currentNew }, { currentConfirmation }) }
}
