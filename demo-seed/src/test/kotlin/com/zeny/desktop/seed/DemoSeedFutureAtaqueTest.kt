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
import com.zeny.desktop.seed.frontdesk.FakeTraces
import com.zeny.desktop.seed.pending.FakeFutureGateway
import com.zeny.desktop.seed.pending.FakePendingGateway
import com.zeny.desktop.seed.pending.FutureGateway
import com.zeny.desktop.seed.people.FakeIam
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SECRET_ECHO = "hóspede Fulano CPF 52998224725"

/** Ataque da Task 2 (E05): a ligação do `FutureSeeder` no `DemoSeed` (ordem, porta opcional, vazamento). */
class DemoSeedFutureAtaqueTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val data = DemoCatalog.build(date)
    private val holderCpf = data.guests.first { it.key == "G12" }.cpf
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

    private fun pendingFake() =
        FakePendingGateway().apply {
            cancellationPolicies[data.cancellationPolicy.name] = "cp-1"
            guests[holderCpf] = "guest-12"
        }

    private fun futureFake() = FakeFutureGateway().apply { guests[holderCpf] = "guest-12" }

    private fun context(
        pending: FakePendingGateway?,
        future: FutureGateway?,
    ): AdminContext {
        val s = iam.open().also { session = it }
        runBlocking { s.signIn("admin", Secret("Nova-Admin-12345")) }
        val seeded = runBlocking { ConfigSeeder(FakeConfigGateway(), SeedReport()).seed(data, date) }
        return AdminContext(s, {
            date
        }, FakeConfigGateway(), iam, FakeBookingGateway(seeded, date), FakeTraces(), iam, pending, future)
    }

    private suspend fun execute(open: () -> AdminContext): Int {
        val ticks = mutableListOf(0L, 1_000_000_000L)
        return DemoSeed.run(config, { open() }, { ticks.removeFirst() }, { lines += it })
    }

    @Test
    fun futureParameterIsLastAndDefaultsToNull() {
        val types =
            AdminContext::class.java.declaredConstructors
                .filterNot {
                    it.isSynthetic
                }.maxBy { it.parameterCount }
                .parameterTypes
        assertEquals(FutureGateway::class.java, types.last())
        assertEquals(com.zeny.desktop.seed.pending.PendingGateway::class.java, types[types.size - 2])
        val minimal =
            AdminContext(
                context(null, null).session,
                {
                    date
                },
                FakeConfigGateway(),
                iam,
                FakeBookingGateway(
                    runBlocking {
                        ConfigSeeder(FakeConfigGateway(), SeedReport()).seed(data, date)
                    },
                    date,
                ),
                FakeTraces(),
                iam,
            )
        assertNull(minimal.future)
        assertNull(minimal.pending)
    }

    @Test
    fun futureRunsWithoutPendingPortWhenOnlyFuturePortIsPresent() =
        runTest {
            val future = futureFake()
            assertEquals(0, execute { context(null, future) })
            assertEquals(3, future.reservationSpecs.size, lines.toString())
        }

    @Test
    fun noFuturePortMeansNoFutureWrites() =
        runTest {
            val pending = pendingFake()
            assertEquals(0, execute { context(pending, null) })
            assertTrue(pending.calls.isNotEmpty())
            assertTrue(pending.calls.none { it.contains("DEMO-FUT") }, pending.calls.toString())
        }

    @Test
    fun futureDoesNotRunWhenPendingFails() =
        runTest {
            val future = futureFake()
            val failing = pendingFake()
            failing.guests.clear()
            val code = execute { context(failing, future) }
            assertEquals(1, code, lines.toString())
            assertTrue(future.calls.isEmpty(), "as futuras não rodam depois de uma pendência falha: ${future.calls}")
        }

    @Test
    fun futureApiErrorPrintsNoGuestDataAndClosesSession() =
        runTest {
            val inner = futureFake()
            val future =
                object : FutureGateway by inner {
                    override suspend fun createReservation(spec: com.zeny.desktop.seed.pending.PendingReservationSpec) =
                        throw ApiError.Problem(422, "ZZ_UNKNOWN", SECRET_ECHO, emptyList())
                }
            assertEquals(1, execute { context(pendingFake(), future) })
            lines.forEach {
                assertFalse("52998224725" in it, it)
                assertFalse("Fulano" in it, it)
            }
            assertTrue(assertNotNull(session).closed)
            assertTrue(session!!.signedOut)
        }

    /** Testador de etapa: a recusa do cancelamento da `C1` (depois de criadas as três) sai como a da criação. */
    @Test
    fun futureCancelRefusalPrintsOneLineWithoutGuestDataAndClosesSession() =
        runTest {
            val inner = futureFake()
            val future =
                object : FutureGateway by inner {
                    override suspend fun cancelReservation(
                        reservationId: String,
                        reason: String,
                    ) = throw ApiError.Problem(422, "ZZ_UNKNOWN", SECRET_ECHO, emptyList())
                }
            assertEquals(1, execute { context(pendingFake(), future) })
            assertEquals(3, inner.reservationSpecs.size, "a recusa vem depois das três criações")
            assertEquals(1, lines.size, "uma linha só, sem o relatório: $lines")
            assertEquals("Falha: o servidor recusou a operação (HTTP 422).", lines.single())
            assertTrue(assertNotNull(session).closed)
            assertTrue(session!!.signedOut)
        }

    @Test
    fun futureGenericFailurePrintsNoMessage() =
        runTest {
            val inner = futureFake()
            val future =
                object : FutureGateway by inner {
                    override suspend fun findReservation(externalId: String) = error(SECRET_ECHO)
                }
            assertEquals(1, execute { context(pendingFake(), future) })
            lines.forEach {
                assertFalse("52998224725" in it, it)
                assertFalse("Fulano" in it, it)
            }
            assertTrue(assertNotNull(session).closed)
        }

    @Test
    fun futureRefusalWithoutHolderPrintsSeedFailureAndExitsOne() =
        runTest {
            val future = FakeFutureGateway()
            assertEquals(1, execute { context(pendingFake(), future) })
            assertTrue(future.calls.isEmpty())
            assertEquals(
                "Hóspede de exemplo das reservas futuras não encontrado; o demo-seed não as cria sem ele.",
                lines.single(),
            )
        }
}
