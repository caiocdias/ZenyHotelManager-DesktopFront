package com.zeny.desktop.app.folio

import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.core.facade.PostingInput
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.FolioStatementResponse
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransactionCodeResponse

/**
 * O que a conta da hospedagem usa de um servidor: as contas abertas (uma página por vez) e as da reserva, a conta, os
 * lançamentos, os códigos de transação ativos, o lançamento, o estorno (que pode pedir aprovação), o extrato e o seu
 * PDF. As escritas levam a `Intent` da tela.
 */
interface FolioSource {
    suspend fun openFolioPage(
        cursor: String?,
        limit: Int = DEFAULT_OPEN_PAGE_SIZE,
    ): Page<FolioResponse>

    suspend fun reservationFolios(reservationId: String): List<FolioResponse>

    suspend fun folio(folioId: String): FolioResponse

    suspend fun postings(folioId: String): List<PostingResponse>

    suspend fun transactionCodes(): List<TransactionCodeResponse>

    suspend fun post(
        folioId: String,
        input: PostingInput,
        intent: Intent,
    ): PostingResponse

    fun reverse(
        folioId: String,
        postingId: String,
        reason: String,
        intent: Intent,
    ): ApprovableAction<PostingResponse>

    suspend fun statement(
        folioId: String,
        audience: StatementAudience,
    ): FolioStatementResponse

    suspend fun statementPdf(
        folioId: String,
        audience: StatementAudience,
    ): PdfDocument
}

private const val DEFAULT_OPEN_PAGE_SIZE = 50
