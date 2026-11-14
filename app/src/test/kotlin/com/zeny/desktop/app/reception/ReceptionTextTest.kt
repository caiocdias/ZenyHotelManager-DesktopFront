package com.zeny.desktop.app.reception

import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.feedback.StatusTone
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReceptionTextTest {
    @Test
    fun statusLabelsAndTones() {
        assertEquals(ChipModel("Pré-reserva", StatusTone.WARNING), ReceptionText.status("PROVISIONAL"))
        assertEquals(ChipModel("Confirmada", StatusTone.INFO), ReceptionText.status("CONFIRMED"))
        assertEquals(ChipModel("Hospedada", StatusTone.SUCCESS), ReceptionText.status("IN_HOUSE"))
        assertEquals(ChipModel("Saída registrada", StatusTone.NEUTRAL), ReceptionText.status("CHECKED_OUT"))
        assertEquals(ChipModel("Cancelada", StatusTone.DANGER), ReceptionText.status("CANCELLED"))
        assertEquals(ChipModel("Não compareceu", StatusTone.DANGER), ReceptionText.status("NO_SHOW"))
        assertEquals(ChipModel("Outro (XYZ)", StatusTone.NEUTRAL), ReceptionText.status("XYZ"))
    }

    @Test
    fun historyKindLabels() {
        val expected =
            mapOf(
                "CHANGE" to "Reserva alterada",
                "CANCEL" to "Reserva cancelada",
                "REACTIVATE" to "Reserva reativada",
                "CONFIRM" to "Pré-reserva confirmada",
                "GUARANTEE" to "Garantia registrada",
                "CHECK_IN" to "Check-in",
                "UNDO_CHECK_IN" to "Check-in desfeito",
                "CHECK_OUT" to "Check-out",
                "ROOM_MOVE" to "Troca de UH",
            )
        expected.forEach { (k, v) -> assertEquals(v, ReceptionText.historyKind(k)) }
        assertEquals("Outro (NEW)", ReceptionText.historyKind("NEW"))
    }

    @Test
    fun changedFieldsLabelsInOrderWithoutRepeats() {
        assertEquals("", ReceptionText.changedFields(emptyList()))
        assertEquals(
            "adultos, crianças, chegada, saída",
            ReceptionText.changedFields(listOf("adults", "childAges", "arrival", "departure", "adults")),
        )
        val all =
            listOf(
                "cancellationTerms",
                "guaranteeTerms",
                "guarantee",
                "nightlyRates",
                "ratePlanId",
                "roomTypeId",
                "segment",
                "specialRequests",
                "status",
                "checkIn",
                "checkOut",
                "guests",
                "roomAssignment",
            )
        assertEquals(
            "política de cancelamento, política de garantia, garantia, diárias, plano, tipo de UH, segmento, " +
                "pedidos especiais, situação, check-in, check-out, hóspedes, UH",
            ReceptionText.changedFields(all),
        )
    }

    @Test
    fun unknownValuesNeverShowFieldNames() {
        assertEquals("situação, outros dados", ReceptionText.changedFields(listOf("status", "fooBar")))
        assertEquals("situação, outros dados", ReceptionText.changedFields(listOf("fooBar", "status", "bazQux")))
        assertEquals("outros dados", ReceptionText.changedFields(listOf("a", "b")))
    }

    @Test
    fun assignmentReasons() {
        assertEquals("Alocação inicial", ReceptionText.assignmentReason("INITIAL"))
        assertEquals("Pedido do hóspede", ReceptionText.assignmentReason("GUEST_REQUEST"))
        assertEquals("UH fora de ordem", ReceptionText.assignmentReason("OUT_OF_ORDER"))
        assertEquals("Problema na UH", ReceptionText.assignmentReason("ROOM_ISSUE"))
        assertEquals("Operacional", ReceptionText.assignmentReason("OPERATIONAL"))
        assertEquals("Outro (X)", ReceptionText.assignmentReason("X"))
    }

    @Test
    fun releaseReasonRoomMoved() {
        assertEquals("Liberada", ReceptionText.releaseReason("UNASSIGNED"))
        assertEquals("Realocada", ReceptionText.releaseReason("REALLOCATED"))
        assertEquals("Reserva cancelada", ReceptionText.releaseReason("RESERVATION_CANCELLED"))
        assertEquals("Reserva alterada", ReceptionText.releaseReason("RESERVATION_CHANGED"))
        assertEquals("Check-out", ReceptionText.releaseReason("CHECKED_OUT"))
        assertEquals("Troca de UH", ReceptionText.releaseReason("ROOM_MOVED"))
        assertEquals("Outro (ROOM_MOVE)", ReceptionText.releaseReason("ROOM_MOVE"))
    }

    @Test
    fun planCategories() {
        assertEquals("Normal", ReceptionText.planCategory("NORMAL"))
        assertEquals("Cortesia", ReceptionText.planCategory("COURTESY"))
        assertEquals("Uso da casa", ReceptionText.planCategory("HOUSE_USE"))
        assertEquals("Outro (Z)", ReceptionText.planCategory("Z"))
    }

    @Test
    fun documentLabelsKeepMaskedNumber() {
        assertEquals("CPF ***.456.789-**", ReceptionText.document("CPF", "***.456.789-**"))
        assertEquals("RG *1234", ReceptionText.document("RG", "*1234"))
        assertEquals("CNH *1", ReceptionText.document("CNH", "*1"))
        assertEquals("RNM *2", ReceptionText.document("RNM", "*2"))
        assertEquals("Passaporte *3", ReceptionText.document("PASSPORT", "*3"))
        assertEquals("Documento estrangeiro *4", ReceptionText.document("FOREIGN_ID", "*4"))
        assertEquals("Documento *5", ReceptionText.document("OTHER", "*5"))
    }

    @Test
    fun dateFormatting() {
        assertEquals("13/11/2026", ReceptionText.date("2026-11-13"))
        assertEquals("—", ReceptionText.date("lixo"))
        assertEquals("—", ReceptionText.date("2026-13-40"))
    }

    @Test
    fun periodFormatting() {
        assertEquals("13/11/2026 a 15/11/2026 · 2 noites", ReceptionText.period("2026-11-13", "2026-11-15", false))
        assertEquals("13/11/2026 a 14/11/2026 · 1 noite", ReceptionText.period("2026-11-13", "2026-11-14", false))
        assertEquals("13/11/2026 · day use", ReceptionText.period("2026-11-13", "2026-11-13", true))
        assertEquals("—", ReceptionText.period("x", "2026-11-14", false))
        assertEquals("—", ReceptionText.period("2026-11-13", "x", false))
        assertEquals("—", ReceptionText.period("x", "2026-11-14", true))
    }

    @Test
    fun guestsFormatting() {
        assertEquals("2 adultos", ReceptionText.guests(2, 0))
        assertEquals("1 adulto e 1 criança", ReceptionText.guests(1, 1))
        assertEquals("2 adultos e 3 crianças", ReceptionText.guests(2, 3))
    }

    @Test
    fun moneyFormatting() {
        assertEquals("R$ 1.234,56", ReceptionText.money("1234.56"))
        assertEquals("R$ 10,00", ReceptionText.money("10"))
        assertEquals("R$ 0,50", ReceptionText.money("0.5"))
        assertEquals("—", ReceptionText.money("abc"))
        assertEquals("—", ReceptionText.money(""))
    }

    @Test
    fun instantUsesHotelZoneNotJvm() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(
                "13/11/2026 14:05",
                ReceptionText.instant("2026-11-13T17:05:00Z", ZoneId.of("America/Sao_Paulo")),
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun instantIsNullWithoutZoneOrWithUnreadableText() {
        assertNull(ReceptionText.instant("2026-11-13T17:05:00Z", null))
        assertNull(ReceptionText.instant("lixo", ZoneId.of("America/Sao_Paulo")))
    }
}
