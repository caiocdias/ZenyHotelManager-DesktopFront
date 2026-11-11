package com.zeny.desktop.core.types

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Data de negócio (`YYYY-MM-DD`), sem conversão de fuso. */
@JvmInline
value class BusinessDate(
    val value: LocalDate,
) {
    fun toApi(): String = value.format(DateTimeFormatter.ISO_LOCAL_DATE)

    companion object {
        fun parse(text: String): BusinessDate =
            try {
                BusinessDate(LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE))
            } catch (_: DateTimeException) {
                throw IllegalArgumentException("Data inválida.")
            }
    }
}

/** Lê um instante ISO-8601 com `Z` ou offset. */
fun parseApiInstant(text: String): Instant =
    try {
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(text, Instant::from)
    } catch (_: DateTimeException) {
        throw IllegalArgumentException("Instante inválido.")
    }

fun Instant.toApi(): String = DateTimeFormatter.ISO_INSTANT.format(this)

/** Mostra instantes no fuso do hotel, nunca no da JVM. */
class HotelClock(
    val zone: ZoneId,
) {
    fun local(instant: Instant): ZonedDateTime = instant.atZone(zone)
}
