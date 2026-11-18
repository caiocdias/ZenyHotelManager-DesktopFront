package com.zeny.desktop.app.reservations

import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.AvailabilityResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import kotlinx.coroutines.CompletableDeferred

/** Uma chamada de `availability` registrada pelo falso. */
data class AvailabilityCall(
    val from: BusinessDate,
    val to: BusinessDate,
)

/** Uma chamada de `quote` registrada pelo falso. */
data class QuoteCall(
    val arrival: BusinessDate,
    val departure: BusinessDate,
    val adults: Int,
    val childAges: List<Int>,
)

/**
 * Servidor de mentira para a disponibilidade e a cotação.
 *
 * - [businessDay], [availability] e [quote] são programáveis e podem ser trocados entre chamadas; cada chamada
 *   devolve o valor do momento. [availability] e [quote] começam vazios.
 * - Cada chamada fica registrada ([businessDayCalls], [availabilityCalls], [quoteCalls]).
 * - [hold] prende a próxima resposta de uma operação até o teste completar o portão devolvido; cada operação tem o
 *   seu portão. [failures] programa o erro por operação (lançado depois do portão, se houver).
 */
class FakeAvailabilitySource : AvailabilitySource {
    /** As operações da porta que o falso segura ou falha por conta própria. */
    enum class Op { BUSINESS_DAY, AVAILABILITY, QUOTE }

    var businessDay: BusinessDay = BusinessDay(BusinessDate.parse("2026-11-16"), null)
    var availability: AvailabilityResponse =
        AvailabilityResponse(from = "2026-11-16", roomTypes = emptyList(), to = "2026-11-22")
    var quote: QuoteResponse =
        QuoteResponse(
            adults = 2,
            arrival = "2026-11-20",
            channel = "DIRECT",
            childAges = emptyList(),
            departure = "2026-11-22",
            nights = 2,
            offers = emptyList(),
        )

    /** Erro programado por operação; vale para toda chamada dela até ser removido. */
    val failures = mutableMapOf<Op, Exception>()

    var businessDayCalls = 0
        private set
    val availabilityCalls = mutableListOf<AvailabilityCall>()
    val quoteCalls = mutableListOf<QuoteCall>()

    private val gates = mutableMapOf<Op, CompletableDeferred<Unit>>()

    /** Prende as próximas chamadas de [op] até o portão devolvido ser completado (o mesmo portão vale para todas). */
    fun hold(op: Op): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[op] = it }

    private suspend fun reached(op: Op) {
        gates[op]?.await()
        failures[op]?.let { throw it }
    }

    override suspend fun businessDay(): BusinessDay {
        businessDayCalls++
        reached(Op.BUSINESS_DAY)
        return businessDay
    }

    override suspend fun availability(
        from: BusinessDate,
        to: BusinessDate,
    ): AvailabilityResponse {
        availabilityCalls += AvailabilityCall(from, to)
        reached(Op.AVAILABILITY)
        return availability
    }

    override suspend fun quote(
        arrival: BusinessDate,
        departure: BusinessDate,
        adults: Int,
        childAges: List<Int>,
    ): QuoteResponse {
        quoteCalls += QuoteCall(arrival, departure, adults, childAges)
        reached(Op.QUOTE)
        return quote
    }
}
