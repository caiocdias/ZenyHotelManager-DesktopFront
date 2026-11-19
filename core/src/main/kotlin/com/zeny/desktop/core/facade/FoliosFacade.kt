package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.client.BillingApi
import com.zeny.desktop.sdk.model.AdjustmentRequest
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.FolioStatementResponse
import com.zeny.desktop.sdk.model.GetFolioStatementParamAudience
import com.zeny.desktop.sdk.model.ListFoliosParamOwnerType
import com.zeny.desktop.sdk.model.ListFoliosParamStatus
import com.zeny.desktop.sdk.model.PostingRequest
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.ReversalRequest
import com.zeny.desktop.sdk.model.SplitPartDto
import com.zeny.desktop.sdk.model.SplitRequest
import com.zeny.desktop.sdk.model.TransactionCodeResponse
import com.zeny.desktop.sdk.model.TransferRequest
import com.zeny.desktop.sdk.model.TransferResponse
import de.quati.kotlin.util.Option
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** Maior página que o contrato aceita: as listas completas vêm com o mínimo de idas ao servidor. */
private const val ALL_PAGES_SIZE = 200
private const val DEFAULT_PAGE_SIZE = 50

private const val MIN_QUANTITY = 1
private const val MAX_QUANTITY = 9999

private const val MAX_REASON_LENGTH = 500
private const val MAX_TRANSFER_POSTINGS = 100
private const val MIN_SPLIT_PARTS = 2
private const val MAX_SPLIT_PARTS = 10
private const val MAX_PERCENT_SCALE = 4
private val MAX_PERCENT = BigDecimal(100)

/** Para quem é o extrato: o hóspede (`GUEST`, o padrão do contrato) ou o uso interno (`INTERNAL`). */
enum class StatementAudience { GUEST, INTERNAL }

/**
 * Um lançamento manual. `quantity` é inteira, de 1 a 9999 (conferida na fachada); `description` em branco não vai.
 */
data class PostingInput(
    val transactionCodeId: String,
    val quantity: Int,
    val unitPrice: Money,
    val description: String?,
)

/** Uma parte da divisão de um lançamento: a conta de destino e o percentual ou o valor, todas do mesmo tipo. */
sealed interface SplitPart {
    val folioId: String

    /** Percentual com até 4 casas, maior que zero e até 100. */
    data class Percent(
        override val folioId: String,
        val percent: BigDecimal,
    ) : SplitPart

    /** Valor maior que zero. */
    data class Amount(
        override val folioId: String,
        val amount: Money,
    ) : SplitPart
}

/**
 * As contas da hospedagem: lista, leitura, lançamentos, lançar, estornar, as correções (ajustar, remover a taxa de
 * serviço, transferir e dividir) e extrato. As escritas levam `Idempotency-Key` pela `Intent`; o estorno e o ajuste
 * podem pedir aprovação (`ApprovableAction`). O front não calcula saldo nem total. Um `id` que não é UUID
 * lança `IllegalArgumentException` antes de ir à rede, sem o valor na mensagem.
 */
@Suppress("TooManyFunctions") // uma função por operação de contas, mais os auxiliares de conferência
class FoliosFacade internal constructor(
    private val billing: BillingApi,
    private val executor: ApiExecutor,
) {
    /** Uma página das contas abertas; a tela percorre com o `CursorPager`. */
    suspend fun openFolioPage(
        cursor: String?,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): Page<FolioResponse> {
        val page =
            executor
                .execute {
                    billing.listFolios(
                        status = ListFoliosParamStatus.OPEN,
                        cursor = cursor,
                        limit = limit,
                        block = it.headers,
                    )
                }.value
        return Page(page.items, nextOf(page.nextCursor))
    }

    /** Todas as contas da reserva, todas as páginas. */
    suspend fun reservationFolios(reservationId: String): List<FolioResponse> {
        val id = idOf(reservationId, "reserva")
        return readAll { cursor, limit ->
            val page =
                executor
                    .execute {
                        billing.listFolios(
                            ownerType = ListFoliosParamOwnerType.RESERVATION,
                            ownerId = id,
                            cursor = cursor,
                            limit = limit,
                            block = it.headers,
                        )
                    }.value
            Page(page.items, nextOf(page.nextCursor))
        }
    }

    suspend fun folio(folioId: String): FolioResponse {
        val id = idOf(folioId, "conta")
        return executor.execute { billing.getFolio(id, it.headers) }.value
    }

    /** Todos os lançamentos da conta, todas as páginas, na ordem do servidor. */
    suspend fun postings(folioId: String): List<PostingResponse> {
        val id = idOf(folioId, "conta")
        return readAll { cursor, limit ->
            val page = executor.execute { billing.listPostings(id, cursor, limit, it.headers) }.value
            Page(page.items, nextOf(page.nextCursor))
        }
    }

    /** Só os códigos de transação ativos, todas as páginas. */
    suspend fun transactionCodes(): List<TransactionCodeResponse> =
        readAll { cursor, limit ->
            val page = executor.execute { billing.listTransactionCodes(true, null, cursor, limit, it.headers) }.value
            Page(page.items, nextOf(page.nextCursor))
        }

    suspend fun post(
        folioId: String,
        input: PostingInput,
        intent: Intent,
    ): PostingResponse {
        val id = idOf(folioId, "conta")
        val codeId = idOf(input.transactionCodeId, "código de transação")
        require(input.quantity in MIN_QUANTITY..MAX_QUANTITY) { "quantidade fora de $MIN_QUANTITY a $MAX_QUANTITY" }
        val description = input.description?.takeIf { it.isNotBlank() }
        val request =
            PostingRequest(
                description = if (description == null) Option.Undefined else Option.Some(description),
                quantity = input.quantity.toDouble(),
                transactionCodeId = codeId,
                unitPrice = input.unitPrice.toApi(),
            )
        return executor
            .execute(intent) { billing.createPosting(id, it.requireIdempotencyKey(), request, it.headers) }
            .value
    }

    /** Estorna o lançamento; os ids são conferidos ao montar a ação. */
    fun reverse(
        folioId: String,
        postingId: String,
        reason: String,
        intent: Intent,
    ): ApprovableAction<PostingResponse> {
        val folio = idOf(folioId, "conta")
        val posting = idOf(postingId, "lançamento")
        val request = ReversalRequest(reason)
        return executor.approvable(intent) {
            billing.reversePosting(
                id = folio,
                postingId = posting,
                idempotencyKey = it.requireIdempotencyKey(),
                reversalRequest = request,
                block = it.headers,
            )
        }
    }

    /**
     * Ajusta o lançamento: `amount` maior que zero (o servidor aplica o sinal oposto ao do original) e `reason`
     * obrigatório, até 500 caracteres.
     */
    fun adjust(
        folioId: String,
        postingId: String,
        amount: Money,
        reason: String,
        intent: Intent,
    ): ApprovableAction<PostingResponse> {
        val folio = idOf(folioId, "conta")
        val posting = idOf(postingId, "lançamento")
        require(amount.amount.signum() > 0) { "valor do ajuste deve ser maior que zero" }
        require(reason.isNotBlank() && reason.length <= MAX_REASON_LENGTH) { "motivo do ajuste fora das regras" }
        val request = AdjustmentRequest(amount = amount.toApi(), reason = reason)
        return executor.approvable(intent) {
            billing.adjustPosting(
                id = folio,
                postingId = posting,
                idempotencyKey = it.requireIdempotencyKey(),
                adjustmentRequest = request,
                block = it.headers,
            )
        }
    }

    /** Remove a taxa de serviço do lançamento da taxa; a chamada não leva corpo. */
    suspend fun removeServiceCharge(
        folioId: String,
        postingId: String,
        intent: Intent,
    ): PostingResponse {
        val folio = idOf(folioId, "conta")
        val posting = idOf(postingId, "lançamento")
        return executor
            .execute(intent) { billing.removeServiceCharge(folio, posting, it.requireIdempotencyKey(), it.headers) }
            .value
    }

    /** Transfere de 1 a 100 lançamentos, sem repetir, para outra conta; `reason` em branco não vai. */
    suspend fun transfer(
        folioId: String,
        postingIds: List<String>,
        targetFolioId: String,
        reason: String?,
        intent: Intent,
    ): TransferResponse {
        val folio = idOf(folioId, "conta")
        val ids = postingIds.map { idOf(it, "lançamento") }
        val target = idOf(targetFolioId, "conta de destino")
        require(ids.size in 1..MAX_TRANSFER_POSTINGS) { "quantidade de lançamentos fora de 1 a $MAX_TRANSFER_POSTINGS" }
        require(ids.toSet().size == ids.size) { "lançamentos repetidos" }
        require(target != folio) { "a conta de destino deve ser outra" }
        val request = TransferRequest(postingIds = ids, reason = reasonOf(reason), targetFolioId = target)
        return executor
            .execute(intent) { billing.transferPostings(folio, it.requireIdempotencyKey(), request, it.headers) }
            .value
    }

    /**
     * Divide o lançamento entre 2 e 10 contas, uma delas a própria, sem repetir; todas as partes são do mesmo tipo
     * (o `by` sai dele); `reason` em branco não vai.
     */
    suspend fun split(
        folioId: String,
        postingId: String,
        parts: List<SplitPart>,
        reason: String?,
        intent: Intent,
    ): TransferResponse {
        val folio = idOf(folioId, "conta")
        val posting = idOf(postingId, "lançamento")
        val folios = parts.map { idOf(it.folioId, "conta de destino") }
        require(parts.size in MIN_SPLIT_PARTS..MAX_SPLIT_PARTS) {
            "quantidade de partes fora de $MIN_SPLIT_PARTS a $MAX_SPLIT_PARTS"
        }
        require(folios.toSet().size == folios.size) { "contas repetidas na divisão" }
        require(folio in folios) { "a divisão deve incluir a própria conta" }
        val by =
            when {
                parts.all { it is SplitPart.Percent } -> SplitRequest.By.PERCENT
                parts.all { it is SplitPart.Amount } -> SplitRequest.By.AMOUNT
                else -> throw IllegalArgumentException("partes de tipos misturados")
            }
        val wire = parts.mapIndexed { index, part -> part.toDto(folios[index]) }
        val request = SplitRequest(by = by, parts = wire, reason = reasonOf(reason))
        return executor
            .execute(intent) { billing.splitPosting(folio, posting, it.requireIdempotencyKey(), request, it.headers) }
            .value
    }

    suspend fun statement(
        folioId: String,
        audience: StatementAudience,
    ): FolioStatementResponse {
        val id = idOf(folioId, "conta")
        val wire = GetFolioStatementParamAudience.fromSerial(audience.name)
        return executor.execute { billing.getFolioStatement(id, wire, it.headers) }.value
    }

    private suspend fun <T : Any> readAll(fetch: suspend (String?, Int) -> Page<T>): List<T> {
        val pager = CursorPager(ALL_PAGES_SIZE, fetch)
        val all = mutableListOf<T>()
        while (pager.hasMore) all += pager.next()
        return all
    }

    private fun nextOf(cursor: Option<String?>): String? = (cursor as? Option.Some)?.value?.takeIf { it.isNotBlank() }

    private fun reasonOf(reason: String?): Option<String?> {
        val text = reason?.takeIf { it.isNotBlank() } ?: return Option.Undefined
        require(text.length <= MAX_REASON_LENGTH) { "motivo acima de $MAX_REASON_LENGTH caracteres" }
        return Option.Some(text)
    }

    private fun SplitPart.toDto(id: Uuid): SplitPartDto =
        when (this) {
            is SplitPart.Percent -> {
                SplitPartDto(folioId = id, percent = Option.Some(percentText(percent)))
            }

            is SplitPart.Amount -> {
                require(amount.amount.signum() > 0) { "valor da parte deve ser maior que zero" }
                SplitPartDto(folioId = id, amount = Option.Some(amount.toApi()))
            }
        }

    /** Até 4 casas, maior que zero e até 100; sai em texto simples, sem expoente nem zeros que passem das 4 casas. */
    private fun percentText(percent: BigDecimal): String {
        val plain = percent.stripTrailingZeros()
        require(plain.signum() > 0 && plain <= MAX_PERCENT && plain.scale() <= MAX_PERCENT_SCALE) {
            "percentual fora das regras"
        }
        val kept = if (percent.scale() in 0..MAX_PERCENT_SCALE) percent else plain.setScale(maxOf(plain.scale(), 0))
        return kept.toPlainString()
    }

    private fun idOf(
        id: String,
        what: String,
    ): Uuid = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de $what inválido")
}
