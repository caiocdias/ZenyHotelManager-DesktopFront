package com.zeny.desktop.app.folio

import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.PaymentInput
import com.zeny.desktop.core.facade.RefundInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.sdk.model.PaymentResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

val SHIFT_ID: Uuid = Uuid.parse("a0000000-0000-0000-0000-000000000001")

@Suppress("LongParameterList") // fixture: cada campo que os testes variam, com padrão
fun paymentOf(
    id: Uuid = Uuid.random(),
    amount: String = "100.00",
    method: String = "CASH",
    type: String = "PAYMENT",
    deposit: Boolean = false,
    cardBrand: String? = null,
    cardLast4: String? = null,
    change: String? = null,
    refunded: String? = null,
    refundable: String? = null,
) = PaymentResponse(
    amount = amount,
    businessDate = "2026-11-18",
    cardBrand = cardBrand?.let { Option.Some(it) } ?: Option.Undefined,
    cardLast4 = cardLast4?.let { Option.Some(it) } ?: Option.Undefined,
    cashierShiftId = SHIFT_ID,
    change = change?.let { Option.Some(it) } ?: Option.Undefined,
    deposit = deposit,
    folioId = FOLIO_ID,
    id = id,
    method = method,
    receivedAt = "2026-11-18T15:00:00Z",
    receivedBy = USER_ID,
    refundableAmount = refundable?.let { Option.Some(it) } ?: Option.Undefined,
    refundedAmount = refunded?.let { Option.Some(it) } ?: Option.Undefined,
    type = type,
)

/** Um recebimento pedido ao falso: a conta, a entrada e a `Intent` da tela. */
data class ReceiveCall(
    val folioId: String,
    val input: PaymentInput,
    val intent: Intent,
)

/** Servidor de mentira dos pagamentos: as respostas são lambdas trocáveis, e cada chamada é registrada. */
class FakePaymentSource : PaymentSource {
    var payments: suspend (String) -> List<PaymentResponse> = { emptyList() }
    var receive: suspend (PaymentInput) -> PaymentResponse = { paymentOf(amount = it.amount.toApi()) }
    var refund: suspend () -> PaymentResponse = { paymentOf(type = "REFUND") }

    val paymentCalls = mutableListOf<String>()
    val receiveCalls = mutableListOf<ReceiveCall>()

    override suspend fun payments(folioId: String): List<PaymentResponse> {
        paymentCalls += folioId
        return payments.invoke(folioId)
    }

    override suspend fun receive(
        folioId: String,
        input: PaymentInput,
        intent: Intent,
    ): PaymentResponse {
        receiveCalls += ReceiveCall(folioId, input, intent)
        return receive.invoke(input)
    }

    override fun refund(
        folioId: String,
        paymentId: String,
        input: RefundInput,
        intent: Intent,
    ): ApprovableAction<PaymentResponse> = ApprovableAction { ApiResponse(refund(), null, 201) }
}
