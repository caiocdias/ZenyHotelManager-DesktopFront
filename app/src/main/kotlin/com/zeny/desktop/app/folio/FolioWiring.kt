package com.zeny.desktop.app.folio

import com.zeny.desktop.app.reception.PdfKind
import com.zeny.desktop.app.reception.RegistrationCardState
import com.zeny.desktop.app.reception.RegistrationCards
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.sdk.model.PaymentResponse
import com.zeny.desktop.sdk.model.PostingResponse

/**
 * O que as três entradas da conta (a aba "Contas", "Conta" na Recepção e "Conta" nas Reservas e pendências) recebem
 * do `AppRoot`: a porta, o conjunto de permissões da casca, a aprovação da sessão e as fábricas do extrato em PDF de
 * cada audiência (nulas sem visualizador); a porta dos pagamentos (nula, sem a seção "Pagamentos"), a aprovação do
 * reembolso, o atalho "Abrir turno" (nulo sem `billing.cashier.operate`) e a porta das correções da conta (nula, sem
 * ajustar, remover a taxa, transferir nem dividir). O `AppRoot` monta as três entradas com o
 * coordenador de aprovação da sessão. O padrão de [approveRefund] nunca roda a ação (equivale à aprovação cancelada):
 * existe só para quem monta o portador sem coordenador compilar, e uma entrada assim não reembolsa por engano.
 */
@Suppress("LongParameterList") // o que as três entradas recebem; os campos novos entram por último, com padrão
class FolioWiring(
    val source: FolioSource,
    val permissions: Set<String>,
    val approve: suspend (ApprovableAction<PostingResponse>) -> ApiResponse<PostingResponse>?,
    val guestPdf: ((folioId: String, number: String) -> RegistrationCardState)?,
    val internalPdf: ((folioId: String, number: String) -> RegistrationCardState)?,
    val payments: PaymentSource? = null,
    val approveRefund: suspend (ApprovableAction<PaymentResponse>) -> ApiResponse<PaymentResponse>? = { null },
    val openShift: (() -> Unit)? = null,
    val corrections: FolioCorrectionSource? = null,
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
