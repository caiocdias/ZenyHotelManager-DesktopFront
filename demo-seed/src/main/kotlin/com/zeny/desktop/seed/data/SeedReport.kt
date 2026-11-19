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
    private var serviceChargeLine: String? = null

    /** Consumos de exemplo lançados e já presentes nas contas. Contados à parte dos `SeedItem`: não são cadastro. */
    var consumptionsCreated = 0
        private set
    var consumptionsKept = 0
        private set

    fun created(item: SeedItem) {
        created[item.ordinal]++
    }

    fun kept(item: SeedItem) {
        kept[item.ordinal]++
    }

    /** Linha da taxa de serviço padrão do hotel: `defined` é `true` se o seed a gravou, `false` se já estava. */
    fun serviceCharge(
        rate: String,
        defined: Boolean,
    ) {
        serviceChargeLine = "taxa de serviço: $rate% (${if (defined) "definida" else "já configurada"})"
    }

    /** Registra um consumo de exemplo: lançado agora (`created`) ou já presente na conta. */
    fun consumption(created: Boolean) {
        if (created) consumptionsCreated++ else consumptionsKept++
    }

    /** Aviso que não falha o seed; texto fixo, sem dado de hóspede ou empresa (LGPD). */
    fun warn(message: String) {
        warnings += message
    }

    fun warnings(): List<String> = warnings.toList()

    fun createdCount(item: SeedItem): Int = created[item.ordinal]

    fun keptCount(item: SeedItem): Int = kept[item.ordinal]

    /**
     * A taxa de serviço vem logo depois do hotel (primeiro item), onde o seed a configura; a linha dos consumos
     * fecha os itens, e os avisos vêm por último.
     */
    fun lines(): List<String> {
        val consumptions =
            listOf("consumos: $consumptionsCreated criados, $consumptionsKept já existiam")
                .filter { consumptionsCreated + consumptionsKept > 0 }
        val items =
            SeedItem.entries
                .filter { createdCount(it) + keptCount(it) > 0 }
                .map { "${it.label}: ${createdCount(it)} criados, ${keptCount(it)} já existiam" } + consumptions
        val afterHotel = if (createdCount(SeedItem.HOTEL) + keptCount(SeedItem.HOTEL) > 0) 1 else 0
        return items.take(afterHotel) + listOfNotNull(serviceChargeLine) + items.drop(afterHotel) +
            warnings.map { "aviso: $it" }
    }

    /** Só rótulos e contagens; nunca o hash de identidade do objeto. */
    override fun toString(): String = "SeedReport(" + lines().joinToString("; ") + ")"
}
