package com.zeny.desktop.app.folio

import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CardPreAuthorizationResponse

/**
 * A porta das cauções a vencer (a aba "Cauções a vencer"): uma página das cauções não encerradas cuja validade cai até
 * [to], inclusive, na ordem do servidor; o `from` não vai (o servidor usa a data operacional). Separada do
 * [PaymentSource]. As respostas imprimem o cartão no `toString`: nunca as logue.
 */
fun interface ExpiringPreAuthorizationSource {
    suspend fun page(
        to: BusinessDate,
        cursor: String?,
        limit: Int,
    ): Page<CardPreAuthorizationResponse>
}
