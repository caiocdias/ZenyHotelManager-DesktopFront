package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.CursorPageReservationListItemResponse
import com.zeny.desktop.seed.booking.BookingSeeder
import com.zeny.desktop.seed.cli.ParseResult
import com.zeny.desktop.seed.cli.SeedArgs
import com.zeny.desktop.seed.cli.SeedConfig
import com.zeny.desktop.seed.config.ConfigSeeder
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.DemoRole
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import com.zeny.desktop.seed.people.AdminSignIn
import com.zeny.desktop.seed.people.UserSeeder
import com.zeny.desktop.seed.people.shutDown
import de.quati.kotlin.util.Option
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EXPECTED_ARRIVALS = 11
private const val EXPECTED_IN_HOUSE = 7
private const val EXPECTED_DEPARTURES = 2
private const val EXPECTED_COMPANIES = 2
private const val EXPECTED_GUESTS = 12

/**
 * Verificação ao vivo: só roda com `ZENY_SEED_SERVER` e `ZENY_SEED_LIVE=1` (sem elas, pula); credenciais só do
 * ambiente (nunca impressas). Comando, só nesse subshell:
 * `(set -a; . <arquivo de segredos>; set +a; ZENY_SEED_LIVE=1 ./gradlew :demo-seed:test --tests '*Live*' --rerun)`
 */
@OptIn(RawSdkAccess::class)
class LiveBookingCheck {
    private fun config(): SeedConfig {
        val parsed = SeedArgs.parse(emptyList(), System.getenv())
        assertTrue(parsed is ParseResult.Ok, "variáveis ZENY_SEED_* incompletas")
        return parsed.config
    }

    private suspend fun countDemo(list: suspend (String?, Int) -> CursorPageReservationListItemResponse): Int {
        val pager =
            CursorPager { cursor, limit ->
                val page = list(cursor, limit)
                Page(page.items, (page.nextCursor as? Option.Some)?.value)
            }
        var count = 0
        while (pager.hasMore) {
            count += pager.next().count { (it.externalId as? Option.Some)?.value.orEmpty().startsWith("DEMO-SEED-") }
        }
        return count
    }

    private suspend fun seedAll(
        config: SeedConfig,
        factory: SdkSessionFactory,
    ): SeedReport {
        val report = SeedReport()
        val admin = AdminSignIn(factory).open(config)
        try {
            val today =
                admin.seed.core.hotel
                    .businessDate()
                    .value
            val data = DemoCatalog.build(today)
            val passwords =
                mapOf(
                    DemoRole.MANAGER to config.managerPassword,
                    DemoRole.RECEPTIONIST to config.receptionistPassword,
                    DemoRole.HOUSEKEEPER to config.housekeeperPassword,
                )
            val seeded = ConfigSeeder(SdkConfigGateway(admin.seed), report).seed(data, today)
            UserSeeder(SdkUserGateway(admin.seed), factory, report).seed(data.users, passwords, config.managerPin)
            BookingSeeder(SdkBookingGateway(admin.seed), report).seed(data, seeded)
            val api = admin.seed.apis.reservations
            val exec = admin.seed.executor
            val date = today.toString()
            assertEquals(
                EXPECTED_ARRIVALS,
                countDemo { c, l -> exec.execute { api.listArrivals(date, c, l, it.headers) }.value },
                "chegadas DEMO-SEED",
            )
            assertEquals(
                EXPECTED_IN_HOUSE,
                countDemo { c, l -> exec.execute { api.listInHouse(date, c, l, it.headers) }.value },
                "hospedados DEMO-SEED",
            )
            assertEquals(
                EXPECTED_DEPARTURES,
                countDemo { c, l -> exec.execute { api.listDepartures(date, c, l, it.headers) }.value },
                "saídas DEMO-SEED",
            )
        } finally {
            admin.shutDown()
        }
        return report
    }

    @Test
    fun bookingsSeededAndSecondRunCreatesNothing() {
        assumeTrue(System.getenv("ZENY_SEED_SERVER") != null && System.getenv("ZENY_SEED_LIVE") == "1")
        val config = config()
        runBlocking {
            val factory = SdkSessionFactory(config.server)
            val first = seedAll(config, factory)
            println("1a execução: $first")
            val second = seedAll(config, factory)
            println("2a execução: $second")
            val created = SeedItem.entries.filter { second.createdCount(it) > 0 }
            assertTrue(created.isEmpty(), "a segunda execução criou: ${created.map { it.label }}")
            assertEquals(EXPECTED_COMPANIES, second.keptCount(SeedItem.COMPANY))
            assertEquals(EXPECTED_GUESTS, second.keptCount(SeedItem.GUEST))
            assertEquals(EXPECTED_ARRIVALS, second.keptCount(SeedItem.RESERVATION))
        }
    }
}
