package com.zeny.desktop.seed.booking

import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class BookingResumeAtaqueTest : BookingAtaqueBase() {
    // ---- retomada com falha injetada em cada escrita ----

    @Test
    fun failureInEveryWriteThenRerunConvergesWithoutDuplicates() {
        for ((call, total) in callTotals) {
            for (nth in 1..total) {
                for (after in listOf(false, true)) {
                    val label = "$call #$nth ${if (after) "resposta perdida" else "antes de gravar"}"
                    val f = FakeBookingGateway(config, date)
                    runTest {
                        val p = Probe(f) { currentTime }
                        p.fault = Fault(call, nth, after)
                        assertFailsWith<Boom>(label) { BookingSeeder(p, SeedReport()).seed(data, config) }
                        p.fault = null
                        val rerun = SeedReport()
                        BookingSeeder(p, rerun).seed(data, config)
                        assertEquals(2, f.companies.size, label)
                        assertEquals(12, f.guests.size, label)
                        assertEquals(11, f.reservations.size, label)
                        assertEquals(9, f.assignments, label)
                        assertEquals(7, f.checkIns.size, label)
                        assertEquals(7, f.reservations.values.count { it.status == "IN_HOUSE" }, label)
                        val third = p.events.size
                        BookingSeeder(p, SeedReport()).seed(data, config)
                        assertTrue(
                            p.events.drop(third).none {
                                it.substringBefore(':') in
                                    setOf(
                                        "createCompany",
                                        "createGuest",
                                        "createReservation",
                                        "assignRoom",
                                        "checkIn",
                                    )
                            },
                            "terceira execução escreveu ($label)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun reportDoesNotCountCompanyThatFailedToCreate() =
        runTest {
            val p = probe()
            p.fault = Fault("createCompany", 1, after = false)
            assertFailsWith<Boom> { BookingSeeder(p, report).seed(data, config) }
            assertEquals(0, fake.companies.size)
            assertEquals(0, report.createdCount(SeedItem.COMPANY), "o relatório contou empresa que não foi criada")
        }

    @Test
    fun reportCountsMatchWhatWasWrittenWhenAWriteFails() {
        for ((call, item) in listOf(
            "createGuest" to SeedItem.GUEST,
            "createReservation" to SeedItem.RESERVATION,
            "assignRoom" to SeedItem.ROOM_ASSIGNMENT,
            "checkIn" to SeedItem.CHECK_IN,
        )) {
            val f = FakeBookingGateway(config, date)
            runTest {
                val p = Probe(f) { currentTime }
                p.fault = Fault(call, 2, after = false)
                val r = SeedReport()
                assertFailsWith<Boom> { BookingSeeder(p, r).seed(data, config) }
                val actual =
                    when (item) {
                        SeedItem.GUEST -> f.guests.size
                        SeedItem.RESERVATION -> f.reservations.size
                        SeedItem.ROOM_ASSIGNMENT -> f.assignments
                        else -> f.checkIns.size
                    }
                assertEquals(actual, r.createdCount(item), call)
            }
        }
    }

    @Test
    fun resumesFromCompaniesOnly() =
        runTest {
            val p = probe()
            data.companies.forEach { fake.createCompany(it) }
            BookingSeeder(p, report).seed(data, config)
            assertFullState()
            assertEquals(2, report.keptCount(SeedItem.COMPANY))
            assertEquals(0, report.createdCount(SeedItem.COMPANY))
            assertEquals(0, p.events.count { it == "createCompany" })
        }

    @Test
    fun resumesFromGuestsWithoutReservations() =
        runTest {
            val p = probe()
            BookingSeeder(p, SeedReport()).seed(data, config)
            fake.reservations.clear()
            fake.checkIns.clear()
            val before = p.events.size
            val second = SeedReport()
            BookingSeeder(p, second).seed(data, config)
            val newEvents = p.events.drop(before)
            assertEquals(0, newEvents.count { it == "createCompany" || it == "createGuest" })
            assertEquals(11, second.createdCount(SeedItem.RESERVATION))
            assertEquals(12, second.keptCount(SeedItem.GUEST))
            assertTrue(newEvents.any { it.startsWith("inventory:") })
        }

    @Test
    fun resumesFromReservationWithoutAllocation() =
        runTest {
            val p = probe()
            BookingSeeder(p, SeedReport()).seed(data, config)
            for (slot in listOf("A1", "A2", "H1", "H3")) {
                fake.reservations[spec(slot).externalId] = ref(slot).copy(status = "CONFIRMED", roomId = null)
            }
            fake.checkIns.clear()
            val before = p.events.size
            BookingSeeder(p, SeedReport()).seed(data, config)
            val newEvents = p.events.drop(before)
            assertEquals(4, newEvents.count { it.startsWith("assignRoom:") })
            assertEquals(2, newEvents.count { it.startsWith("checkIn:") })
            assertEquals("room-101", ref("A1").roomId)
            assertEquals("CONFIRMED", ref("A1").status)
            assertEquals("IN_HOUSE", ref("H3").status)
            assertEquals(0, newEvents.count { it.startsWith("inventory:") })
        }

    @Test
    fun resumesAllocatedWithoutCheckInAndAssignsOnlyOnce() =
        runTest {
            val p = probe()
            BookingSeeder(p, SeedReport()).seed(data, config)
            fake.reservations[spec("H2").externalId] = ref("H2").copy(status = "CONFIRMED")
            val assignments = fake.assignments
            val r = SeedReport()
            BookingSeeder(p, r).seed(data, config)
            assertEquals(assignments, fake.assignments)
            assertEquals(1, r.createdCount(SeedItem.CHECK_IN))
            assertEquals(0, r.createdCount(SeedItem.ROOM_ASSIGNMENT))
        }
}
