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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EXPECTED_ROOMS = 20
private const val EXPECTED_TYPES = 3

/**
 * Verificação ao vivo: só roda com `ZENY_SEED_SERVER` e `ZENY_SEED_LIVE=1` (sem elas, pula); credenciais só do
 * ambiente (nunca impressas). Comando, só nesse subshell:
 * `(set -a; . <arquivo de segredos>; set +a; ZENY_SEED_LIVE=1 ./gradlew :demo-seed:test --tests '*Live*' --rerun)`
 */
class LiveConfigCheck {
    @Test
    fun seedingTwiceCreatesNothingTheSecondTime() {
        assumeTrue(System.getenv("ZENY_SEED_SERVER") != null && System.getenv("ZENY_SEED_LIVE") == "1")
        val parsed = SeedArgs.parse(emptyList(), System.getenv())
        assertTrue(parsed is ParseResult.Ok, "variáveis ZENY_SEED_* incompletas")
        val config = parsed.config
        runBlocking {
            val session = AdminSignIn(SdkSessionFactory(config.server)).open(config)
            try {
                val today =
                    session.seed.core.hotel
                        .businessDate()
                        .value
                val data = DemoCatalog.build(today)
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
