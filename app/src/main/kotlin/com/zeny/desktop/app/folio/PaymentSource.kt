package com.zeny.desktop.app.folio

import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.CaptureInput
import com.zeny.desktop.core.facade.PaymentInput
import com.zeny.desktop.core.facade.PreAuthorizationInput
import com.zeny.desktop.core.facade.RefundInput
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import com.zeny.desktop.sdk.model.PaymentResponse
import com.zeny.desktop.sdk.model.PreAuthorizationCaptureResponse

/**
 * O que os pagamentos da conta usam de um servidor: a lista, o recebimento e o reembolso (que pode pedir aprovação),
 * as cauções (lista, registro, captura e liberação) e a data operacional com o fuso do hotel (a validade da caução).
 * As escritas levam a `Intent` da tela. As respostas de caução imprimem o cartão no `toString`: nunca as logue.
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

    suspend fun preAuthorizations(folioId: String): List<CardPreAuthorizationResponse>

    suspend fun registerPreAuthorization(
        folioId: String,
        input: PreAuthorizationInput,
        intent: Intent,
    ): CardPreAuthorizationResponse

    suspend fun capturePreAuthorization(
        folioId: String,
        preAuthorizationId: String,
        input: CaptureInput,
        intent: Intent,
    ): PreAuthorizationCaptureResponse

    suspend fun releasePreAuthorization(
        folioId: String,
        preAuthorizationId: String,
        intent: Intent,
    ): CardPreAuthorizationResponse

    suspend fun businessDay(): BusinessDay
}
