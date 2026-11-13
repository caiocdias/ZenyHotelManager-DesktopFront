package com.zeny.desktop.seed.data

enum class SeedItem(
    val label: String,
) {
    HOTEL("hotel"),
    POLICY("políticas"),
    ROOM_TYPE("tipos de UH"),
    AREA("áreas"),
    ROOM("UHs"),
    ROOM_STATUS("status de UH"),
    OUT_OF_ORDER("bloqueios OOO"),
    SEASON("temporadas"),
    RATE_PLAN("planos"),
    PRICES("tabelas de preço"),
    USER("usuários"),
    COMPANY("empresas"),
    GUEST("hóspedes"),
    RESERVATION("reservas"),
    ROOM_ASSIGNMENT("alocações"),
    CHECK_IN("check-ins"),
}

/** Relatório do seed: só contagens por tipo de item, nunca dado de hóspede ou empresa (LGPD). */
class SeedReport {
    private val created = IntArray(SeedItem.entries.size)
    private val kept = IntArray(SeedItem.entries.size)

    fun created(item: SeedItem) {
        created[item.ordinal]++
    }

    fun kept(item: SeedItem) {
        kept[item.ordinal]++
    }

    fun createdCount(item: SeedItem): Int = created[item.ordinal]

    fun keptCount(item: SeedItem): Int = kept[item.ordinal]

    fun lines(): List<String> =
        SeedItem.entries
            .filter { createdCount(it) + keptCount(it) > 0 }
            .map { "${it.label}: ${createdCount(it)} criados, ${keptCount(it)} já existiam" }

    /** Só rótulos e contagens; nunca o hash de identidade do objeto. */
    override fun toString(): String = "SeedReport(" + lines().joinToString("; ") + ")"
}
