package com.zeny.desktop.app.reception

import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.feedback.StatusTone
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Ataques à entrada externa e ao dado pessoal de [ReceptionText] (complementa ReceptionTextTest). */
class ReceptionTextAttackTest {
    private val saoPaulo = ZoneId.of("America/Sao_Paulo")

    @Test
    fun dateRejectsImpossibleCalendarDays() {
        assertEquals("—", ReceptionText.date("2026-02-30"))
        assertEquals("—", ReceptionText.date("2026-02-29"))
        assertEquals("—", ReceptionText.date("2026-04-31"))
    }

    @Test
    fun dateAcceptsLeapDay() {
        assertEquals("29/02/2028", ReceptionText.date("2028-02-29"))
    }

    @Test
    fun dateRejectsEmptyBlankAndNonIsoShapes() {
        listOf("", " ", " 2026-11-13", "2026-11-13 ", "2026-11-13T00:00:00Z", "13/11/2026", "2026-1-3", "2026/11/13")
            .forEach { assertEquals("—", ReceptionText.date(it), "entrada: '$it'") }
    }

    @Test
    fun dateIgnoresJvmTimeZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            assertEquals("13/11/2026", ReceptionText.date("2026-11-13"))
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago"))
            assertEquals("13/11/2026", ReceptionText.date("2026-11-13"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun periodCountsNightsAcrossMonthYearAndLeapBoundaries() {
        assertEquals("30/12/2026 a 02/01/2027 · 3 noites", ReceptionText.period("2026-12-30", "2027-01-02", false))
        assertEquals("28/02/2028 a 01/03/2028 · 2 noites", ReceptionText.period("2028-02-28", "2028-03-01", false))
        assertEquals("28/02/2027 a 01/03/2027 · 1 noite", ReceptionText.period("2027-02-28", "2027-03-01", false))
    }

    @Test
    fun periodRejectsImpossibleDates() {
        assertEquals("—", ReceptionText.period("2026-02-30", "2026-03-02", false))
        assertEquals("—", ReceptionText.period("2026-03-01", "2026-02-30", false))
        assertEquals("—", ReceptionText.period("", "", false))
    }

    @Test
    fun periodDayUseShowsOnlyArrivalEvenWithBadDeparture() {
        assertEquals("13/11/2026 · day use", ReceptionText.period("2026-11-13", "lixo", true))
    }

    @Test
    fun periodWithDepartureBeforeArrivalNeverShowsNegativeNights() {
        val text = ReceptionText.period("2026-11-15", "2026-11-13", false)
        assertFalse(text.contains("-2"), "texto: $text")
        assertEquals("—", text)
    }

    @Test
    fun guestsHandlesZeroAdultsAndNoChildren() {
        assertEquals("0 adultos", ReceptionText.guests(0, 0))
        assertEquals("0 adultos e 1 criança", ReceptionText.guests(0, 1))
        assertEquals("1 adulto", ReceptionText.guests(1, 0))
    }

    @Test
    fun moneyFormatsThousandsMillionsAndSmallValues() {
        assertEquals("R$ 0,00", ReceptionText.money("0"))
        assertEquals("R$ 0,05", ReceptionText.money("0.05"))
        assertEquals("R$ 999,99", ReceptionText.money("999.99"))
        assertEquals("R$ 1.000,00", ReceptionText.money("1000"))
        assertEquals("R$ 1.234.567,89", ReceptionText.money("1234567.89"))
        assertEquals("R$ 12.345.678.901.234,50", ReceptionText.money("12345678901234.5"))
    }

    @Test
    fun moneyKeepsNegativeSign() {
        assertEquals("R$ -5,00", ReceptionText.money("-5"))
        assertEquals("R$ -1.234,50", ReceptionText.money("-1234.5"))
    }

    @Test
    fun moneyNeverRoundsSilently() {
        assertEquals("—", ReceptionText.money("1.234"))
        assertEquals("—", ReceptionText.money("0.005"))
        assertEquals("—", ReceptionText.money("10.999"))
    }

    @Test
    fun moneyRejectsNonContractFormats() {
        listOf(
            "1,5",
            "1.234,56",
            "NaN",
            "Infinity",
            "-Infinity",
            "1e3",
            "1E3",
            "0x10",
            "+5",
            " 5",
            "5 ",
            "R$ 5",
            "5.",
            ".5",
            "--5",
            "1_000",
            "١٢٣",
        ).forEach { assertEquals("—", ReceptionText.money(it), "entrada: '$it'") }
    }

    @Test
    fun instantAcceptsOffsetAndConvertsToHotelZone() {
        assertEquals("13/11/2026 14:05", ReceptionText.instant("2026-11-13T17:05:00+00:00", saoPaulo))
        assertEquals("13/11/2026 14:05", ReceptionText.instant("2026-11-14T02:05:00+09:00", saoPaulo))
        assertEquals("13/11/2026 14:05", ReceptionText.instant("2026-11-13T14:05:00-03:00", saoPaulo))
    }

    @Test
    fun instantCrossesMidnightIntoPreviousHotelDay() {
        assertEquals("13/11/2026 22:30", ReceptionText.instant("2026-11-14T01:30:00Z", saoPaulo))
        assertEquals("14/11/2026 00:00", ReceptionText.instant("2026-11-14T03:00:00Z", saoPaulo))
    }

    @Test
    fun instantAcceptsFractionalSecondsAndMissingSeconds() {
        assertEquals("13/11/2026 14:05", ReceptionText.instant("2026-11-13T17:05:00.123456Z", saoPaulo))
        assertEquals("13/11/2026 14:05", ReceptionText.instant("2026-11-13T17:05Z", saoPaulo))
    }

    @Test
    fun instantFollowsHotelZoneDaylightSaving() {
        val lisbon = ZoneId.of("Europe/Lisbon")
        assertEquals("01/07/2026 13:00", ReceptionText.instant("2026-07-01T12:00:00Z", lisbon))
        assertEquals("01/12/2026 12:00", ReceptionText.instant("2026-12-01T12:00:00Z", lisbon))
    }

    @Test
    fun instantIsNullForMalformedOrOutOfRangeText() {
        listOf(
            "",
            " ",
            "2026-11-13T25:00:00Z",
            "2026-11-13T17:60:00Z",
            "2026-02-30T10:00:00Z",
            "2026-11-13",
            "2026-11-13T17:05:00",
            "2026-11-13 17:05:00Z",
            "2026-11-13T17:05:00+25:00",
            "NaN",
        ).forEach { assertNull(ReceptionText.instant(it, saoPaulo), "entrada: '$it'") }
    }

    @Test
    fun instantIsNullWithoutZoneEvenForValidText() {
        assertNull(ReceptionText.instant("2026-11-13T17:05:00Z", null))
        assertNull(ReceptionText.instant("", null))
    }

    @Test
    fun statusIsCaseSensitiveAndEmptyIsOther() {
        assertEquals(ChipModel("Outro (confirmed)", StatusTone.NEUTRAL), ReceptionText.status("confirmed"))
        assertEquals(ChipModel("Outro ()", StatusTone.NEUTRAL), ReceptionText.status(""))
        assertEquals(ChipModel("Outro (CONFIRMED )", StatusTone.NEUTRAL), ReceptionText.status("CONFIRMED "))
    }

    @Test
    fun enumLabelsTreatLowercaseAndEmptyAsOther() {
        assertEquals("Outro (change)", ReceptionText.historyKind("change"))
        assertEquals("Outro ()", ReceptionText.historyKind(""))
        assertEquals("Outro (initial)", ReceptionText.assignmentReason("initial"))
        assertEquals("Outro ()", ReceptionText.assignmentReason(""))
        assertEquals("Outro (unassigned)", ReceptionText.releaseReason("unassigned"))
        assertEquals("Outro ()", ReceptionText.releaseReason(""))
        assertEquals("Outro (normal)", ReceptionText.planCategory("normal"))
        assertEquals("Outro ()", ReceptionText.planCategory(""))
    }

    @Test
    fun historyKindAndReleaseReasonDoNotCrossLabelsBetweenEnums() {
        assertEquals("Outro (CHECKED_OUT)", ReceptionText.historyKind("CHECKED_OUT"))
        assertEquals("Outro (CHECK_OUT)", ReceptionText.releaseReason("CHECK_OUT"))
        assertEquals("Outro (ROOM_MOVED)", ReceptionText.historyKind("ROOM_MOVED"))
    }

    @Test
    fun changedFieldsCollapsesManyUnknownsIntoOneAndKeepsThemLast() {
        assertEquals(
            "chegada, saída, outros dados",
            ReceptionText.changedFields(listOf("x", "arrival", "y", "departure", "x", "z")),
        )
    }

    @Test
    fun changedFieldsDeduplicatesKnownAcrossNonAdjacentRepeats() {
        assertEquals(
            "adultos, situação",
            ReceptionText.changedFields(listOf("adults", "status", "adults", "status", "adults")),
        )
    }

    @Test
    fun changedFieldsNeverLeaksRawNamesOrIsCaseInsensitive() {
        val text = ReceptionText.changedFields(listOf("Status", "ADULTS", "", "guestDocument", "status"))
        assertEquals("situação, outros dados", text)
        assertFalse(text.contains("guestDocument"))
    }

    @Test
    fun changedFieldsEmptyStringEntryIsUnknown() {
        assertEquals("outros dados", ReceptionText.changedFields(listOf("")))
    }

    @Test
    fun documentNeverReformatsOrRevealsTheMaskedNumber() {
        val masked = "***.456.789-**"
        assertEquals("CPF $masked", ReceptionText.document("CPF", masked))
        assertEquals("CPF  x  y", ReceptionText.document("CPF", " x  y"))
        assertEquals("RG ", ReceptionText.document("RG", ""))
        assertEquals("Passaporte AB****89", ReceptionText.document("PASSPORT", "AB****89"))
        assertEquals("Documento estrangeiro 1234", ReceptionText.document("FOREIGN_ID", "1234"))
    }

    @Test
    fun documentUnknownOrLowercaseTypeFallsBackToGenericLabelWithoutLeakingType() {
        assertEquals("Documento *9", ReceptionText.document("cpf", "*9"))
        assertEquals("Documento *9", ReceptionText.document("", "*9"))
        assertEquals("Documento *9", ReceptionText.document("VOTER_ID", "*9"))
    }
}
