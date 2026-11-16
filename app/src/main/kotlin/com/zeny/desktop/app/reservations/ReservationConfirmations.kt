package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.RegistrationCards
import com.zeny.desktop.core.facade.PdfDocument

/** A confirmação de uma reserva em PDF, vinda do servidor. */
fun interface ReservationConfirmations {
    suspend fun confirmation(reservationId: String): PdfDocument
}

/** A confirmação lida pelo mesmo estado da ficha. */
fun ReservationConfirmations.asCards(): RegistrationCards = RegistrationCards { confirmation(it) }
