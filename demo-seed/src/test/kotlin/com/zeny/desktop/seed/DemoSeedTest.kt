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
import com.zeny.desktop.seed.people.FakeIam
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoSeedTest {
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

    private val configGateway = FakeConfigGateway()
    private val iam = FakeIam().also { it.plant("admin", "Inicial-Admin-123", mustChange = true) }
    private val bookings =
        FakeBookingGateway(
            runBlockingSeeded(),
            date,
        )
    private var adminSession: FakeIam.FakeSession? = null
    private val lines = mutableListOf<String>()

    /** Os ids do falso de configuração são determinísticos: um falso descartável dá o `SeededConfig` esperado. */
    private fun runBlockingSeeded() =
        kotlinx.coroutines.runBlocking { ConfigSeeder(FakeConfigGateway(), SeedReport()).seed(data, date) }

    private suspend fun openAdmin(
        @Suppress("UNUSED_PARAMETER") cfg: SeedConfig,
    ): AdminContext {
        val session = iam.open().also { adminSession = it }
        session.signIn("admin", config.adminNewPassword ?: config.adminPassword)
        return AdminContext(session, { date }, configGateway, iam, bookings, iam)
    }

    private val ticks = longArrayOf(1_000_000_000L, 2_500_000_000L)

    private suspend fun execute(open: suspend (SeedConfig) -> AdminContext = ::openAdmin): Int {
        val queue = ticks.toMutableList()
        return DemoSeed.run(config, open, { queue.removeFirst() }, { lines += it })
    }

    @Test
    fun happyPathPrintsCountsAndElapsed() =
        runTest {
            iam.users.getValue("admin").mustChange = false
            iam.users.getValue("admin").password = "Nova-Admin-12345"
            assertEquals(0, execute())
            assertTrue(lines.any { it.startsWith("reservas: 11 criados, 0 já existiam") }, lines.toString())
            assertTrue(lines.any { it.startsWith("UHs: 20 criados") }, lines.toString())
            assertEquals("Concluído em 1,5 s.", lines.last())
        }

    @Test
    fun secondRunPrintsOnlyKept() =
        runTest {
            iam.users.getValue("admin").mustChange = false
            iam.users.getValue("admin").password = "Nova-Admin-12345"
            assertEquals(0, execute())
            lines.clear()
            assertEquals(0, execute())
            assertTrue(lines.any { it.startsWith("reservas: 0 criados, 11 já existiam") }, lines.toString())
            assertFalse(lines.any { Regex("""^[^:]+: [1-9]\d* criados""").containsMatchIn(it) }, lines.toString())
        }

    @Test
    fun seedFailureExitsOneWithMessage() =
        runTest {
            iam.roles.clear()
            assertEquals(1, execute())
            assertTrue(lines.any { it.contains("modelo de perfil") }, lines.toString())
            assertFalse(lines.any { it.startsWith("Concluído") })
        }

    @Test
    fun apiErrorPrintsUserMessageWithoutCause() =
        runTest {
            val secretUrl = "http://h/12345678000190"
            val code =
                execute { throw ApiError.Network(IllegalStateException(secretUrl)) }
            assertEquals(1, code)
            assertEquals(listOf("Falha: Não foi possível falar com o servidor. Confira o endereço e a rede."), lines)
        }

    @Test
    fun networkErrorExitsOneWithoutStackTrace() =
        runTest {
            val code = execute { throw ApiError.Network(java.net.ConnectException("Connection refused")) }
            assertEquals(1, code)
            val text = lines.joinToString("\n")
            assertFalse(text.contains("Exception"), text)
            assertFalse(Regex("""\bat com\.""").containsMatchIn(text), text)
        }

    @Test
    fun unexpectedErrorExitsOneWithoutMessageNorStack() =
        runTest {
            val code = execute { throw IllegalStateException("cpf 12345678909") }
            assertEquals(1, code)
            val text = lines.joinToString("\n")
            assertFalse(text.contains("12345678909"), text)
        }

    @Test
    fun sessionsClosedOnFailure() =
        runTest {
            iam.roles.clear()
            assertEquals(1, execute())
            val admin = checkNotNull(adminSession)
            assertTrue(admin.signedOut)
            assertTrue(admin.closed)
        }

    @Test
    fun sessionsClosedOnSuccess() =
        runTest {
            iam.users.getValue("admin").mustChange = false
            iam.users.getValue("admin").password = "Nova-Admin-12345"
            assertEquals(0, execute())
            val admin = checkNotNull(adminSession)
            assertTrue(admin.signedOut && admin.closed)
        }

    @Test
    fun outputHasNoSecretsNorGuestData() =
        runTest {
            iam.users.getValue("admin").mustChange = false
            iam.users.getValue("admin").password = "Nova-Admin-12345"
            assertEquals(0, execute())
            val text = lines.joinToString("\n")
            val secrets =
                listOf(
                    config.adminPassword,
                    config.adminNewPassword!!,
                    config.managerPassword,
                    config.receptionistPassword,
                    config.housekeeperPassword,
                    config.managerPin,
                ).map { it.value }
            secrets.forEach { assertFalse(text.contains(it), "vazou segredo") }
            data.guests.forEach {
                assertFalse(text.contains(it.cpf), "vazou CPF")
                assertFalse(text.contains(it.fullName), "vazou nome")
            }
            data.companies.forEach { assertFalse(text.contains(it.cnpj), "vazou CNPJ") }
        }

    private fun cli(args: List<String>): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(args, emptyMap(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Triple(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    @Test
    fun helpExitsZeroAndPrintsUsage() {
        val (code, out, _) = cli(listOf("--help"))
        assertEquals(0, code)
        assertTrue(out.contains("--server"))
    }

    @Test
    fun unknownOptionExitsTwoWithMessageAndUsageOnStderr() {
        val (code, out, err) = cli(listOf("--nada"))
        assertEquals(2, code)
        assertEquals("", out)
        assertTrue(err.contains("Opção desconhecida: --nada."), err)
        assertTrue(err.contains("Uso: demo-seed"), err)
    }
}
