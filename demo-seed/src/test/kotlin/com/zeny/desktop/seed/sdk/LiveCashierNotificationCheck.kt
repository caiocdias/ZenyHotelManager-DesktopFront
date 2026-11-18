package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.facade.CashCount
import com.zeny.desktop.core.facade.PaymentMethod
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Intent
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.Money
import com.zeny.desktop.sdk.client.NotificationsApi
import com.zeny.desktop.sdk.model.NotificationResponse
import com.zeny.desktop.seed.cli.ParseResult
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.cli.SeedArgs
import com.zeny.desktop.seed.cli.SeedConfig
import com.zeny.desktop.seed.people.SignIn
import com.zeny.desktop.seed.people.shutDown
import de.quati.kotlin.util.Option
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

private const val RECEPTIONIST = "recepcao"
private const val MANAGER = "gerente"
private const val DIFFERENCE_TYPE = "billing.cashier-shift-difference"
private const val OPENING_FLOAT = "100.00"
private const val NOTIFICATION_ATTEMPTS = 10
private val NOTIFICATION_PAUSE = 3.seconds

/**
 * Verificação ao vivo da notificação de diferença no caixa: só roda com `ZENY_SEED_SERVER` e `ZENY_SEED_LIVE=1` (sem
 * elas, pula); credenciais só do ambiente (nunca impressas). Comando, só nesse subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_SEED_LIVE=1 ./gradlew :demo-seed:test --tests '*CashierNotification*' --rerun)`
 *
 * Gera a própria prova: como a Recepcionista, reaproveita o turno corrente ou abre um (fundo de R$ 100,00), fecha com
 * o dinheiro declarado zero e exige `hasDifference` no relatório; como o Gerente, exige em `GET /api/v1/notifications`
 * uma `billing.cashier-shift-difference` cujo título cite o número desse turno (nunca "a mais recente").
 *
 * **Escreve na demo e não desfaz: fecha o turno da Recepcionista**, que a H2 e a E05 esperam aberto. O comando
 * genérico dos checks do módulo (`ZENY_SEED_LIVE=1 ./gradlew :demo-seed:test --tests '*Live*'`) agora inclui este
 * fechamento.
 */
@OptIn(RawSdkAccess::class)
class LiveCashierNotificationCheck {
    private fun config(): SeedConfig {
        val parsed = SeedArgs.parse(emptyList(), System.getenv())
        assertTrue(parsed is ParseResult.Ok, "variáveis ZENY_SEED_* incompletas")
        return parsed.config
    }

    private suspend fun <T> signedIn(
        factory: SdkSessionFactory,
        login: String,
        password: Secret,
        block: suspend (SdkSession) -> T,
    ): T {
        val session = factory.open()
        try {
            assertEquals(SignIn.Active, session.signIn(login, password), "login do usuário $login não ficou ativo")
            return block(session)
        } finally {
            session.shutDown()
        }
    }

    /** Fecha o turno da Recepcionista com o dinheiro declarado zero e devolve o `number` dele. */
    private suspend fun closeWithDifference(session: SdkSession): String {
        val cashier = session.seed.core.cashier
        val shift =
            cashier.currentShift() ?: run {
                val point = cashier.cashPoints().firstOrNull() ?: fail("sem ponto de caixa ativo para abrir o turno")
                cashier.openShift(point.id.toString(), Money.parse(OPENING_FLOAT), Intent())
            }
        val counts = listOf(CashCount(PaymentMethod.CASH, Money.parse("0.00")))
        val report = cashier.closeShift(shift.id.toString(), counts, Intent())
        val closed = report.shift
        if ((closed.hasDifference as? Option.Some)?.value != true) {
            fail(
                "o fechamento do turno ${closed.number} não teve diferença: sem ela o servidor não notifica. " +
                    "Rode de novo (sem turno aberto, a verificação abre um com fundo de R$ 100,00).",
            )
        }
        return closed.number
    }

    private suspend fun differenceNotification(
        session: SdkSession,
        number: String,
    ): NotificationResponse? {
        val api = NotificationsApi(session.seed.apis.billing.client)
        val exec = session.seed.executor
        val pager =
            CursorPager { cursor, limit ->
                val page = exec.execute { api.listNotifications(null, cursor, limit, it.headers) }.value
                Page(page.items, (page.nextCursor as? Option.Some)?.value)
            }
        while (pager.hasMore) {
            pager.next().firstOrNull { it.type == DIFFERENCE_TYPE && number in it.title }?.let { return it }
        }
        return null
    }

    @Test
    fun closingWithDifferenceNotifiesTheManager() {
        assumeTrue(System.getenv("ZENY_SEED_SERVER") != null && System.getenv("ZENY_SEED_LIVE") == "1")
        val config = config()
        runBlocking {
            val factory = SdkSessionFactory(config.server)
            val number = signedIn(factory, RECEPTIONIST, config.receptionistPassword) { closeWithDifference(it) }
            println("LiveCashierNotificationCheck: turno $number fechado com diferença")
            val found =
                signedIn(factory, MANAGER, config.managerPassword) { manager ->
                    // A notificação pode sair depois da resposta do fechamento: algumas leituras, com pausa.
                    var notification = differenceNotification(manager, number)
                    var attempts = 1
                    while (notification == null && attempts < NOTIFICATION_ATTEMPTS) {
                        delay(NOTIFICATION_PAUSE)
                        notification = differenceNotification(manager, number)
                        attempts++
                    }
                    notification
                }
            val notification = found ?: fail("o Gerente não recebeu a $DIFFERENCE_TYPE do turno $number")
            println("LiveCashierNotificationCheck: o Gerente recebeu \"${notification.title}\"")
        }
    }
}
