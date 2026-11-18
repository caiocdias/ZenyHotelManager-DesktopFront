package com.zeny.desktop.app.folio

import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.core.facade.PostingInput
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.FolioOwnerDto
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.FolioStatementResponse
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.StatementDayResponse
import com.zeny.desktop.sdk.model.StatementFolioResponse
import com.zeny.desktop.sdk.model.StatementLineInternalResponse
import com.zeny.desktop.sdk.model.StatementLineResponse
import com.zeny.desktop.sdk.model.TransactionCodeResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

val FOLIO_ID: Uuid = Uuid.parse("60000000-0000-0000-0000-000000000001")
val OTHER_FOLIO_ID: Uuid = Uuid.parse("60000000-0000-0000-0000-000000000002")
val OWNER_ID: Uuid = Uuid.parse("70000000-0000-0000-0000-000000000001")
val USER_ID: Uuid = Uuid.parse("80000000-0000-0000-0000-000000000001")

/** Um PDF mínimo de uma página em branco; o PDFBox reconstrói a tabela de referências. */
val FOLIO_PDF: ByteArray =
    (
        "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>endobj\n" +
            "trailer<</Root 1 0 R/Size 4>>\n%%EOF\n"
    ).toByteArray(Charsets.ISO_8859_1)

@Suppress("LongParameterList") // fixture: cada campo que os testes variam, com padrão
fun folioOf(
    id: Uuid = FOLIO_ID,
    number: String = "F-0001",
    balance: String = "150.00",
    status: FolioResponse.Status = FolioResponse.Status.OPEN,
    ownerType: FolioOwnerDto.Type? = FolioOwnerDto.Type.RESERVATION,
    creditLimit: String? = null,
    exceeded: Boolean = false,
) = FolioResponse(
    balance = balance,
    creditLimit = creditLimit?.let { Option.Some(it) } ?: Option.Undefined,
    creditLimitExceeded = exceeded,
    id = id,
    number = number,
    openedAt = "2026-11-18T12:00:00Z",
    openedOn = "2026-11-18",
    owner = FolioOwnerDto(Option.Some(OWNER_ID), ownerType),
    status = status,
    updatedAt = "2026-11-18T12:00:00Z",
)

@Suppress("LongParameterList") // fixture: cada campo que os testes variam, com padrão
fun postingOf(
    id: Uuid = Uuid.random(),
    description: String = "Frigobar",
    amount: String = "20.00",
    type: String = "REGULAR",
    quantity: Double = 1.0,
    reversedBy: Uuid? = null,
    serviceChargeFor: Uuid? = null,
) = PostingResponse(
    amount = amount,
    businessDate = "2026-11-18",
    description = description,
    folioId = FOLIO_ID,
    id = id,
    postedAt = "2026-11-18T13:00:00Z",
    postedBy = Option.Some(USER_ID),
    quantity = quantity,
    reversedBy = reversedBy?.let { Option.Some(it) } ?: Option.Undefined,
    serviceChargeForPostingId = serviceChargeFor?.let { Option.Some(it) } ?: Option.Undefined,
    source = PostingResponse.Source.MANUAL,
    transactionCodeId = Uuid.random(),
    type = type,
    unitPrice = amount,
)

fun lineOf(
    description: String = "Frigobar",
    amount: String = "20.00",
    kind: String = "POSTING",
    reason: String? = null,
) = StatementLineResponse(
    amount = amount,
    at = "2026-11-18T13:00:00Z",
    businessDate = "2026-11-18",
    description = description,
    id = Uuid.random(),
    `internal` =
        reason?.let {
            Option.Some(StatementLineInternalResponse(reason = Option.Some(it), userId = Option.Some(USER_ID)))
        } ?: Option.Undefined,
    kind = kind,
    method = Option.Some("PIX"),
    postingType = Option.Some("REGULAR"),
)

fun statementOf(
    audience: FolioStatementResponse.Audience = FolioStatementResponse.Audience.INTERNAL,
    lines: List<StatementLineResponse> = listOf(lineOf()),
    balance: String = "150.00",
) = FolioStatementResponse(
    audience = audience,
    balance = balance,
    chargesTotal = "170.00",
    days =
        listOf(
            StatementDayResponse(
                balanceAtEnd = "140.00",
                businessDate = "2026-11-18",
                charges = "170.00",
                lines = lines,
                payments = "30.00",
            ),
        ),
    folio =
        StatementFolioResponse(
            id = FOLIO_ID,
            number = "F-0001",
            openedOn = "2026-11-18",
            owner = FolioOwnerDto(Option.Some(OWNER_ID), FolioOwnerDto.Type.RESERVATION),
            status = StatementFolioResponse.Status.OPEN,
        ),
    generatedAt = "2026-11-18T14:00:00Z",
    paymentsTotal = "20.00",
    preAuthorizations = emptyList(),
)

/** Servidor de mentira das contas: as respostas são lambdas trocáveis, e cada chamada é registrada. */
@Suppress("TooManyFunctions") // falso da porta, que espelha as operações dela
class FakeFolioSource : FolioSource {
    var pages: suspend (String?) -> Page<FolioResponse> = { Page(listOf(folioOf()), null) }
    var byReservation: suspend (String) -> List<FolioResponse> = { listOf(folioOf()) }
    var folio: suspend (String) -> FolioResponse = { folioOf() }
    var postings: suspend (String) -> List<PostingResponse> = { emptyList() }
    var statement: suspend (StatementAudience) -> FolioStatementResponse = { statementOf() }
    var pdf: suspend (StatementAudience) -> PdfDocument = { PdfDocument(FOLIO_PDF, "extrato-F-0001.pdf") }

    val pageCalls = mutableListOf<Pair<String?, Int>>()
    val reservationCalls = mutableListOf<String>()
    val folioCalls = mutableListOf<String>()
    val postingCalls = mutableListOf<String>()
    val statementCalls = mutableListOf<Pair<String, StatementAudience>>()
    val pdfCalls = mutableListOf<Pair<String, StatementAudience>>()

    override suspend fun openFolioPage(
        cursor: String?,
        limit: Int,
    ): Page<FolioResponse> {
        pageCalls += cursor to limit
        return pages(cursor)
    }

    override suspend fun reservationFolios(reservationId: String): List<FolioResponse> {
        reservationCalls += reservationId
        return byReservation(reservationId)
    }

    override suspend fun folio(folioId: String): FolioResponse {
        folioCalls += folioId
        return folio.invoke(folioId)
    }

    override suspend fun postings(folioId: String): List<PostingResponse> {
        postingCalls += folioId
        return postings.invoke(folioId)
    }

    override suspend fun transactionCodes(): List<TransactionCodeResponse> = emptyList()

    override suspend fun post(
        folioId: String,
        input: PostingInput,
        intent: Intent,
    ): PostingResponse = error("não usado nesta tarefa")

    override fun reverse(
        folioId: String,
        postingId: String,
        reason: String,
        intent: Intent,
    ): ApprovableAction<PostingResponse> = error("não usado nesta tarefa")

    override suspend fun statement(
        folioId: String,
        audience: StatementAudience,
    ): FolioStatementResponse {
        statementCalls += folioId to audience
        return statement.invoke(audience)
    }

    override suspend fun statementPdf(
        folioId: String,
        audience: StatementAudience,
    ): PdfDocument {
        pdfCalls += folioId to audience
        return pdf(audience)
    }
}

/** O portador com as duas fábricas de PDF desligadas; a aprovação nunca é chamada nestes testes. */
fun wiringOf(
    source: FolioSource,
    permissions: Set<String> = setOf("billing.folios.read"),
) = FolioWiring(source, permissions, { error("não usado nesta tarefa") }, null, null)
