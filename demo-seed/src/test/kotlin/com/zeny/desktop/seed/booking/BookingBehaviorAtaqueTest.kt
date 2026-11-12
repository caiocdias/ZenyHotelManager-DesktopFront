package com.zeny.desktop.seed.booking

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class BookingBehaviorAtaqueTest : BookingAtaqueBase() {
    // ---- situações inesperadas ----

    @Test
    fun unexpectedStatusesAreKeptWithoutAnyCall() {
        for (status in listOf("CANCELLED", "NO_SHOW", "CHECKED_OUT", "IN_HOUSE", "PENDING", "confirmed", "")) {
            val f = FakeBookingGateway(config, date)
            runTest {
                val p = Probe(f) { currentTime }
                BookingSeeder(p, SeedReport()).seed(data, config)
                data.reservations.forEach { s ->
                    f.reservations[s.externalId] =
                        f.reservations.getValue(s.externalId).copy(status = status, roomId = null)
                }
                val before = p.events.size
                val r = SeedReport()
                BookingSeeder(p, r).seed(data, config)
                assertEquals(emptyList(), p.events.drop(before), "status '$status'")
                assertEquals(11, r.keptCount(SeedItem.RESERVATION), status)
                assertEquals(0, r.createdCount(SeedItem.RESERVATION), status)
                assertEquals(0, r.createdCount(SeedItem.CHECK_IN), status)
                assertEquals(0, r.createdCount(SeedItem.ROOM_ASSIGNMENT), status)
                assertTrue(f.reservations.values.all { it.status == status && it.roomId == null }, status)
            }
        }
    }

    @Test
    fun oneCancelledAmongMissingOnesIsKeptWhileTheRestIsCreated() =
        runTest {
            val p = probe()
            fake.createCompany(data.companies.first())
            val g = data.guests.first { it.key == spec("A1").holderGuestKey }
            fake.createGuest(g, null)
            fake.reservations[spec("A1").externalId] = ReservationRef("re-x", "CANCELLED", null)
            BookingSeeder(p, report).seed(data, config)
            assertEquals("CANCELLED", ref("A1").status)
            assertEquals(null, ref("A1").roomId)
            assertEquals(10, report.createdCount(SeedItem.RESERVATION))
            assertEquals(1, report.keptCount(SeedItem.RESERVATION))
            assertEquals(11, fake.reservations.size)
            assertFalse(p.events.any { it == "assignRoom:re-x" || it == "checkIn:re-x" })
        }

    @Test
    fun confirmedWithWrongRoomIsReassignedToTheExpectedOne() =
        runTest {
            val p = probe()
            BookingSeeder(p, SeedReport()).seed(data, config)
            fake.reservations[spec("A1").externalId] = ref("A1").copy(roomId = "room-999")
            BookingSeeder(p, SeedReport()).seed(data, config)
            assertEquals("room-101", ref("A1").roomId)
        }

    // ---- titular, ordem, vínculo ----

    @Test
    fun everyCheckInHasExactlyTheHolderAndNoOneElseEvenWithManyAdults() =
        runTest {
            BookingSeeder(probe(), report).seed(data, config)
            val expected = data.reservations.filter { it.checkIn }
            assertEquals(expected.size, fake.checkIns.size)
            for (s in expected) {
                val call = fake.checkIns.single { it.reservationId == ref(s.slot).id }
                assertEquals(listOf(holderId(s.slot)), call.guestIds, s.slot)
                assertEquals("room-${s.roomNumber}", call.roomId, s.slot)
            }
            assertEquals(3, spec("H4").adults)
            assertEquals(
                1,
                fake.checkIns
                    .single { it.reservationId == ref("H4").id }
                    .guestIds.size,
            )
        }

    @Test
    fun checkInOnlyForReservationsThatShouldBeCheckedIn() =
        runTest {
            BookingSeeder(probe(), report).seed(data, config)
            for (s in data.reservations.filter { !it.checkIn }) {
                assertTrue(fake.checkIns.none { it.reservationId == ref(s.slot).id }, s.slot)
                assertEquals("CONFIRMED", ref(s.slot).status, s.slot)
            }
            assertEquals(null, ref("A3").roomId)
            assertEquals(null, ref("A4").roomId)
        }

    @Test
    fun assignmentAlwaysPrecedesCheckInOfTheSameReservation() =
        runTest {
            val p = probe()
            BookingSeeder(p, report).seed(data, config)
            for (s in data.reservations.filter { it.checkIn }) {
                val id = ref(s.slot).id
                val assign = p.events.indexOf("assignRoom:$id")
                val checkIn = p.events.indexOf("checkIn:$id")
                assertTrue(assign in 0 until checkIn, "${s.slot}: alocação $assign, check-in $checkIn")
            }
        }

    @Test
    fun holderGuestAndCompanyAreResolvedByDocumentOnly() =
        runTest {
            fake.createCompany(data.companies.first())
            val first = data.guests.first()
            fake.createGuest(first, null)
            BookingSeeder(probe(), report).seed(data, config)
            assertEquals(2, fake.companies.size)
            assertEquals(12, fake.guests.size)
            assertEquals(1, report.keptCount(SeedItem.COMPANY))
            assertEquals(1, report.keptCount(SeedItem.GUEST))
            assertEquals(11, report.createdCount(SeedItem.GUEST))
        }

    // ---- LGPD ----

    @Test
    fun reportAndToStringHaveNoPersonalDataAfterFullAndSecondRun() =
        runTest {
            val r = SeedReport()
            BookingSeeder(probe(), r).seed(data, config)
            BookingSeeder(probe(), r).seed(data, config)
            val text = (r.lines() + r.toString()).joinToString("\n")
            val secrets =
                data.guests.flatMap { listOf(it.fullName, it.cpf, it.key) } +
                    data.companies.flatMap { listOf(it.legalName, it.tradeName, it.cnpj) } +
                    data.reservations.map { it.externalId } + data.reservations.map { it.slot }
            secrets.forEach { assertFalse(text.contains(it), "vazou: $it") }
            assertFalse(text.contains("@"))
            assertTrue(text.contains("reservas: 11 criados, 11 já existiam"))
        }

    @Test
    fun failureMessageOfTimeoutHasNoPersonalData() =
        runTest {
            val p = probe()
            p.transform = { _, _, _ -> emptyList() }
            val msg = assertFailsWith<SeedFailure> { BookingSeeder(p, report).seed(data, config) }.message.orEmpty()
            data.guests.forEach { assertFalse(msg.contains(it.fullName) || msg.contains(it.cpf)) }
            data.companies.forEach { assertFalse(msg.contains(it.cnpj) || msg.contains(it.legalName)) }
        }
}
