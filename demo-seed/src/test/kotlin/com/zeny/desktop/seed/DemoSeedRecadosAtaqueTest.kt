package com.zeny.desktop.seed

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.seed.booking.FakeBookingGateway
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.cli.SeedConfig
import com.zeny.desktop.seed.config.ConfigSeeder
import com.zeny.desktop.seed.config.FakeConfigGateway
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.SeedReport
import com.zeny.desktop.seed.frontdesk.FakeTraces
import com.zeny.desktop.seed.people.FakeIam
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ataque da Task 2 (E06): os recados na execução completa do seed (ordem do relatório, idempotência, data). */
class DemoSeedRecadosAtaqueTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val data = DemoCatalog.build(date)
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
    private val iam = FakeIam().also { it.plant("admin", "Nova-Admin-12345") }
    private val traces = FakeTraces()
    private val bookings =
        FakeBookingGateway(
            kotlinx.coroutines.runBlocking { ConfigSeeder(FakeConfigGateway(), SeedReport()).seed(data, date) },
            date,
        )
    private val lines = mutableListOf<String>()

    private suspend fun execute(businessDate: LocalDate = date): Int {
        val open: suspend (SeedConfig) -> AdminContext = {
            val s = iam.open()
            s.signIn("admin", Secret("Nova-Admin-12345"))
            AdminContext(s, { businessDate }, FakeConfigGateway(), iam, bookings, traces, iam)
        }
        val ticks = mutableListOf(0L, 1_000_000_000L)
        return DemoSeed.run(config, open, { ticks.removeFirst() }, { lines += it })
    }

    @Test
    fun reportLineComesRightAfterCheckInsAndBeforeTheElapsedLine() =
        runTest {
            assertEquals(0, execute())
            val checkIns = lines.indexOfFirst { it.startsWith("check-ins:") }
            assertTrue(checkIns >= 0, lines.toString())
            assertEquals("recados: 3 criados, 0 já existiam", lines[checkIns + 1], lines.toString())
            assertTrue(lines[checkIns + 2].startsWith("Concluído"), lines.toString())
        }

    @Test
    fun secondFullRunKeepsAllThreeAndCreatesNone() =
        runTest {
            assertEquals(0, execute())
            lines.clear()
            assertEquals(0, execute())
            assertTrue("recados: 0 criados, 3 já existiam" in lines, lines.toString())
            assertEquals(3, traces.traces.size)
        }

    @Test
    fun tracesAreDueOnTheServerBusinessDateNotTheCatalogDate() =
        runTest {
            val server = LocalDate.of(2026, 11, 13)
            assertEquals(0, execute(server))
            assertTrue(traces.traces.isNotEmpty())
            assertTrue(traces.traces.all { it.dueOn == server }, traces.traces.toString())
        }
}
