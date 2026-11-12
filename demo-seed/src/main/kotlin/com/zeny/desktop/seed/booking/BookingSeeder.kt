package com.zeny.desktop.seed.booking

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoData
import com.zeny.desktop.seed.data.ReservationSpec
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Espera crescente (500 ms, 1 s, 2 s, até 5 s) medida pelo que foi dormido, para valer em tempo virtual. */
private class Backoff(
    private val limit: Duration,
) {
    private var next = FIRST_WAIT
    private var waited = Duration.ZERO

    /** Dorme o próximo intervalo; falha com a mensagem do operador quando o limite já foi gasto. */
    suspend fun pause() {
        if (waited >= limit) throw SeedFailure(INVENTORY_TIMEOUT_MESSAGE)
        delay(next)
        waited += next
        next = minOf(next * 2, MAX_WAIT)
    }

    private companion object {
        val FIRST_WAIT = 500.milliseconds
        val MAX_WAIT = 5.seconds
    }
}

private const val INVENTORY_TIMEOUT_MESSAGE =
    "O servidor não refez o inventário de UHs a tempo; rode o demo-seed de novo em alguns segundos."
private val RETRIABLE_CODES = setOf("ROOM_TYPE_UNAVAILABLE", "INVENTORY_DATE_OUT_OF_HORIZON")

/**
 * Semeia empresas, hóspedes e reservas do dia conferindo antes de criar (por CNPJ, CPF e `externalId`),
 * para que repetir a execução não crie nada e uma execução interrompida seja retomada.
 */
@Suppress("TooManyFunctions") // uma função por etapa do roteiro de semeadura, na ordem em que roda
class BookingSeeder(
    private val gateway: BookingGateway,
    private val report: SeedReport,
    private val inventoryTimeout: Duration = 60.seconds,
) {
    suspend fun seed(
        data: DemoData,
        config: SeededConfig,
    ) {
        val companyIds = seedCompanies(data)
        val guestIds = seedGuests(data, companyIds)
        val existing = data.reservations.associateWith { gateway.findReservation(it.externalId) }
        if (existing.values.any { it == null }) {
            awaitInventory(config, existing.filterValues { it == null }.keys)
        }
        for ((spec, found) in existing) {
            val holderId = guestIds.getValue(spec.holderGuestKey)
            val ref = found ?: create(spec, holderId, config)
            settle(spec, ref, holderId, config, created = found == null)
        }
    }

    private suspend fun seedCompanies(data: DemoData): Map<String, String> =
        data.companies.associate { spec ->
            val existing = gateway.companyId(spec.cnpj)
            if (existing != null) {
                report.kept(SeedItem.COMPANY)
                spec.cnpj to existing
            } else {
                report.created(SeedItem.COMPANY)
                spec.cnpj to gateway.createCompany(spec)
            }
        }

    private suspend fun seedGuests(
        data: DemoData,
        companyIds: Map<String, String>,
    ): Map<String, String> =
        data.guests.associate { spec ->
            val existing = gateway.guestId(spec.cpf)
            if (existing != null) {
                report.kept(SeedItem.GUEST)
                spec.key to existing
            } else {
                val id = gateway.createGuest(spec, spec.companyCnpj?.let { companyIds.getValue(it) })
                report.created(SeedItem.GUEST)
                spec.key to id
            }
        }

    /**
     * Espera o servidor refazer o inventário dos tipos a reservar: uma linha por data, com `total` de ao menos
     * as UHs ativas e, na data operacional (de onde o bloqueio OOO parte), `outOfOrder` de ao menos as UHs em OOO.
     */
    private suspend fun awaitInventory(
        config: SeededConfig,
        pending: Set<ReservationSpec>,
    ) {
        val backoff = Backoff(inventoryTimeout)
        for ((code, specs) in pending.groupBy { it.roomTypeCode.uppercase() }) {
            val from = specs.minOf { it.arrival }
            val to = specs.maxOf { maxOf(it.arrival, it.departure.minusDays(1)) }
            val typeId = config.roomTypeIds.getValue(code)
            while (!inventoryReady(typeId, code, from, to, config)) backoff.pause()
        }
    }

    private suspend fun inventoryReady(
        typeId: String,
        code: String,
        from: LocalDate,
        to: LocalDate,
        config: SeededConfig,
    ): Boolean {
        val rows = gateway.inventory(typeId, from, to).associateBy { it.date }
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        val active = config.activeRoomsByType[code] ?: 0
        val blocked = config.outOfOrderRoomsByType[code] ?: 0
        return days.all { day ->
            val row = rows[day]
            row != null && row.total >= active && (day != from || row.outOfOrder >= blocked)
        }
    }

    private suspend fun create(
        spec: ReservationSpec,
        holderId: String,
        config: SeededConfig,
    ): ReservationRef {
        val backoff = Backoff(inventoryTimeout)
        val typeId = config.roomTypeIds.getValue(spec.roomTypeCode.uppercase())
        while (true) {
            try {
                return gateway.createReservation(spec, holderId, typeId, config.ratePlanId)
            } catch (e: ApiError.Problem) {
                if (e.code !in RETRIABLE_CODES) throw e
                backoff.pause()
            }
        }
    }

    private suspend fun settle(
        spec: ReservationSpec,
        found: ReservationRef,
        holderId: String,
        config: SeededConfig,
        created: Boolean,
    ) {
        if (created) report.created(SeedItem.RESERVATION) else report.kept(SeedItem.RESERVATION)
        if (found.status != CONFIRMED) return
        val roomId = spec.roomNumber?.let { config.roomIds.getValue(it) } ?: return
        if (found.roomId != roomId) {
            gateway.assignRoom(found.id, roomId)
            report.created(SeedItem.ROOM_ASSIGNMENT)
        } else {
            report.kept(SeedItem.ROOM_ASSIGNMENT)
        }
        if (spec.checkIn) {
            gateway.checkIn(found.id, roomId, listOf(holderId))
            report.created(SeedItem.CHECK_IN)
        }
    }

    private companion object {
        const val CONFIRMED = "CONFIRMED"
    }
}
