package com.zeny.desktop.seed.data

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SeedReportToStringAtaqueTest {
    @Test
    fun twoReportsWithTheSameCountsHaveTheSameToString() {
        val a = SeedReport()
        val b = SeedReport()
        listOf(a, b).forEach {
            it.created(SeedItem.USER)
            it.kept(SeedItem.COMPANY)
            it.created(SeedItem.CHECK_IN)
        }
        assertEquals(a.toString(), b.toString())
    }

    @Test
    fun toStringHasNoIdentityHashAndIsExactlyWrappedLines() {
        val r = SeedReport()
        SeedItem.entries.forEach {
            r.created(it)
            r.kept(it)
            r.kept(it)
        }
        val text = r.toString()
        assertFalse(text.contains('@'))
        assertFalse(text.contains("SeedReport@"))
        assertEquals("SeedReport(" + r.lines().joinToString("; ") + ")", text)
        assertEquals(SeedItem.entries.size, r.lines().size)
        SeedItem.entries.forEach { assertTrue(text.contains(it.label), it.name) }
    }

    @Test
    fun toStringFollowsEveryMutation() {
        val r = SeedReport()
        val seen = mutableSetOf(r.toString())
        SeedItem.entries.forEach {
            r.created(it)
            assertTrue(seen.add(r.toString()), "created ${it.name} não mudou o toString")
            r.kept(it)
            assertTrue(seen.add(r.toString()), "kept ${it.name} não mudou o toString")
        }
    }

    @Test
    fun toStringNeverCarriesCatalogPersonalDataNorLongDigitRunsRegardlessOfCounts() {
        val data = DemoCatalog.build(LocalDate.of(2026, 11, 12))
        val r = SeedReport()
        repeat(2026) { r.created(SeedItem.GUEST) }
        data.companies.forEach { r.kept(SeedItem.COMPANY) }
        val text = r.toString()
        val secrets =
            data.guests.flatMap { listOf(it.fullName, it.cpf) } +
                data.companies.flatMap { listOf(it.legalName, it.tradeName, it.cnpj) }
        secrets.forEach { assertFalse(text.contains(it)) }
        assertFalse(Regex("\\d{11}").containsMatchIn(text))
        assertEquals(text, r.toString())
    }

    @Test
    fun emptyReportToStringIsStableAndDistinctFromNonEmpty() {
        val r = SeedReport()
        assertEquals("SeedReport()", r.toString())
        r.created(SeedItem.HOTEL)
        assertNotEquals("SeedReport()", r.toString())
    }
}
