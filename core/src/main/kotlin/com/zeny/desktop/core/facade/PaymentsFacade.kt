package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.client.BillingApi
import com.zeny.desktop.sdk.model.PaymentResponse
import com.zeny.desktop.sdk.model.ReceivePaymentRequest
import com.zeny.desktop.sdk.model.RefundPaymentRequest
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

/** Maior página que o contrato aceita: a lista completa vem com o mínimo de idas ao servidor. */
private const val ALL_PAGES_SIZE = 200

private const val MAX_BRAND = 20
private const val MAX_CODE = 20
private const val MAX_CODE_DIGITS = 12
private const val MAX_REASON = 200

private val BRAND_FORMAT = Regex("^[\\p{L} ]{1,$MAX_BRAND}$")
private val LAST4_FORMAT = Regex("^[0-9]{4}$")
private val CODE_FORMAT = Regex("^[A-Za-z0-9]{1,$MAX_CODE}$")

/** O que a entrada de pagamento ou de reembolso tem de errado; a tela escolhe o texto. */
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
        if (reason.present() == null || reason.length > MAX_REASON) add(PaymentProblem.REASON)
        addAll(codeProblems(nsu, authorizationCode, allowed = true))
    }

/**
 * Os pagamentos da conta: lista, recebimento e reembolso. Receber e reembolsar levam `Idempotency-Key` pela `Intent`;
 * o reembolso pode pedir aprovação (`ApprovableAction`). O front não calcula troco, reembolsado nem reembolsável.
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
                deposit = input.deposit,
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

    private fun optional(text: String?): Option<String?> = text.present()?.let { Option.Some(it) } ?: Option.Undefined

    private fun idOf(
        id: String,
        what: String,
    ): Uuid = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de $what inválido")
}
