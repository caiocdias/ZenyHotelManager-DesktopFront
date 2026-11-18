package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.client.BillingApi
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.FolioStatementResponse
import com.zeny.desktop.sdk.model.GetFolioStatementParamAudience
import com.zeny.desktop.sdk.model.ListFoliosParamOwnerType
import com.zeny.desktop.sdk.model.ListFoliosParamStatus
import com.zeny.desktop.sdk.model.PostingRequest
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.ReversalRequest
import com.zeny.desktop.sdk.model.TransactionCodeResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

/** Maior página que o contrato aceita: as listas completas vêm com o mínimo de idas ao servidor. */
private const val ALL_PAGES_SIZE = 200
private const val DEFAULT_PAGE_SIZE = 50

private const val MIN_QUANTITY = 1
private const val MAX_QUANTITY = 9999

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

/**
 * As contas da hospedagem: lista, leitura, lançamentos, lançar, estornar e extrato. Lançar e estornar levam
 * `Idempotency-Key` pela `Intent`; o estorno pode pedir aprovação (`ApprovableAction`). O front não calcula saldo nem
 * total. Um `id` que não é UUID lança `IllegalArgumentException` antes de ir à rede, sem o valor na mensagem.
 */
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

    private fun idOf(
        id: String,
        what: String,
    ): Uuid = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de $what inválido")
}
