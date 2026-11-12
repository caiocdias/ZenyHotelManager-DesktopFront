package com.zeny.desktop.seed.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeedReportTest {
    @Test
    fun linesHaveCountsPerItem() {
        val report = SeedReport()
        report.created(SeedItem.ROOM)
        report.created(SeedItem.ROOM)
        report.kept(SeedItem.ROOM)
        report.kept(SeedItem.USER)
        assertEquals(2, report.createdCount(SeedItem.ROOM))
        assertEquals(1, report.keptCount(SeedItem.ROOM))
        assertEquals(0, report.createdCount(SeedItem.GUEST))
        assertEquals(listOf("UHs: 2 criados, 1 já existiam", "usuários: 0 criados, 1 já existiam"), report.lines())
    }

    @Test
    fun emptyReportHasNoLines() {
        assertTrue(SeedReport().lines().isEmpty())
    }

    @Test
    fun reportLinesHaveOnlyCounts() {
        val data = DemoCatalog.build(java.time.LocalDate.of(2026, 11, 12))
        val report = SeedReport()
        SeedItem.entries.forEach { report.created(it) }
        val text = report.lines().joinToString("\n")
        val secrets =
            data.guests.flatMap { listOf(it.fullName, it.cpf) } +
                data.companies.flatMap { listOf(it.legalName, it.tradeName, it.cnpj) } +
                data.hotel.cnpj
        secrets.forEach { assertFalse(text.contains(it), "vazou: $it") }
        assertTrue(text.lines().all { Regex("[^\\d]+: \\d+ criados, \\d+ já existiam").matches(it) })
    }
}
