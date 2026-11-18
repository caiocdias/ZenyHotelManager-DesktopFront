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
    CASH_POINT("pontos de caixa"),
    ROLE("perfis"),
    USER("usuários"),
    COMPANY("empresas"),
    GUEST("hóspedes"),
    RESERVATION("reservas"),
    ROOM_ASSIGNMENT("alocações"),
    CHECK_IN("check-ins"),
    TRACE("recados"),
}

/** Relatório do seed: só contagens por tipo de item, nunca dado de hóspede ou empresa (LGPD). */
class SeedReport {
    private val created = IntArray(SeedItem.entries.size)
    private val kept = IntArray(SeedItem.entries.size)
    private val warnings = mutableListOf<String>()

    fun created(item: SeedItem) {
        created[item.ordinal]++
    }

    fun kept(item: SeedItem) {
        kept[item.ordinal]++
    }

    /** Aviso que não falha o seed; texto fixo, sem dado de hóspede ou empresa (LGPD). */
    fun warn(message: String) {
        warnings += message
    }

    fun warnings(): List<String> = warnings.toList()

    fun createdCount(item: SeedItem): Int = created[item.ordinal]

    fun keptCount(item: SeedItem): Int = kept[item.ordinal]

    fun lines(): List<String> =
        SeedItem.entries
            .filter { createdCount(it) + keptCount(it) > 0 }
            .map { "${it.label}: ${createdCount(it)} criados, ${keptCount(it)} já existiam" } +
            warnings.map { "aviso: $it" }

    /** Só rótulos e contagens; nunca o hash de identidade do objeto. */
    override fun toString(): String = "SeedReport(" + lines().joinToString("; ") + ")"
}
