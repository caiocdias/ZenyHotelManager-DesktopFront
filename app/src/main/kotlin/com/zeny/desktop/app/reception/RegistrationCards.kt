package com.zeny.desktop.app.reception

import com.zeny.desktop.core.facade.PdfDocument

/** A ficha de registro de uma reserva em PDF, vinda do servidor. */
fun interface RegistrationCards {
    suspend fun registrationCard(reservationId: String): PdfDocument
}
