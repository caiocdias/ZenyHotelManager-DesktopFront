package com.zeny.desktop.seed.folio

import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.DemoData
import com.zeny.desktop.seed.data.SeedReport

private const val MARK = "Seed M4: "

/**
 * Lança os consumos de exemplo nas contas das reservas hospedadas do seed. Cada consumo leva a marca
 * "Seed M4: <item>" na descrição e só é lançado se a conta ainda não tem um lançamento com ela; repetir o seed não
 * duplica nada. Sem código ativo com taxa de serviço no servidor, ou sem conta numa reserva, o seed avisa e segue.
 */
class FolioSeeder(
    private val gateway: FolioGateway,
    private val report: SeedReport,
) {
    @Suppress("UnusedParameter") // a assinatura segue a dos outros seeders; o que importa vem do catálogo
    suspend fun seed(
        data: DemoData,
        seeded: SeededConfig,
    ) {
        val codeId = gateway.chargeableCodeId(DemoCatalog.CHARGEABLE_CODE_NAMES)
        if (codeId == null) {
            report.warn("sem código de lançamento ativo com taxa de serviço; os consumos de exemplo ficaram de fora.")
            return
        }
        for (spec in data.reservations.filter { it.checkIn }) {
            val folioId = gateway.reservationId(spec.externalId)?.let { gateway.reservationFolioId(it) }
            if (folioId == null) {
                report.warn("reserva de exemplo ${spec.slot} sem conta aberta; os consumos dela não foram lançados.")
            } else {
                postMissing(folioId, codeId, data)
            }
        }
    }

    private suspend fun postMissing(
        folioId: String,
        codeId: String,
        data: DemoData,
    ) {
        val existing = gateway.postingDescriptions(folioId).toSet()
        for (consumption in data.consumptions) {
            val description = MARK + consumption.item
            if (description in existing) {
                report.consumption(created = false)
            } else {
                gateway.post(folioId, codeId, consumption.unitPrice, description)
                report.consumption(created = true)
            }
        }
    }
}
