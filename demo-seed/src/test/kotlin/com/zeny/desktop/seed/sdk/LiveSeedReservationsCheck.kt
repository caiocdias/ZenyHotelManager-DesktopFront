package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.seed.cli.ParseResult
import com.zeny.desktop.seed.cli.SeedArgs
import com.zeny.desktop.seed.cli.SeedConfig
import com.zeny.desktop.seed.people.AdminSignIn
import com.zeny.desktop.seed.people.shutDown
import de.quati.kotlin.util.Option
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

private val SEED_EXTERNAL_IDS =
    listOf("DEMO-PEND-P1", "DEMO-PEND-R1", "DEMO-FUT-F1", "DEMO-FUT-F2", "DEMO-FUT-C1")
private val FUTURE_CONFIRMED = listOf("DEMO-FUT-F1", "DEMO-FUT-F2")
private const val CANCELLED_ID = "DEMO-FUT-C1"

/**
 * Verificação ao vivo das reservas do seed (M3 E05). Só lê: não cria nem altera nada. Só roda com
 * `ZENY_SEED_SERVER` e `ZENY_SEED_LIVE_RESERVATIONS=1` (sem elas, pula). Credenciais só do ambiente (nunca
 * impressas). Vale **logo depois do seed**: depois da demo, a `DEMO-FUT-C1` reativada e as futuras alteradas a
 * reprovam de propósito. Comando, só nesse subshell:
 * `(set -a; . .server/seed-secrets.env; set +a; ZENY_SEED_LIVE_RESERVATIONS=1 ./gradlew :demo-seed:test
 * --tests '*LiveSeedReservationsCheck*' --rerun --max-workers=2)`
 */
@OptIn(RawSdkAccess::class)
class LiveSeedReservationsCheck {
    private fun optedIn() =
        assumeTrue(
            System.getenv("ZENY_SEED_SERVER") != null && System.getenv("ZENY_SEED_LIVE_RESERVATIONS") == "1",
        )

    private fun config(): SeedConfig {
        val parsed = SeedArgs.parse(emptyList(), System.getenv())
        assertTrue(parsed is ParseResult.Ok, "variáveis ZENY_SEED_* incompletas")
        return parsed.config
    }

    private fun <T> withAdmin(block: suspend (SeedCore) -> T): T {
        val config = config()
        return runBlocking {
            val admin = AdminSignIn(SdkSessionFactory(config.server)).open(config)
            try {
                block(admin.seed)
            } finally {
                admin.shutDown()
            }
        }
    }

    /** Todas as reservas achadas por `externalId` (o filtro do servidor não é garantia: confere cada item). */
    private suspend fun SeedCore.idsOf(externalId: String): List<Uuid> {
        val api = apis.reservations
        val found = mutableListOf<Uuid>()
        var cursor: String? = null
        do {
            val page =
                executor
                    .execute {
                        api.searchReservations(
                            externalId = externalId,
                            cursor = cursor,
                            limit = PAGE,
                            block = it.headers,
                        )
                    }.value
            found += page.items.filter { (it.externalId as? Option.Some)?.value == externalId }.map { it.id }
            cursor = (page.nextCursor as? Option.Some)?.value
        } while (cursor != null)
        return found
    }

    private suspend fun SeedCore.detailOf(externalId: String): ReservationDetailResponse {
        val id = idsOf(externalId).singleOrNull() ?: fail("NEED_SEED: rode o demo-seed desta etapa ($externalId)")
        val api = apis.reservations
        return executor.execute { api.getReservation(id, it.headers) }.value
    }

    @Test
    fun seedReservationsExistExactlyOnce() {
        optedIn()
        withAdmin { seed ->
            SEED_EXTERNAL_IDS.forEach { externalId ->
                val found = seed.idsOf(externalId).size
                if (found == 0) fail("NEED_SEED: rode o demo-seed desta etapa ($externalId)")
                assertEquals(1, found, "reservas com o externalId $externalId")
            }
        }
    }

    @Test
    fun cancelledSeedReservationHasNoPenalty() {
        optedIn()
        withAdmin { seed ->
            val reservation = seed.detailOf(CANCELLED_ID).reservation
            assertEquals("CANCELLED", reservation.status, "situação da $CANCELLED_ID")
            val penalty = (reservation.cancellation as? Option.Some)?.value?.penalty
            val amount = penalty?.amount?.let(::BigDecimal) ?: BigDecimal.ZERO
            assertEquals(0, amount.compareTo(BigDecimal.ZERO), "multa da $CANCELLED_ID")
            assertFalse(penalty?.waived ?: false, "multa isenta na $CANCELLED_ID")
        }
    }

    @Test
    fun futureReservationsAreConfirmedAfterBusinessDate() {
        optedIn()
        withAdmin { seed ->
            val today =
                seed.core.hotel
                    .businessDate()
                    .value
            FUTURE_CONFIRMED.forEach { externalId ->
                val reservation = seed.detailOf(externalId).reservation
                assertEquals("CONFIRMED", reservation.status, "situação da $externalId")
                assertTrue(LocalDate.parse(reservation.arrival) > today, "a $externalId chega depois de $today")
            }
        }
    }

    private companion object {
        const val PAGE = 50
    }
}
