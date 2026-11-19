package com.zeny.desktop.app.folio

import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.input.DateInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val LOAD_TIMEOUT_MILLIS = 60_000L
private const val POLL_MILLIS = 100L
private const val DAYS_AHEAD = 89L
private const val NEED_SEED = "NEED_SEED: nenhuma caução a vencer; rode a LivePreAuthorizationCheck ou o demo-seed"
private val VALIDITY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")

/**
 * Verificação, só de leitura, da lista das cauções a vencer contra um servidor real (o ambiente `zeny-front`), com a
 * Recepcionista, pela `CoreConnection` real e pelo estado real (`ExpiringPreAuthorizationsState`). Com o "Até" na data
 * operacional mais 89 dias, exige ao menos uma linha (a caução da `LivePreAuthorizationCheck` ou do seed; sem nenhuma,
 * `NEED_SEED`), as validades em ordem não decrescente e, para a primeira linha, que a conta lida pelo identificador
 * existe. Não escreve na demo.
 *
 * As respostas de caução do `sdk` mostram o cartão no `toString`: nenhuma é impressa, interpolada nem concatenada; só
 * contagens e textos fixos. Pulada sem `ZENY_LIVE_EXPIRING=1`; com ele, exige `ZENY_LIVE_URL`,
 * `ZENY_LIVE_VIEWER_LOGIN` e `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca impressas). Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_EXPIRING=1 timeout 1500 ./gradlew :app:test --tests
 * '*LiveExpiringPreAuthorizationCheck' --rerun)`
 */
class LiveExpiringPreAuthorizationCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_EXPIRING") == "1", "sem ZENY_LIVE_EXPIRING=1: verificação real pulada")
    }

    @Test
    fun listsTheExpiringPreAuthorizationsInOrderAndOpensTheFirstFolio() =
        withViewer { connection ->
            val port = checkNotNull(connection.expiringPreAuthorizationSource()) { "a conexão não tem a lista" }
            val payments = checkNotNull(connection.paymentSource()) { "a conexão não tem pagamentos" }
            val folios = checkNotNull(connection.folioSource()) { "a conexão não tem contas" }
            val scope = CoroutineScope(SupervisorJob())
            val state = ExpiringPreAuthorizationsState(port, payments, scope)
            try {
                val today = runBlocking { payments.businessDay() }.date.value
                state.enter()
                awaitIdle(state)
                assertNull(state.error, "a leitura inicial falhou")

                state.untilText = DateInput.format(BusinessDate(today.plusDays(DAYS_AHEAD)))
                state.search()
                awaitIdle(state)
                assertNull(state.untilError, "o \"Até\" foi recusado")
                assertNull(state.error, "a leitura falhou")
                val rows = state.rows
                assertTrue(rows.isNotEmpty(), NEED_SEED)

                val times = rows.map { LocalDateTime.parse(it.expires, VALIDITY_FORMAT) }
                assertEquals(times.sorted(), times, "as validades não vieram em ordem não decrescente")
                println("Cauções a vencer ao vivo: ${rows.size} linha(s) em ordem")

                val folio = runBlocking { folios.folio(rows.first().folioId) }
                assertEquals(rows.first().folioId, folio.id.toString(), "a conta da primeira linha não foi lida")
                println("Cauções a vencer ao vivo: a conta da primeira linha existe")
            } finally {
                state.close()
                scope.cancel()
            }
        }

    /** Espera a leitura do estado terminar: o "Até" preenchido, nada em voo e linhas ou erro. */
    private fun awaitIdle(state: ExpiringPreAuthorizationsState) {
        val done =
            runBlocking {
                withTimeoutOrNull(LOAD_TIMEOUT_MILLIS) {
                    while (state.untilText.isBlank() || state.loading) delay(POLL_MILLIS)
                    // A releitura de uma busca começa no mesmo instante: dá a vez ao `loading` antes da checagem.
                    delay(POLL_MILLIS)
                    while (state.loading) delay(POLL_MILLIS)
                    true
                }
            }
        assertEquals(true, done, "a lista não terminou de carregar a tempo")
    }

    private fun withViewer(block: (CoreConnection) -> Unit) {
        val url = ServerUrl.parse(env("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida")
        val core = ZenyCore(url, MemorySettings())
        val connection = CoreConnection(core)
        try {
            runBlocking { connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD")) }
            block(connection)
            assertIs<SessionState.Active>(connection.state.value, "a sessão da Recepcionista caiu")
        } finally {
            runCatching { runBlocking { connection.logout() } }
            connection.close()
        }
    }

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fail("defina $name para a verificação real")
}
