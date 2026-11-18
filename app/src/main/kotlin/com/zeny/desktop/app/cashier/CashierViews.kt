package com.zeny.desktop.app.cashier

import com.zeny.desktop.sdk.model.CashierShiftReportResponse
import com.zeny.desktop.sdk.model.CashierShiftResponse
import de.quati.kotlin.util.Option

/** Um ponto de caixa ativo que a abertura pode usar. */
data class CashPointOption(
    val id: String,
    val name: String,
)

/** Uma sangria ou um suprimento do turno, com o valor do servidor em BRL. */
data class MovementLine(
    val type: String,
    val amount: String,
    val reason: String,
)

/** O esperado de uma forma de pagamento, como o servidor o mandou. */
data class ExpectedLine(
    val method: String,
    val amount: String,
)

/**
 * O turno corrente na tela: número, ponto, data de abertura, fundo e movimentos. O esperado ([expected],
 * [expectedTotal]) só vem quando o servidor o mostra (`expectedVisible`); no turno cego, vazio e nulo.
 */
data class ShiftView(
    val id: String,
    val number: String,
    val cashPointName: String,
    val openedOn: String,
    val openingFloat: String,
    val movements: List<MovementLine>,
    val expected: List<ExpectedLine>,
    val expectedTotal: String?,
)

/** A contagem de uma forma no relatório: esperado, declarado e diferença, em BRL, como o servidor os mandou. */
data class CountLine(
    val method: String,
    val expected: String,
    val declared: String,
    val difference: String,
)

/** O relatório de fechamento: a contagem por forma e os totais do servidor, sem nenhum cálculo do front. */
data class ReportView(
    val number: String,
    val cashPointName: String,
    val closedOn: String?,
    val counts: List<CountLine>,
    val expectedTotal: String?,
    val declaredTotal: String?,
    val differenceTotal: String?,
    val hasDifference: Boolean?,
)

internal fun shiftViewOf(response: CashierShiftResponse): ShiftView {
    val visible = response.expectedVisible
    val expected = response.expected.orNull().orEmpty()
    return ShiftView(
        id = response.id.toString(),
        number = response.number,
        cashPointName = response.cashPointName,
        openedOn = CashierText.date(response.openedOn),
        openingFloat = CashierText.money(response.openingFloat),
        movements =
            response.movements.map {
                MovementLine(CashierText.movementType(it.type), CashierText.money(it.amount), it.reason)
            },
        expected =
            if (visible) {
                expected.map { ExpectedLine(CashierText.method(it.method), CashierText.money(it.expected)) }
            } else {
                emptyList()
            },
        expectedTotal = if (visible) response.expectedTotal.orNull()?.let(CashierText::money) else null,
    )
}

internal fun reportViewOf(response: CashierShiftReportResponse): ReportView {
    val shift = response.shift
    return ReportView(
        number = shift.number,
        cashPointName = shift.cashPointName,
        closedOn = shift.closedOn.orNull()?.let(CashierText::date),
        counts =
            shift.counts.orNull().orEmpty().map {
                CountLine(
                    method = CashierText.method(it.method),
                    expected = CashierText.money(it.expected),
                    declared = CashierText.money(it.declared),
                    difference = CashierText.money(it.difference),
                )
            },
        expectedTotal = shift.expectedTotal.orNull()?.let(CashierText::money),
        declaredTotal = shift.declaredTotal.orNull()?.let(CashierText::money),
        differenceTotal = shift.differenceTotal.orNull()?.let(CashierText::money),
        hasDifference = shift.hasDifference.orNull(),
    )
}

private fun <T> Option<T?>.orNull(): T? = (this as? Option.Some)?.value
