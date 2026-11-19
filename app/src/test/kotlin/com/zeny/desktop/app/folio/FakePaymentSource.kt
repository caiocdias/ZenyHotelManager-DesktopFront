package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.CaptureInput
import com.zeny.desktop.core.facade.PaymentInput
import com.zeny.desktop.core.facade.PreAuthorizationInput
import com.zeny.desktop.core.facade.RefundInput
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import com.zeny.desktop.sdk.model.PaymentResponse
import com.zeny.desktop.sdk.model.PreAuthorizationCaptureResponse
import de.quati.kotlin.util.Option
import java.time.ZoneId
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

@Suppress("LongParameterList") // fixture: cada campo que os testes variam, com padrão
fun preAuthorizationOf(
    id: Uuid = Uuid.random(),
    amount: String = "300.00",
    captured: String = "0.00",
    capturable: String = "300.00",
    status: String = "ACTIVE",
    expiresAt: String = "2026-11-20T02:59:59Z",
    released: String? = null,
    cardBrand: String? = null,
    cardLast4: String? = null,
    nsu: String? = null,
) = CardPreAuthorizationResponse(
    amount = amount,
    authorizationCode = "AUT123",
    businessDate = "2026-11-18",
    capturableAmount = capturable,
    capturePaymentIds = emptyList(),
    capturedAmount = captured,
    cardBrand = cardBrand?.let { Option.Some(it) } ?: Option.Undefined,
    cardLast4 = cardLast4?.let { Option.Some(it) } ?: Option.Undefined,
    expiresAt = expiresAt,
    folioId = FOLIO_ID,
    id = id,
    nsu = nsu?.let { Option.Some(it) } ?: Option.Undefined,
    registeredAt = "2026-11-18T15:00:00Z",
    registeredBy = USER_ID,
    releasedAmount = released?.let { Option.Some(it) } ?: Option.Undefined,
    status = status,
)

/** Um registro de caução pedido ao falso: a conta, a entrada e a `Intent` da tela. */
data class RegisterCall(
    val folioId: String,
    val input: PreAuthorizationInput,
    val intent: Intent,
)

/** Uma captura pedida ao falso: a conta, a caução, a entrada e a `Intent` da tela. */
data class CaptureCall(
    val folioId: String,
    val preAuthorizationId: String,
    val input: CaptureInput,
    val intent: Intent,
)

/** Uma liberação pedida ao falso: a conta, a caução e a `Intent` da tela. */
data class ReleaseCall(
    val folioId: String,
    val preAuthorizationId: String,
    val intent: Intent,
)

/** Um recebimento pedido ao falso: a conta, a entrada e a `Intent` da tela. */
data class ReceiveCall(
    val folioId: String,
    val input: PaymentInput,
    val intent: Intent,
)

/** Um reembolso pedido ao falso (a criação da ação): a conta, o pagamento, a entrada e a `Intent` da tela. */
data class RefundCall(
    val folioId: String,
    val paymentId: String,
    val input: RefundInput,
    val intent: Intent,
)

/** Uma execução (`run`) de uma ação de reembolso: a `Intent.key` em uso e o token, se houve. */
data class RefundRun(
    val key: String,
    val token: String?,
)

/** Servidor de mentira dos pagamentos: as respostas são lambdas trocáveis, e cada chamada é registrada. */
class FakePaymentSource : PaymentSource {
    var payments: suspend (String) -> List<PaymentResponse> = { emptyList() }
    var receive: suspend (PaymentInput) -> PaymentResponse = { paymentOf(amount = it.amount.toApi()) }

    /** O reembolso: sem token e com [requiresApproval], falha; senão, o próximo de [refundOutcomes] ou [refund]. */
    var refund: suspend () -> PaymentResponse = { paymentOf(type = "REFUND") }
    var requiresApproval: ApiError.ApprovalRequired? = null
    val refundOutcomes = ArrayDeque<Throwable?>()

    val paymentCalls = mutableListOf<String>()
    val receiveCalls = mutableListOf<ReceiveCall>()
    val refundCalls = mutableListOf<RefundCall>()
    val refundRuns = mutableListOf<RefundRun>()
    val refundActions = mutableListOf<ApprovableAction<PaymentResponse>>()

    var preAuthorizations: suspend (String) -> List<CardPreAuthorizationResponse> = { emptyList() }
    var register: suspend (PreAuthorizationInput) -> CardPreAuthorizationResponse = {
        preAuthorizationOf(amount = it.amount.toApi(), capturable = it.amount.toApi())
    }
    var capture: suspend (CaptureInput) -> PreAuthorizationCaptureResponse = {
        PreAuthorizationCaptureResponse(
            payment = paymentOf(amount = it.amount.toApi(), method = "CREDIT_CARD"),
            preAuthorization = preAuthorizationOf(captured = it.amount.toApi(), status = "PARTIALLY_CAPTURED"),
        )
    }
    var release: suspend () -> CardPreAuthorizationResponse = { preAuthorizationOf(status = "RELEASED") }
    var businessDay: suspend () -> BusinessDay = {
        BusinessDay(BusinessDate.parse("2026-11-18"), ZoneId.of("America/Sao_Paulo"))
    }

    val preAuthorizationCalls = mutableListOf<String>()
    val registerCalls = mutableListOf<RegisterCall>()
    val captureCalls = mutableListOf<CaptureCall>()
    val releaseCalls = mutableListOf<ReleaseCall>()
    var businessDayCalls = 0

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
    ): ApprovableAction<PaymentResponse> {
        refundCalls += RefundCall(folioId, paymentId, input, intent)
        val action =
            ApprovableAction { token ->
                refundRuns += RefundRun(intent.key, token)
                val approval = requiresApproval
                if (token == null && approval != null) throw approval
                refundOutcomes.removeFirstOrNull()?.let { throw it }
                ApiResponse(refund(), null, 201)
            }
        refundActions += action
        return action
    }

    override suspend fun preAuthorizations(folioId: String): List<CardPreAuthorizationResponse> {
        preAuthorizationCalls += folioId
        return preAuthorizations.invoke(folioId)
    }

    override suspend fun registerPreAuthorization(
        folioId: String,
        input: PreAuthorizationInput,
        intent: Intent,
    ): CardPreAuthorizationResponse {
        registerCalls += RegisterCall(folioId, input, intent)
        return register.invoke(input)
    }

    override suspend fun capturePreAuthorization(
        folioId: String,
        preAuthorizationId: String,
        input: CaptureInput,
        intent: Intent,
    ): PreAuthorizationCaptureResponse {
        captureCalls += CaptureCall(folioId, preAuthorizationId, input, intent)
        return capture.invoke(input)
    }

    override suspend fun releasePreAuthorization(
        folioId: String,
        preAuthorizationId: String,
        intent: Intent,
    ): CardPreAuthorizationResponse {
        releaseCalls += ReleaseCall(folioId, preAuthorizationId, intent)
        return release.invoke()
    }

    override suspend fun businessDay(): BusinessDay {
        businessDayCalls++
        return businessDay.invoke()
    }
}
