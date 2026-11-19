package com.zeny.desktop.seed

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.booking.FakeBookingGateway
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.cli.SeedConfig
import com.zeny.desktop.seed.config.ConfigSeeder
import com.zeny.desktop.seed.config.FakeConfigGateway
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.SeedReport
import com.zeny.desktop.seed.folio.FakeFolioGateway
import com.zeny.desktop.seed.frontdesk.FakeTraces
import com.zeny.desktop.seed.people.FakeIam
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ECHO = "DETALHE-MARCADO-DO-SERVIDOR"

/** A ligação do `FolioSeeder` no `DemoSeed.run` (M4 E05): a linha "consumos:" do relatório e as recusas. */
class DemoSeedFolioAtaqueTest {
    private val date = LocalDate.of(2026, 11, 19)
    private val data = DemoCatalog.build(date)
    private val inHouse = data.reservations.filter { it.checkIn }
    private val config =
        SeedConfig(
            server = ServerUrl.parse("http://localhost:8088")!!,
            adminLogin = "admin",
            adminPassword = Secret("Inicial-Admin-123"),
            adminNewPassword = Secret("Nova-Admin-12345"),
            managerPassword = Secret("Senha-Gerente-123"),
            receptionistPassword = Secret("Senha-Recepcao-123"),
            housekeeperPassword = Secret("Senha-Governanca-1"),
            managerPin = Secret("482913"),
        )
    private val configGateway = FakeConfigGateway()
    private val iam =
        FakeIam().also {
            it.plant("admin", "Inicial-Admin-123", mustChange = false)
            it.users.getValue("admin").password = "Nova-Admin-12345"
        }
    private val bookings =
        FakeBookingGateway(
            kotlinx.coroutines.runBlocking { ConfigSeeder(FakeConfigGateway(), SeedReport()).seed(data, date) },
            date,
        )
    private val traces = FakeTraces()
    private val lines = mutableListOf<String>()

    private fun folios(): FakeFolioGateway {
        val gateway = FakeFolioGateway()
        for (spec in inHouse) gateway.plant(spec.externalId)
        return gateway
    }

    private suspend fun execute(folios: FakeFolioGateway?): Int {
        val open: suspend (SeedConfig) -> AdminContext = { _ ->
            val session = iam.open()
            session.signIn("admin", config.adminNewPassword ?: config.adminPassword)
            val admin = AdminContext(session, { date }, configGateway, iam, bookings, traces, iam)
            admin.folios = folios
            admin
        }
        val ticks = ArrayDeque(listOf(1_000_000_000L, 2_000_000_000L))
        return DemoSeed.run(config, open, { ticks.removeFirst() }, { lines += it })
    }

    @Test
    fun reportPrintsTheConsumptionsLineWhenTheFolioPortIsWired() =
        runTest {
            val gateway = folios()
            assertEquals(0, execute(gateway))
            val expected = inHouse.size * data.consumptions.size
            assertTrue(lines.contains("consumos: $expected criados, 0 já existiam"), lines.toString())
            assertEquals(expected, gateway.postings.size)
        }

    @Test
    fun reportHasNoConsumptionsLineWithoutTheFolioPort() =
        runTest {
            assertEquals(0, execute(null))
            assertTrue(lines.none { it.startsWith("consumos:") }, lines.toString())
        }

    @Test
    fun secondRunPrintsTheConsumptionsAsKeptAndPostsNothingMore() =
        runTest {
            val gateway = folios()
            assertEquals(0, execute(gateway))
            val count = gateway.postings.size
            lines.clear()
            assertEquals(0, execute(gateway))
            assertEquals(count, gateway.postings.size)
            assertTrue(lines.contains("consumos: 0 criados, $count já existiam"), lines.toString())
        }

    @Test
    fun aRefusedPostingExitsOneWithoutTheServerDetailAndARerunCompletes() =
        runTest {
            val gateway = folios()
            gateway.failures += ApiError.Problem(422, "ALGUM_CODIGO", ECHO, emptyList())
            assertEquals(1, execute(gateway))
            val text = lines.joinToString("\n")
            assertFalse(ECHO in text, text)
            assertTrue(lines.any { it.startsWith("Falha:") }, text)
            assertFalse(lines.any { it.startsWith("Concluído") }, text)
            lines.clear()
            assertEquals(0, execute(gateway))
            assertEquals(inHouse.size * data.consumptions.size, gateway.postings.size)
        }

    @Test
    fun noFeeCodeStillExitsZeroWithTheWarningAndNoConsumptionsLine() =
        runTest {
            val gateway = folios().also { it.codeId = null }
            assertEquals(0, execute(gateway))
            assertTrue(gateway.postings.isEmpty())
            assertTrue(lines.none { it.startsWith("consumos:") }, lines.toString())
            assertTrue(lines.any { "taxa de serviço" in it }, lines.toString())
        }
}
