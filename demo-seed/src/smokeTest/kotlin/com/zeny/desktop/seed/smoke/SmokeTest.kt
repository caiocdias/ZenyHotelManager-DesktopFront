package com.zeny.desktop.seed.smoke

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.seed.DemoSeed
import com.zeny.desktop.seed.cli.ParseResult
import com.zeny.desktop.seed.cli.SeedArgs
import com.zeny.desktop.seed.cli.SeedConfig
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.DemoRole
import com.zeny.desktop.seed.data.HousekeepingTarget
import com.zeny.desktop.seed.data.UserSpec
import com.zeny.desktop.seed.people.AdminSignIn
import com.zeny.desktop.seed.people.SessionPort
import com.zeny.desktop.seed.people.SignIn
import com.zeny.desktop.seed.sdk.SdkSession
import com.zeny.desktop.seed.sdk.SdkSessionFactory
import de.quati.kotlin.util.Option
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fumaça contra um servidor real: `./gradlew smokeTest -PserverUrl=http://127.0.0.1:8088`. O endereço vem da
 * propriedade `zeny.smoke.serverUrl`; as credenciais, só do ambiente (`ZENY_SEED_*`), e nada delas é impresso.
 * Só contagens e rótulos de tipo aparecem nas mensagens: nunca dado de hóspede.
 */
@OptIn(RawSdkAccess::class)
class SmokeTest {
    private val config: SeedConfig by lazy {
        val server = System.getProperty("zeny.smoke.serverUrl") ?: fail("Informe -PserverUrl=http://endereço:porta")
        when (val parsed = SeedArgs.parse(listOf("--server", server), System.getenv())) {
            is ParseResult.Ok -> parsed.config
            is ParseResult.Invalid -> fail("Configuração do ambiente inválida: ${parsed.message}")
            ParseResult.Help -> fail("Configuração do ambiente inválida")
        }
    }

    private suspend fun <I> readAll(fetch: suspend (cursor: String?, limit: Int) -> Page<I>): List<I> {
        val pager = CursorPager(fetch = fetch)
        val all = mutableListOf<I>()
        while (pager.hasMore) all += pager.next()
        return all
    }

    @Test
    fun seedRunsAndServerAnswersLikeTheDemo() =
        runBlocking {
            val lines = mutableListOf<String>()
            val code = DemoSeed.run(config, lines::add)
            assertEquals(0, code, "o seed terminou com falha: " + lines.lastOrNull().orEmpty())

            val session = AdminSignIn(SdkSessionFactory(config.server)).open(config)
            val users =
                try {
                    checkServer(session)
                } finally {
                    close(session)
                }
            checkRoleLogins(config.server, users)
        }

    private suspend fun checkServer(session: SdkSession): List<UserSpec> {
        val core = session.seed.core
        val apis = session.seed.apis
        val executor = session.seed.executor

        val version = executor.execute { apis.meta.getServerVersion() }.value
        assertEquals("zeny-hotel-manager", version.application, "application")
        assertEquals("v1", version.apiVersion, "apiVersion")

        core.account.me()

        val statuses =
            readAll { cursor, limit ->
                val page = executor.execute { apis.rooms.listRoomStatuses(null, null, cursor, limit, it.headers) }.value
                Page(page.items, (page.nextCursor as? Option.Some)?.value)
            }
        assertTrue(statuses.size >= MIN_ROOMS, "UHs no painel: ${statuses.size} (esperado ao menos $MIN_ROOMS)")
        val byNumber = statuses.associateBy { it.number }
        val businessDate = core.hotel.businessDate().value
        val catalog = DemoCatalog.build(businessDate)
        catalog.rooms.forEach { spec ->
            val room = byNumber[spec.number] ?: fail("UH ${spec.number} ausente do painel")
            val (status, pending) =
                when (spec.target) {
                    HousekeepingTarget.DIRTY -> "DIRTY" to false
                    HousekeepingTarget.CLEAN_PENDING_INSPECTION -> "CLEAN" to true
                    HousekeepingTarget.CLEAN -> "CLEAN" to false
                    HousekeepingTarget.INSPECTED -> "INSPECTED" to false
                }
            assertEquals(status, room.housekeepingStatus.value, "governança da UH ${spec.number}")
            assertEquals(pending, room.inspectionPending, "inspeção pendente da UH ${spec.number}")
        }

        val codes =
            readAll { cursor, limit ->
                val page = executor.execute { apis.rooms.listRoomTypes(null, cursor, limit, it.headers) }.value
                Page(page.items, (page.nextCursor as? Option.Some)?.value)
            }.map { it.code.uppercase() }
        listOf("STD", "LUX", "SUI").forEach { assertTrue(it in codes, "tipo de UH $it ausente") }

        val areas =
            readAll { cursor, limit ->
                val page = executor.execute { apis.rooms.listAreas(null, null, cursor, limit, it.headers) }.value
                Page(page.items, (page.nextCursor as? Option.Some)?.value)
            }
        assertTrue(areas.isNotEmpty(), "nenhuma área cadastrada")

        val date = businessDate.toString()
        val arrivals = executor.execute { apis.reservations.listArrivals(date, null, 1, it.headers) }.value
        val inHouse = executor.execute { apis.reservations.listInHouse(date, null, 1, it.headers) }.value
        val departures = executor.execute { apis.reservations.listDepartures(date, null, 1, it.headers) }.value
        assertTrue(arrivals.items.isNotEmpty(), "sem chegadas na data de negócio")
        assertTrue(inHouse.items.isNotEmpty(), "sem hospedados na data de negócio")
        assertTrue(departures.items.isNotEmpty(), "sem saídas na data de negócio")
        return catalog.users
    }

    private suspend fun checkRoleLogins(
        server: ServerUrl,
        users: List<UserSpec>,
    ) {
        val passwords =
            mapOf(
                DemoRole.MANAGER to config.managerPassword,
                DemoRole.RECEPTIONIST to config.receptionistPassword,
                DemoRole.HOUSEKEEPER to config.housekeeperPassword,
            )
        val factory = SdkSessionFactory(server)
        users.forEach { user ->
            val session = factory.open()
            try {
                val result = session.signIn(user.login, passwords.getValue(user.role))
                assertEquals(SignIn.Active, result, "login de ${user.login}")
            } finally {
                close(session)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a saída é melhor esforço
    private suspend fun close(session: SessionPort) {
        try {
            session.signOut()
        } catch (e: Exception) {
            // sem sessão ou sem rede: nada a encerrar
        } finally {
            session.close()
        }
    }

    private companion object {
        const val MIN_ROOMS = 20
    }
}
