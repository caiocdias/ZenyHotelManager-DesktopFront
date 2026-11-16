package com.zeny.desktop.seed.frontdesk

import com.zeny.desktop.core.error.ApiError
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

/** Ataque da Task 2 (E06): idempotência em estados parciais, falha no meio, ausências e vazamento. */
class TraceSeederAtaqueTest {
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

    private fun plant(vararg slots: String) =
        slots.forEach {
            val external = data.reservations.first { r -> r.slot == it }.externalId
            bookings.reservations[external] = ReservationRef("res-$it", "CONFIRMED", null)
        }

    /** Registra toda consulta e criação, na ordem, e delega ao falso. */
    private class Spy(
        val inner: FakeTraces,
    ) : TraceGateway {
        val events = mutableListOf<String>()
        val queries = mutableListOf<Pair<String?, String?>>()

        override suspend fun departmentIdsByKey() = inner.departmentIdsByKey().also { events += "departments" }

        override suspend fun traces(
            reservationId: String?,
            roomId: String?,
        ): List<TraceRef> {
            queries += reservationId to roomId
            events += "list"
            return inner.traces(reservationId, roomId)
        }

        override suspend fun create(
            departmentId: String,
            dueOn: LocalDate,
            text: String,
            reservationId: String?,
            roomId: String?,
        ): String {
            events += "create"
            return inner.create(departmentId, dueOn, text, reservationId, roomId)
        }
    }

    @Test
    fun neverListsWithoutALink() =
        runTest {
            plant("H1", "A1")
            val spy = Spy(traces)
            TraceSeeder(spy, bookings, report).seed(config, date)
            assertEquals(3, spy.queries.size)
            assertTrue(spy.queries.all { (r, u) -> (r == null) != (u == null) }, spy.queries.toString())
        }

    @Test
    fun resolvedTraceOfSameLinkAndDepartmentCounts() =
        runTest {
            plant("H1", "A1")
            // o falso não distingue situação: um recado resolvido é só mais um recado do vínculo
            traces.create("dep-reception", date.minusDays(9), "já resolvido", "res-A1", null)
            val before = traces.createCalls
            TraceSeeder(traces, bookings, report).seed(config, date)
            assertEquals(2, traces.createCalls - before)
            assertEquals(1, report.keptCount(SeedItem.TRACE))
        }

    @Test
    fun roomTraceDoesNotCountForReservationOfSameDepartment() =
        runTest {
            plant("H1", "A1")
            // governança na UH 101 não satisfaz nada de reserva, e uma de reserva não satisfaz a UH 101
            traces.create("dep-housekeeping", date, "g", "res-H1", null)
            TraceSeeder(traces, bookings, report).seed(config, date)
            assertEquals(3, report.createdCount(SeedItem.TRACE))
            assertTrue(traces.traces.any { it.departmentId == "dep-housekeeping" && it.roomId == "room-101" })
        }

    @Test
    fun traceOnOtherRoomDoesNotCount() =
        runTest {
            plant("H1", "A1")
            traces.create("dep-housekeeping", date, "g", null, "room-102")
            TraceSeeder(traces, bookings, report).seed(config, date)
            assertEquals(3, report.createdCount(SeedItem.TRACE))
        }

    @Test
    fun failureInTheMiddleSurfacesAndNextRunCompletesWithoutDuplicates() =
        runTest {
            plant("H1", "A1")
            val failing = FakeTraces()
            val gateway = FailingOnSecondCreate(failing)
            assertFailsWith<ApiError.Network> { TraceSeeder(gateway, bookings, report).seed(config, date) }
            assertEquals(1, failing.traces.size)
            assertEquals(2, gateway.createCalls, "sem retry: uma criação que passou e uma que falhou")
            assertEquals(1, report.createdCount(SeedItem.TRACE))
            val again = SeedReport()
            TraceSeeder(failing, bookings, again).seed(config, date)
            assertEquals(3, failing.traces.size)
            assertEquals(1, again.keptCount(SeedItem.TRACE))
            assertEquals(2, again.createdCount(SeedItem.TRACE))
            assertEquals(
                3,
                failing.traces
                    .map { it.departmentId }
                    .toSet()
                    .size,
            )
        }

    private class FailingOnSecondCreate(
        val inner: FakeTraces,
    ) : TraceGateway by inner {
        var createCalls = 0

        override suspend fun create(
            departmentId: String,
            dueOn: LocalDate,
            text: String,
            reservationId: String?,
            roomId: String?,
        ): String {
            createCalls++
            if (createCalls == 2) throw ApiError.Network(RuntimeException("queda"))
            return inner.create(departmentId, dueOn, text, reservationId, roomId)
        }
    }

    @Test
    fun missingEachPieceCreatesNothingAndListsNothingWrong() =
        runTest {
            // H1 ausente
            plant("A1")
            val spyA = Spy(traces)
            assertFailsWith<SeedFailure> { TraceSeeder(spyA, bookings, report).seed(config, date) }
            assertFalse("create" in spyA.events, spyA.events.toString())
            assertEquals(0, report.createdCount(SeedItem.TRACE) + report.keptCount(SeedItem.TRACE))
        }

    @Test
    fun missingA1CreatesNothing() =
        runTest {
            plant("H1")
            val failure = assertFailsWith<SeedFailure> { TraceSeeder(traces, bookings, report).seed(config, date) }
            assertTrue(failure.message.orEmpty().contains("A1"), failure.message)
            assertEquals(0, traces.createCalls)
        }

    @Test
    fun missingDepartmentCreatesNothingEvenWhenOthersExist() =
        runTest {
            plant("H1", "A1")
            // governança é a primeira da lista; falta a última (Recepção)
            val gateway = FakeTraces(mapOf("HOUSEKEEPING" to "dep-h", "MAINTENANCE" to "dep-m"))
            val failure = assertFailsWith<SeedFailure> { TraceSeeder(gateway, bookings, report).seed(config, date) }
            assertTrue(failure.message.orEmpty().contains("RECEPTION"), failure.message)
            assertEquals(0, gateway.createCalls)
            assertEquals(0, gateway.traces.size)
        }

    @Test
    fun missingRoomWithEarlierTracesAlreadyPresentStillFailsBeforeCreating() =
        runTest {
            plant("H1", "A1")
            traces.create("dep-maintenance", date, "m", "res-H1", null)
            val before = traces.createCalls
            assertFailsWith<SeedFailure> {
                TraceSeeder(traces, bookings, report).seed(config.copy(roomIds = emptyMap()), date)
            }
            assertEquals(before, traces.createCalls)
        }

    @Test
    fun failureMessagesCarryNoSecretsOrPersonalData() =
        runTest {
            val messages = mutableListOf<String>()
            plant("A1")
            messages +=
                assertFailsWith<SeedFailure> { TraceSeeder(traces, bookings, SeedReport()).seed(config, date) }
                    .message
                    .orEmpty()
            val names = data.guests.map { it.fullName.substringAfterLast(" ") }
            val cpfs = data.guests.map { it.cpf }
            messages.forEach { m ->
                assertTrue(names.none { m.contains(it, ignoreCase = true) }, m)
                assertTrue(cpfs.none { m.contains(it) }, m)
                assertFalse(m.contains("DEMO-SEED"), m)
                assertFalse(Regex("""\d{6,}""").containsMatchIn(m), m)
            }
        }

    @Test
    fun dueOnIsTheOperationalDateGiven() =
        runTest {
            plant("H1", "A1")
            val other = LocalDate.of(2030, 2, 28)
            TraceSeeder(traces, bookings, report).seed(config, other)
            assertTrue(traces.traces.all { it.dueOn == other })
        }

    @Test
    fun textsAreNonBlankDistinctAndEndWithPeriod() =
        runTest {
            plant("H1", "A1")
            TraceSeeder(traces, bookings, report).seed(config, date)
            val texts = traces.traces.map { it.text }
            assertEquals(3, texts.toSet().size)
            assertTrue(texts.all { it.isNotBlank() && it.endsWith(".") && it.length <= 500 }, texts.toString())
        }
}
