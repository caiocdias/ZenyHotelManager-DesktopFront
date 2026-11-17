package com.zeny.desktop.app.reservations

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingTextTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Test
    fun guaranteeWithoutDeposit() {
        val item = pendingGuaranteeOf(kind = "PROVISIONAL", guaranteeType = "CARD")
        val (detail, due) = PendingText.guarantee(item, zone)

        assertEquals("Pré-reserva · Cartão", detail)
        assertEquals("15/11/2026 14:00", due)
    }

    @Test
    fun guaranteeWithDeposit() {
        val item =
            pendingGuaranteeOf(
                kind = "UNGUARANTEED",
                guaranteeType = "DEPOSIT",
                requiredDeposit = "300.00",
                depositReceived = "100.00",
            )

        assertEquals(
            "Sem garantia · Depósito · exigido R$ 300,00 · recebido R$ 100,00",
            PendingText.guarantee(item, zone).first,
        )
    }

    @Test
    fun guaranteeWithRequiredButNoReceived() {
        val item = pendingGuaranteeOf(guaranteeType = "DEPOSIT", requiredDeposit = "300.00")

        assertEquals("Pré-reserva · Depósito · exigido R$ 300,00", PendingText.guarantee(item, zone).first)
    }

    @Test
    fun kindUnknownIsOther() {
        val (detail, _) = PendingText.guarantee(pendingGuaranteeOf(kind = "FUTURE_KIND"), zone)

        assertEquals("Outro (FUTURE_KIND) · Cartão", detail)
    }

    @Test
    fun overdueIsFlagged() {
        val late = PendingText.guarantee(pendingGuaranteeOf(overdue = true), zone).second
        val onTime = PendingText.guarantee(pendingGuaranteeOf(overdue = false), zone).second

        assertEquals("15/11/2026 14:00 (vencida)", late)
        assertFalse("vencida" in onTime)
    }

    @Test
    fun withoutZoneInstantIsDash() {
        assertEquals("—", PendingText.guarantee(pendingGuaranteeOf(), null).second)
        assertEquals("— (vencida)", PendingText.guarantee(pendingGuaranteeOf(overdue = true), null).second)
        assertTrue(PendingText.deposit(depositShortfallOf(), null).first.endsWith("garantia registrada em —"))
    }

    @Test
    fun depositShowsThreeServerValues() {
        val item = depositShortfallOf(requiredDeposit = "300.00", depositReceived = "100.00", missing = "250.00")

        assertEquals(
            "Exigido R$ 300,00 · recebido R$ 100,00 · falta R$ 250,00 · garantia registrada em 10/11/2026 12:30",
            PendingText.deposit(item, zone).first,
        )
    }

    @Test
    fun depositHasNoDueDate() {
        assertEquals("—", PendingText.deposit(depositShortfallOf(), zone).second)
    }

    @Test
    fun noShowShowsStatusAndDueIsArrival() {
        val item = searchItemOf(status = "CONFIRMED", arrival = "2026-11-13")

        assertEquals("Confirmada" to "13/11/2026", PendingText.noShow(item))
    }

    @Test
    fun noShowFlagsOverdueArrival() {
        val item = searchItemOf(status = "CONFIRMED", arrival = "2026-11-13").copy(arrivalOverdue = true)

        assertEquals("Confirmada · chegada vencida" to "13/11/2026", PendingText.noShow(item))
    }

    @Test
    fun reallocationWithRoomNumber() {
        assertEquals(
            "UH 208 fora de ordem na estadia" to "13/11/2026",
            PendingText.reallocation(reallocationOf(roomNumber = "208", arrival = "2026-11-13")),
        )
    }

    @Test
    fun reallocationWithoutRoomNumber() {
        val expected = "UH alocada fora de ordem na estadia"

        assertEquals(expected, PendingText.reallocation(reallocationOf(roomNumber = null)).first)
        assertEquals(expected, PendingText.reallocation(reallocationOf(roomNumber = " ")).first)
    }
}
