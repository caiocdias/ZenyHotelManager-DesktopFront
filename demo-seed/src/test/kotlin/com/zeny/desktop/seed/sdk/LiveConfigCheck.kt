package com.zeny.desktop.seed.sdk

import com.zeny.desktop.seed.cli.ParseResult
import com.zeny.desktop.seed.cli.SeedArgs
import com.zeny.desktop.seed.config.ConfigSeeder
import com.zeny.desktop.seed.config.HousekeepingPlan
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import com.zeny.desktop.seed.people.AdminSignIn
import com.zeny.desktop.seed.people.shutDown
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EXPECTED_ROOMS = 20
private const val EXPECTED_TYPES = 3

/** Verificação ao vivo: só roda com `ZENY_SEED_SERVER`; credenciais só do ambiente (nunca impressas). */
class LiveConfigCheck {
    @Test
    fun seedingTwiceCreatesNothingTheSecondTime() {
        assumeTrue(System.getenv("ZENY_SEED_SERVER") != null)
        val parsed = SeedArgs.parse(emptyList(), System.getenv())
        assertTrue(parsed is ParseResult.Ok, "variáveis ZENY_SEED_* incompletas")
        val config = parsed.config
        val today = LocalDate.now(ZoneId.of("America/Sao_Paulo"))
        val data = DemoCatalog.build(today)
        runBlocking {
            val session = AdminSignIn(SdkSessionFactory(config.server)).open(config)
            try {
                val gateway = SdkConfigGateway(session.seed)
                val first = SeedReport()
                val result = ConfigSeeder(gateway, first).seed(data, today)
                println("1a execução: $first")
                assertEquals(EXPECTED_ROOMS, gateway.rooms().size)
                assertEquals(EXPECTED_TYPES, result.roomTypeIds.size)
                val states = gateway.rooms()
                for (spec in data.rooms) {
                    val state = states.getValue(spec.number)
                    assertTrue(HousekeepingPlan.transitions(state, spec.target).isEmpty(), "UH fora do alvo de status")
                    assertEquals(spec.outOfOrder, state.outOfOrder, "OOO da UH diverge do catálogo")
                }
                val second = SeedReport()
                ConfigSeeder(gateway, second).seed(data, today)
                println("2a execução: $second")
                val created = SeedItem.entries.filter { second.createdCount(it) > 0 }
                assertTrue(created.isEmpty(), "a segunda execução criou: ${created.map { it.label }}")
                assertEquals(EXPECTED_ROOMS, gateway.rooms().size)
            } finally {
                session.shutDown()
            }
        }
    }
}
