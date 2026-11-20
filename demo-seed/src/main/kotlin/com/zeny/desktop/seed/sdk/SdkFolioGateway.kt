@file:OptIn(RawSdkAccess::class)

package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
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
        return redactingServerDetail {
            var found: String? = null
            while (found == null && pager.hasMore) {
                found =
                    pager
                        .next()
                        .firstOrNull { (it.externalId as? Option.Some)?.value == externalId }
                        ?.id
                        ?.toString()
            }
            found
        }
    }

    /** A conta é a que a própria reserva traz (`folioId`): determinística, sem escolher entre várias. */
    override suspend fun reservationFolioId(reservationId: String): String? =
        redactingServerDetail {
            executor
                .execute { seed.apis.reservations.getReservation(Uuid.parse(reservationId), it.headers) }
                .value.reservation.folioId
                .toString()
        }

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

    override suspend fun post(
        folioId: String,
        codeId: String,
        unitPrice: String,
        description: String,
    ) {
        val request =
            PostingRequest(
                description = Option.Some(description),
                quantity = Option.Some(QUANTITY),
                transactionCodeId = Uuid.parse(codeId),
                unitPrice = unitPrice,
            )
        redactingServerDetail {
            executor.execute(Intent()) {
                billing.createPosting(Uuid.parse(folioId), it.requireIdempotencyKey(), request, it.headers)
            }
        }
    }

    private suspend fun <I> readAll(fetch: suspend (cursor: String?, limit: Int) -> Page<I>): List<I> {
        val pager = CursorPager(PAGE_SIZE, fetch)
        val all = mutableListOf<I>()
        redactingServerDetail { while (pager.hasMore) all += pager.next() }
        return all
    }
}
