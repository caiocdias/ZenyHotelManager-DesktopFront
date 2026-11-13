package com.zeny.desktop.seed.sdk

import com.zeny.desktop.seed.cli.ParseResult
import com.zeny.desktop.seed.cli.SeedArgs
import com.zeny.desktop.seed.cli.SeedConfig
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.DemoRole
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import com.zeny.desktop.seed.people.AdminSignIn
import com.zeny.desktop.seed.people.SignIn
import com.zeny.desktop.seed.people.UserSeeder
import com.zeny.desktop.seed.people.shutDown
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verificação ao vivo: só roda com `ZENY_SEED_SERVER` e `ZENY_SEED_LIVE=1` (sem elas, pula); credenciais só do
 * ambiente (nunca impressas). Comando, só nesse subshell:
 * `(set -a; . <arquivo de segredos>; set +a; ZENY_SEED_LIVE=1 ./gradlew :demo-seed:test --tests '*Live*' --rerun)`
 */
class LiveUsersCheck {
    private fun config(): SeedConfig {
        val parsed = SeedArgs.parse(emptyList(), System.getenv())
        assertTrue(parsed is ParseResult.Ok, "variáveis ZENY_SEED_* incompletas")
        return parsed.config
    }

    /** O teste redefine senha e PIN de usuários: só roda em servidor sem hotel ou com o hotel da demo. */
    private suspend fun assertDemoHotel(seed: SeedCore) {
        val name = SdkConfigGateway(seed).configuredHotelName()
        assertTrue(
            name == null || name == DemoCatalog.HOTEL_TRADE_NAME,
            "o servidor tem outro hotel configurado; a verificação de usuários não roda nele",
        )
    }

    private suspend fun seedUsers(config: SeedConfig): SeedReport {
        val report = SeedReport()
        val factory = SdkSessionFactory(config.server)
        val admin = AdminSignIn(factory).open(config)
        try {
            assertDemoHotel(admin.seed)
            val passwords =
                mapOf(
                    DemoRole.MANAGER to config.managerPassword,
                    DemoRole.RECEPTIONIST to config.receptionistPassword,
                    DemoRole.HOUSEKEEPER to config.housekeeperPassword,
                )
            val specs = DemoCatalog.build(LocalDate.of(2026, 1, 1)).users
            UserSeeder(SdkUserGateway(admin.seed), factory, report).seed(specs, passwords, config.managerPin)
            val gateway = SdkUserGateway(admin.seed)
            val managerRole = gateway.roleIdsByTemplate().getValue(DemoRole.MANAGER.template)
            val manager = assertNotNull(gateway.findUser("gerente"), "gerente não existe")
            assertTrue(managerRole in manager.roleIds, "gerente sem a role do modelo MANAGER")
        } finally {
            admin.shutDown()
        }
        return report
    }

    private suspend fun assertCanSignIn(config: SeedConfig) {
        val factory = SdkSessionFactory(config.server)
        for (
        (login, password) in
        listOf(
            "gerente" to config.managerPassword,
            "recepcao" to config.receptionistPassword,
            "governanta" to config.housekeeperPassword,
        )
        ) {
            val session = factory.open()
            try {
                assertEquals(SignIn.Active, session.signIn(login, password), "login do usuário $login não ficou ativo")
            } finally {
                session.shutDown()
            }
        }
    }

    @Test
    fun usersSeededAndSecondRunCreatesNothing() {
        assumeTrue(System.getenv("ZENY_SEED_SERVER") != null && System.getenv("ZENY_SEED_LIVE") == "1")
        val config = config()
        runBlocking {
            seedUsers(config)
            assertCanSignIn(config)
            val second = seedUsers(config)
            assertEquals(0, second.createdCount(SeedItem.USER), "a segunda execução criou usuários")
            assertEquals(3, second.keptCount(SeedItem.USER))
            assertCanSignIn(config)
        }
    }
}
