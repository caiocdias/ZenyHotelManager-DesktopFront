package com.zeny.desktop.seed.data

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeedReportAtaqueTest {
    @Test
    fun createdAndKeptAreCountedIndependentlyPerItem() {
        val r = SeedReport()
        repeat(5) { r.created(SeedItem.GUEST) }
        repeat(3) { r.kept(SeedItem.GUEST) }
        r.kept(SeedItem.RESERVATION)
        assertEquals(5, r.createdCount(SeedItem.GUEST))
        assertEquals(3, r.keptCount(SeedItem.GUEST))
        assertEquals(0, r.createdCount(SeedItem.RESERVATION))
        assertEquals(1, r.keptCount(SeedItem.RESERVATION))
        assertEquals(0, r.keptCount(SeedItem.CHECK_IN))
    }

    @Test
    fun linesFollowEnumOrderAndOmitUntouchedItems() {
        val r = SeedReport()
        r.created(SeedItem.CHECK_IN)
        r.created(SeedItem.HOTEL)
        r.kept(SeedItem.ROOM)
        assertEquals(
            listOf(
                "hotel: 1 criados, 0 já existiam",
                "UHs: 0 criados, 1 já existiam",
                "check-ins: 1 criados, 0 já existiam",
            ),
            r.lines(),
        )
    }

    @Test
    fun allItemsProduceExactLabelsFromBrief() {
        val labels =
            listOf(
                "hotel",
                "políticas",
                "tipos de UH",
                "áreas",
                "UHs",
                "status de UH",
                "bloqueios OOO",
                "temporadas",
                "planos",
                "tabelas de preço",
                "usuários",
                "empresas",
                "hóspedes",
                "reservas",
                "alocações",
                "check-ins",
            )
        assertEquals(labels, SeedItem.entries.map { it.label })
        val r = SeedReport()
        SeedItem.entries.forEach { r.kept(it) }
        assertEquals(labels.map { "$it: 0 criados, 1 já existiam" }, r.lines())
    }

    @Test
    fun linesAreRecomputedAfterMoreEvents() {
        val r = SeedReport()
        r.created(SeedItem.ROOM)
        assertEquals(listOf("UHs: 1 criados, 0 já existiam"), r.lines())
        r.created(SeedItem.ROOM)
        assertEquals(listOf("UHs: 2 criados, 0 já existiam"), r.lines())
    }

    @Test
    fun reportsAreIndependentInstances() {
        val a = SeedReport()
        val b = SeedReport()
        a.created(SeedItem.ROOM)
        assertEquals(0, b.createdCount(SeedItem.ROOM))
        assertTrue(b.lines().isEmpty())
    }

    @Test
    fun noCatalogPersonalDataLeaksInLinesOrToStringEvenWithManyEvents() {
        val data = DemoCatalog.build(LocalDate.of(2026, 11, 12))
        val r = SeedReport()
        data.guests.forEach { r.created(SeedItem.GUEST) }
        data.companies.forEach { r.kept(SeedItem.COMPANY) }
        data.reservations.forEach { r.created(SeedItem.RESERVATION) }
        val text = (r.lines() + r.toString()).joinToString("\n")
        val secrets =
            data.guests.flatMap { listOf(it.fullName, it.cpf, it.cpf.take(3), it.key) } +
                data.companies.flatMap { listOf(it.legalName, it.tradeName, it.cnpj) } +
                listOf(data.hotel.cnpj, data.hotel.legalName) +
                data.reservations.map { it.externalId } +
                data.users.flatMap { listOf(it.login, it.name) }
        secrets.forEach { assertFalse(text.contains(it), "vazou: $it") }
        assertTrue(text.contains("hóspedes: 12 criados, 0 já existiam"))
        assertTrue(text.contains("empresas: 0 criados, 2 já existiam"))
        assertTrue(text.contains("reservas: 11 criados, 0 já existiam"))
    }

    @Test
    fun noDigitSequenceOfDocumentLengthAppearsInAnyLine() {
        val r = SeedReport()
        repeat(12) { r.created(SeedItem.GUEST) }
        SeedItem.entries.forEach { r.kept(it) }
        assertTrue(r.lines().none { Regex("\\d{11}").containsMatchIn(it) })
        assertTrue(r.lines().none { it.contains('@') })
    }
}
