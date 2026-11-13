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
import com.zeny.desktop.seed.people.AdminSignIn
import com.zeny.desktop.seed.people.FakeIam
import com.zeny.desktop.seed.people.SessionFactory
import com.zeny.desktop.seed.people.SessionPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Ataque da Task 11: fronteira do programa (códigos de saída, vazamento, sessões fechadas, tempo). */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoSeedAtaqueTest {
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
    private val lines = mutableListOf<String>()
    private var session: FakeIam.FakeSession? = null

    private fun seeded() =
        kotlinx.coroutines.runBlocking { ConfigSeeder(FakeConfigGateway(), SeedReport()).seed(data, date) }

    private fun context(
        wrap: (FakeIam.FakeSession) -> SessionPort = { it },
        businessDate: suspend () -> LocalDate = { date },
    ): AdminContext {
        val s = iam.open().also { session = it }
        kotlinx.coroutines.runBlocking { s.signIn("admin", Secret("Nova-Admin-12345")) }
        return AdminContext(wrap(s), businessDate, FakeConfigGateway(), iam, FakeBookingGateway(seeded(), date), iam)
    }

    private suspend fun execute(
        ticks: List<Long> = listOf(0L, 1_000_000_000L),
        open: suspend (SeedConfig) -> AdminContext,
    ): Int {
        val queue = ticks.toMutableList()
        return DemoSeed.run(config, open, { queue.removeFirst() }, { lines += it })
    }

    private fun assertClosed() {
        val s = assertNotNull(session, "sessão nunca aberta")
        assertTrue(s.signedOut, "sem signOut")
        assertTrue(s.closed, "sem close")
    }

    @Test
    fun businessDateApiErrorExitsOneAndClosesSession() =
        runTest {
            val code = execute { context(businessDate = { throw ApiError.Network(RuntimeException("x")) }) }
            assertEquals(1, code)
            assertClosed()
            assertEquals(listOf("Falha: Não foi possível falar com o servidor. Confira o endereço e a rede."), lines)
        }

    @Test
    fun cancellationPropagatesClosesSessionAndPrintsNothing() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var thrown: Throwable? = null
            val job =
                launch {
                    try {
                        execute {
                            context(businessDate = {
                                started.complete(Unit)
                                awaitCancellation()
                            })
                        }
                    } catch (e: CancellationException) {
                        thrown = e
                        throw e
                    }
                }
            advanceUntilIdle()
            started.await()
            job.cancel()
            job.join()
            assertTrue(thrown is CancellationException, "a cancelação foi engolida: $thrown")
            assertClosed()
            assertEquals(emptyList(), lines, "cancelar não é falha nem sucesso")
        }

    @Test
    fun signOutFailureDoesNotTurnSuccessIntoFailureAndStillCloses() =
        runTest {
            val code =
                execute {
                    context(wrap = { real ->
                        object : SessionPort by real {
                            override suspend fun signOut(): Unit = throw ApiError.Network(RuntimeException("sem rede"))
                        }
                    })
                }
            assertEquals(0, code, lines.toString())
            assertTrue(assertNotNull(session).closed, "sem close")
            assertEquals("Concluído em 1,0 s.", lines.last())
        }

    @Test
    fun catalogCodeProblemPrintsOnlyCatalogMessageNeverTheDetail() =
        runTest {
            val detail = "Hóspede Maria Silva CPF 123.456.789-09 já existe"
            val code = execute { throw ApiError.Problem(409, "CONFLICT", detail, emptyList()) }
            assertEquals(1, code)
            assertEquals(listOf("Falha: Esta operação entra em conflito com o estado atual do registro."), lines)
        }

    @Test
    fun uncatalogedProblemDetailWithGuestDataIsNotEchoed() =
        runTest {
            val detail = "Hóspede Maria Silva, CPF 123.456.789-09, já cadastrado"
            val code = execute { throw ApiError.Problem(422, "CODIGO_NOVO_FORA_DO_CATALOGO", detail, emptyList()) }
            assertEquals(1, code)
            val text = lines.joinToString("\n")
            assertFalse(text.contains("123.456.789-09") || text.contains("Maria Silva"), "vazou dado do hóspede: $text")
        }

    @Test
    fun problemWithRawJsonDetailPrintsGenericMessage() =
        runTest {
            val code =
                execute { throw ApiError.Problem(500, null, """{"password":"Senha-Gerente-123"}""", emptyList()) }
            assertEquals(1, code)
            assertEquals(listOf("Falha: Não foi possível concluir a operação."), lines)
        }

    @Test
    fun incompatibleResponseDoesNotEchoCause() =
        runTest {
            val code =
                execute {
                    throw ApiError.IncompatibleResponse(
                        IllegalStateException("corpo {\"cpf\":\"12345678909\"}"),
                    )
                }
            assertEquals(1, code)
            val text = lines.joinToString("\n")
            assertFalse(text.contains("12345678909") || text.contains("{") || text.contains("IllegalState"), text)
        }

    @Test
    fun unexpectedErrorPrintsOnlyClassNameOnOneLine() =
        runTest {
            val code = execute { throw IllegalArgumentException("senha Senha-Gerente-123\n\tat com.zeny.X") }
            assertEquals(1, code)
            assertEquals(listOf("Falha inesperada (IllegalArgumentException)."), lines)
        }

    @Test
    fun errorThatIsNotAnExceptionStillBecomesExitOneWithoutStack() =
        runTest {
            val result =
                runCatching { execute { throw NoClassDefFoundError("io/ktor/Foo") } }
            assertTrue(result.isSuccess, "um Error vazou do run (vira pilha no main): ${result.exceptionOrNull()}")
            assertEquals(1, result.getOrNull())
        }

    @Test
    fun wrongAdminPasswordExitsOneWithoutLeakingAnySecretAndClosesSession() =
        runTest {
            val wrong = config.copy(adminNewPassword = null, adminPassword = Secret("Errada-Admin-12345"))
            val queue = mutableListOf(0L, 1L)
            val code =
                DemoSeed.run(
                    wrong,
                    { cfg ->
                        val factory = SessionFactory { iam.open().also { session = it } }
                        val admin = AdminSignIn(factory).open(cfg)
                        AdminContext(admin, { date }, FakeConfigGateway(), iam, FakeBookingGateway(seeded(), date), iam)
                    },
                    { queue.removeFirst() },
                    { lines += it },
                )
            assertEquals(1, code)
            assertEquals(listOf("Login ou senha do administrador recusados."), lines)
            assertClosed()
        }

    @Test
    fun elapsedUsesCommaForSubSecondZeroAndLongRunsWithoutGrouping() {
        val cases =
            mapOf(
                0L to "0,0",
                40_000_000L to "0,0",
                1_250_000_000L to "1,3",
                61_949_000_000L to "61,9",
                1_234_500_000_000L to "1234,5",
            )
        val previous = Locale.getDefault()
        try {
            listOf(Locale.US, Locale.GERMANY, Locale.of("pt", "BR")).forEach { loc ->
                Locale.setDefault(loc)
                cases.forEach { (nanos, expected) ->
                    lines.clear()
                    val code =
                        kotlinx.coroutines.runBlocking {
                            execute(listOf(0L, nanos)) { context() }
                        }
                    assertEquals(0, code)
                    assertEquals("Concluído em $expected s.", lines.last(), "locale $loc, nanos $nanos")
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun timeIsPrintedOnlyOnSuccessAndLast() =
        runTest {
            assertEquals(0, execute { context() })
            assertEquals(1, lines.count { it.startsWith("Concluído") })
            assertTrue(lines.last().startsWith("Concluído"))
            lines.clear()
            assertEquals(1, execute { throw ApiError.Network(RuntimeException()) })
            assertFalse(lines.any { it.startsWith("Concluído") })
        }

    // runCli: a casca do main.

    private fun cli(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
    ): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(args, env, PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Triple(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    @Test
    fun helpWinsOverInvalidArgumentsAndPrintsToStdoutOnly() {
        val (code, out, err) = cli(listOf("--nada", "--help"))
        assertEquals(0, code)
        assertTrue(out.contains("Uso: demo-seed"))
        assertEquals("", err)
    }

    @Test
    fun missingEverythingExitsTwoWithoutStdout() {
        val (code, out, err) = cli(emptyList())
        assertEquals(2, code)
        assertEquals("", out)
        assertTrue(err.contains("Uso: demo-seed"), err)
    }

    @Test
    fun invalidSecretArgumentIsNotEchoedOnStderr() {
        val leaked = "curta-demais-Ab1"
        val (code, out, err) = cli(listOf("--manager-pin", leaked, "--admin-password", "xyz"))
        assertEquals(2, code)
        assertFalse((out + err).contains(leaked) || (out + err).contains("xyz"), err)
    }

    @Test
    fun unreachableServerExitsOneWithOneLineAndNoSecretsNorUrl() {
        val env =
            mapOf(
                "ZENY_SEED_SERVER" to "http://127.0.0.1:1",
                "ZENY_SEED_ADMIN_LOGIN" to "admin",
                "ZENY_SEED_ADMIN_PASSWORD" to "Inicial-Admin-123",
                "ZENY_SEED_MANAGER_PASSWORD" to "Senha-Gerente-123",
                "ZENY_SEED_RECEPTIONIST_PASSWORD" to "Senha-Recepcao-123",
                "ZENY_SEED_HOUSEKEEPER_PASSWORD" to "Senha-Governanca-1",
                "ZENY_SEED_MANAGER_PIN" to "482913",
            )
        val (code, out, err) = cli(emptyList(), env)
        assertEquals(1, code)
        assertEquals("", err)
        assertEquals(
            listOf("Falha: Não foi possível falar com o servidor. Confira o endereço e a rede."),
            out.trim().lines(),
        )
    }
}
