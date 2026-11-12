package com.zeny.desktop.seed.data

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoCatalogTest {
    private val d = LocalDate.of(2026, 11, 12)
    private val data = DemoCatalog.build(d)

    @Test
    fun catalogIsDeterministic() {
        assertEquals(DemoCatalog.build(d), DemoCatalog.build(d))
    }

    @Test
    fun catalogHas20UniqueRooms() {
        assertEquals(20, data.rooms.size)
        assertEquals(
            20,
            data.rooms
                .map { it.number }
                .toSet()
                .size,
        )
    }

    @Test
    fun everyCodeReferenceResolves() {
        val types = data.roomTypes.map { it.code }.toSet()
        assertTrue(data.rooms.all { it.roomTypeCode in types })
        assertTrue(data.reservations.all { it.roomTypeCode in types })
        assertTrue(data.ratePlan.roomTypeCodes.all { it in types })
        val seasons = data.seasons.map { it.name }.toSet()
        assertTrue(data.prices.all { it.seasonName in seasons && it.planCode == data.ratePlan.code })
        assertTrue(data.prices.all { it.roomTypeCode in types })
        val keys = data.guests.map { it.key }.toSet()
        assertTrue(data.reservations.all { it.holderGuestKey in keys })
        val cnpjs = data.companies.map { it.cnpj }.toSet()
        assertTrue(data.guests.all { it.companyCnpj == null || it.companyCnpj in cnpjs })
        assertTrue(data.guests.any { it.companyCnpj != null })
    }

    @Test
    fun reservedRoomsMatchTypeAndAreInspected() {
        val rooms = data.rooms.associateBy { it.number }
        data.reservations.mapNotNull { r -> r.roomNumber?.let { r to rooms.getValue(it) } }.forEach { (r, room) ->
            assertEquals(r.roomTypeCode, room.roomTypeCode)
            assertEquals(HousekeepingTarget.INSPECTED, room.target)
        }
    }

    @Test
    fun oneRoomPerReservationAndNoneOnOutOfOrder() {
        val numbers = data.reservations.mapNotNull { it.roomNumber }
        assertEquals(numbers.size, numbers.toSet().size)
        val ooo =
            data.rooms
                .filter { it.outOfOrder }
                .map { it.number }
                .toSet()
        assertTrue(numbers.none { it in ooo })
    }

    @Test
    fun statusMix() {
        val ooo = data.rooms.filter { it.outOfOrder }
        assertEquals(1, ooo.size)
        assertEquals(HousekeepingTarget.DIRTY, ooo.single().target)
        val rest =
            data.rooms
                .filter { !it.outOfOrder }
                .groupingBy { it.target }
                .eachCount()
        assertEquals(3, rest[HousekeepingTarget.DIRTY])
        assertEquals(2, rest[HousekeepingTarget.CLEAN_PENDING_INSPECTION])
        assertEquals(2, rest[HousekeepingTarget.CLEAN])
    }

    @Test
    fun listsOnBusinessDate() {
        val r = data.reservations
        assertEquals(11, r.size)
        assertEquals(11, r.count { it.arrival == d })
        assertEquals(7, r.count { it.checkIn && it.departure >= d })
        assertEquals(2, r.count { it.departure == d })
        assertEquals(setOf("A1", "A2", "A3", "A4", "H1", "H2", "H3", "H4", "H5", "S1", "S2"), r.map { it.slot }.toSet())
        assertEquals("DEMO-SEED-A1", r.first { it.slot == "A1" }.externalId)
    }

    @Test
    fun allDocumentsValidAndUnique() {
        val cpfs = data.guests.map { it.cpf }
        val cnpjs = data.companies.map { it.cnpj } + data.hotel.cnpj
        assertEquals(12, cpfs.size)
        assertTrue(cpfs.all(FictitiousDocuments::isValidCpf))
        assertTrue(cnpjs.all(FictitiousDocuments::isValidCnpj))
        assertEquals(cpfs.size, cpfs.toSet().size)
        assertEquals(cnpjs.size, cnpjs.toSet().size)
        assertEquals(3, cnpjs.size)
    }

    @Test
    fun adultsWithinRoomTypeMax() {
        val types = data.roomTypes.associateBy { it.code }
        assertTrue(data.reservations.all { it.adults in 1..types.getValue(it.roomTypeCode).maxAdults })
    }

    @Test
    fun pricesCoverEveryTypeAndSeason() {
        assertEquals(6, data.prices.size)
        val pairs = data.prices.map { it.roomTypeCode to it.seasonName }.toSet()
        assertEquals(6, pairs.size)
        assertTrue(
            data.prices.all {
                it.oneAdult.scale() == 2 && it.twoAdults.scale() == 2 && it.extraAdult.scale() == 2
            },
        )
        val std = data.prices.first { it.roomTypeCode == "STD" && it.seasonName == "Baixa (demo)" }
        assertEquals(BigDecimal("280.00"), std.oneAdult)
        assertEquals(BigDecimal("320.00"), std.twoAdults)
        assertEquals(BigDecimal("90.00"), std.extraAdult)
    }

    @Test
    fun seasonsCover60Days() {
        val s = data.seasons.sortedBy { it.startsOn }
        assertEquals(d, s.first().startsOn)
        assertEquals(d.plusDays(59), s.last().endsOn)
        s.zipWithNext().forEach { (a, b) -> assertEquals(a.endsOn.plusDays(1), b.startsOn) }
    }
}
