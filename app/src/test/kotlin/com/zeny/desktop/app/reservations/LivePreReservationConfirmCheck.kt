@file:OptIn(ExperimentalTestApi::class)

package com.zeny.desktop.app.reservations

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.ReservationFilter
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val WIDTH = 1400
private const val HEIGHT = 900
private const val LOAD_TIMEOUT_MILLIS = 60_000L

// O teto do `limit` do servidor nas listas por cursor é 200 (`PageParams.MAX_LIMIT`).
private const val PAGE_LIMIT = 200
private const val PRE_BOOKING = "DEMO-PEND-P1"
private const val PROVISIONAL = "PROVISIONAL"
private const val CONFIRMED = "CONFIRMED"
private const val CARD = "CARD"
private const val CARD_TERMS = "Garantia exigida: Cartão"
private const val NO_PRE_BOOKING =
    "NEED_SEED: recrie a demo; a pré-reserva do seed vale 48 h e é consumida por esta verificação"

/**
 * Verificação da confirmação da pré-reserva (M3 E03, provada na E05) contra um servidor real (o ambiente
 * `zeny-front`), com a Recepcionista (confirmar não pede aprovação), pelo `CoreConnection` real e pelos estados reais
 * (`PendingListsState` e `ReservationConfirmState`): a `DEMO-PEND-P1` do seed está em "Garantias pendentes", a
 * confirmação a abre com a garantia que a política pede (`CARD`), confirma, e a reserva relida fica `CONFIRMED`, com a
 * garantia registrada, e sai de "Garantias pendentes". Até aqui a confirmação só tinha teste com fake.
 *
 * **Escreve na demo e não desfaz:** consome a pré-reserva do seed (a única que o seed cria; a 48 h do vencimento ela
 * some sozinha). Por isso roda por último no gate, depois das outras verificações que dependem dela (a
 * `LiveReservationLifecycleCheck`), e uma segunda execução só passa com a demo recriada e o seed rodado de novo.
 *
 * Pulada sem o opt-in `ZENY_LIVE_PRERESERVATION=1`; com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN` e
 * `ZENY_LIVE_VIEWER_PASSWORD` (só do ambiente, nunca impressas). Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_PRERESERVATION=1 ./gradlew :app:test
 * --tests '*LivePreReservationConfirmCheck*' --rerun --max-workers=2)`
 */
class LivePreReservationConfirmCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(
            System.getenv("ZENY_LIVE_PRERESERVATION") == "1",
            "sem ZENY_LIVE_PRERESERVATION=1: verificação real pulada",
        )
    }

    /** Confirmar a pré-reserva do seed com `CARD` a confirma, registra a garantia e a tira das garantias pendentes. */
    @Test
    fun confirmingProvisionalLeavesPendingGuarantees() =
        withScreen { scope, ports ->
            val preBooking = provisionalSeed(ports)
            val id = preBooking.id.toString()
            val before = pendingGuaranteeRows(scope, ports)
            val listed =
                before.firstOrNull { it.code == preBooking.code }
                    ?: fail(
                        "${preBooking.code} ($PRE_BOOKING, ${preBooking.status}) não está em " +
                            "\"${PendingList.GUARANTEES.label}\", que tem ${before.size} linha(s); esperava a " +
                            "pré-reserva do seed nela antes de confirmar",
                    )
            assertEquals(id, listed.reservationId, "a linha da ${preBooking.code} nas garantias é outra reserva")
            val changed = AtomicInteger()
            val state = openConfirmation(scope, ports, id) { changed.incrementAndGet() }
            try {
                confirm(state, preBooking.code)
                assertEquals(1, changed.get(), "o aviso de reserva alterada da ${preBooking.code}")
            } finally {
                runOnUiThread { state.close() }
            }
            assertConfirmedWithGuarantee(ports, preBooking)
            val after = pendingGuaranteeRows(scope, ports)
            assertFalse(
                after.any { it.code == preBooking.code },
                "${preBooking.code} continua em \"${PendingList.GUARANTEES.label}\" (${after.size} linha(s)) " +
                    "depois de confirmada com $CARD",
            )
            println(
                "LivePreReservationConfirmCheck: ${preBooking.code} ($PRE_BOOKING) saiu de " +
                    "\"${PendingList.GUARANTEES.label}\" (de ${before.size} para ${after.size} linha(s)) e ficou " +
                    "$CONFIRMED com a garantia $CARD registrada",
            )
        }

    /** Uma tela vazia só para ter o escopo de UI dos estados, e a sessão da Recepcionista, encerrada no fim. */
    private fun withScreen(block: ComposeUiTest.(CoroutineScope, ConfirmPorts) -> Unit) =
        withViewer { connection ->
            val ports = ConfirmPorts(connection)
            lateinit var scope: CoroutineScope
            runDesktopComposeUiTest(WIDTH, HEIGHT) {
                setContent { scope = rememberCoroutineScope() }
                waitForIdle()
                block(scope, ports)
            }
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

/** As portas reais do `CoreConnection` que a verificação usa. */
private class ConfirmPorts(
    connection: CoreConnection,
) {
    val reception = connection.receptionSource()
    val search = connection.reservationSearchSource()
    val pending = connection.pendingSource()
    val confirm = connection.reservationConfirmSource()
}

/** A `DEMO-PEND-P1`, achada pelo `externalId`: exatamente uma e `PROVISIONAL`; senão, `NEED_SEED`. */
private fun provisionalSeed(ports: ConfirmPorts): ReservationListItemResponse {
    val found =
        runBlocking {
            val rows = mutableListOf<ReservationListItemResponse>()
            var cursor: String? = null
            do {
                val page = ports.search.search(ReservationFilter(externalId = PRE_BOOKING), cursor, PAGE_LIMIT)
                rows += page.items
                cursor = page.nextCursor
            } while (cursor != null)
            rows
        }
    if (found.isEmpty()) fail("$NO_PRE_BOOKING (a busca pelo externalId $PRE_BOOKING não achou reserva)")
    assertEquals(
        1,
        found.size,
        "a busca pelo externalId $PRE_BOOKING achou ${found.size} reservas " +
            "(${found.map { "${it.code} ${it.status}" }}); esperava exatamente uma: o seed duplicou a pré-reserva " +
            "(recrie a demo)",
    )
    val preBooking = found.single()
    if (preBooking.status != PROVISIONAL) {
        fail(
            "$NO_PRE_BOOKING (a $PRE_BOOKING é a ${preBooking.code} e está ${preBooking.status}; " +
                "esperava $PROVISIONAL)",
        )
    }
    return preBooking
}

/** Todas as linhas de "Garantias pendentes", de um `PendingListsState` novo (que lê do servidor); a lista não falha. */
private fun ComposeUiTest.pendingGuaranteeRows(
    scope: CoroutineScope,
    ports: ConfirmPorts,
): List<PendingRow> {
    val label = PendingList.GUARANTEES.label
    lateinit var pending: PendingListsState
    runOnUiThread {
        pending = PendingListsState(ports.pending, scope, { Instant.now() }, PAGE_LIMIT)
        pending.start()
    }
    try {
        val table = pending.table(PendingList.GUARANTEES)
        waitUntil("todas as páginas de \"$label\"", LOAD_TIMEOUT_MILLIS) {
            when {
                table.error != null -> {
                    true
                }

                table.loading -> {
                    false
                }

                !table.hasMore -> {
                    true
                }

                else -> {
                    scope.launch { table.loadMore() }
                    false
                }
            }
        }
        assertNull(table.error, "a lista \"$label\" falhou: ${table.error?.userMessage}")
        return table.rows.toList()
    } finally {
        runOnUiThread { pending.close() }
    }
}

/** Abre a confirmação da reserva: os termos são os da política de cartão e a garantia nasce `CARD`. */
private fun ComposeUiTest.openConfirmation(
    scope: CoroutineScope,
    ports: ConfirmPorts,
    id: String,
    onChanged: () -> Unit,
): ReservationConfirmState {
    lateinit var state: ReservationConfirmState
    runOnUiThread {
        state = ReservationConfirmState(ports.confirm, scope, id, onChanged)
        state.start()
    }
    try {
        waitUntil("a leitura da reserva na confirmação", LOAD_TIMEOUT_MILLIS) {
            state.code != null || state.error != null
        }
        assertNull(state.error, "a leitura da reserva na confirmação falhou: ${state.error}")
        val terms = assertNotNull(state.terms, "a confirmação não mostra a garantia exigida; esperava \"$CARD_TERMS\"")
        assertTrue(terms.contains(CARD_TERMS), "os termos da confirmação são \"$terms\"; esperava \"$CARD_TERMS\"")
        assertEquals(CARD, state.guarantee, "a garantia pré-escolhida da confirmação")
    } catch (e: Throwable) {
        runOnUiThread { state.close() }
        throw e
    }
    return state
}

/** Confirma e espera o resumo da reserva [expectedCode]. */
private fun ComposeUiTest.confirm(
    state: ReservationConfirmState,
    expectedCode: String,
) {
    runOnUiThread { state.confirm() }
    waitUntil("a confirmação da $expectedCode", LOAD_TIMEOUT_MILLIS) { state.done != null || state.error != null }
    assertNull(state.error, "a confirmação da $expectedCode falhou: ${state.error}")
    assertEquals("Reserva $expectedCode confirmada.", state.done, "o resumo da confirmação da $expectedCode")
}

/** A reserva relida do servidor: `CONFIRMED`, com a garantia `CARD` registrada. */
private fun assertConfirmedWithGuarantee(
    ports: ConfirmPorts,
    preBooking: ReservationListItemResponse,
) {
    val reservation = runBlocking { ports.reception.reservation(preBooking.id.toString()) }.reservation
    assertEquals(CONFIRMED, reservation.status, "a situação relida da ${preBooking.code} depois de confirmar")
    val guarantee =
        assertNotNull(
            (reservation.guarantee as? Option.Some)?.value,
            "a ${preBooking.code} foi confirmada sem a garantia registrada",
        )
    assertEquals(CARD, guarantee.method, "o meio da garantia registrada na ${preBooking.code}")
}
