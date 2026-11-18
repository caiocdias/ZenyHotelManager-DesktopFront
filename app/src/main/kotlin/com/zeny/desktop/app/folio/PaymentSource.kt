package com.zeny.desktop.app.folio

import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.PaymentInput
import com.zeny.desktop.core.facade.RefundInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.PaymentResponse

/**
 * O que os pagamentos da conta usam de um servidor: a lista, o recebimento e o reembolso (que pode pedir aprovação).
 * As escritas levam a `Intent` da tela.
 */
interface PaymentSource {
    suspend fun payments(folioId: String): List<PaymentResponse>

    suspend fun receive(
        folioId: String,
        input: PaymentInput,
        intent: Intent,
    ): PaymentResponse

    fun refund(
        folioId: String,
        paymentId: String,
        input: RefundInput,
        intent: Intent,
    ): ApprovableAction<PaymentResponse>
}
