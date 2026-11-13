package com.zeny.desktop.app.reception

import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.core.types.ExtensibleEnumLabels
import com.zeny.desktop.core.types.HotelClock
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.core.types.parseApiInstant
import com.zeny.desktop.design.feedback.ChipModel
import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.design.feedback.chipModel
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Textos e formatação puros da recepção (pt-BR); sem estado. */
@Suppress("TooManyFunctions") // superfície mandada pelo plano: uma função pura por texto
object ReceptionText {
    private const val UNKNOWN = "—"
    private const val OTHER_FIELDS = "outros dados"

    private val locale = Locale.forLanguageTag("pt-BR")
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val instantFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    private val statusLabels =
        ExtensibleEnumLabels(
            mapOf(
                "PROVISIONAL" to "Pré-reserva",
                "CONFIRMED" to "Confirmada",
                "IN_HOUSE" to "Hospedada",
                "CHECKED_OUT" to "Saída registrada",
                "CANCELLED" to "Cancelada",
                "NO_SHOW" to "Não compareceu",
            ),
        )
    private val statusTones =
        mapOf(
            "PROVISIONAL" to StatusTone.WARNING,
            "CONFIRMED" to StatusTone.INFO,
            "IN_HOUSE" to StatusTone.SUCCESS,
            "CHECKED_OUT" to StatusTone.NEUTRAL,
            "CANCELLED" to StatusTone.DANGER,
            "NO_SHOW" to StatusTone.DANGER,
        )
    private val historyKinds =
        ExtensibleEnumLabels(
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
            ),
        )
    private val fieldLabels =
        mapOf(
            "adults" to "adultos",
            "childAges" to "crianças",
            "arrival" to "chegada",
            "departure" to "saída",
            "cancellationTerms" to "política de cancelamento",
            "guaranteeTerms" to "política de garantia",
            "guarantee" to "garantia",
            "nightlyRates" to "diárias",
            "ratePlanId" to "plano",
            "roomTypeId" to "tipo de UH",
            "segment" to "segmento",
            "specialRequests" to "pedidos especiais",
            "status" to "situação",
            "checkIn" to "check-in",
            "checkOut" to "check-out",
            "guests" to "hóspedes",
            "roomAssignment" to "UH",
        )
    private val assignmentReasons =
        ExtensibleEnumLabels(
            mapOf(
                "INITIAL" to "Alocação inicial",
                "GUEST_REQUEST" to "Pedido do hóspede",
                "OUT_OF_ORDER" to "UH fora de ordem",
                "ROOM_ISSUE" to "Problema na UH",
                "OPERATIONAL" to "Operacional",
            ),
        )
    private val releaseReasons =
        ExtensibleEnumLabels(
            mapOf(
                "UNASSIGNED" to "Liberada",
                "REALLOCATED" to "Realocada",
                "RESERVATION_CANCELLED" to "Reserva cancelada",
                "RESERVATION_CHANGED" to "Reserva alterada",
                "CHECKED_OUT" to "Check-out",
                "ROOM_MOVED" to "Troca de UH",
            ),
        )
    private val planCategories =
        ExtensibleEnumLabels(
            mapOf("NORMAL" to "Normal", "COURTESY" to "Cortesia", "HOUSE_USE" to "Uso da casa"),
        )
    private val documentLabels =
        mapOf(
            "CPF" to "CPF",
            "RG" to "RG",
            "CNH" to "CNH",
            "RNM" to "RNM",
            "PASSPORT" to "Passaporte",
            "FOREIGN_ID" to "Documento estrangeiro",
        )

    fun status(value: String): ChipModel = chipModel(value, statusLabels, statusTones)

    fun historyKind(value: String): String = historyKinds.label(value)

    fun changedFields(fields: List<String>): String {
        val known = fields.mapNotNull { fieldLabels[it] }.distinct()
        val hasUnknown = fields.any { it !in fieldLabels }
        return (if (hasUnknown) known + OTHER_FIELDS else known).joinToString(", ")
    }

    fun assignmentReason(value: String): String = assignmentReasons.label(value)

    fun releaseReason(value: String): String = releaseReasons.label(value)

    fun planCategory(value: String): String = planCategories.label(value)

    fun document(
        type: String,
        masked: String,
    ): String = "${documentLabels[type] ?: "Documento"} $masked"

    fun date(iso: String): String = parseDate(iso)?.format(dateFormat) ?: UNKNOWN

    fun period(
        arrival: String,
        departure: String,
        dayUse: Boolean,
    ): String {
        val from = parseDate(arrival)
        val to = parseDate(departure)
        return when {
            from == null -> {
                UNKNOWN
            }

            dayUse -> {
                "${from.format(dateFormat)} · day use"
            }

            to == null -> {
                UNKNOWN
            }

            else -> {
                val nights = ChronoUnit.DAYS.between(from, to)
                val noun = if (nights == 1L) "noite" else "noites"
                "${from.format(dateFormat)} a ${to.format(dateFormat)} · $nights $noun"
            }
        }
    }

    fun guests(
        adults: Int,
        children: Int,
    ): String {
        val adultText = "$adults ${if (adults == 1) "adulto" else "adultos"}"
        if (children <= 0) return adultText
        return "$adultText e $children ${if (children == 1) "criança" else "crianças"}"
    }

    fun money(decimal: String): String {
        val amount =
            try {
                Money.parse(decimal).amount
            } catch (_: IllegalArgumentException) {
                return UNKNOWN
            }
        val format =
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }
        return "R$ ${format.format(amount)}"
    }

    fun instant(
        iso: String,
        zone: ZoneId?,
    ): String? {
        if (zone == null) return null
        return try {
            HotelClock(zone).local(parseApiInstant(iso)).format(instantFormat)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseDate(iso: String) =
        try {
            BusinessDate.parse(iso).value
        } catch (_: IllegalArgumentException) {
            null
        }
}
