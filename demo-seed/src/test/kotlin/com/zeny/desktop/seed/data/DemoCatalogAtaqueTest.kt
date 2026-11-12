package com.zeny.desktop.seed.data

import java.time.LocalDate
import java.time.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DemoCatalogAtaqueTest {
    private val dates =
        listOf(
            LocalDate.of(2026, 11, 12),
            LocalDate.of(2026, 12, 31),
            LocalDate.of(2027, 1, 1),
            LocalDate.of(2028, 2, 28),
            LocalDate.of(2028, 2, 29),
            LocalDate.of(2028, 3, 1),
            LocalDate.of(2026, 1, 31),
            LocalDate.of(2026, 2, 28),
            LocalDate.of(2099, 12, 31),
        )

    @Test
    fun businessDateInvariantsHoldAcrossMonthYearAndLeapBoundaries() {
        for (d in dates) {
            val data = DemoCatalog.build(d)
            val r = data.reservations
            assertEquals(11, r.count { it.arrival == d }, "chegadas em $d")
            assertEquals(7, r.count { it.checkIn && it.departure >= d }, "hospedados em $d")
            assertEquals(2, r.count { it.departure == d }, "saídas em $d")
            assertTrue(r.all { it.departure >= it.arrival }, "saída antes da chegada em $d")
            val s = data.seasons.sortedBy { it.startsOn }
            assertEquals(d, s.first().startsOn)
            assertEquals(d.plusDays(59), s.last().endsOn)
            assertEquals(s[0].endsOn.plusDays(1), s[1].startsOn)
            assertTrue(s.all { !it.endsOn.isBefore(it.startsOn) })
        }
    }

    @Test
    fun everyReservationStaysInsideTheSeasonCoverage() {
        for (d in dates) {
            val data = DemoCatalog.build(d)
            val last = data.seasons.maxOf { it.endsOn }
            assertTrue(data.reservations.all { it.arrival >= d && it.departure <= last })
        }
    }

    @Test
    fun onlyDatesChangeBetweenBusinessDates() {
        val a = DemoCatalog.build(dates[0])
        val b = DemoCatalog.build(dates[4])
        assertNotEquals(a, b)
        assertEquals(a.hotel, b.hotel)
        assertEquals(a.rooms, b.rooms)
        assertEquals(a.guests, b.guests)
        assertEquals(a.companies, b.companies)
        assertEquals(a.prices, b.prices)
        assertEquals(a.users, b.users)
        assertEquals(
            a.reservations.map { Triple(it.slot, it.roomNumber, it.checkIn) },
            b.reservations.map { Triple(it.slot, it.roomNumber, it.checkIn) },
        )
        val nights = { x: DemoData ->
            x.reservations.map {
                Period.between(it.arrival, it.departure).let { p ->
                    p.days +
                        p.months * 31
                }
            }
        }
        assertEquals(nights(a).size, nights(b).size)
        assertEquals(
            a.reservations.map {
                java.time.temporal.ChronoUnit.DAYS
                    .between(it.arrival, it.departure)
            },
            b.reservations.map {
                java.time.temporal.ChronoUnit.DAYS
                    .between(it.arrival, it.departure)
            },
        )
    }

    @Test
    fun determinismHoldsAcrossIndependentBuildsForEveryDate() {
        for (d in dates) assertEquals(DemoCatalog.build(d), DemoCatalog.build(d))
    }

    @Test
    fun roomFloorMatchesNumberAndTypeDistribution() {
        val data = DemoCatalog.build(dates[0])
        assertTrue(data.rooms.all { it.floor == it.number.first().digitToInt() })
        assertEquals(setOf(1, 2), data.rooms.map { it.floor }.toSet())
        assertEquals(10, data.rooms.count { it.floor == 1 })
        val byType = data.rooms.groupingBy { it.roomTypeCode }.eachCount()
        assertEquals(12, byType["STD"])
        assertEquals(6, byType["LUX"])
        assertEquals(2, byType["SUI"])
        assertEquals(
            (101..110).map(Int::toString) + (201..210).map(Int::toString),
            data.rooms.map { it.number }.sorted(),
        )
    }

    @Test
    fun outOfOrderRoomIsVacantAndFlaggedExactlyOnce() {
        val data = DemoCatalog.build(dates[0])
        val ooo = data.rooms.single { it.outOfOrder }
        assertEquals(HousekeepingTarget.DIRTY, ooo.target)
        assertTrue(data.reservations.none { it.roomNumber == ooo.number })
        // 12 INSPECTED restantes (20 - 1 OOO - 3 - 2 - 2)
        assertEquals(12, data.rooms.count { it.target == HousekeepingTarget.INSPECTED })
        assertEquals(4, data.rooms.count { it.target == HousekeepingTarget.DIRTY })
    }

    @Test
    fun reservationsFollowBriefShape() {
        val r = DemoCatalog.build(dates[0]).reservations.associateBy { it.slot }
        listOf("A1", "A2", "A3", "A4").forEach {
            assertTrue(r.getValue(it).departure in dates[0].plusDays(2)..dates[0].plusDays(3), it)
            assertEquals(false, r.getValue(it).checkIn, it)
        }
        assertTrue(r.getValue("A1").roomNumber != null && r.getValue("A2").roomNumber != null)
        assertEquals(null, r.getValue("A3").roomNumber)
        assertEquals(null, r.getValue("A4").roomNumber)
        listOf("H1", "H2", "H3", "H4", "H5").forEach {
            assertTrue(r.getValue(it).departure in dates[0].plusDays(1)..dates[0].plusDays(4), it)
            assertTrue(r.getValue(it).checkIn && r.getValue(it).roomNumber != null, it)
        }
        listOf("S1", "S2").forEach {
            assertEquals(dates[0], r.getValue(it).departure, it)
            assertTrue(r.getValue(it).checkIn && r.getValue(it).roomNumber != null, it)
        }
    }

    @Test
    fun holdersAreDistinctGuestsAndCompaniesOwnedByGuestsExist() {
        val data = DemoCatalog.build(dates[0])
        val holders = data.reservations.map { it.holderGuestKey }
        assertEquals(holders.size, holders.toSet().size)
        assertEquals(
            12,
            data.guests
                .map { it.key }
                .toSet()
                .size,
        )
        assertEquals(2, data.companies.size)
        assertTrue(data.companies.all { c -> data.guests.any { it.companyCnpj == c.cnpj } })
    }

    @Test
    fun guestsAreAdultsOnEveryBusinessDate() {
        for (d in dates) {
            DemoCatalog.build(d).guests.forEach {
                assertTrue(Period.between(it.birthDate, d).years >= 18, "${it.key} em $d")
            }
        }
    }

    @Test
    fun namesAreVisiblyFictitiousAndUnique() {
        val data = DemoCatalog.build(dates[0])
        assertEquals(
            12,
            data.guests
                .map { it.fullName }
                .toSet()
                .size,
        )
        assertTrue(data.guests.all { it.fullName.contains("Demonstra") })
        assertTrue(data.companies.all { it.legalName.contains("Fictícia") })
        assertEquals(
            3,
            data.users
                .map { it.login }
                .toSet()
                .size,
        )
        assertEquals(DemoCatalog.HOTEL_TRADE_NAME, data.hotel.tradeName)
    }

    @Test
    fun hotelCnpjDiffersFromCompaniesAndAllDocumentsValidAcrossDates() {
        for (d in dates) {
            val data = DemoCatalog.build(d)
            assertTrue(FictitiousDocuments.isValidCnpj(data.hotel.cnpj))
            assertTrue(data.companies.none { it.cnpj == data.hotel.cnpj })
            assertTrue(data.guests.all { FictitiousDocuments.isValidCpf(it.cpf) })
            assertTrue(data.companies.all { FictitiousDocuments.isValidCnpj(it.cnpj) })
            assertTrue(data.guests.all { it.cpf.length == 11 && it.cpf.all { c -> c in '0'..'9' } })
        }
    }

    @Test
    fun pricesAreSaneAndExact() {
        val p = DemoCatalog.build(dates[0]).prices.associateBy { it.roomTypeCode to it.seasonName }
        val expected =
            mapOf(
                ("LUX" to "Baixa (demo)") to listOf("420.00", "480.00", "110.00"),
                ("SUI" to "Baixa (demo)") to listOf("650.00", "720.00", "150.00"),
                ("STD" to "Alta (demo)") to listOf("340.00", "390.00", "100.00"),
                ("LUX" to "Alta (demo)") to listOf("500.00", "570.00", "120.00"),
                ("SUI" to "Alta (demo)") to listOf("780.00", "860.00", "170.00"),
            )
        expected.forEach { (k, v) ->
            assertEquals(
                v,
                p.getValue(k).let {
                    listOf(it.oneAdult, it.twoAdults, it.extraAdult).map { x ->
                        x.toPlainString()
                    }
                },
                "$k",
            )
        }
        assertTrue(p.values.all { it.twoAdults > it.oneAdult })
    }

    @Test
    fun policiesPlanAndTypesMatchBrief() {
        val data = DemoCatalog.build(dates[0])
        assertEquals(CancellationPolicySpec("Flexível (demo)", 24, 1), data.cancellationPolicy)
        assertEquals("Sem garantia (demo)", data.guaranteePolicy.name)
        assertEquals(listOf("DIRECT", "WEBSITE"), data.ratePlan.channels)
        assertEquals(setOf("STD", "LUX", "SUI"), data.ratePlan.roomTypeCodes.toSet())
        assertEquals("BREAKFAST", data.ratePlan.mealPlan)
        assertEquals(listOf(3, 3, 4), data.roomTypes.map { it.maxAdults })
        assertEquals(listOf(1, 2, 2), data.roomTypes.map { it.maxChildren })
        assertEquals(listOf("LOBBY", "POOL", "OTHER"), data.areas.map { it.kind })
        assertEquals(listOf("MANAGER", "RECEPTIONIST", "HOUSEKEEPER"), data.users.map { it.role.template })
        assertEquals("3550308", data.hotel.address.ibgeCityCode)
        assertEquals("01000-000", data.hotel.address.postalCode)
    }
}
