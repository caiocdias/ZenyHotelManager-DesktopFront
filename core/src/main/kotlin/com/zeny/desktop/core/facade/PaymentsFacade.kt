package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.core.types.toApi
import com.zeny.desktop.sdk.client.BillingApi
import com.zeny.desktop.sdk.model.CapturePreAuthorizationRequest
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse
import com.zeny.desktop.sdk.model.PaymentResponse
import com.zeny.desktop.sdk.model.PreAuthorizationCaptureResponse
import com.zeny.desktop.sdk.model.ReceivePaymentRequest
import com.zeny.desktop.sdk.model.RefundPaymentRequest
import com.zeny.desktop.sdk.model.RegisterPreAuthorizationRequest
import de.quati.kotlin.util.Option
import java.time.Instant
import kotlin.uuid.Uuid

/** Maior página que o contrato aceita: a lista completa vem com o mínimo de idas ao servidor. */
private const val ALL_PAGES_SIZE = 200

/** O tamanho padrão da página das cauções a vencer. */
private const val EXPIRING_PAGE_SIZE = 50

private const val MAX_BRAND = 20
private const val MAX_CODE = 20
private const val MAX_CODE_DIGITS = 12
private const val MAX_REASON = 200

private val BRAND_FORMAT = Regex("^[\\p{L} ]{1,$MAX_BRAND}$")
private val LAST4_FORMAT = Regex("^[0-9]{4}$")
private val CODE_FORMAT = Regex("^[A-Za-z0-9]{1,$MAX_CODE}$")

/** O que a entrada de pagamento, de reembolso ou de caução tem de errado; a tela escolhe o texto. */
enum class PaymentProblem { AMOUNT, TENDERED, CARD_BRAND, CARD_LAST4, NSU, AUTHORIZATION_CODE, REASON }

/**
 * Um recebimento manual, com o `PaymentMethod` do `CashierFacade` (as mesmas seis formas; sem `CITY_LEDGER`).
 * Bandeira, últimos dígitos, NSU e autorização só no cartão (NSU e autorização, em qualquer
 * forma menos o dinheiro); texto em branco conta como ausente. O `toString()` não mostra dado de cartão.
 */
data class PaymentInput(
    val method: PaymentMethod,
    val amount: Money,
    val deposit: Boolean = false,
    val tendered: Money? = null,
    val cardBrand: String? = null,
    val cardLast4: String? = null,
    val nsu: String? = null,
    val authorizationCode: String? = null,
) {
    override fun toString(): String = "PaymentInput(method=$method, amount=$amount, deposit=$deposit)"
}

/** Um reembolso. A forma não vem aqui: NSU e autorização no dinheiro, o estado e o servidor recusam. */
data class RefundInput(
    val amount: Money,
    val reason: String,
    val nsu: String? = null,
    val authorizationCode: String? = null,
) {
    override fun toString(): String = "RefundInput(amount=$amount)"
}

/**
 * Uma caução (pré-autorização de cartão de crédito feita na maquininha): o valor autorizado, a validade [expiresAt]
 * (o estado confere o intervalo, que depende do fuso do hotel), a autorização (obrigatória) e, opcionais, o NSU, a
 * bandeira e os 4 últimos dígitos. Não há número de cartão nem CVV. O `toString()` não mostra cartão, NSU nem
 * autorização.
 */
data class PreAuthorizationInput(
    val amount: Money,
    val expiresAt: Instant,
    val authorizationCode: String,
    val nsu: String? = null,
    val cardBrand: String? = null,
    val cardLast4: String? = null,
) {
    override fun toString(): String = "PreAuthorizationInput(amount=$amount, expiresAt=$expiresAt)"
}

/** Uma captura de caução, total ou parcial. O `toString()` não mostra o NSU nem a autorização. */
data class CaptureInput(
    val amount: Money,
    val nsu: String? = null,
    val authorizationCode: String? = null,
) {
    override fun toString(): String = "CaptureInput(amount=$amount)"
}

private fun String?.present(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun String.validCode(): Boolean = CODE_FORMAT.matches(this) && count { it.isDigit() } <= MAX_CODE_DIGITS

private fun Money.positive(): Boolean = amount.signum() > 0

private fun codeProblems(
    nsu: String?,
    authorizationCode: String?,
    allowed: Boolean,
): Set<PaymentProblem> =
    buildSet {
        nsu.present()?.let { if (!allowed || !it.validCode()) add(PaymentProblem.NSU) }
        authorizationCode.present()?.let { if (!allowed || !it.validCode()) add(PaymentProblem.AUTHORIZATION_CODE) }
    }

/** As regras de formato do recebimento, uma vez: a fachada as confere e os estados as usam para avisar a operadora. */
fun PaymentInput.problems(): Set<PaymentProblem> =
    buildSet {
        val cash = method == PaymentMethod.CASH
        val card = method == PaymentMethod.DEBIT_CARD || method == PaymentMethod.CREDIT_CARD
        if (!amount.positive()) add(PaymentProblem.AMOUNT)
        tendered?.let { if (!cash || it.amount < amount.amount) add(PaymentProblem.TENDERED) }
        cardBrand.present()?.let { if (!card || !BRAND_FORMAT.matches(it)) add(PaymentProblem.CARD_BRAND) }
        cardLast4.present()?.let { if (!card || !LAST4_FORMAT.matches(it)) add(PaymentProblem.CARD_LAST4) }
        addAll(codeProblems(nsu, authorizationCode, allowed = !cash))
    }

/** Valor maior que zero, motivo de 1 a 200 caracteres e, se vierem, NSU e autorização no formato. */
fun RefundInput.problems(): Set<PaymentProblem> =
    buildSet {
        if (!amount.positive()) add(PaymentProblem.AMOUNT)
        // O limite é o do texto enviado, sem os espaços das pontas.
        if (reason.present() == null || reason.trim().length > MAX_REASON) add(PaymentProblem.REASON)
        addAll(codeProblems(nsu, authorizationCode, allowed = true))
    }

/** Valor maior que zero, autorização presente e no formato e, se vierem, NSU, bandeira e últimos no formato. */
fun PreAuthorizationInput.problems(): Set<PaymentProblem> =
    buildSet {
        if (!amount.positive()) add(PaymentProblem.AMOUNT)
        if (authorizationCode.present() == null) add(PaymentProblem.AUTHORIZATION_CODE)
        cardBrand.present()?.let { if (!BRAND_FORMAT.matches(it)) add(PaymentProblem.CARD_BRAND) }
        cardLast4.present()?.let { if (!LAST4_FORMAT.matches(it)) add(PaymentProblem.CARD_LAST4) }
        addAll(codeProblems(nsu, authorizationCode, allowed = true))
    }

/** Valor maior que zero e, se vierem, NSU e autorização no formato. */
fun CaptureInput.problems(): Set<PaymentProblem> =
    buildSet {
        if (!amount.positive()) add(PaymentProblem.AMOUNT)
        addAll(codeProblems(nsu, authorizationCode, allowed = true))
    }

/**
 * Os pagamentos da conta: lista, recebimento e reembolso, e as cauções (lista, registro, captura e liberação). As
 * escritas levam `Idempotency-Key` pela `Intent`; o reembolso pode pedir aprovação (`ApprovableAction`). O front não
 * calcula troco, reembolsado, reembolsável, capturado, capturável nem liberado. As respostas de caução do `sdk`
 * imprimem o cartão no `toString`: nunca as logue nem as interpole.
 * Entrada com problema ou `id` que não é UUID lança `IllegalArgumentException` antes de ir à rede, sem o valor
 * na mensagem.
 */
class PaymentsFacade internal constructor(
    private val billing: BillingApi,
    private val executor: ApiExecutor,
) {
    /** Todos os pagamentos da conta, todas as páginas, na ordem do servidor. */
    suspend fun payments(folioId: String): List<PaymentResponse> {
        val id = idOf(folioId, "conta")
        val pager =
            CursorPager(ALL_PAGES_SIZE) { cursor, limit ->
                val page = executor.execute { billing.listPayments(id, cursor, limit, it.headers) }.value
                Page(page.items, (page.nextCursor as? Option.Some)?.value?.takeIf { it.isNotBlank() })
            }
        val all = mutableListOf<PaymentResponse>()
        while (pager.hasMore) all += pager.next()
        return all
    }

    suspend fun receive(
        folioId: String,
        input: PaymentInput,
        intent: Intent,
    ): PaymentResponse {
        val id = idOf(folioId, "conta")
        val problems = input.problems()
        require(problems.isEmpty()) { "pagamento inválido: $problems" }
        val request =
            ReceivePaymentRequest(
                amount = input.amount.toApi(),
                authorizationCode = optional(input.authorizationCode),
                cardBrand = optional(input.cardBrand),
                cardLast4 = optional(input.cardLast4),
                deposit = Option.Some(input.deposit),
                method = ReceivePaymentRequest.Method.fromSerial(input.method.name),
                nsu = optional(input.nsu),
                tendered = input.tendered?.let { Option.Some(it.toApi()) } ?: Option.Undefined,
            )
        return executor
            .execute(intent) { billing.receivePayment(id, it.requireIdempotencyKey(), request, it.headers) }
            .value
    }

    /** Reembolsa o pagamento; os ids e a entrada são conferidos ao montar a ação. */
    fun refund(
        folioId: String,
        paymentId: String,
        input: RefundInput,
        intent: Intent,
    ): ApprovableAction<PaymentResponse> {
        val folio = idOf(folioId, "conta")
        val payment = idOf(paymentId, "pagamento")
        val problems = input.problems()
        require(problems.isEmpty()) { "reembolso inválido: $problems" }
        val request =
            RefundPaymentRequest(
                amount = input.amount.toApi(),
                authorizationCode = optional(input.authorizationCode),
                nsu = optional(input.nsu),
                reason = input.reason.trim(),
            )
        return executor.approvable(intent) {
            billing.refundPayment(
                folioId = folio,
                paymentId = payment,
                idempotencyKey = it.requireIdempotencyKey(),
                refundPaymentRequest = request,
                block = it.headers,
            )
        }
    }

    /** Todas as cauções da conta, todas as páginas, na ordem do servidor. */
    suspend fun preAuthorizations(folioId: String): List<CardPreAuthorizationResponse> {
        val id = idOf(folioId, "conta")
        val pager =
            CursorPager(ALL_PAGES_SIZE) { cursor, limit ->
                val page = executor.execute { billing.listCardPreAuthorizations(id, cursor, limit, it.headers) }.value
                Page(page.items, (page.nextCursor as? Option.Some)?.value?.takeIf { it.isNotBlank() })
            }
        val all = mutableListOf<CardPreAuthorizationResponse>()
        while (pager.hasMore) all += pager.next()
        return all
    }

    /**
     * Uma página das cauções a vencer, na ordem de validade do servidor, até o dia [to] (inclusive). Sem `from`: o
     * servidor parte da data operacional. A tela percorre com o `CursorPager`. A resposta de caução imprime o cartão no
     * `toString`: nunca a logue.
     */
    suspend fun expiringPreAuthorizationPage(
        to: BusinessDate,
        cursor: String?,
        limit: Int = EXPIRING_PAGE_SIZE,
    ): Page<CardPreAuthorizationResponse> {
        val page =
            executor
                .execute {
                    billing.listExpiringCardPreAuthorizations(
                        to = to.toApi(),
                        cursor = cursor,
                        limit = limit,
                        block = it.headers,
                    )
                }.value
        return Page(page.items, (page.nextCursor as? Option.Some)?.value?.takeIf { it.isNotBlank() })
    }

    suspend fun registerPreAuthorization(
        folioId: String,
        input: PreAuthorizationInput,
        intent: Intent,
    ): CardPreAuthorizationResponse {
        val id = idOf(folioId, "conta")
        val problems = input.problems()
        require(problems.isEmpty()) { "caução inválida: $problems" }
        val request =
            RegisterPreAuthorizationRequest(
                amount = input.amount.toApi(),
                authorizationCode = optional(input.authorizationCode),
                cardBrand = optional(input.cardBrand),
                cardLast4 = optional(input.cardLast4),
                expiresAt = input.expiresAt.toApi(),
                nsu = optional(input.nsu),
            )
        return executor
            .execute(intent) {
                billing.registerCardPreAuthorization(id, it.requireIdempotencyKey(), request, it.headers)
            }.value
    }

    suspend fun capturePreAuthorization(
        folioId: String,
        preAuthorizationId: String,
        input: CaptureInput,
        intent: Intent,
    ): PreAuthorizationCaptureResponse {
        val folio = idOf(folioId, "conta")
        val preAuthorization = idOf(preAuthorizationId, "caução")
        val problems = input.problems()
        require(problems.isEmpty()) { "captura inválida: $problems" }
        val request =
            CapturePreAuthorizationRequest(
                amount = input.amount.toApi(),
                authorizationCode = optional(input.authorizationCode),
                nsu = optional(input.nsu),
            )
        return executor
            .execute(intent) {
                billing.captureCardPreAuthorization(
                    folioId = folio,
                    preAuthorizationId = preAuthorization,
                    idempotencyKey = it.requireIdempotencyKey(),
                    capturePreAuthorizationRequest = request,
                    block = it.headers,
                )
            }.value
    }

    /** Libera a sobra; vale também com a conta encerrada e com a caução vencida (o servidor decide). */
    suspend fun releasePreAuthorization(
        folioId: String,
        preAuthorizationId: String,
        intent: Intent,
    ): CardPreAuthorizationResponse {
        val folio = idOf(folioId, "conta")
        val preAuthorization = idOf(preAuthorizationId, "caução")
        return executor
            .execute(intent) {
                billing.releaseCardPreAuthorization(folio, preAuthorization, it.requireIdempotencyKey(), it.headers)
            }.value
    }

    private fun optional(text: String?): Option<String?> = text.present()?.let { Option.Some(it) } ?: Option.Undefined

    private fun idOf(
        id: String,
        what: String,
    ): Uuid = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de $what inválido")
}
