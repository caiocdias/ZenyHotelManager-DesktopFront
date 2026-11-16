package com.zeny.desktop.app.reception

import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.feedback.StatusTone
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ataque aos rótulos do no-show (M0 E03, Task 2): os vizinhos não mudam e o desconhecido continua "Outro". */
class ReceptionTextNoShowAtaqueTest {
    @Test
    fun noShowStatusIsDangerChipAndNeighboursKeepTheirLabels() {
        assertEquals(ChipModel("Não compareceu", StatusTone.DANGER), ReceptionText.status("NO_SHOW"))
        assertEquals(ChipModel("Cancelada", StatusTone.DANGER), ReceptionText.status("CANCELLED"))
        assertEquals(ChipModel("Confirmada", StatusTone.INFO), ReceptionText.status("CONFIRMED"))
        assertEquals(ChipModel("Saída registrada", StatusTone.NEUTRAL), ReceptionText.status("CHECKED_OUT"))
    }

    @Test
    fun noShowKindAndReleaseReasonDoNotCrossBetweenEnums() {
        assertEquals("Outro (RESERVATION_NO_SHOW)", ReceptionText.historyKind("RESERVATION_NO_SHOW"))
        assertEquals("Outro (NO_SHOW)", ReceptionText.releaseReason("NO_SHOW"))
        assertEquals("Outro (NO_SHOW)", ReceptionText.assignmentReason("NO_SHOW"))
        assertEquals("Outro (RESERVATION_NO_SHOW)", ReceptionText.assignmentReason("RESERVATION_NO_SHOW"))
    }

    @Test
    fun noShowLabelsAreCaseAndWhitespaceSensitive() {
        assertEquals("Outro (no_show)", ReceptionText.historyKind("no_show"))
        assertEquals("Outro (NO_SHOW )", ReceptionText.historyKind("NO_SHOW "))
        assertEquals("Outro (reservation_no_show)", ReceptionText.releaseReason("reservation_no_show"))
        assertEquals("Outro (NO-SHOW)", ReceptionText.status("NO-SHOW").label)
        assertEquals("Outro (RESERVATION_NO_SHOW_X)", ReceptionText.releaseReason("RESERVATION_NO_SHOW_X"))
    }

    @Test
    fun existingReleaseReasonsAndKindsStayIntactNextToNoShow() {
        assertEquals("Reserva cancelada", ReceptionText.releaseReason("RESERVATION_CANCELLED"))
        assertEquals("Reserva alterada", ReceptionText.releaseReason("RESERVATION_CHANGED"))
        assertEquals("Troca de UH", ReceptionText.releaseReason("ROOM_MOVED"))
        assertEquals("Troca de UH", ReceptionText.historyKind("ROOM_MOVE"))
        assertEquals("Reserva cancelada", ReceptionText.historyKind("CANCEL"))
    }
}
