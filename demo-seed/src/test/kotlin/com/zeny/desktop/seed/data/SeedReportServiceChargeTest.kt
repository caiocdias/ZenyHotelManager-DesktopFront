package com.zeny.desktop.seed.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SeedReportServiceChargeTest {
    @Test
    fun serviceChargeLineSaysDefined() {
        val report = SeedReport()
        report.serviceCharge("10", defined = true)
        assertEquals(listOf("taxa de serviço: 10% (definida)"), report.lines())
    }

    @Test
    fun serviceChargeLineSaysAlreadyConfigured() {
        val report = SeedReport()
        report.serviceCharge("10", defined = false)
        assertEquals(listOf("taxa de serviço: 10% (já configurada)"), report.lines())
    }

    @Test
    fun serviceChargeLineComesRightAfterTheHotelAndBeforeWarnings() {
        val report = SeedReport()
        report.warn("x")
        report.serviceCharge("10", defined = true)
        report.created(SeedItem.ROOM)
        report.created(SeedItem.HOTEL)
        assertEquals(
            listOf(
                "hotel: 1 criados, 0 já existiam",
                "taxa de serviço: 10% (definida)",
                "UHs: 1 criados, 0 já existiam",
                "aviso: x",
            ),
            report.lines(),
        )
    }

    @Test
    fun reportWithoutServiceChargeHasNoSuchLine() {
        assertEquals(emptyList(), SeedReport().lines())
    }
}
