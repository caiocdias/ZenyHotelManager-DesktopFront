@file:OptIn(RawSdkAccess::class)

package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.ListFoliosParamOwnerType
import com.zeny.desktop.sdk.model.ListFoliosParamStatus
import com.zeny.desktop.sdk.model.PostingRequest
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.TransactionCodeResponse
import com.zeny.desktop.seed.folio.FolioGateway
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

private const val PAGE_SIZE = 200
private const val QUANTITY = 1.0

/**
 * Contas e lançamentos sobre o `sdk`. O lançamento exige `Idempotency-Key`: cada chamada cria a sua `Intent`.
 * O `detail` do servidor pode ecoar dado do hóspede: numa recusa do lançamento só o status e o código sobem.
 * Nomes são comparados sem diferenciar maiúsculas, porque o servidor os trata assim.
 */
class SdkFolioGateway(
    private val seed: SeedCore,
) : FolioGateway {
    private val executor get() = seed.executor
    private val billing get() = seed.apis.billing

    override suspend fun reservationId(externalId: String): String? {
        val pager =
            CursorPager<ReservationListItemResponse>(PAGE_SIZE) { cursor, limit ->
                val page =
                    executor
                        .execute {
                            seed.apis.reservations.searchReservations(
                                externalId = externalId,
                                cursor = cursor,
                                limit = limit,
                                block = it.headers,
                            )
                        }.value
                Page(page.items, (page.nextCursor as? Option.Some)?.value)
            }
        while (pager.hasMore) {
            pager
                .next()
                .firstOrNull { (it.externalId as? Option.Some)?.value == externalId }
                ?.let { return it.id.toString() }
        }
        return null
    }

    override suspend fun reservationFolioId(reservationId: String): String? =
        executor
            .execute {
                billing.listFolios(
                    ListFoliosParamOwnerType.RESERVATION,
                    Uuid.parse(reservationId),
                    ListFoliosParamStatus.OPEN,
                    null,
                    null,
                    PAGE_SIZE,
                    it.headers,
                )
            }.value.items
            .firstOrNull()
            ?.id
            ?.toString()

    override suspend fun postingDescriptions(folioId: String): List<String> =
        readAll { cursor, limit ->
            val page = executor.execute { billing.listPostings(Uuid.parse(folioId), cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.map { it.description }

    override suspend fun chargeableCodeId(names: List<String>): String? =
        readAll { cursor, limit ->
            val page = executor.execute { billing.listTransactionCodes(true, null, cursor, limit, it.headers) }.value
            Page(page.items, (page.nextCursor as? Option.Some)?.value)
        }.firstOrNull { code ->
            code.active &&
                code.serviceChargeApplies &&
                code.kind == TransactionCodeResponse.Kind.CHARGE &&
                names.any { it.equals(code.name, ignoreCase = true) }
        }?.id
            ?.toString()

    @Suppress("SwallowedException") // o erro original traz o detail do servidor, descartado de propósito
    override suspend fun post(
        folioId: String,
        codeId: String,
        unitPrice: String,
        description: String,
    ) {
        val request =
            PostingRequest(
                description = Option.Some(description),
                quantity = QUANTITY,
                transactionCodeId = Uuid.parse(codeId),
                unitPrice = unitPrice,
            )
        try {
            executor.execute(Intent()) {
                billing.createPosting(Uuid.parse(folioId), it.requireIdempotencyKey(), request, it.headers)
            }
        } catch (e: ApiError) {
            throw e.withoutServerDetail()
        }
    }

    private suspend fun <I> readAll(fetch: suspend (cursor: String?, limit: Int) -> Page<I>): List<I> {
        val pager = CursorPager(PAGE_SIZE, fetch)
        val all = mutableListOf<I>()
        while (pager.hasMore) all += pager.next()
        return all
    }
}
