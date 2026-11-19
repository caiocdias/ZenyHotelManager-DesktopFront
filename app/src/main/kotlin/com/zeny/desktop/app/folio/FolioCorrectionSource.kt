package com.zeny.desktop.app.folio

import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.SplitPart
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransferResponse

/**
 * O que as correções da conta usam de um servidor: ajustar (que pode pedir aprovação de superior), remover a taxa de
 * serviço, transferir e dividir. As escritas levam a `Intent` da tela. Entrada fora das regras e id que não é UUID
 * lançam `IllegalArgumentException` antes da rede, sem o valor na mensagem.
 */
interface FolioCorrectionSource {
    fun adjust(
        folioId: String,
        postingId: String,
        amount: Money,
        reason: String,
        intent: Intent,
    ): ApprovableAction<PostingResponse>

    suspend fun removeServiceCharge(
        folioId: String,
        postingId: String,
        intent: Intent,
    ): PostingResponse

    suspend fun transfer(
        folioId: String,
        postingIds: List<String>,
        targetFolioId: String,
        reason: String?,
        intent: Intent,
    ): TransferResponse

    suspend fun split(
        folioId: String,
        postingId: String,
        parts: List<SplitPart>,
        reason: String?,
        intent: Intent,
    ): TransferResponse
}
