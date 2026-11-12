package com.zeny.desktop.seed

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.booking.BookingGateway
import com.zeny.desktop.seed.booking.BookingSeeder
import com.zeny.desktop.seed.cli.SeedConfig
import com.zeny.desktop.seed.config.ConfigGateway
import com.zeny.desktop.seed.config.ConfigSeeder
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.DemoRole
import com.zeny.desktop.seed.data.SeedReport
import com.zeny.desktop.seed.people.AdminSignIn
import com.zeny.desktop.seed.people.SessionFactory
import com.zeny.desktop.seed.people.SessionPort
import com.zeny.desktop.seed.people.UserGateway
import com.zeny.desktop.seed.people.UserSeeder
import com.zeny.desktop.seed.people.shutDown
import com.zeny.desktop.seed.sdk.SdkBookingGateway
import com.zeny.desktop.seed.sdk.SdkConfigGateway
import com.zeny.desktop.seed.sdk.SdkSessionFactory
import com.zeny.desktop.seed.sdk.SdkUserGateway
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.util.Locale

/** Tudo o que o programa usa depois de abrir o administrador: a sessão e as portas ligadas a ela. */
data class AdminContext(
    val session: SessionPort,
    val businessDate: suspend () -> LocalDate,
    val config: ConfigGateway,
    val users: UserGateway,
    val bookings: BookingGateway,
    val sessions: SessionFactory<SessionPort>,
)

/** O programa: abre o administrador, semeia configuração, usuários e reservas e imprime o relatório. */
object DemoSeed {
    private const val FAILURE = 1
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    suspend fun run(
        config: SeedConfig,
        out: (String) -> Unit = ::println,
    ): Int = run(config, ::openSdkAdmin, System::nanoTime, out)

    private suspend fun openSdkAdmin(config: SeedConfig): AdminContext {
        val factory = SdkSessionFactory(config.server)
        val session = AdminSignIn(factory).open(config)
        val seed = session.seed
        return AdminContext(
            session = session,
            businessDate = {
                seed.core.hotel
                    .businessDate()
                    .value
            },
            config = SdkConfigGateway(seed),
            users = SdkUserGateway(seed),
            bookings = SdkBookingGateway(seed),
            sessions = factory,
        )
    }

    /** `clock` em nanossegundos. Falhas viram uma linha em pt-BR e o código 1, nunca pilha nem a causa do erro. */
    @Suppress("TooGenericExceptionCaught") // última fronteira: nenhuma exceção chega ao operador como pilha
    internal suspend fun run(
        config: SeedConfig,
        openAdmin: suspend (SeedConfig) -> AdminContext,
        clock: () -> Long,
        out: (String) -> Unit,
    ): Int {
        val start = clock()
        return try {
            val report = seedAll(config, openAdmin)
            report.lines().forEach(out)
            val seconds = String.format(Locale.of("pt", "BR"), "%.1f", (clock() - start) / NANOS_PER_SECOND)
            out("Concluído em $seconds s.")
            0
        } catch (e: CancellationException) {
            throw e
        } catch (e: SeedFailure) {
            out(e.message.orEmpty())
            FAILURE
        } catch (e: ApiError) {
            out("Falha: " + e.userMessage)
            FAILURE
        } catch (e: Exception) {
            // sem a mensagem: pode ecoar dado de hóspede ou segredo
            out("Falha inesperada (${e::class.simpleName}).")
            FAILURE
        }
    }

    private suspend fun seedAll(
        config: SeedConfig,
        openAdmin: suspend (SeedConfig) -> AdminContext,
    ): SeedReport {
        val admin = openAdmin(config)
        try {
            val data = DemoCatalog.build(admin.businessDate())
            val report = SeedReport()
            val seeded = ConfigSeeder(admin.config, report).seed(data, admin.businessDate())
            val passwords =
                mapOf(
                    DemoRole.MANAGER to config.managerPassword,
                    DemoRole.RECEPTIONIST to config.receptionistPassword,
                    DemoRole.HOUSEKEEPER to config.housekeeperPassword,
                )
            UserSeeder(admin.users, admin.sessions, report).seed(data.users, passwords, config.managerPin)
            BookingSeeder(admin.bookings, report).seed(data, seeded)
            return report
        } finally {
            admin.session.shutDown()
        }
    }
}
