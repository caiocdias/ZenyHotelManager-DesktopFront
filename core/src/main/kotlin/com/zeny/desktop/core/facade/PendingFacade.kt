package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.toApi
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.model.DepositShortfallResponse
import com.zeny.desktop.sdk.model.PendingGuaranteeResponse
import com.zeny.desktop.sdk.model.ReallocationRequiredResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import de.quati.kotlin.util.Option
import java.time.Instant

/**
 * As listas de pendências de reservas: garantias pendentes, depósitos em falta, no-shows previstos e reservas que
 * precisam de realocação. Todas exigem `reservations.reservations.read`; o servidor decide.
 */
class PendingFacade internal constructor(
    private val reservations: ReservationsApi,
    private val executor: ApiExecutor,
) {
    /**
     * Uma página das garantias pendentes até `to` (`dueAt < to`), das já vencidas em diante: `from` nunca vai. Sem
     * `to` o servidor só mostraria as que vencem dentro do alerta do hotel.
     */
    suspend fun pendingGuarantees(
        to: Instant,
        cursor: String?,
        limit: Int,
    ): Page<PendingGuaranteeResponse> {
        val page =
            executor
                .execute {
                    reservations.listPendingGuarantees(
                        to = to.toApi(),
                        cursor = cursor,
                        limit = limit,
                        block = it.headers,
                    )
                }.value
        return Page(page.items, page.nextCursor.valueOrNull())
    }

    /** Os depósitos em falta; a lista não tem cursor. */
    suspend fun depositShortfalls(): List<DepositShortfallResponse> =
        executor.execute { reservations.listDepositShortfalls(it.headers) }.value.items

    /** Uma página dos no-shows previstos; sem `date`, vale a data operacional do servidor. */
    suspend fun expectedNoShows(
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse> {
        val page =
            executor
                .execute { reservations.listExpectedNoShows(cursor = cursor, limit = limit, block = it.headers) }
                .value
        return Page(page.items, page.nextCursor.valueOrNull())
    }

    /** Uma página das reservas que precisam de nova UH. */
    suspend fun reallocationRequired(
        cursor: String?,
        limit: Int,
    ): Page<ReallocationRequiredResponse> {
        val page =
            executor
                .execute { reservations.listReallocationRequired(cursor = cursor, limit = limit, block = it.headers) }
                .value
        return Page(page.items, page.nextCursor.valueOrNull())
    }

    /** Cursor ausente, nulo ou em branco é o fim da lista. */
    private fun Option<String?>.valueOrNull(): String? = (this as? Option.Some)?.value?.takeIf { it.isNotBlank() }
}
