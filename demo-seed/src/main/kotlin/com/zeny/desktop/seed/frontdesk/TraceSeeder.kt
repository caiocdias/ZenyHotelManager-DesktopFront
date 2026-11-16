package com.zeny.desktop.seed.frontdesk

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.booking.BookingGateway
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import java.time.LocalDate

/** O recado de exemplo: setor (chave), texto e a que ele se liga (slot da reserva ou número da UH). */
private data class TraceSpec(
    val departmentKey: String,
    val text: String,
    val reservationSlot: String? = null,
    val roomNumber: String? = null,
)

private val SPECS =
    listOf(
        TraceSpec("HOUSEKEEPING", "Trocar o enxoval extra da UH antes da chegada.", roomNumber = "101"),
        TraceSpec("MAINTENANCE", "Verificar o ar-condicionado, reclamação do hóspede.", reservationSlot = "H1"),
        TraceSpec("RECEPTION", "Confirmar o horário de chegada por telefone.", reservationSlot = "A1"),
    )

/**
 * Semeia os recados de exemplo. Um recado já existe quando há qualquer recado com o mesmo vínculo e o mesmo setor,
 * seja qual for o texto ou a situação; só o que falta é criado. Tudo é resolvido antes da primeira criação.
 */
class TraceSeeder(
    private val traces: TraceGateway,
    private val bookings: BookingGateway,
    private val report: SeedReport,
) {
    private class Resolved(
        val departmentId: String,
        val text: String,
        val reservationId: String?,
        val roomId: String?,
    )

    suspend fun seed(
        config: SeededConfig,
        dueOn: LocalDate,
    ) {
        val departments = traces.departmentIdsByKey()
        val resolved = SPECS.map { resolve(it, departments, config) }
        for (trace in resolved) {
            val existing = traces.traces(trace.reservationId, trace.roomId)
            if (existing.any { it.departmentId == trace.departmentId }) {
                report.kept(SeedItem.TRACE)
            } else {
                traces.create(trace.departmentId, dueOn, trace.text, trace.reservationId, trace.roomId)
                report.created(SeedItem.TRACE)
            }
        }
    }

    private suspend fun resolve(
        spec: TraceSpec,
        departments: Map<String, String>,
        config: SeededConfig,
    ): Resolved {
        val departmentId = departments[spec.departmentKey] ?: missing("Setor ${spec.departmentKey}", "ele")
        return Resolved(departmentId, spec.text, reservationId(spec), roomId(spec, config))
    }

    private suspend fun reservationId(spec: TraceSpec): String? =
        spec.reservationSlot?.let { slot ->
            bookings.findReservation("DEMO-SEED-$slot")?.id ?: missing("Reserva de exemplo $slot", "ela")
        }

    private fun roomId(
        spec: TraceSpec,
        config: SeededConfig,
    ): String? = spec.roomNumber?.let { number -> config.roomIds[number] ?: missing("UH $number", "ela") }

    private fun missing(
        what: String,
        pronoun: String,
    ): Nothing = throw SeedFailure("$what não encontrado(a); o demo-seed não cria recados sem $pronoun.")
}
