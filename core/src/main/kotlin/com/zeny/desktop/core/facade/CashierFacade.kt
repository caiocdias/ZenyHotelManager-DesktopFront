package com.zeny.desktop.core.facade

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.client.BillingApi
import com.zeny.desktop.sdk.model.CashCountRequest
import com.zeny.desktop.sdk.model.CashMovementRequest
import com.zeny.desktop.sdk.model.CashMovementResponse
import com.zeny.desktop.sdk.model.CashPointResponse
import com.zeny.desktop.sdk.model.CashierShiftCloseRequest
import com.zeny.desktop.sdk.model.CashierShiftOpenRequest
import com.zeny.desktop.sdk.model.CashierShiftReportResponse
import com.zeny.desktop.sdk.model.CashierShiftResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

/** Maior página que o contrato aceita: os pontos de caixa vêm inteiros, com o mínimo de idas ao servidor. */
private const val CASH_POINTS_PAGE_SIZE = 200

private const val NOT_FOUND = 404
private const val SHIFT_NOT_FOUND = "CASHIER_SHIFT_NOT_FOUND"

/** O tipo do movimento de dinheiro do turno: sangria (sai) ou suprimento (entra). */
enum class CashMovementType { WITHDRAWAL, SUPPLY }

/** A forma de pagamento da contagem do fechamento. */
enum class PaymentMethod { CASH, DEBIT_CARD, CREDIT_CARD, PIX, BANK_TRANSFER, OTHER }

/** O valor contado de uma forma de pagamento; a forma omitida vale zero no servidor. */
data class CashCount(
    val method: PaymentMethod,
    val amount: Money,
)

/**
 * O turno de caixa do usuário: pontos ativos, turno corrente, abertura, sangria e suprimento, fechamento e relatório.
 * Abrir, movimentar e fechar levam `Idempotency-Key` pela `Intent`. O front não calcula esperado, total nem
 * diferença. Um `id` que não é UUID lança `IllegalArgumentException` antes de ir à rede, sem o valor na mensagem.
 */
class CashierFacade internal constructor(
    private val billing: BillingApi,
    private val executor: ApiExecutor,
) {
    /** Só os pontos ativos, todas as páginas, na ordem do servidor. */
    suspend fun cashPoints(): List<CashPointResponse> {
        val pager =
            CursorPager(CASH_POINTS_PAGE_SIZE) { cursor, limit ->
                val page = executor.execute { billing.listCashPoints(true, cursor, limit, it.headers) }.value
                Page(page.items, (page.nextCursor as? Option.Some)?.value?.takeIf { it.isNotBlank() })
            }
        val all = mutableListOf<CashPointResponse>()
        while (pager.hasMore) all += pager.next()
        return all
    }

    /** O turno aberto do usuário, ou `null` no `404 CASHIER_SHIFT_NOT_FOUND`; outra falha lança o `ApiError`. */
    suspend fun currentShift(): CashierShiftResponse? =
        try {
            executor.execute { billing.getCurrentCashierShift(it.headers) }.value
        } catch (error: ApiError.Problem) {
            if (error.status == NOT_FOUND && error.code == SHIFT_NOT_FOUND) null else throw error
        }

    suspend fun openShift(
        cashPointId: String,
        openingFloat: Money,
        intent: Intent,
    ): CashierShiftResponse {
        val request = CashierShiftOpenRequest(idOf(cashPointId, "ponto de caixa"), openingFloat.toApi())
        return executor
            .execute(intent) { billing.openCashierShift(it.requireIdempotencyKey(), request, it.headers) }
            .value
    }

    suspend fun recordMovement(
        shiftId: String,
        type: CashMovementType,
        amount: Money,
        reason: String,
        intent: Intent,
    ): CashMovementResponse {
        val id = idOf(shiftId, "turno")
        val request = CashMovementRequest(amount.toApi(), reason, CashMovementRequest.Type.fromSerial(type.name))
        return executor
            .execute(intent) { billing.recordCashMovement(id, it.requireIdempotencyKey(), request, it.headers) }
            .value
    }

    /** Fecha o turno com a contagem por forma; a forma que fica de fora não vai no corpo. */
    suspend fun closeShift(
        shiftId: String,
        counts: List<CashCount>,
        intent: Intent,
    ): CashierShiftReportResponse {
        val id = idOf(shiftId, "turno")
        val request =
            CashierShiftCloseRequest(
                counts.map { CashCountRequest(it.amount.toApi(), CashCountRequest.Method.fromSerial(it.method.name)) },
            )
        return executor
            .execute(intent) { billing.closeCashierShift(id, it.requireIdempotencyKey(), request, it.headers) }
            .value
    }

    suspend fun report(shiftId: String): CashierShiftReportResponse {
        val id = idOf(shiftId, "turno")
        return executor.execute { billing.getCashierShiftReport(id, it.headers) }.value
    }

    private fun idOf(
        id: String,
        what: String,
    ): Uuid = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de $what inválido")
}
