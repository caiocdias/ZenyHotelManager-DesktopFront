package com.zeny.desktop.app.folio

import com.zeny.desktop.app.reception.PdfKind
import com.zeny.desktop.app.reception.RegistrationCardState
import com.zeny.desktop.app.reception.RegistrationCards
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.PostingResponse

/**
 * O que as três entradas da conta (a aba "Contas", "Conta" na Recepção e "Conta" nas Reservas e pendências) recebem
 * do `AppRoot`: a porta, o conjunto de permissões da casca, a aprovação da sessão e as fábricas do extrato em PDF de
 * cada audiência (nulas sem visualizador). Nenhuma entrada fica sem coordenador de aprovação.
 */
class FolioWiring(
    val source: FolioSource,
    val permissions: Set<String>,
    val approve: suspend (ApprovableAction<PostingResponse>) -> ApiResponse<PostingResponse>?,
    val guestPdf: ((folioId: String, number: String) -> RegistrationCardState)?,
    val internalPdf: ((folioId: String, number: String) -> RegistrationCardState)?,
)

/**
 * O extrato em PDF da [audience] lido pelo estado da ficha. O interno descarta o nome de arquivo do servidor, que dá
 * `extrato-<número>.pdf` às duas audiências: assim o nome sugerido sai do `PdfKind` (`extrato-interno-<número>.pdf`).
 */
internal fun FolioSource.statementCards(audience: StatementAudience): RegistrationCards =
    RegistrationCards { folioId ->
        val pdf = statementPdf(folioId, audience)
        if (audience == StatementAudience.INTERNAL) PdfDocument(pdf.bytes, null) else pdf
    }

/** O título e o nome sugerido do extrato em PDF de cada audiência. */
internal val StatementAudience.pdfKind: PdfKind
    get() =
        when (this) {
            StatementAudience.GUEST -> PdfKind.FOLIO_STATEMENT_GUEST
            StatementAudience.INTERNAL -> PdfKind.FOLIO_STATEMENT_INTERNAL
        }
