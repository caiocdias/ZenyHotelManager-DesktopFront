package com.zeny.desktop.app.reservations

import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.DepositShortfallResponse
import com.zeny.desktop.sdk.model.PendingGuaranteeResponse
import com.zeny.desktop.sdk.model.ReallocationRequiredResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid

/** Uma garantia pendente fictícia; os opcionais chegam como o servidor os manda (`Option.Some(valor)`) ou ausentes. */
@Suppress("LongParameterList") // fixture: cada campo que os testes variam, com padrão
fun pendingGuaranteeOf(
    code: String = "R-1",
    holderGuestId: String = Uuid.random().toString(),
    kind: String = "PROVISIONAL",
    guaranteeType: String = "CARD",
    dueAt: String = "2026-11-15T17:00:00Z",
    overdue: Boolean = false,
    requiredDeposit: String? = null,
    depositReceived: String? = null,
) = PendingGuaranteeResponse(
    arrival = "2026-11-20",
    bookingId = Uuid.random(),
    code = code,
    departure = "2026-11-22",
    depositReceived = if (depositReceived == null) Option.Undefined else Option.Some(depositReceived),
    dueAt = dueAt,
    folioId = Uuid.random(),
    guaranteePolicyId = Uuid.random(),
    guaranteeType = guaranteeType,
    holderGuestId = Uuid.parse(holderGuestId),
    id = Uuid.random(),
    kind = kind,
    overdue = overdue,
    ratePlanId = Uuid.random(),
    requiredDeposit = if (requiredDeposit == null) Option.Undefined else Option.Some(requiredDeposit),
    roomTypeId = Uuid.random(),
    status = "PROVISIONAL",
    total = "600.00",
)

/** Um depósito em falta fictício; os três valores vêm como o servidor os manda, fechem a conta ou não. */
@Suppress("LongParameterList") // fixture: cada campo que os testes variam, com padrão
fun depositShortfallOf(
    code: String = "R-1",
    holderGuestId: String = Uuid.random().toString(),
    requiredDeposit: String = "300.00",
    depositReceived: String = "100.00",
    missing: String = "200.00",
    guaranteedAt: String = "2026-11-10T15:30:00Z",
) = DepositShortfallResponse(
    depositReceived = depositReceived,
    guaranteedAt = guaranteedAt,
    missing = missing,
    requiredDeposit = requiredDeposit,
    reservation = searchItemOf(code = code, holderGuestId = holderGuestId),
)

/** Uma realocação necessária fictícia; sem [roomNumber], o número da UH chega ausente. */
fun reallocationOf(
    code: String = "R-1",
    roomNumber: String? = "208",
    arrival: String = "2026-11-13",
) = Uuid.random().let { reservationId ->
    ReallocationRequiredResponse(
        arrival = arrival,
        assignment =
            RoomAssignmentResponse(
                active = true,
                assignedAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                doNotMove = false,
                endsOn = "2026-11-15",
                id = Uuid.random(),
                reason = "INITIAL",
                reservationId = reservationId,
                roomId = Uuid.random(),
                roomNumber = if (roomNumber == null) Option.Undefined else Option.Some(roomNumber),
                startsOn = arrival,
            ),
        departure = "2026-11-15",
        reservationCode = code,
        reservationId = reservationId,
        status = "CONFIRMED",
    )
}

/** Uma chamada de `pendingGuarantees` registrada pelo falso. */
data class GuaranteeCall(
    val to: Instant,
    val cursor: String?,
    val limit: Int,
)

/** Uma chamada paginada registrada pelo falso. */
data class PageCall(
    val cursor: String?,
    val limit: Int,
)

/**
 * Servidor de mentira para as pendências de reservas.
 *
 * - As quatro listas ([guarantees], [deposits], [noShows], [reallocations]) são a base em memória, na ordem em que a
 *   página sai. A paginação é por índice: o cursor é numérico (o índice do próximo item) e o `limit` recebido vale.
 * - Um titular sem resumo em [holders] nem erro em [holderFailures] é erro do teste, não `404`.
 * - Cada chamada fica registrada. [hold] prende a próxima resposta de uma operação até o teste completar o portão;
 *   [failures] e [holderFailures] programam o erro (lançado depois do portão, se houver).
 */
class FakePendingSource : PendingSource {
    enum class Op { BUSINESS_DAY, GUARANTEES, DEPOSITS, NO_SHOWS, REALLOCATION, HOLDER }

    var businessDay: BusinessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
    val guarantees = mutableListOf<PendingGuaranteeResponse>()
    val deposits = mutableListOf<DepositShortfallResponse>()
    val noShows = mutableListOf<ReservationListItemResponse>()
    val reallocations = mutableListOf<ReallocationRequiredResponse>()
    val holders = mutableMapOf<String, HolderSummary>()

    val failures = mutableMapOf<Op, Exception>()
    val holderFailures = mutableMapOf<String, Exception>()

    val guaranteeCalls = mutableListOf<GuaranteeCall>()
    val noShowCalls = mutableListOf<PageCall>()
    val reallocationCalls = mutableListOf<PageCall>()
    val holderCalls = mutableListOf<String>()
    var depositCalls = 0
        private set
    var businessDayCalls = 0
        private set

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

    override suspend fun pendingGuarantees(
        to: Instant,
        cursor: String?,
        limit: Int,
    ): Page<PendingGuaranteeResponse> {
        require(limit > 0) { "limit inválido" }
        guaranteeCalls += GuaranteeCall(to, cursor, limit)
        reached(Op.GUARANTEES)
        return pageOf(guarantees, cursor, limit)
    }

    override suspend fun depositShortfalls(): List<DepositShortfallResponse> {
        depositCalls++
        reached(Op.DEPOSITS)
        return deposits.toList()
    }

    override suspend fun expectedNoShows(
        cursor: String?,
        limit: Int,
    ): Page<ReservationListItemResponse> {
        require(limit > 0) { "limit inválido" }
        noShowCalls += PageCall(cursor, limit)
        reached(Op.NO_SHOWS)
        return pageOf(noShows, cursor, limit)
    }

    override suspend fun reallocationRequired(
        cursor: String?,
        limit: Int,
    ): Page<ReallocationRequiredResponse> {
        require(limit > 0) { "limit inválido" }
        reallocationCalls += PageCall(cursor, limit)
        reached(Op.REALLOCATION)
        return pageOf(reallocations, cursor, limit)
    }

    override suspend fun holder(guestId: String): HolderSummary {
        holderCalls += guestId
        reached(Op.HOLDER)
        holderFailures[guestId]?.let { throw it }
        return holders[guestId] ?: error("holder sem resumo no fake: $guestId")
    }

    // Dado pessoal: nunca em `toString`.
    override fun toString(): String = "FakePendingSource(***)"

    private fun <I> pageOf(
        items: List<I>,
        cursor: String?,
        limit: Int,
    ): Page<I> {
        val start = cursor?.toInt() ?: 0
        val end = minOf(start + limit, items.size)
        return Page(items.subList(minOf(start, end), end), if (end < items.size) end.toString() else null)
    }
}
