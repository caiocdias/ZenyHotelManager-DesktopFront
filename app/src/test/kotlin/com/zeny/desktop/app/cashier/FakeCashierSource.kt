package com.zeny.desktop.app.cashier

import com.zeny.desktop.core.facade.CashCount
import com.zeny.desktop.core.facade.CashMovementType
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.model.CashMovementResponse
import com.zeny.desktop.sdk.model.CashPointResponse
import com.zeny.desktop.sdk.model.CashierShiftReportResponse
import com.zeny.desktop.sdk.model.CashierShiftResponse
import com.zeny.desktop.sdk.model.ExpectedAmountResponse
import de.quati.kotlin.util.Option
import kotlin.uuid.Uuid

const val FRONT_DESK_POINT = "40000000-0000-0000-0000-000000000001"
const val BAR_POINT = "40000000-0000-0000-0000-000000000002"
const val SHIFT_ID = "50000000-0000-0000-0000-000000000001"

fun cashPointOf(
    id: String = FRONT_DESK_POINT,
    name: String = "Recepção",
): CashPointResponse =
    CashPointResponse(
        active = true,
        blindClose = true,
        createdAt = "2026-11-01T10:00:00Z",
        id = Uuid.parse(id),
        name = name,
        updatedAt = "2026-11-01T10:00:00Z",
    )

fun movementOf(
    type: CashMovementResponse.Type = CashMovementResponse.Type.WITHDRAWAL,
    amount: String = "50.00",
    reason: String = "Depósito no cofre",
): CashMovementResponse =
    CashMovementResponse(
        amount = amount,
        businessDate = "2026-11-18",
        id = Uuid.random(),
        reason = reason,
        recordedAt = "2026-11-18T12:00:00Z",
        recordedBy = Uuid.random(),
        shiftId = Uuid.parse(SHIFT_ID),
        type = type,
    )

fun expectedOf(
    method: String = "CASH",
    expected: String = "150.00",
): ExpectedAmountResponse =
    ExpectedAmountResponse(
        expected = expected,
        method = method,
        openingFloat = "100.00",
        payments = "100.00",
        supplies = "0.00",
        withdrawals = "50.00",
    )

@Suppress("LongParameterList") // os campos do turno que os testes variam
fun shiftOf(
    id: String = SHIFT_ID,
    number: String = "CX-0001",
    cashPointId: String = FRONT_DESK_POINT,
    cashPointName: String = "Recepção",
    openingFloat: String = "100.00",
    movements: List<CashMovementResponse> = emptyList(),
    expectedVisible: Boolean = false,
    expected: List<ExpectedAmountResponse>? = null,
    expectedTotal: String? = null,
): CashierShiftResponse =
    CashierShiftResponse(
        blindClose = !expectedVisible,
        cashPointId = Uuid.parse(cashPointId),
        cashPointName = cashPointName,
        expected = if (expected == null) Option.Undefined else Option.Some(expected),
        expectedTotal = if (expectedTotal == null) Option.Undefined else Option.Some(expectedTotal),
        expectedVisible = expectedVisible,
        id = Uuid.parse(id),
        movements = movements,
        number = number,
        openedAt = "2026-11-18T11:00:00Z",
        openedOn = "2026-11-18",
        openingFloat = openingFloat,
        operatorId = Uuid.random(),
        status = CashierShiftResponse.Status.OPEN,
    )

/** O que a abertura recebeu: o ponto, o fundo e a chave da intenção. */
data class OpenCall(
    val cashPointId: String,
    val openingFloat: Money,
    val key: String,
)

/** Porta falsa do caixa: as respostas são trocáveis e as chamadas ficam contadas. */
class FakeCashierSource : CashierSource {
    var points: suspend () -> List<CashPointResponse> = {
        listOf(cashPointOf(), cashPointOf(BAR_POINT, "Bar"))
    }
    var current: suspend () -> CashierShiftResponse? = { null }
    var opener: suspend (String, Money) -> CashierShiftResponse = { id, _ ->
        shiftOf(cashPointId = id, cashPointName = if (id == BAR_POINT) "Bar" else "Recepção")
    }
    var cashPointsCalls = 0
    var currentCalls = 0
    val openCalls = mutableListOf<OpenCall>()

    override suspend fun cashPoints(): List<CashPointResponse> {
        cashPointsCalls++
        return points()
    }

    override suspend fun currentShift(): CashierShiftResponse? {
        currentCalls++
        return current()
    }

    override suspend fun openShift(
        cashPointId: String,
        openingFloat: Money,
        intent: Intent,
    ): CashierShiftResponse {
        openCalls += OpenCall(cashPointId, openingFloat, intent.key)
        return opener(cashPointId, openingFloat)
    }

    override suspend fun recordMovement(
        shiftId: String,
        type: CashMovementType,
        amount: Money,
        reason: String,
        intent: Intent,
    ): CashMovementResponse = error("não usado")

    override suspend fun closeShift(
        shiftId: String,
        counts: List<CashCount>,
        intent: Intent,
    ): CashierShiftReportResponse = error("não usado")

    override suspend fun report(shiftId: String): CashierShiftReportResponse = error("não usado")
}
