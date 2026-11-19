package com.zeny.desktop.app.folio

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.SplitPart
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.PostingResponse
import com.zeny.desktop.sdk.model.TransferResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

/** Um ajuste pedido ao falso (a criação da ação): a conta, o lançamento, o valor, o motivo e a `Intent` da tela. */
data class AdjustCall(
    val folioId: String,
    val postingId: String,
    val amount: Money,
    val reason: String,
    val intent: Intent,
)

/** Uma remoção da taxa pedida ao falso: a conta, o lançamento da taxa e a `Intent` da tela. */
data class RemovalCall(
    val folioId: String,
    val postingId: String,
    val intent: Intent,
)

/** O lançamento com o restante [value] do servidor (nulo: ausente). */
fun PostingResponse.withRemaining(value: String?): PostingResponse =
    copy(remainingAmount = value?.let { Option.Some(it) } ?: Option.Undefined)

/** O lançamento que corrige o lançamento [id] (um `ADJUSTMENT` ou `REVERSAL`). */
fun PostingResponse.correcting(id: Uuid): PostingResponse = copy(correctsId = Option.Some(id))

/** Servidor de mentira das correções da conta: as respostas são trocáveis, e cada chamada é registrada. */
class FakeFolioCorrectionSource : FolioCorrectionSource {
    /** O ajuste: sem token e com [requiresApproval], falha; senão, o próximo de [adjustOutcomes] ou [adjusted]. */
    var adjusted: suspend () -> PostingResponse = {
        postingOf(description = "Ajuste", type = "ADJUSTMENT", amount = "-1.00")
    }
    var requiresApproval: ApiError.ApprovalRequired? = null
    val adjustOutcomes = ArrayDeque<Throwable?>()
    val adjustCalls = mutableListOf<AdjustCall>()

    /** A remoção da taxa: o próximo de [removalOutcomes] ou [removed]. */
    var removed: suspend () -> PostingResponse = {
        postingOf(description = "Taxa de serviço", type = "REVERSAL", amount = "-2.00")
            .copy(reason = Option.Some("Taxa de serviço removida a pedido do cliente."))
    }
    val removalOutcomes = ArrayDeque<Throwable?>()
    val removalCalls = mutableListOf<RemovalCall>()

    override fun adjust(
        folioId: String,
        postingId: String,
        amount: Money,
        reason: String,
        intent: Intent,
    ): ApprovableAction<PostingResponse> {
        adjustCalls += AdjustCall(folioId, postingId, amount, reason, intent)
        return ApprovableAction { token ->
            val approval = requiresApproval
            if (token == null && approval != null) throw approval
            adjustOutcomes.removeFirstOrNull()?.let { throw it }
            ApiResponse(adjusted(), null, 201)
        }
    }

    override suspend fun removeServiceCharge(
        folioId: String,
        postingId: String,
        intent: Intent,
    ): PostingResponse {
        removalCalls += RemovalCall(folioId, postingId, intent)
        removalOutcomes.removeFirstOrNull()?.let { throw it }
        return removed()
    }

    override suspend fun transfer(
        folioId: String,
        postingIds: List<String>,
        targetFolioId: String,
        reason: String?,
        intent: Intent,
    ): TransferResponse = error("não usado")

    override suspend fun split(
        folioId: String,
        postingId: String,
        parts: List<SplitPart>,
        reason: String?,
        intent: Intent,
    ): TransferResponse = error("não usado")
}

/** O portador com a porta das correções; sem [approve], a ação corre sem aprovação. */
fun correctionWiringOf(
    source: FolioSource,
    corrections: FolioCorrectionSource?,
    permissions: Set<String>,
    approve: suspend (ApprovableAction<PostingResponse>) -> ApiResponse<PostingResponse>? = { it.run(null) },
    payments: PaymentSource? = null,
) = FolioWiring(source, permissions, approve, null, null, payments = payments, corrections = corrections)
