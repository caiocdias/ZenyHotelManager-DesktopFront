package com.zeny.desktop.app.reception

import com.zeny.desktop.core.facade.CheckOutResult
import com.zeny.desktop.core.facade.LateCheckOutQuote
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money

/**
 * O que o check-out usa de um servidor: a cotação do late check-out (`null` quando não é oferecido) e o check-out, com
 * a `Intent` da tela. O saldo devido (`409 FOLIO_BALANCE_NOT_ZERO`) volta como [CheckOutResult.BalanceDue].
 */
interface CheckOutSource {
    suspend fun lateCheckOutQuote(reservationId: String): LateCheckOutQuote?

    suspend fun checkOut(
        reservationId: String,
        lateFee: Money?,
        intent: Intent,
    ): CheckOutResult
}
