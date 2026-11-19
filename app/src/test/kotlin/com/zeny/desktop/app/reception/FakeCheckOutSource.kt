package com.zeny.desktop.app.reception

import com.zeny.desktop.core.facade.CheckOutResult
import com.zeny.desktop.core.facade.FolioDue
import com.zeny.desktop.core.facade.LateCheckOutQuote
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money

/** Um check-out pedido ao falso: a reserva, o late enviado e a `Intent` da tela. */
data class CheckOutCall(
    val reservationId: String,
    val lateFee: Money?,
    val intent: Intent,
)

/** O devido de uma conta, como o servidor manda no `409 FOLIO_BALANCE_NOT_ZERO`. */
fun dueOf(
    folioId: String,
    number: String,
    balance: String,
) = FolioDue(folioId, number, Money.parse(balance))

/** Servidor de mentira do check-out: as respostas são lambdas trocáveis, e cada chamada é registrada. */
class FakeCheckOutSource : CheckOutSource {
    var quote: suspend () -> LateCheckOutQuote? = { null }
    var checkOut: suspend (Money?) -> CheckOutResult = { CheckOutResult.Done }

    var quoteCalls = 0
    val calls = mutableListOf<CheckOutCall>()

    override suspend fun lateCheckOutQuote(reservationId: String): LateCheckOutQuote? {
        quoteCalls++
        return quote()
    }

    override suspend fun checkOut(
        reservationId: String,
        lateFee: Money?,
        intent: Intent,
    ): CheckOutResult {
        calls += CheckOutCall(reservationId, lateFee, intent)
        return checkOut.invoke(lateFee)
    }
}
