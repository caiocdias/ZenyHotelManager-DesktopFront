package com.zeny.desktop.app.cashier

import com.zeny.desktop.core.facade.CashCount
import com.zeny.desktop.core.facade.CashMovementType
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.CashMovementResponse
import com.zeny.desktop.sdk.model.CashPointResponse
import com.zeny.desktop.sdk.model.CashierShiftReportResponse
import com.zeny.desktop.sdk.model.CashierShiftResponse

/**
 * O que o caixa usa de um servidor: os pontos de caixa ativos, o turno corrente do usuário (`null` sem turno), a
 * abertura, a sangria e o suprimento, o fechamento e o relatório. As escritas levam a `Intent` da tela.
 */
interface CashierSource {
    suspend fun cashPoints(): List<CashPointResponse>

    suspend fun currentShift(): CashierShiftResponse?

    suspend fun openShift(
        cashPointId: String,
        openingFloat: Money,
        intent: Intent,
    ): CashierShiftResponse

    suspend fun recordMovement(
        shiftId: String,
        type: CashMovementType,
        amount: Money,
        reason: String,
        intent: Intent,
    ): CashMovementResponse

    suspend fun closeShift(
        shiftId: String,
        counts: List<CashCount>,
        intent: Intent,
    ): CashierShiftReportResponse

    suspend fun report(shiftId: String): CashierShiftReportResponse
}
