package com.zeny.desktop.seed.frontdesk

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.booking.FakeBookingGateway
import com.zeny.desktop.seed.booking.ReservationRef
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceSeederTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val data = DemoCatalog.build(date)
    private val config =
        SeededConfig(
            roomTypeIds = mapOf("STD" to "rt-std", "LUX" to "rt-lux", "SUI" to "rt-sui"),
            roomIds = data.rooms.associate { it.number to "room-${it.number}" },
            ratePlanId = "plan-1",
            activeRoomsByType = emptyMap(),
            outOfOrderRoomsByType = emptyMap(),
        )
    private val bookings = FakeBookingGateway(config, date)
    private val traces = FakeTraces()
    private val report = SeedReport()

    private fun externalId(slot: String) = data.reservations.first { it.slot == slot }.externalId

    private fun plantReservations(vararg slots: String) =
        slots.forEach { bookings.reservations[externalId(it)] = ReservationRef("res-$it", "CONFIRMED", null) }

    private fun seeder(gateway: TraceGateway = traces) = TraceSeeder(gateway, bookings, report)

    private suspend fun seed(config: SeededConfig = this.config) = seeder().seed(config, date)

    @Test
    fun createsThreeTraces() =
        runTest {
            plantReservations("H1", "A1")
            seed()
            assertEquals(3, traces.traces.size)
            val byDepartment = traces.traces.associateBy { it.departmentId }
            val housekeeping = byDepartment.getValue("dep-housekeeping")
            assertEquals("room-101", housekeeping.roomId)
            assertEquals(null, housekeeping.reservationId)
            assertEquals("Trocar o enxoval extra da UH antes da chegada.", housekeeping.text)
            val maintenance = byDepartment.getValue("dep-maintenance")
            assertEquals("res-H1", maintenance.reservationId)
            assertEquals("Verificar o ar-condicionado, reclamação do hóspede.", maintenance.text)
            val reception = byDepartment.getValue("dep-reception")
            assertEquals("res-A1", reception.reservationId)
            assertEquals("Confirmar o horário de chegada por telefone.", reception.text)
            assertTrue(traces.traces.all { it.dueOn == date && it.roomId == null != (it.reservationId == null) })
        }

    @Test
    fun secondRunCreatesNothing() =
        runTest {
            plantReservations("H1", "A1")
            seed()
            val again = SeedReport()
            TraceSeeder(traces, bookings, again).seed(config, date)
            assertEquals(3, traces.traces.size)
            assertEquals(0, again.createdCount(SeedItem.TRACE))
            assertEquals(3, again.keptCount(SeedItem.TRACE))
        }

    @Test
    fun existingTraceWithOtherTextCounts() =
        runTest {
            plantReservations("H1", "A1")
            traces.create("dep-maintenance", date.minusDays(3), "Texto do operador", "res-H1", null)
            val before = traces.createCalls
            seed()
            assertEquals(2, traces.createCalls - before)
            assertEquals(1, report.keptCount(SeedItem.TRACE))
            assertEquals(2, report.createdCount(SeedItem.TRACE))
            assertEquals(1, traces.traces.count { it.reservationId == "res-H1" })
        }

    @Test
    fun partialStateCreatesOnlyMissing() =
        runTest {
            plantReservations("H1", "A1")
            traces.create("dep-housekeeping", date, "x", null, "room-101")
            traces.create("dep-reception", date, "y", "res-A1", null)
            seed()
            assertEquals(3, traces.traces.size)
            assertEquals("dep-maintenance", traces.traces.last().departmentId)
            assertEquals(2, report.keptCount(SeedItem.TRACE))
            assertEquals(1, report.createdCount(SeedItem.TRACE))
        }

    @Test
    fun otherDepartmentOnSameLinkDoesNotCount() =
        runTest {
            plantReservations("H1", "A1")
            traces.create("dep-reception", date, "z", "res-H1", null)
            seed()
            assertEquals(3, report.createdCount(SeedItem.TRACE))
            assertEquals(0, report.keptCount(SeedItem.TRACE))
        }

    @Test
    fun missingDepartmentFails() =
        runTest {
            plantReservations("H1", "A1")
            val gateway = FakeTraces(mapOf("RECEPTION" to "dep-reception", "HOUSEKEEPING" to "dep-housekeeping"))
            val failure = assertFailsWith<SeedFailure> { seeder(gateway).seed(config, date) }
            assertTrue(failure.message.orEmpty().contains("setor", ignoreCase = true), failure.message)
            assertEquals(0, gateway.createCalls)
        }

    @Test
    fun missingReservationFails() =
        runTest {
            plantReservations("A1")
            val failure = assertFailsWith<SeedFailure> { seed() }
            assertTrue(failure.message.orEmpty().contains("reserva", ignoreCase = true), failure.message)
            assertEquals(0, traces.createCalls)
        }

    @Test
    fun missingRoomFails() =
        runTest {
            plantReservations("H1", "A1")
            val failure = assertFailsWith<SeedFailure> { seed(config.copy(roomIds = config.roomIds - "101")) }
            assertTrue(failure.message.orEmpty().contains("UH"), failure.message)
            assertEquals(0, traces.createCalls)
        }

    @Test
    fun reportCountsTraces() =
        runTest {
            plantReservations("H1", "A1")
            seed()
            assertEquals(listOf("recados: 3 criados, 0 já existiam"), report.lines())
        }

    @Test
    fun textsHaveNoPersonalData() =
        runTest {
            plantReservations("H1", "A1")
            seed()
            val names = data.guests.map { it.fullName.substringAfterLast(" ") }
            traces.traces.forEach { t ->
                assertFalse(names.any { t.text.contains(it, ignoreCase = true) }, t.text)
                assertFalse(Regex("""\d{6,}""").containsMatchIn(t.text), t.text)
            }
        }
}
